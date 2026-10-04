#!/bin/bash
# Build the SFS kernel (GKI + KernelSU + SUSFS) locally, the same way
# .github/workflows/build-sfs-kernel.yml does, and pack an AnyKernel3 zip.
#
# Run inside WSL/Linux:
#   bash "/mnt/c/.../sfs/build_local.sh" [kmi] [gki manifest branch]
#
# The GKI tree is synced once into $WORK/<kmi> and reset on every run, so later
# builds only pay for compiling. KernelSU sources come from the repo's working
# tree ($SRC), uncommitted changes included; the version is 30000 + the commit
# count of the sfs branch, like CI.
#
# Environment:
#   SRC   repo seen from Linux     WORK  where GKI trees and outputs live
#   COMMON_REV  kernel/common commit to build instead of the branch head ("" = head)
#   JOBS  bazel --jobs             LTO   none, like the official android16-6.12 GKI
set -euo pipefail

KMI="${1:-android16-6.12}"
case "$KMI" in
  # KMI generation 5, which 6.12.30-android16-5 devices run; 2025-12 and later are generation 6
  android16-6.12)
    DEFAULT_BRANCH=common-android16-6.12-2025-09
    # 6.12.30-android16-5-g1750f757fabe (ab13938768), the GKI these devices ship: building the
    # very same commit keeps every stock module, system_dlkm's GKI modules included, matching
    DEFAULT_REV=1750f757fabea014ecc59d327c0c9d3c15ab1e6d
    ;;
  *) DEFAULT_BRANCH="common-$KMI" ;;
esac
COMMON_REV="${COMMON_REV-${DEFAULT_REV:-}}"
GKI_BRANCH="${2:-$DEFAULT_BRANCH}"
SRC="${SRC:-/mnt/c/Users/cam/Desktop/Cam Kernel SU}"
WORK="${WORK:-$HOME/sfs-build}"
JOBS="${JOBS:-$(nproc)}"
LTO="${LTO:-none}"
# one tree per GKI branch: switching branches must not resync over another one
TREE="$WORK/${GKI_BRANCH#common-}"
OUT="$SRC/sfs/out"

log() { printf '\n== %s\n' "$*"; }

command -v repo >/dev/null || {
  mkdir -p "$HOME/bin"
  curl -sSL https://storage.googleapis.com/git-repo-downloads/repo > "$HOME/bin/repo"
  chmod +x "$HOME/bin/repo"
  export PATH="$HOME/bin:$PATH"
}

log "GKI $GKI_BRANCH -> $TREE"
mkdir -p "$TREE"
cd "$TREE"
if [ ! -d .repo ]; then
  repo init --depth=1 -u https://android.googlesource.com/kernel/manifest -b "$GKI_BRANCH" --repo-rev=v2.16
  repo sync -c -j"$(nproc)" --no-tags --fail-fast
  git -C common rev-parse HEAD > .sfs-branch-head
else
  # back to the pristine tree: drop the previous run's SUSFS patch and KernelSU copy
  git -C common reset -q --hard
  git -C common clean -qfdx
  [ -d build/kernel/.git ] && git -C build/kernel reset -q --hard
fi
[ -f .sfs-branch-head ] || git -C common rev-parse HEAD > .sfs-branch-head
if [ -n "$COMMON_REV" ]; then
  log "common at $COMMON_REV"
  git -C common cat-file -e "$COMMON_REV^{commit}" 2>/dev/null \
    || git -C common fetch -q --depth=1 aosp "$COMMON_REV"
  git -C common checkout -q "$COMMON_REV"
else
  git -C common checkout -q "$(cat .sfs-branch-head)"
fi
git -C common log -1 --format='common: %h %s'

log "SUSFS"
SUSFS="$WORK/susfs4ksu-$KMI"
if [ -d "$SUSFS" ]; then
  git -C "$SUSFS" fetch -q --depth=1 origin "gki-$KMI" && git -C "$SUSFS" reset -q --hard FETCH_HEAD
else
  git clone -q --depth=1 -b "gki-$KMI" https://gitlab.com/simonpunk/susfs4ksu.git "$SUSFS"
