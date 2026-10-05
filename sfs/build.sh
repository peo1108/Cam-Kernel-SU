#!/bin/bash
# Build one SFS kernel (GKI + KernelSU + SUSFS) from sfs/targets.json and pack
# it as an AnyKernel3 zip. CI (.github/workflows/build-sfs-kernel.yml) and local
# builds both run this script, so they cannot drift apart.
#
#   bash sfs/build.sh <target tag>     e.g. android16-6.12-2025-06_r7
#   bash sfs/build.sh --list           the targets
#
# A target is a GKI release tag of kernel/common, i.e. a kernel Google shipped:
# building the very tag a device runs keeps every stock module's CRCs matching.
# The toolchain and Kleaf come from the manifest branch of that release.
#
# Environment:
#   SRC          repo root (default: this script's parent dir)
#   WORK         GKI trees, SUSFS clone, AnyKernel3 (default: ~/sfs-build)
#   OUT          where the zip lands (default: $SRC/sfs/out)
#   JOBS         bazel --jobs (default: nproc)
#   KSU_VERSION  KernelSU version (default: 30000 + commit count of the sfs branch)
set -euo pipefail

SRC="${SRC:-$(cd "$(dirname "$0")/.." && pwd)}"
TARGETS="$SRC/sfs/targets.json"

target_field() {
  python3 - "$TARGETS" "$1" "$2" <<'PY'
import json, sys
targets, tag, field = sys.argv[1], sys.argv[2], sys.argv[3]
for t in json.load(open(targets))["targets"]:
    if t["tag"] == tag:
        print(t.get(field, ""))
        sys.exit(0)
sys.exit(f"unknown target {tag}; see sfs/targets.json")
PY
}

if [ "${1:-}" = "--list" ]; then
  python3 -c 'import json,sys; [print(t["tag"], t["kernel"]) for t in json.load(open(sys.argv[1]))["targets"]]' "$TARGETS"
  exit 0
fi
TAG="${1:?usage: build.sh <target tag> | --list}"
KMI=$(target_field "$TAG" kmi)
MANIFEST=$(target_field "$TAG" manifest)
LTO=$(target_field "$TAG" lto)
FIXUPS=$(target_field "$TAG" fixups)

WORK="${WORK:-$HOME/sfs-build}"
OUT="${OUT:-$SRC/sfs/out}"
JOBS="${JOBS:-$(nproc)}"
TREE="$WORK/${MANIFEST#common-}"

log() { printf '\n== %s\n' "$*"; }

command -v repo >/dev/null || {
  mkdir -p "$HOME/bin"
  curl -sSL https://storage.googleapis.com/git-repo-downloads/repo > "$HOME/bin/repo"
  chmod +x "$HOME/bin/repo"
  export PATH="$HOME/bin:$PATH"
}

log "$TAG: tools from $MANIFEST -> $TREE"
mkdir -p "$TREE"
cd "$TREE"
[ -d .repo ] || repo init --depth=1 -u https://android.googlesource.com/kernel/manifest -b "$MANIFEST" --repo-rev=v2.16
# Monthly manifests pin common to a release branch Google deletes later on; the
# release tag stays, so sync common at the tag itself.
mkdir -p .repo/local_manifests
cat > .repo/local_manifests/sfs.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <extend-project name="kernel/common" revision="refs/tags/$TAG" />
</manifest>
EOF
if [ ! -e common/.git ]; then
  repo sync -c -j"$(nproc)" --no-tags --fail-fast
else
  # back to a pristine tree: drop the previous run's SUSFS patch and KernelSU copy
  git -C common reset -q --hard
  git -C common clean -qfdx
  [ -d build/kernel/.git ] && git -C build/kernel reset -q --hard
fi
git -C common rev-parse -q --verify "refs/tags/$TAG" >/dev/null \
  || git -C common fetch -q --depth=1 aosp "refs/tags/$TAG:refs/tags/$TAG"
