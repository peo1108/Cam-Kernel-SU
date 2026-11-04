# shellcheck shell=bash
# Source before building the camd that goes into the Manager APK. Panic messages carry the
# source path of the code that panicked, and git dependencies live in checkout dirs named after
# their repository (one is upstream's props crate): map every checkout to a neutral /deps/gitN
# so libcamd.so holds no upstream name (docs/CAM_CHANGES.md row 85).
#
#   source scripts/remap_dep_paths.sh <path/to/Cargo.toml>
_remap_manifest="${1:?usage: source remap_dep_paths.sh <Cargo.toml>}"
_remap_home="${CARGO_HOME:-$HOME/.cargo}"
cargo fetch --manifest-path "$_remap_manifest" > /dev/null 2>&1 || true
_remap_i=0
for _remap_dir in "$_remap_home"/git/checkouts/*/*/; do
    [ -d "$_remap_dir" ] || continue
    _remap_i=$((_remap_i + 1))
    RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }--remap-path-prefix=${_remap_dir%/}=/deps/git$_remap_i"
done
export RUSTFLAGS
unset _remap_manifest _remap_home _remap_i _remap_dir