fi
cp -r "$SUSFS"/kernel_patches/fs/* common/fs/
cp -r "$SUSFS"/kernel_patches/include/linux/* common/include/linux/
PATCH="$WORK/50_add_susfs_in_gki-$KMI.patch"
cp "$SUSFS/kernel_patches/50_add_susfs_in_gki-$KMI.patch" "$PATCH"
python3 "$SRC/sfs/patches/fixup_susfs.py" "$GKI_BRANCH" "$PATCH"
(cd common && patch -p1 --forward --fuzz=0 < "$PATCH")
SUSFS_VERSION=$(grep -E '^#define SUSFS_VERSION' common/include/linux/susfs.h | cut -d'"' -f2)

log "KernelSU"
# the sfs branch's history gives the version; a throwaway clone keeps WSL git off /mnt/c
VERSION_CLONE="$WORK/ksu-version"
rm -rf "$VERSION_CLONE"
git clone -q --bare --branch sfs "$SRC" "$VERSION_CLONE"
KSU_VERSION=$((30000 + $(git -C "$VERSION_CLONE" rev-list --count sfs)))
rm -rf common/drivers/kernelsu
cp -r "$SRC/kernel" common/drivers/kernelsu
rm -f common/drivers/kernelsu/include/uapi
cp -r "$SRC/uapi" common/drivers/kernelsu/include/uapi
sed -i "s/-DKSU_VERSION=16/-DKSU_VERSION=$KSU_VERSION/" common/drivers/kernelsu/Kbuild
grep -q kernelsu common/drivers/Makefile || printf '\nobj-$(CONFIG_KSU) += kernelsu/\n' >> common/drivers/Makefile
grep -q 'drivers/kernelsu/Kconfig' common/drivers/Kconfig || sed -i '/endmenu/i\source "drivers/kernelsu/Kconfig"' common/drivers/Kconfig
echo "KernelSU $KSU_VERSION, SUSFS $SUSFS_VERSION"

log "Configure"
# CONFIG_KSU* default to y: gki_defconfig stays untouched (savedefconfig drops defaults)
sed -i 's/check_defconfig//' common/build.config.gki
rm -f common/android/abi_gki_protected_exports_*
perl -pi -e 's/^\s*"protected_exports_list"\s*:\s*"android\/abi_gki_protected_exports_aarch64",\s*$//;' common/BUILD.bazel || true
# 6.12 builds the list from protected_module_names_list instead. The device keeps its stock
# system_dlkm modules, signed with another key: with the list, rfkill.ko etc. are refused
# ("exports protected symbol") and Bluetooth, Wi-Fi and the audio HAL never come up.
sed -i '/protected_module_names_list/d' common/BUILD.bazel
sed -i 's/-dirty//' common/scripts/setlocalversion
sed -i '/stable_scmversion_cmd/s/-maybe-dirty//' build/kernel/kleaf/impl/stamp.bzl || true

log "Build (lto=$LTO, jobs=$JOBS)"
rm -rf dist
# No LTO, like the official android16-6.12 GKI: with LTO, Kconfig drops RUST
# (BTF needs !LTO, GENDWARFKSYMS needs !LTO) and rust_binder.ko never gets built.
tools/bazel run --config=fast --lto="$LTO" --jobs="$JOBS" //common:kernel_aarch64_dist -- --destdir=dist
grep -aq "susfs is initialized" dist/Image || { echo "SUSFS is missing from the Image"; exit 1; }
grep -aqi "kernelsu" dist/Image || { echo "KernelSU is missing from the Image"; exit 1; }
# e.g. android16-5 from "Linux version 6.12.38-android16-5-..."
KMI_TAG=$(grep -aoE 'Linux version [0-9.]+-android[0-9]+-[0-9]+' dist/Image | head -1 | grep -oE 'android[0-9]+-[0-9]+$')
[ -n "$KMI_TAG" ] || { echo "cannot read the KMI generation from the Image"; exit 1; }
echo "KMI $KMI_TAG"

log "AnyKernel3"
AK3="$WORK/ak3"
rm -rf "$AK3"
# upstream AnyKernel3 only has 32-bit tools; 64-bit-only devices need arm64 ones
git clone -q --depth=1 -b gki-2.0 https://github.com/WildKernels/AnyKernel3.git "$AK3"
rm -rf "$AK3"/.git "$AK3"/.github "$AK3"/modules "$AK3"/patch "$AK3"/ramdisk "$AK3"/README.md
cp "$SRC/sfs/anykernel3/anykernel.sh" "$AK3/anykernel.sh"
sed -i \
  -e "s/@KMI@/$KMI/g" \
  -e "s/@KERNEL_VERSION@/${KMI#*-}/g" \
  -e "s/@KSU_VERSION@/$KSU_VERSION/g" \
  -e "s/@SUSFS_VERSION@/$SUSFS_VERSION/g" \
  -e "s/@KMI_TAG@/$KMI_TAG/g" \
  "$AK3/anykernel.sh"
cp dist/Image "$AK3/Image"
mkdir -p "$OUT"
ZIP="$OUT/KernelSU-SFS-$KMI_TAG-${KMI#*-}-$KSU_VERSION-susfs-$SUSFS_VERSION.zip"
rm -f "$ZIP"
(cd "$AK3" && zip -qr9 "$ZIP" ./*)
ls -la "$ZIP"