git -C common checkout -q "refs/tags/$TAG"
git -C common log -1 --format='common: %h %s'

log "SUSFS"
SUSFS="$WORK/susfs4ksu-$KMI"
if [ -d "$SUSFS" ]; then
  git -C "$SUSFS" fetch -q --depth=1 origin "gki-$KMI" && git -C "$SUSFS" reset -q --hard FETCH_HEAD
else
  git clone -q --depth=1 -b "gki-$KMI" https://gitlab.com/simonpunk/susfs4ksu.git "$SUSFS"
fi
git -C "$SUSFS" log -1 --format='susfs4ksu: %h %cs %s'
cp -r "$SUSFS"/kernel_patches/fs/* common/fs/
cp -r "$SUSFS"/kernel_patches/include/linux/* common/include/linux/
PATCH="$WORK/50_add_susfs_in_gki-$KMI.patch"
cp "$SUSFS/kernel_patches/50_add_susfs_in_gki-$KMI.patch" "$PATCH"
# adjust context to this release, then apply strictly: fuzz once misplaced a hunk
python3 "$SRC/sfs/patches/fixup_susfs.py" "$FIXUPS" "$PATCH"
(cd common && patch -p1 --forward --fuzz=0 < "$PATCH")
SUSFS_VERSION=$(grep -E '^#define SUSFS_VERSION' common/include/linux/susfs.h | cut -d'"' -f2)

log "KernelSU"
if [ -z "${KSU_VERSION:-}" ]; then
  # the sfs branch's history gives the version; a throwaway bare clone keeps git off a
  # Windows checkout (WSL git on /mnt/c breaks the repo's symlinks)
  VERSION_CLONE="$WORK/ksu-version"
  rm -rf "$VERSION_CLONE"
  git clone -q --bare --branch sfs "$SRC" "$VERSION_CLONE"
  KSU_VERSION=$((30000 + $(git -C "$VERSION_CLONE" rev-list --count sfs)))
fi
rm -rf common/drivers/kernelsu
# Bazel sandboxes the build, so copy the sources instead of symlinking them
cp -r "$SRC/kernel" common/drivers/kernelsu
rm -f common/drivers/kernelsu/include/uapi
cp -r "$SRC/uapi" common/drivers/kernelsu/include/uapi
# no .git inside the sandbox: pin the version the Manager expects
sed -i "s/-DKSU_VERSION=16/-DKSU_VERSION=$KSU_VERSION/" common/drivers/kernelsu/Kbuild
grep -q kernelsu common/drivers/Makefile || printf '\nobj-$(CONFIG_KSU) += kernelsu/\n' >> common/drivers/Makefile
grep -q 'drivers/kernelsu/Kconfig' common/drivers/Kconfig || sed -i '/endmenu/i\source "drivers/kernelsu/Kconfig"' common/drivers/Kconfig
echo "KernelSU $KSU_VERSION, SUSFS $SUSFS_VERSION"

log "Configure"
# CONFIG_KSU* default to y: gki_defconfig stays untouched (Kleaf's savedefconfig check drops defaults)
sed -i 's/check_defconfig//' common/build.config.gki
# The device keeps its stock GKI modules (system_dlkm), signed with Google's key, not ours.
# With module protection on they may not export protected symbols: rfkill.ko gets refused,
# then Bluetooth, Wi-Fi and the audio HAL never come up. 6.1/6.6 list the symbols in
# abi_gki_protected_exports_*, 6.12 derives them from protected_module_names_list.
rm -f common/android/abi_gki_protected_exports_*
perl -pi -e 's/^\s*"protected_exports_list"\s*:\s*"android\/abi_gki_protected_exports_aarch64",\s*$//;' common/BUILD.bazel || true
sed -i '/protected_module_names_list/d' common/BUILD.bazel
# drop the -dirty suffix from the kernel release string
sed -i 's/-dirty//' common/scripts/setlocalversion
sed -i '/stable_scmversion_cmd/s/-maybe-dirty//' build/kernel/kleaf/impl/stamp.bzl || true

log "Build (lto=$LTO, jobs=$JOBS)"
rm -rf dist
# 6.12 needs lto=none like the official GKI: with LTO, Kconfig drops RUST (BTF and
# GENDWARFKSYMS need !LTO) and rust_binder.ko never gets built
tools/bazel run --config=fast --lto="$LTO" --jobs="$JOBS" //common:kernel_aarch64_dist -- --destdir=dist
grep -aq "susfs is initialized" dist/Image || { echo "SUSFS is missing from the Image"; exit 1; }
grep -aqi "kernelsu" dist/Image || { echo "KernelSU is missing from the Image"; exit 1; }
# e.g. "Linux version 6.12.30-android16-5-..." -> 6.12.30 and android16-5
BANNER=$(grep -aoE 'Linux version [0-9]+\.[0-9]+\.[0-9]+-android[0-9]+-[0-9]+' dist/Image | head -1)
KVER=$(echo "$BANNER" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
KMI_TAG=$(echo "$BANNER" | grep -oE 'android[0-9]+-[0-9]+$')
[ -n "$KVER" ] && [ -n "$KMI_TAG" ] || { echo "cannot read the kernel version from the Image"; exit 1; }
# the whole uname -r, which the first boot after flashing checks against
RELEASE=$(grep -aoE 'Linux version [^ ]+ \(' dist/Image | head -1 | cut -d' ' -f3)
[ -n "$RELEASE" ] || { echo "cannot read the kernel release from the Image"; exit 1; }
echo "kernel $KVER, KMI $KMI_TAG, release $RELEASE"

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
# ksud flash-ak3 checks the device's stock modules against these CRCs before flashing;
# only the CRC and symbol columns are needed
cut -f1,2 dist/vmlinux.symvers > "$AK3/vmlinux.symvers"
[ -s "$AK3/vmlinux.symvers" ] || { echo "dist/vmlinux.symvers is missing or empty"; exit 1; }
# what this build is; the first boot after flashing checks the running kernel against it
python3 - "$AK3/sfs.json" "$KMI" "$KMI_TAG" "$KVER" "$RELEASE" "$TAG" "$KSU_VERSION" "$SUSFS_VERSION" <<'PY'
import json, sys
out, kmi, kmi_tag, kernel, release, tag, ksu, susfs = sys.argv[1:]
json.dump({"kmi": kmi, "kmiTag": kmi_tag, "kernel": kernel, "release": release,
           "gkiTag": tag, "ksuVersion": int(ksu), "susfsVersion": susfs},
          open(out, "w"), indent=2)
PY
mkdir -p "$OUT"
# the Manager reads <KMI generation>-<kernel version> from this name to recommend a build
ZIP="$OUT/KernelSU-SFS-$KMI_TAG-$KVER-$KSU_VERSION-susfs-$SUSFS_VERSION.zip"
rm -f "$ZIP"
(cd "$AK3" && zip -qr9 "$ZIP" ./*)
ls -la "$ZIP"
# release.yml merges these into sfs-manifest.json; the Manager checks the download's sha256
python3 - "$AK3/sfs.json" "$ZIP" <<'PY'
import hashlib, json, os, sys
info, zip_path = sys.argv[1:]
entry = json.load(open(info))
entry["file"] = os.path.basename(zip_path)
entry["size"] = os.path.getsize(zip_path)
with open(zip_path, "rb") as f:
    entry["sha256"] = hashlib.sha256(f.read()).hexdigest()
json.dump(entry, open(zip_path + ".json", "w"), indent=2)
print(f"sha256 {entry['sha256']}")
PY
[ -n "${GITHUB_ENV:-}" ] && echo "ZIP=$ZIP" >> "$GITHUB_ENV"
exit 0
