# Attestation keystore2 — M1+M2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the vendored TEESimulator native core (TA + keymint interceptor + inject) for arm64-v8a and x86_64, and make camd inject it into `keystore2` and push a config over the control socket — proven on a Lenovo Y700 Gen5 with no crash-loop. This is the feasibility gate for the whole attestation feature.

**Architecture:** Vendor TEESimulator at a pinned commit under `native/teesim/` (git submodule; its heavy AOSP sub-submodules are fetched at build time, never committed into Cam). A WSL build script produces the three artifacts per ABI. A new camd module `attest/` drives the `inject` binary across keystore2 restarts and speaks the native control protocol (`[u32 BE length][JSON]` frames to `/data/misc/keystore/.teesim-ctl`), gated behind a default-off flag file like `hide_bootloader`.

**Tech Stack:** Rust (camd, `std::os::unix::net::UnixStream`, `serde_json`, `anyhow`), C++/CMake + Rust static lib (vendored native core), Android NDK (WSL), KernelSU/Cam sepolicy.

**Spec:** `docs/superpowers/specs/2026-11-08-attestation-keystore2-design.md`

**Milestone scope:** This plan is **M1 + M2** of that spec. M3 (Manager harvest/resolve/keybox UI) and M4 (owner-scoped re-attest, sepolicy hardening, UI) get their own plan once M2 is verified green on device — their interfaces depend on what harvest returns and on inject working, which M2 proves.

## Global Constraints

- **ABIs:** build for `arm64-v8a` (device) and `x86_64` (emulator) only. Drop the Android 10/11 `keystore` path entirely — build only `inject`, `teesim_keymint`, `rust_ta` CMake targets, never `teesim_keystore`.
- **keystore2 only:** interceptor target process is `keystore2` (Android 12+); interceptor lib is `libteesim_keymint.so`.
- **Control socket (bit-exact, from upstream `common/control.cpp`):** path `/data/misc/keystore/.teesim-ctl`; frames are `[u32 big-endian length][UTF-8 JSON]`, max 8 MiB; peer must be uid 0; lib sends `hello` on connect; camd sends `config`, lib replies `ack`.
- **Flag default OFF:** feature inert until explicitly enabled; flag file under `WORKING_DIR` (mirror `HIDE_BOOTLOADER_FLAG`). Flag off or no `push.json` ⇒ camd does not inject and does not push.
- **No existing ABI change:** do not touch any current supercall/IOCTL. No kernel change in M1–M2.
- **Vendoring:** TEESimulator pinned at `06f9f2ee` (the `teesimulator.seen` in `.github/upstream-watch.json`). Its `third_party/*` AOSP submodules are fetched at build time (shallow), not committed into Cam.
- **Build is WSL-only** (like `scripts/build_camd.sh`); built `.so`/`inject` are binaries, gitignored like `libcamd.so`.
- **Git:** single branch `main`; stage paths explicitly, never `git add -a`/`-A` (the `uapi` junction shows as deleted on Windows). Commit message style `<scope>: <summary>`.
- **Rust gate (every camd task):** `cargo ndk -t arm64-v8a check`, `… clippy`, `cargo fmt`, plus host tests `cargo test --target x86_64-unknown-linux-gnu`. Fix all warnings before done.

## Review Focus

- **Malformed / truncated `push.json`** — camd must keep the last-good push (or stay inert) and never panic the daemon. Test in Task M2-2 (`load_push_with_epoch` on bad JSON returns `Err`, caller logs and holds).
- **keystore2 restart mid-session** — the pid changes and the lib drops; the inject loop must re-inject and re-push, and never double-inject a live pid. Pure part tested in Task M2-3 (`cmdline_basename`); full loop verified on device in Task M2-5.
- **Flag off / missing `push.json` / missing cache** — must be fully inert: no inject, no socket, no prop change. Test in Task M2-1 (`start()` returns early when disabled) and M2-4.
- **Undecodable / absent keybox in a profile** — the push must still commit (that profile is skipped, its apps fall through to real hardware); camd treats `profilesApplied < total` as success, not failure. Verified in Task M2-5 (device push with a dummy keybox: `ack.ok == true`, no crash).
- **`.so` built for the wrong ABI vs the device** — loading an arch-mismatched interceptor into keystore2 crashes it. Task M1-2 asserts each artifact's ELF machine matches its ABI; Task M2-5 pushes only the device-matching ABI.

---

## M1 — Vendor and build the native core

### Task M1-1: Vendor TEESimulator at a pinned commit

