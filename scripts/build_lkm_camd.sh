#!/bin/bash
# Build camsu.ko (per KMI) and camd from ONE clean clone of a branch, so the LKM and the
# Manager built afterwards from the same commit report the same version (30000 + commit count).
#
# Run inside WSL (root), then build the Manager on Windows from the same commit:
#   wsl -u root -- bash "/mnt/c/.../scripts/build_lkm_camd.sh" [branch]
#   cd manager && ./gradlew :app:assembleRelease
#
# Results are copied back into the Windows repo:
#   userspace/camd/bin/aarch64/<kmi>_camsu.ko
#   manager/app/src/main/jniLibs/arm64-v8a/libcamd.so
#
# Environment (defaults match Cam's machine):
#   SRC    Windows repo seen from WSL     KW   kernel trees + toolchains (scune-kmod-work)
#   NDK    NDK llvm prebuilt dir (the Windows NDK works: only its sysroot and runtime libs are used)
#   KMIS   "kmi:clang ..." pairs to build
set -euo pipefail

BRANCH="${1:-main}"
SRC="${SRC:-/mnt/c/Users/cam/Desktop/Cam Kernel SU}"
WORK="${WORK:-/root/ksu-git}"
KW="${KW:-/root/scune-kmod-work}"
NDK="${NDK:-/mnt/c/Users/cam/AppData/Local/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64}"
KMIS="${KMIS:-android16-6.12:clang-r536225 android15-6.6:clang-r510928}"
API=31
CL="$KW/toolchains/clang-r536225/bin"
SYSROOT="$NDK/sysroot"
clang_rt_dirs=("$NDK"/lib/clang/*/lib/linux/aarch64)
CLANG_RT="${clang_rt_dirs[0]}"

# A real clone (with .git) is required: without history the module reports version 16.
# Never run WSL git on the /mnt/c repo itself (it breaks the cpp/uapi junction); clone is read-only.
rm -rf "$WORK"
git clone --quiet --branch "$BRANCH" "$SRC" "$WORK"
COUNT=$(git -C "$WORK" rev-list --count HEAD)
echo "== $(git -C "$WORK" log --oneline -1) -> version $((30000 + COUNT))"

for pair in $KMIS; do
  kmi=${pair%%:*}
  clang=${pair##*:}
  cd "$WORK/kernel"
  PATH="$KW/toolchains/$clang/bin:$PATH" make -s -C "$KW/$kmi/kernel" M="$PWD" ARCH=arm64 LLVM=1 LLVM_IAS=1 clean >/dev/null 2>&1 || true
  PATH="$KW/toolchains/$clang/bin:$PATH" make -C "$KW/$kmi/kernel" M="$PWD" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    CONFIG_KSU=m KBUILD_MODPOST_WARN=1 modules > "/root/ko-$kmi.log" 2>&1 \
    || { grep -E "error" "/root/ko-$kmi.log" | head -20; exit 1; }
  cp camsu.ko "$WORK/userspace/camd/bin/aarch64/${kmi}_camsu.ko"
  echo "== built ${kmi}_camsu.ko"
done

# caminit is not in git; reuse the copy from the Windows repo.
cp "$SRC/userspace/camd/bin/aarch64/caminit" "$WORK/userspace/camd/bin/aarch64/caminit"

# Android cross toolchain without a Linux NDK: AOSP clang + the NDK sysroot/runtime.
WRAP="$WORK/.ndk-wrap"
mkdir -p "$WRAP"
printf '#!/bin/sh\nexec "%s/clang" --target=aarch64-linux-android%s --sysroot="%s" -L"%s" "$@"\n' "$CL" "$API" "$SYSROOT" "$CLANG_RT" > "$WRAP/cc"
printf '#!/bin/sh\nexec "%s/clang++" --target=aarch64-linux-android%s --sysroot="%s" -L"%s" "$@"\n' "$CL" "$API" "$SYSROOT" "$CLANG_RT" > "$WRAP/cxx"
chmod +x "$WRAP/cc" "$WRAP/cxx"

export CC_aarch64_linux_android="$WRAP/cc"
export CXX_aarch64_linux_android="$WRAP/cxx"
export AR_aarch64_linux_android="$CL/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$WRAP/cc"
# build.rs assembles the LKM bootstrap; without an aarch64 gcc or llvm-mc point it at clang.
export KSU_LKM_BOOTSTRAP_CC="$CL/clang"
# bindgen needs libclang; AOSP clang ships one.
export LIBCLANG_PATH="$KW/toolchains/clang-r536225/lib"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=aarch64-linux-android$API --sysroot=$SYSROOT"

cd "$WORK/userspace/camd"
cargo build --release --target aarch64-linux-android > /root/camd-build.log 2>&1 \
  || { tail -30 /root/camd-build.log; exit 1; }
# camd is a workspace member: the binary lands in the workspace root target/.
CAMD="$WORK/target/aarch64-linux-android/release/camd"

cp "$CAMD" "$SRC/manager/app/src/main/jniLibs/arm64-v8a/libcamd.so"
cp "$WORK/userspace/camd/bin/aarch64/"*_camsu.ko "$SRC/userspace/camd/bin/aarch64/"
echo "== camd $(strings "$CAMD" | grep -m1 -oE '3\.[0-9]+\.[0-9]+-[0-9]+-g[0-9a-f]+ \(uapi: [0-9]+\)')"
echo "== copied libcamd.so and camsu.ko back; now build the Manager from the same commit"
