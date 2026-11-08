# Vendored TEESimulator native core

`native/teesim/` is a git submodule of [JingMatrix/TEESimulator](https://github.com/JingMatrix/TEESimulator),
pinned at commit **`06f9f2ee`** (the `teesimulator.seen` in `.github/upstream-watch.json`).

Cam vendors only the **native core** of this project to build three artifacts for the keystore2
attestation feature (see `docs/superpowers/specs/2026-11-08-attestation-keystore2-design.md`):

- `libteesim_km.a` — the reference KeyMint TA (Rust), linked into the interceptor (not shipped alone).
- `libteesim_keymint.so` — the Android 12+ keystore2 interceptor + control-socket server.
- `inject` — the ptrace loader.

Cam does **not** build the Android 10/11 `teesim_keystore` path, the Kotlin control daemon, or the WebUI
— camd and the Manager replace those (spec §4). Build with `scripts/build_teesim.sh` (WSL only).

The upstream tree is left untouched; Cam-side notes live here in the parent repo, not inside the
submodule. The heavy AOSP sub-submodules under `native/teesim/third_party/` (`keymint`, `interfaces`,
`frameworks-native`, `boringssl`, `lsplt`) are **fetched at build time**, at their pinned SHAs, and are
never committed into Cam. `upstream-watch.yml` tracks new TEESimulator `dev` commits; bump the pin
deliberately after reviewing them.