**Files:**
- Create: `.gitmodules` entry + `native/teesim/` (submodule → `https://github.com/JingMatrix/TEESimulator`, pinned `06f9f2ee`)
- Modify: `.gitignore` (ignore `native/teesim/out/`, `native/teesim/build/`, `native/teesim/third_party/`)
- Create: `native/teesim/README.cam.md` (one paragraph: what is vendored, the pinned commit, that `third_party/*` is fetched at build time, and that `upstream-watch.yml` tracks new `dev` commits)

**Interfaces:**
- Produces: the vendored source tree at `native/teesim/` with upstream `CMakeLists.txt`, `keymint/`, `injector/`, `rust/`, `common/` present; `third_party/` absent until build time.

- [ ] **Step 1:** Add the submodule pinned to `06f9f2ee`: `git submodule add https://github.com/JingMatrix/TEESimulator native/teesim`, then `cd native/teesim && git checkout 06f9f2ee`, and **do not** recurse its submodules yet.
- [ ] **Step 2:** Add `native/teesim/out/`, `native/teesim/build/`, `native/teesim/third_party/` to `.gitignore`.
- [ ] **Step 3:** Write `native/teesim/README.cam.md` per Files above.
- [ ] **Step 4: Verify** the working tree is sane: `git status` shows `native/teesim` at the pinned commit and `.gitmodules` staged; `ls native/teesim/keymint native/teesim/rust/teesim-km native/teesim/injector` all exist.
- [ ] **Step 5: Commit** — `git add .gitmodules native/teesim .gitignore native/teesim/README.cam.md` then `git commit -m "native: Vendor TEESimulator core at 06f9f2ee"`.

### Task M1-2: Build script producing the three artifacts per ABI

**Files:**
- Create: `scripts/build_teesim.sh` (WSL)

**Interfaces:**
- Consumes: the vendored tree from M1-1; env `ANDROID_HOME`, `ANDROID_NDK_HOME` (as `build_camd.sh` expects).
- Produces: `native/teesim/out/<abi>/{inject,libteesim_keymint.so}` for `arm64-v8a` and `x86_64`. (`libteesim_km.a` is an internal input linked into the `.so`, not shipped separately.)

- [ ] **Step 1: Write the build script.** It must, for each ABI in `arm64-v8a x86_64`:
  - fetch the vendored sub-submodules shallow into `native/teesim/third_party/` (`git -C native/teesim submodule update --init --recursive --depth 1`) — the build needs `lsplt`, `keymint`, `interfaces`, `frameworks-native`, `boringssl` (see upstream `CMakeLists.txt`);
  - run CMake with `-DANDROID_ABI=<abi> -DANDROID_PLATFORM=android-29`, the NDK toolchain file, and build **only** targets `inject` and `teesim_keymint` (which depends on `rust_ta`), never `teesim_keystore`;
  - copy the two outputs to `native/teesim/out/<abi>/`.
  - `set -euo pipefail`; print the resolved NDK and the output paths.
- [ ] **Step 2: Run it** in WSL: `bash scripts/build_teesim.sh`.
  Expected: exits 0; `native/teesim/out/arm64-v8a/` and `native/teesim/out/x86_64/` each contain `inject` and `libteesim_keymint.so`.
- [ ] **Step 3: Verify the artifacts are correct ELF.** For each ABI, `readelf -h native/teesim/out/<abi>/libteesim_keymint.so` shows the expected machine (`AArch64` for arm64-v8a, `Advanced Micro Devices X86-64` for x86_64); `readelf -h .../inject` likewise. And the interceptor exports exactly `entry`: `readelf --dyn-syms native/teesim/out/arm64-v8a/libteesim_keymint.so | grep -w entry` returns a `FUNC GLOBAL` line (the `exports.map` hides everything else).
- [ ] **Step 4: Commit** — `git add scripts/build_teesim.sh` then `git commit -m "scripts: Build the vendored teesim core per ABI"`. (Outputs stay gitignored.)

### Task M1-3: CI job building the native core

**Files:**
- Modify: `.github/workflows/test.yml` (add a `build-teesim` job) or create `.github/workflows/build-teesim.yml`

**Interfaces:**
- Consumes: M1-2's script.
- Produces: a CI artifact `teesim-native` holding `out/arm64-v8a/*` and `out/x86_64/*`, built from the same commit (so a later Manager packaging step can pull it, as `manager` pulls `libcamd.so`).

