#!/bin/bash
# Puts a working camd into manager/app/src/main/jniLibs/arm64-v8a/libcamd.so, the file Gradle packs
# into the Manager (it is gitignored, so a lost or broken copy cannot be checked out again).
#
#   Build from a commit (WSL, root; default HEAD of the Windows checkout):
#     wsl -u root -- bash "/mnt/c/.../scripts/build_camd.sh" [ref]
#   Take the camd of the Manager installed on the device (Git Bash, adb; no root needed):
#     scripts/build_camd.sh --from-device
#
# A build from a commit carries the CAMD_SOURCE_ID that Gradle's check<Variant>CamdSource expects
# for that commit, so build from the commit you are about to build the Manager from. Uncommitted
# camd changes are not in it (the build clones the repo). The device's camd only passes the check
# in debug builds (a warning), unless it was built from the same sources.
#
# Environment (WSL; defaults match Cam's machine, see docs/CAM_CHANGES.md section 5):
#   NDK      Linux NDK (r28c)          WORK  clone directory     CARGO_TARGET_DIR  build output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST="${DEST:-$SRC/manager/app/src/main/jniLibs/arm64-v8a/libcamd.so}"

# Refuse anything that is not an aarch64 ELF: an error message saved as libcamd.so builds fine
# and only fails on the device.
check_elf() {
  local f="$1"
  if [ ! -s "$f" ] || [ "$(head -c 4 "$f" | od -An -tx1 | tr -d ' \n')" != "7f454c46" ]; then
    echo "error: $f is not an ELF file ($(wc -c < "$f" 2>/dev/null || echo 0) bytes)" >&2
    head -c 200 "$f" >&2 2>/dev/null || true
    echo >&2
    return 1
  fi
  # e_machine at offset 18: 0xb7 = AArch64
  if [ "$(od -An -tx1 -j18 -N1 "$f" | tr -d ' ')" != "b7" ]; then
    echo "error: $f is not an AArch64 binary" >&2
    return 1
  fi
}

install_camd() {
  local built="$1"
  check_elf "$built"
  mkdir -p "$(dirname "$DEST")"
  cp "$built" "$DEST.tmp"
  mv "$DEST.tmp" "$DEST"
  echo "== $DEST ($(wc -c < "$DEST") bytes)"
  local id
  id="$(grep -a -oE 'CAMD_SOURCE_ID=[0-9a-f]+' "$DEST" | head -1 || true)"
  echo "== ${id:-CAMD_SOURCE_ID: none (built before the id existed)}"
}

if [ "${1:-}" = "--from-device" ]; then
  export MSYS_NO_PATHCONV=1
  ADB="${ADB:-$(command -v adb || echo "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe")}"
  pkg="${KSU_PACKAGE_NAME:-cam.su.kernel}"
  apk="$("$ADB" shell pm path "$pkg" | tr -d '\r' | grep -m1 'base.apk' | cut -d: -f2)"
  [ -n "$apk" ] || { echo "error: $pkg is not installed on the device" >&2; exit 1; }
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  # adb.exe under Git Bash wants a Windows path for the local side
  local_apk="$tmp/base.apk"
  command -v cygpath >/dev/null && local_apk="$(cygpath -m "$local_apk")"
  "$ADB" pull "$apk" "$local_apk" >/dev/null
  # the APK keeps native libs uncompressed (useLegacyPackaging), unzip reads them either way
  unzip -p "$tmp/base.apk" lib/arm64-v8a/libcamd.so > "$tmp/libcamd.so"
  install_camd "$tmp/libcamd.so"
  exit 0
fi

REF="${1:-HEAD}"
NDK="${NDK:-/root/ndk/android-ndk-r28c}"
WORK="${WORK:-/root/camd-src}"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/root/camd-target}"
export PATH="/root/.cargo/bin:$PATH"
API=31
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TC" ] || { echo "error: no NDK at $NDK (set NDK)" >&2; exit 1; }

# A clean clone: the source id and the version come from git, and WSL git must never touch the
# Windows checkout itself (it breaks the cpp/uapi junction). The clone only reads it.
rm -rf "$WORK"
git clone --quiet "$SRC" "$WORK"
git -C "$WORK" checkout --quiet "$REF"
echo "== $(git -C "$WORK" log --oneline -1)"

# caminit and the LKMs are gitignored but embedded into camd: take them from the checkout.
for f in "$SRC"/userspace/camd/bin/aarch64/caminit "$SRC"/userspace/camd/bin/aarch64/*.ko; do
  [ -f "$f" ] && cp "$f" "$WORK/userspace/camd/bin/aarch64/"
done
ls "$WORK/userspace/camd/bin/aarch64/caminit" >/dev/null 2>&1 \
  || echo "warning: no caminit in userspace/camd/bin/aarch64: camd will not carry it" >&2
ls "$WORK"/userspace/camd/bin/aarch64/*.ko >/dev/null 2>&1 \
  || echo "warning: no *_camsu.ko in userspace/camd/bin/aarch64: camd will not carry an LKM" >&2

# cargo-ndk with an API level panics, and the default API cannot link camd: point cargo at the
# NDK's android31 clang directly.
export CC_aarch64_linux_android="$TC/bin/aarch64-linux-android$API-clang"
export CXX_aarch64_linux_android="$TC/bin/aarch64-linux-android$API-clang++"
export AR_aarch64_linux_android="$TC/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
export KSU_LKM_BOOTSTRAP_CC="$TC/bin/clang"
export LIBCLANG_PATH="$TC/lib"
export BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=aarch64-linux-android$API --sysroot=$TC/sysroot"

cd "$WORK/userspace/camd"
log="$CARGO_TARGET_DIR/camd-build.log"
mkdir -p "$CARGO_TARGET_DIR"
cargo build --release --target aarch64-linux-android > "$log" 2>&1 \
  || { tail -30 "$log"; exit 1; }

install_camd "$CARGO_TARGET_DIR/aarch64-linux-android/release/camd"
echo "== built from $(git -C "$WORK" rev-parse --short HEAD); build the Manager from the same commit"
