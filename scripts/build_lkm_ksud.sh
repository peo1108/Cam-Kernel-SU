#!/bin/bash
# Build kernelsu.ko (per KMI) and ksud from ONE clean clone of a branch, so the LKM and the
# Manager built afterwards from the same commit report the same version (30000 + commit count).
#
# Run inside WSL (root), then build the Manager on Windows from the same commit:
#   wsl -u root -- bash "/mnt/c/.../scripts/build_lkm_ksud.sh" [branch]
#   cd manager && ./gradlew :app:assembleRelease
#
# Results are copied back into the Windows repo:
#   userspace/ksud/bin/aarch64/<kmi>_kernelsu.ko
#   manager/app/src/main/jniLibs/arm64-v8a/libksud.so
#
# Environment (defaults match Cam's machine):
#   SRC    Windows repo seen from WSL     KW   kernel trees + toolchains (scune-kmod-work)
#   NDK    NDK llvm prebuilt dir (the Windows NDK works: only its sysroot and runtime libs are used)
#   KMIS   "kmi:clang ..." pairs to build
set -euo pipefail

BRANCH="${1:-feat/managerless-seed}"
SRC="${SRC:-/mnt/c/Users/cam/Desktop/Cam Kernel SU}"
WORK="${WORK:-/root/ksu-git}"
KW="${KW:-/root/scune-kmod-work}"
NDK="${NDK:-/mnt/c/Users/cam/AppData/Local/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64}"
KMIS="${KMIS:-android16-6.12:clang-r536225 android15-6.6:clang-r510928}"
API=31
CL="$KW/toolchains/clang-r536225/bin"
SYSROOT="$NDK/sysroot"
CLANG_RT=$(ls -d "$NDK"/lib/clang/*/lib/linux/aarch64 | head -1)

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
  cp kernelsu.ko "$WORK/userspace/ksud/bin/aarch64/${kmi}_kernelsu.ko"
  echo "== built ${kmi}_kernelsu.ko"
done

# ksuinit is not in git; reuse the copy from the Windows repo.
cp "$SRC/userspace/ksud/bin/aarch64/ksuinit" "$WORK/userspace/ksud/bin/aarch64/ksuinit"

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

cd "$WORK/userspace/ksud"
cargo build --release --target aarch64-linux-android > /root/ksud-build.log 2>&1 \
  || { tail -30 /root/ksud-build.log; exit 1; }
# ksud is a workspace member: the binary lands in the workspace root target/.
KSUD="$WORK/target/aarch64-linux-android/release/ksud"

cp "$KSUD" "$SRC/manager/app/src/main/jniLibs/arm64-v8a/libksud.so"
cp "$WORK/userspace/ksud/bin/aarch64/"*_kernelsu.ko "$SRC/userspace/ksud/bin/aarch64/"
echo "== ksud $(strings "$KSUD" | grep -m1 -oE '3\.[0-9]+\.[0-9]+-[0-9]+-g[0-9a-f]+ \(uapi: [0-9]+\)')"
echo "== copied libksud.so and kernelsu.ko back; now build the Manager from the same commit"