- [ ] **Step 1:** Add a job that checks out with submodules off, installs NDK (pin the same version `build_camd.sh`/CI uses), runs `bash scripts/build_teesim.sh`, caches `native/teesim/third_party/`, and uploads `native/teesim/out/**` as artifact `teesim-native`.
- [ ] **Step 2: Verify** by pushing the branch and confirming the job goes green and the artifact contains all four files. (If CI cannot be triggered from the execution environment, record this step as "verify on next push" and proceed.)
- [ ] **Step 3: Commit** — `git add .github/workflows/…` then `git commit -m "ci: Build the teesim native core"`.

---

## M2 — camd injects and pushes (feasibility proof on device)

### Task M2-1: `attest` module skeleton + flag + defs

**Files:**
- Create: `userspace/camd/src/attest/mod.rs`
- Modify: `userspace/camd/src/main.rs` (add `mod attest;`), `userspace/camd/src/defs.rs` (constants)
- Test: host tests inline in `attest/mod.rs` (`#[cfg(test)]`)

**Interfaces:**
- Produces:
  - `defs::ATTEST_DIR = WORKING_DIR + "attest/"`, `ATTEST_PUSH = ATTEST_DIR + "push.json"`, `ATTEST_BOOT = ATTEST_DIR + "boot.json"`, `ATTEST_FLAG = WORKING_DIR + ".attest_sim"`, `TEESIM_CTL = "/data/misc/keystore/.teesim-ctl"`, `TEESIM_LIB = LIBRARY_DIR + "libteesim_keymint.so"`, `TEESIM_INJECT = BINARY_DIR + "inject"`.
  - `attest::is_enabled() -> bool` (flag file exists), `attest::set_enabled(enabled: bool) -> anyhow::Result<()>`, `attest::start()` (no-op stub this task).

- [ ] **Step 1: Write the failing test** in `attest/mod.rs`: `set_enabled_toggles_flag` — `set_enabled(true)` makes `is_enabled()` true against a temp `ATTEST_FLAG`, `set_enabled(false)` makes it false. Use a test seam that lets the flag path be overridden (e.g. an inner `fn flag_path() -> PathBuf` the test points at a `tempdir`), mirroring how `hide_bootloader` is structured.
- [ ] **Step 2: Run** `cd userspace/camd && cargo test --target x86_64-unknown-linux-gnu attest::` — Expected: FAIL (module/functions absent).
- [ ] **Step 3: Implement** the constants in `defs.rs` and `is_enabled`/`set_enabled`/`start` (stub) in `attest/mod.rs`; add `mod attest;` to `main.rs`. Follow `hide_bootloader::{is_enabled,set_enabled}` for the flag-file pattern.
- [ ] **Step 4: Run** the test — Expected: PASS. Then the Rust gate (`cargo ndk -t arm64-v8a check`/`clippy`, `cargo fmt`).
- [ ] **Step 5: Commit** — `git add userspace/camd/src/attest/mod.rs userspace/camd/src/main.rs userspace/camd/src/defs.rs` then `git commit -m "camd: Add attest module skeleton and flag"`.

### Task M2-2: Control protocol client (`control.rs`)

**Files:**
- Create: `userspace/camd/src/attest/control.rs`
- Modify: `userspace/camd/src/attest/mod.rs` (`mod control;`)
- Test: `#[cfg(test)]` in `control.rs`

**Interfaces:**
- Consumes: `defs::TEESIM_CTL`.
- Produces:
  - `fn encode_frame(json: &str) -> Vec<u8>` — 4-byte big-endian length prefix + bytes.
  - `fn read_frame<R: Read>(r: &mut R) -> anyhow::Result<String>` — read 4-byte BE len (reject 0 or > 8 MiB), then len bytes, UTF-8.
  - `struct Hello { protocol: u32, hook: String, android_api: i32, keystore_pid: i32 }` + `fn parse_hello(json: &str) -> Result<Hello>`.
  - `struct Ack { epoch: u64, ok: bool, profiles_applied: i32, profiles_failed: i32 }` + `fn parse_ack(json: &str) -> Result<Ack>`.
  - `fn load_push_with_epoch(path: &Path, epoch: u64) -> Result<String>` — read `push.json`, set its top-level `"epoch"` to `epoch` via `serde_json::Value`, re-serialize; `Err` on unreadable/!unparseable JSON.
  - `struct ControlClient` with `fn connect(path: &Path) -> Result<(ControlClient, Hello)>`, `fn push(&mut self, config_json: &str) -> Result<Ack>`, `fn ping(&mut self, epoch: u64) -> Result<u64>`.

