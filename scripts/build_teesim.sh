#!/usr/bin/env bash
# Build the vendored TEESimulator native core for the keystore2 attestation feature.
# Produces, per ABI, the two artifacts Cam ships:
#   native/teesim/out/<abi>/inject
#   native/teesim/out/<abi>/libteesim_keymint.so
# (libteesim_km.a — the Rust TA — is an internal input linked into the .so, not shipped alone.)
#
# Only the keystore2 (Android 12+) path is built: CMake targets `inject` and `teesim_keymint`.
# The Android 10/11 `teesim_keystore` target, the Kotlin daemon and the WebUI are NOT built —
# camd and the Manager replace them (spec docs/superpowers/specs/2026-11-08-attestation-keystore2-design.md).
#
# WSL only (like scripts/build_camd.sh). Requires:
#   ANDROID_HOME        Android SDK (needs build-tools >= 35 for aidl, and cmake)
#   ANDROID_NDK_HOME    Android NDK (or ANDROID_NDK_ROOT, else the newest under $ANDROID_HOME/ndk)
#   cargo + cargo-ndk with the android Rust targets (the TA builds via rust/build.sh)
# Env overrides: ABIS (default "arm64-v8a x86_64"), API (default 29).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
TEE="$ROOT/native/teesim"
ABIS="${ABIS:-arm64-v8a x86_64}"
API="${API:-29}"

[ -d "$TEE/keymint" ] || { echo "ERROR: $TEE is empty — run from the Cam repo with the native/teesim submodule" >&2; exit 1; }

# SDK (aidl). The CMakeLists globs $ANDROID_HOME/build-tools/*/aidl and needs >= 35.
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK (needs build-tools >= 35)}"

# Resolve the NDK: honour the standard env vars, else the newest installed under the SDK.
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "ERROR: NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
echo "build_teesim: NDK=$NDK  API=$API  ABIS=$ABIS"

# Fetch the vendored AOSP sub-submodules (shallow, at their recorded SHAs). The build needs
# third_party/{lsplt,keymint,interfaces,frameworks-native,boringssl}. Checking out the recorded SHAs
# keeps native/teesim clean (not a "dirty submodule") in the parent repo.
echo "build_teesim: fetching native/teesim sub-submodules (shallow)…"
git -C "$TEE" submodule update --init --recursive --depth 1

GEN="${CMAKE_GENERATOR:-Unix Makefiles}"
JOBS="$(nproc 2>/dev/null || echo 4)"

for ABI in $ABIS; do
  echo "=== build_teesim: $ABI ==="
  BUILD="$TEE/build/$ABI"
  OUT="$TEE/out/$ABI"
  mkdir -p "$OUT"
  cmake -S "$TEE" -B "$BUILD" -G "$GEN" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_NDK="$NDK"
  # Build only the keystore2 artifacts; teesim_keymint pulls in rust_ta (the TA) via add_dependencies.
  cmake --build "$BUILD" -j "$JOBS" --target inject teesim_keymint
  cp "$BUILD/inject" "$OUT/inject"
  cp "$BUILD/libteesim_keymint.so" "$OUT/libteesim_keymint.so"
  echo "build_teesim: -> $OUT/inject, $OUT/libteesim_keymint.so"
done

echo "build_teesim: done"