- [ ] **Step 1: Write failing tests:**
  - `frame_roundtrip`: `read_frame(&mut Cursor::new(encode_frame(s)))` returns `s`; a length of 0 and a length > 8 MiB each return `Err`.
  - `epoch_injected`: `load_push_with_epoch` on a temp file `{"type":"config","epoch":0,"profiles":[]}` with `epoch=7` yields JSON whose parsed `epoch == 7` and keeps `profiles`.
  - `bad_push_errs`: `load_push_with_epoch` on `"{ not json"` returns `Err`.
  - `parse_hello_ok` / `parse_ack_ok`: parse the exact strings `BuildHello`/`BuildAck` emit (see spec §3.1–3.2) into the structs.
- [ ] **Step 2: Run** `cargo test --target x86_64-unknown-linux-gnu attest::control` — Expected: FAIL.
- [ ] **Step 3: Implement** `control.rs`. Use `serde_json` for parse/edit; `std::io::Read`/`Write` for frames; `UnixStream` in `connect`/`push`/`ping`. `connect` reads the first frame and `parse_hello`s it; `push` writes `encode_frame(config_json)` then reads one frame and `parse_ack`s it.
- [ ] **Step 4: Run** the tests — Expected: PASS. Then the Rust gate.
- [ ] **Step 5: Commit** — `git commit -m "camd: Add attest control-socket client"` (stage the two files).

### Task M2-3: Injector driver (`inject.rs`)

**Files:**
- Create: `userspace/camd/src/attest/inject.rs`
- Modify: `userspace/camd/src/attest/mod.rs` (`mod inject;`)
- Test: `#[cfg(test)]` in `inject.rs`

**Interfaces:**
- Consumes: `defs::{TEESIM_INJECT, TEESIM_LIB}`.
- Produces:
  - `fn cmdline_basename(cmdline: &[u8]) -> Option<String>` — the arg0 basename from a `/proc/<pid>/cmdline` byte blob (NUL-terminated), `None` if empty.
  - `fn keystore2_pid() -> Option<i32>` — scan `/proc/*/cmdline`, return the pid whose basename is `keystore2`.
  - `fn run_inject(pid: i32) -> anyhow::Result<()>` — spawn `TEESIM_INJECT <pid> <TEESIM_LIB> entry`, wait, `Err` on non-zero exit (capture stderr+stdout into the error).
  - `fn inject_loop(stop: &AtomicBool, injected_pid: &mut i32)` — the watch loop (device path; not unit-tested): skip the full `/proc` walk while `injected_pid` is still `keystore2`, re-inject when the pid changes, back off after repeated failures. Mirrors upstream `Injector.kt`.

- [ ] **Step 1: Write failing tests** for `cmdline_basename`: `b"/system/bin/keystore2\0--flag\0"` → `Some("keystore2")`; `b"keystore2\0"` → `Some("keystore2")`; `b""` → `None`.
- [ ] **Step 2: Run** `cargo test --target x86_64-unknown-linux-gnu attest::inject` — Expected: FAIL.
- [ ] **Step 3: Implement** `inject.rs`. `keystore2_pid` reads `/proc` dir entries whose names are all-digits, reads each `cmdline`, applies `cmdline_basename`. `run_inject` uses `std::process::Command`. `inject_loop` follows `Injector.kt`'s structure (alive-pid fast path, `findPid` fallback, backoff, confirm-by-hello handled by the caller in mod.rs).
- [ ] **Step 4: Run** the tests — Expected: PASS. Then the Rust gate.
- [ ] **Step 5: Commit** — `git commit -m "camd: Add attest injector driver"` (stage the two files).

### Task M2-4: Orchestration + wire into init

**Files:**
- Modify: `userspace/camd/src/attest/mod.rs` (implement `start()`), `userspace/camd/src/init_event.rs` (call `attest::start()` from `on_services`)

**Interfaces:**
- Consumes: `control::{ControlClient, load_push_with_epoch}`, `inject::{inject_loop, keystore2_pid}`, `defs::*`.
- Produces: `attest::start()` — returns immediately when `!is_enabled()` or `ATTEST_PUSH` is missing; otherwise spawns one background thread that: runs the inject loop; once the lib checks in (hello via `ControlClient::connect` succeeds), loads `ATTEST_PUSH` with a monotonically increasing epoch and `push`es it; watches `ATTEST_PUSH` for changes and re-pushes; bumps boot props via the existing `hide_bootloader`/`vbmeta` path if `ATTEST_BOOT` requests it (minimal in M2 — may be a logged TODO deferred to M4, stated as such).

- [ ] **Step 1: Implement `start()`** with the early-return guards first (test them: a host test `start_inert_when_disabled` asserting `start()` does nothing and spawns no thread when the flag is off — assert via a seam, e.g. `start()` returns an enum `Started::Disabled`/`Started::NoConfig`/`Started::Running` the test can read).
- [ ] **Step 2: Run** `cargo test --target x86_64-unknown-linux-gnu attest::` — Expected: the new guard test PASSES; existing tests still pass.
- [ ] **Step 3: Wire** `attest::start()` into `init_event::on_services()` (after the `service` stage run), guarded so a failure only logs (`if let Err(e) = … { warn!(...) }` style, matching neighbors).
- [ ] **Step 4: Full gate** — `cargo ndk -t arm64-v8a check`, `clippy`, `cargo fmt`, and host tests all green. Then build the arm64 camd (`scripts/build_camd.sh`) to confirm it links.
- [ ] **Step 5: Commit** — `git commit -m "camd: Wire attest inject+push into on_services"` (stage `attest/mod.rs`, `init_event.rs`).

### Task M2-5: Device bring-up on Y700 Gen5 (the feasibility proof)

**Files:** none (device procedure + a throwaway `push.json`); record results in `docs/CAM_CHANGES.md` only after it passes.

**Interfaces:**
- Consumes: M1 artifacts (`native/teesim/out/arm64-v8a/*`), M2 camd.

> **Safety:** a bad interceptor crash-loops keystore2 and can soft-brick the tablet. Recovery is `su -c 'kill $(pidof keystore2)'` (a fresh keystore2 starts clean, uninjected until camd re-injects). Keep the flag OFF by default; turn it on only for this test; run it under the boot-guard harness. This is a **visible device change** — tell the user before flipping it on, and leave a clean detector scan after.

- [ ] **Step 1:** Push artifacts to the device paths camd expects: `native/teesim/out/arm64-v8a/libteesim_keymint.so` → `/data/adb/cam/lib/`, `.../inject` → `/data/adb/cam/bin/` (chmod `inject` `0700`, correct SELinux label via `restorecon`). Install the M2 camd.
- [ ] **Step 2:** Write a throwaway `/data/adb/cam/attest/push.json`: one profile `id:"default"`, `securityLevel:1`, `packages:["<a harmless test app>"]`, `uids:[<that app's uid>]`, an **empty/dummy** `keyboxB64` (so the profile is inert — this test proves plumbing, not STRONG), `bootInfo` with `deviceLocked:true,verifiedBootState:0`. Write a minimal `boot.json` or omit (defaults apply). Enable the feature by creating the flag file: `touch /data/adb/cam/.attest_sim`.
- [ ] **Step 3: Verify injection + control, no crash-loop.** Reboot (or restart keystore2). In logcat: camd logs an inject into `keystore2`, the lib's control-socket `hello` arrives (`protocol:1,hook:"keymint"`), and camd logs an `ack` with `ok:true` (profilesApplied 0 is fine — dummy keybox skipped). `pidof keystore2` is **stable** (not restarting every few seconds); Play Store / normal key use on the device still works. Expected observable: one steady keystore2 pid, one `hello`, one `ack`, no `avc: denied` on the control socket.
- [ ] **Step 4: Verify SELinux reachability.** If `hello` never arrives, check logcat for `avc: denied` on `.teesim-ctl` / ptrace / `adb_data_file`, and add the sepolicy rules from spec §6 (translated to Cam's domains) via Cam's sepolicy path; repeat Step 3. Record which rules were needed.
- [ ] **Step 5: Verify boot-guard survival** — run `scripts/device_bootguard_test.sh` with the flag on; the device must boot through its reboot loop and come back (no permanent crash-loop). Then **turn the flag OFF**, confirm keystore2 is clean, and leave a clean `scripts/device_detector_scan.py` scan ([[warn-before-visible-device-changes]]).
- [ ] **Step 6:** Record the outcome (inject works? which sepolicy rules? any instability) at the bottom of this plan file and in a short `docs/CAM_CHANGES.md` note. This result gates whether M3/M4 proceed as designed.

---

## Notes for M3/M4 (next plan, after M2 is green)

- M3: Manager `Harvester` (freeze RoT/patch/attest-version/module-hash → `boot.json`), `Resolver` (packages→uids, KeyMint patch-level encoding → `push.json`), `KeyboxStore` (XML parse + `Revocation.kt` check), target-app UI; package the M1 artifacts into the Manager APK jniLibs and have camd extract them to `/data/adb/cam/{lib,bin}` (like `libcamd.so`). End-to-end check: a targeted app reaches **STRONG**.
- M4: owner-scoped re-attest/purge via `CamRootService`, `push.json` file-watch hardening, boot-prop integration, sepolicy finalized from M2-5's findings, the UI toggle, detector + bootguard regression before default-on.
- Keep the flag OFF by default through M4; only a configured device with a real keybox turns it on.
