# Attestation keystore2 — M3+M4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Manager the brain of the attestation feature — harvest the device's real root of trust, import and validate a keybox, resolve target apps into the native push config, ship the vendored interceptor artifacts, and drive it all through the Manager UI — so a targeted app reaches Play Integrity STRONG; then harden it for shipping (persisted SELinux, owner-scoped re-attestation, a safe default-off toggle).

**Architecture:** Hướng 1 (spec §4): the Manager (Kotlin, has AndroidKeyStore + PackageManager + the root service) produces `boot.json` (frozen harvest) and `push.json` (resolved config); camd (already built in M1-M2) injects the interceptor and pushes. Pure encoders/parsers live in JVM-testable helpers; device-bound work (harvest, uid resolution, key ops, UI) goes through `CamRootService`/the framework.

**Tech Stack:** Kotlin + Jetpack Compose (Miuix), AndroidKeyStore / KeyMint attestation, AIDL root service, Rust (camd packaging + sepolicy), the vendored TEESimulator native core.

**Spec:** `docs/superpowers/specs/2026-11-08-attestation-keystore2-design.md`

**Builds on:** `docs/superpowers/plans/2026-11-08-attestation-keystore2-m1-m2.md` (DONE + verified on the emulator). camd already has `attest {enable,disable,status,push,ping,inject,daemon}`, the control client, the resident daemon, and reads `/data/adb/cam/attest/{push.json,boot.json}`. M2-5 proved the plumbing with a HAND-BUILT push.json + dummy keybox; M3 produces those files for real from the Manager.

## Status — resume here (as of 2026-11-09)

**DONE (on `main`, commits `363ac99b`, `845ae2b8`, `21def93a`, `115a30e6`; all unit-tested):**
- **M3-1** — harvest the full root of trust (`KeyAttestation.kt` extended + `attest/Harvester.kt`; `HarvestRecord.toBootInfoJson()`/`toJson()`/`fromJson()`).
- **M3-2** — resolve apps → push config (`attest/Resolver.kt` `resolvePush(...)` + `attest/PatchLevel.kt`).
- **M3-3** — keybox parse + revocation (`attest/KeyboxStore.kt`; `parseKeybox`, `KeyboxStatus`, `Keybox.statusAgainst/toB64`; fixture `src/test/resources/keybox_valid.xml`).
- **M4-1** — persist SELinux (`attest/mod.rs` `ATTEST_SEPOLICY` + `apply_attest_sepolicy()` from `attest::start()`).

**REMAINING (suggested order):** M3-4 → M3-5 → M4-2 → M4-3 (all buildable without the user) → **M3-6 UI** (user reviews the look) → **M3-7 / M4-4 STRONG** (needs the user's real, non-revoked keybox). The per-task detail is below; each remaining task keeps its TDD steps.

**Resume environment notes:**
- WSL toolchain for the native build is set up: NDK `/root/ndk/android-ndk-r28c`, Android SDK `/root/android-sdk` (aidl build-tools 36), cmake/ninja via apt. Build the teesim core with `scripts/build_teesim.sh` (or the one-off CMake used in M1-2: clone to `/root/teesim`, `cmake -S … -DANDROID_ABI=…`), or let CI `teesim.yml` do it. x86_64 artifacts last built to `/root/teesim/{inject,libteesim_keymint.so}` (stripped).
- Manager builds from `K:\manager` (the subst drive; recreate if gone). `libcamd.so` for x86_64 + arm64 is staged in `manager/app/src/main/jniLibs/<abi>/` (gitignored); rebuild + re-stage if lost (the camd build env is in `scripts/build_camd.sh` / the M1-2 ledger). Unit tests: `K:\manager> .\gradlew.bat :app:testDebugUnitTest`.
- Emulator M2-5 procedure (for M3-7): `scripts/emu_y700.sh start`; adb at `$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe -s emulator-5554`; MSYS_NO_PATHCONV=1 on every adb call; push camd + teesim artifacts to `/data/adb/cam/{bin,lib}`; the one SELinux rule needed is now auto-applied by M4-1. A dummy keybox proves the pipeline; a real keybox is needed for actual STRONG.
- Ledger: `.superpowers/sdd/2026-11-08-attestation-keystore2-m3-m4/progress.md` (and the M1-M2 one) — read on resume, trust it + `git log` over memory.
- **The STRONG acceptance test (M3-7 Step 4, M4-4) is blocked until the user supplies a valid keybox.** Do not fake it.

## Global Constraints

- **Keybox is user-provided** (spec §1): the Manager imports/validates/revocation-checks it; Cam never ships one. An absent/invalid/revoked keybox ⇒ the profile is skipped and its apps fall through to real hardware (the feature is inert, never a hazard).
- **Does NOT touch Widevine L1**; only Play Integrity STRONG via keystore2 attestation. Android 12+ only.
- **Flag default OFF** until a device is configured; only a configured device with a valid keybox turns it on. Default-on (if ever) is gated on a clean detector + bootguard run.
- **The frozen harvest** (`boot.json`) feeds the TA's KEK derivation, so verifiedBootKey/Hash/state must be stable per device; re-harvest only when `ro.build.fingerprint` changes.
- **Manager build needs `libcamd.so` in jniLibs first** (AGENTS.md); it also now needs the teesim artifacts there. All three are built from the same commit.
- **Kotlin/Manager gate:** `./gradlew :app:testDebugUnitTest` green; a release build only after `libcamd.so` + teesim artifacts are packed.
- **The discovered SELinux rule (M2-5):** `allow keystore adb_data_file file { read open getattr map execute }` is the one strictly required for inject on Android 16; persist it (M4-1), do not rely on a live `sepolicy patch`.
- **Git:** single branch `main`; stage paths explicitly (never `-a`/`-A`; the `uapi` junction shows as deleted). `<scope>: <summary>` commits (`manager`, `camd`, `docs`).

## Review Focus

- **A keybox that is valid XML but revoked / not Google-signed** — the Manager must warn clearly and still let the user proceed at their own risk (it is their keybox), not silently pretend STRONG. Test in M3-3 (revoked serial in the chain → `KeyboxStatus.Revoked`).
- **Harvest on a device whose attestation omits a field** (no module hash pre-A16, no StrongBox) — the harvest must emit the tag as absent, never a made-up default, or the attested root of trust is internally inconsistent. Test in M3-1 (an attestation extension missing optional tags → nulls, not zeros).
- **A target app with a work-profile clone** (same package, user 0 + user 10) — the resolver must emit distinct uid entries so one profile's key is not served to the other. Test in M3-2 (two uids for one package across users).
- **`push.json`/`boot.json` written while the daemon is mid-push** — the Manager writes atomically (temp + rename) so camd never reads a half-written file. Test in M3-5 (the writer leaves no partial file on failure).
- **Re-attest/purge targeting a key that is not the simulator's** — owner-scoped deletion must only evict the target app's own stale attest key, never an unrelated hardware key. Test in M4-2 (a non-target uid is left untouched).

---

## M3 — The Manager brain, packaging, and the real pipeline

### Task M3-1: Harvest the full root of trust

**Files:**
- Modify: `manager/app/src/main/java/cam/caesar/vn/data/model/KeyAttestation.kt` (extend `AttestationInfo` + `parseKeyDescription`)
- Create: `manager/app/src/main/java/cam/caesar/vn/attest/Harvester.kt`
- Test: `manager/app/src/test/java/cam/caesar/vn/attest/HarvesterTest.kt`

**Interfaces:**
- Consumes: the existing `parseKeyDescription`, `AttestationInfo`, and `AttestationCheck.attest()` (the throwaway attested EC key).
- Produces:
  - `AttestationInfo` gains `deviceLocked: Boolean`, `verifiedBootKeyB64: String?`, `verifiedBootHashB64: String?`, `attestationVersion: Int?`, `securityLevel: Int?` (0 SW/1 TEE/2 StrongBox), `moduleHashB64: String?` — each null/false when the extension omits it (RootOfTrust `[0]`=key, `[1]`=deviceLocked, `[3]`=hash; AuthorizationList security level + attestationVersion; A16 MODULE_HASH tag 724).
  - `data class HarvestRecord(verifiedBootKeyB64, verifiedBootHashB64, moduleHashB64, deviceLocked, verifiedBootState, strongBoxAvailable, attestVersionTee, attestVersionStrongBox, osVersion, osPatchLevel, vendorPatchLevel, bootPatchLevel, fingerprint)` + `fun HarvestRecord.toBootInfoJson(): String` (the `bootInfo` object shape of spec §3.2).
  - `fun harvest(): HarvestRecord?` (generates the throwaway key, reads its attestation; blocking, off the main thread) and `fun HarvestRecord.toJson()/fromJson()`.

- [ ] **Step 1: Write failing tests** in `HarvesterTest.kt`: `parses_root_of_trust_key_and_hash_and_lock` (a hand-built KeyDescription extension with RootOfTrust key/deviceLocked/state/hash → the new `AttestationInfo` fields); `omitted_module_hash_is_null` (no MODULE_HASH tag → `moduleHashB64 == null`, not ""); `boot_info_json_shape` (a `HarvestRecord` → JSON with `verifiedBootKey`/`verifiedBootHash`/`deviceLocked`/`verifiedBootState`/`attestVersionTee` keys matching spec §3.2).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*HarvesterTest*"` — Expected: FAIL (fields/functions absent).
- [ ] **Step 3: Implement** the `AttestationInfo` fields + `parseKeyDescription` extensions (read RootOfTrust `[0]`/`[1]`/`[3]`, the AuthorizationList security level + attestationVersion, the A16 module hash), and `Harvester` (`harvest()` builds on `AttestationCheck.attest()`; `toBootInfoJson` uses `org.json`). StrongBox availability: try a StrongBox-backed throwaway key; `attestVersionStrongbox` falls back to the TEE value if unavailable.
- [ ] **Step 4: Run** the tests — Expected: PASS. Then the full `:app:testDebugUnitTest`.
- [ ] **Step 5: Commit** — `manager: Harvest the full root of trust for attestation`.

### Task M3-2: Resolve target apps into the push config

**Files:**
- Create: `manager/app/src/main/java/cam/caesar/vn/attest/Resolver.kt`, `manager/app/src/main/java/cam/caesar/vn/attest/PatchLevel.kt`
- Test: `manager/app/src/test/java/cam/caesar/vn/attest/ResolverTest.kt`, `PatchLevelTest.kt`

**Interfaces:**
- Consumes: `HarvestRecord` (M3-1); a `PackageManager`-backed `fun packageUids(pkg: String): List<Pair<Int,Int>>` (uid, userId) seam so tests inject a fake.
- Produces:
  - `PatchLevel`: `fun osPatchLevelInt(yyyymm: Int): Int`, `fun datePatchLevelInt(yyyymmdd: Int): Int`, `fun osVersionInt(major, minor, sub): Int` — the KeyMint encodings (osPatchLevel `YYYYMM`, vendor/boot `YYYYMMDD`, osVersion `major*10000+minor*100+sub`).
  - `data class AttestProfile(id, keyboxB64, securityLevel, packages: List<String>, perUserTargets: ...)` and `fun resolvePush(profiles: List<AttestProfile>, harvest: HarvestRecord, uids: (String)->List<Pair<Int,Int>>): String` — the full `push.json` string (spec §3.2: `profiles[]` with parallel `packages`/`packageUsers`/`uids`/`uidPackages`, plus `bootInfo`).

- [ ] **Step 1: Write failing tests**: `PatchLevelTest` (osVersion 16.0.0 → 160000; osPatchLevel 2026-11 → 202611; boot 2026-11-05 → 20261105); `ResolverTest.work_profile_clone_gets_two_uid_entries` (a package resolving to [(10123,0),(1010123,10)] → two `uids` with matching `uidPackages`/`packageUsers`); `ResolverTest.push_json_has_bootinfo_and_profile` (shape check against spec §3.2).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*Resolver*" --tests "*PatchLevel*"` — Expected: FAIL.
- [ ] **Step 3: Implement** `PatchLevel` (pure arithmetic) and `Resolver` (JSON via `org.json`; the uid list comes from the injected seam, backed by `PackageManager.getPackagesForUid`/`getPackageUid` per user in production).
- [ ] **Step 4: Run** the tests — Expected: PASS. Then full `:app:testDebugUnitTest`.
- [ ] **Step 5: Commit** — `manager: Resolve attest target apps into the push config`.

### Task M3-3: Import, validate, and revocation-check the keybox

**Files:**
- Create: `manager/app/src/main/java/cam/caesar/vn/attest/KeyboxStore.kt`
- Test: `manager/app/src/test/java/cam/caesar/vn/attest/KeyboxStoreTest.kt`

**Interfaces:**
- Consumes: the existing `Revocation.kt` (`serialHex`, `findRevoked`, `parseRevocationList`) and `AttestationCheck.fetchRevocationList` pattern.
- Produces:
  - `sealed interface KeyboxStatus { object Valid; object Revoked; object NotGoogleSigned; data class Invalid(reason) }` (revoked/not-Google-signed are WARNINGS the user may override — spec §1).
  - `fun parseKeybox(xml: String): Keybox?` (both an `rsa` and an `ecdsa` key, each PEM PrivateKey + a chain ≥ 2; null on malformed) and `fun Keybox.statusAgainst(revoked: Map<String,String>?): KeyboxStatus`, `fun Keybox.toB64(): String` (the raw keybox XML, base64, for `keyboxB64`).

- [ ] **Step 1: Write failing tests**: `rejects_keybox_without_both_algos` (RSA only → null); `parses_valid_keybox` (a minimal RSA+ECDSA keybox → non-null, chains ≥ 2); `revoked_serial_is_revoked` (a chain whose serial is in the revoked map → `KeyboxStatus.Revoked`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*KeyboxStore*"` — Expected: FAIL.
- [ ] **Step 3: Implement** `KeyboxStore` (XML parse via the platform XML reader; cert chain via `CertificateFactory`; serial match reuses `Revocation.serialHex`/`findRevoked`).
- [ ] **Step 4: Run** the tests — Expected: PASS. Then full `:app:testDebugUnitTest`.
- [ ] **Step 5: Commit** — `manager: Parse and revocation-check the attestation keybox`.

### Task M3-4: Ship the interceptor artifacts with the Manager

**Files:**
- Modify: `manager/app/build.gradle.kts` (pack the teesim artifacts from jniLibs, like `libcamd.so`), `userspace/camd/src/assets.rs` or a new `userspace/camd/src/attest/deploy.rs` (copy them from the app's native lib dir to `/data/adb/cam/{lib,bin}`)
- Modify: `scripts/build_teesim.sh` consumers / a staging step that names `inject` as `libteesiminject.so` so Android packs+extracts it as a native lib
- Test: a camd host test for the deploy path's pure parts if any; otherwise device-verified in M3-7

**Interfaces:**
- Consumes: `native/teesim/out/<abi>/{inject,libteesim_keymint.so}` (M1-2).
- Produces: on device, `/data/adb/cam/lib/libteesim_keymint.so` (0600) and `/data/adb/cam/bin/inject` (0700), `restorecon`'d to `adb_data_file` — the paths camd's `defs::TEESIM_LIB`/`TEESIM_INJECT` already point at. A `deploy_teesim()` camd step run from `attest::start()`/first boot.

- [ ] **Step 1:** Decide the carry mechanism and write it: stage `native/teesim/out/<abi>/libteesim_keymint.so` and `.../inject` (renamed `libteesiminject.so`) into `manager/app/src/main/jniLibs/<abi>/` (gitignored like `libcamd.so`); `build.gradle.kts` already packs `jniLibs/*/*.so`. camd `deploy_teesim()` copies them from its own `nativeLibraryDir` sibling (resolved like the emulator's `load()` does: `pm path`/the app lib dir) to `/data/adb/cam/{lib,bin}`, chmod + restorecon, only when missing or the APK's is newer.
- [ ] **Step 2:** Wire `deploy_teesim()` into `attest::start()` before the daemon spawns (so the artifacts exist before inject). Rust gate (`cargo ndk check/clippy/fmt`, host tests).
- [ ] **Step 3: Verify** on the emulator (continues in M3-7): after installing the Manager, `/data/adb/cam/lib/libteesim_keymint.so` and `/bin/inject` exist with the right labels. Expected: both present, `adb_data_file`.
- [ ] **Step 4: Commit** — `manager: Ship the teesim interceptor artifacts` (+ a `camd:` commit for `deploy_teesim` if separate).

### Task M3-5: Root-service config writer + feature toggle

**Files:**
- Modify: `manager/app/src/main/aidl/cam/caesar/vn/ICamRootService.aidl`, `manager/app/src/main/java/cam/caesar/vn/ui/CamRootService.kt`, `manager/app/src/main/java/cam/caesar/vn/Cam.kt` (facade)
- Create: `manager/app/src/main/java/cam/caesar/vn/attest/AttestRepository.kt`
- Test: `manager/app/src/test/java/cam/caesar/vn/attest/AttestRepositoryTest.kt` (the atomic-write helper with a fake fs)

**Interfaces:**
- Consumes: M3-1/2/3 outputs (boot.json/push.json strings), camd's `attest enable/disable`.
- Produces: AIDL `boolean writeAttestConfig(String bootJson, String pushJson); boolean setAttestEnabled(boolean on); String attestStatus();` implemented in `CamRootService` (writes `/data/adb/cam/attest/{boot.json,push.json}` atomically via temp+rename+fsync as root, toggles the flag through camd, reads `camd attest status`). `AttestRepository` is the UI-facing wrapper over the `Cam` facade.

- [ ] **Step 1: Write a failing test** `AttestRepositoryTest.atomic_write_leaves_no_partial_file` for the pure temp+rename helper (inject a fake writer that throws mid-write → the destination is unchanged / the temp is cleaned).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*AttestRepository*"` — Expected: FAIL.
- [ ] **Step 3: Implement** the atomic-write helper, the AIDL methods + `CamRootService` impl, and the `Cam`/`AttestRepository` facade.
- [ ] **Step 4: Run** the test — Expected: PASS. Then full `:app:testDebugUnitTest`.
- [ ] **Step 5: Commit** — `manager: Add the attest config writer and toggle to the root service`.

### Task M3-6: The attestation screen

**Files:**
- Create: `manager/app/src/main/java/cam/caesar/vn/ui/screen/attest/AttestScreen.kt`, `AttestMiuix.kt`, `AttestViewModel.kt`, `AttestUiState.kt`
- Modify: navigation (wherever the Features/hiding screens register), `res/values/strings.xml`, `res/values-vi/strings.xml`
- Test: `AttestViewModelTest.kt` (state reducer, pure)

**Interfaces:**
- Consumes: `AttestRepository` (M3-5), `Harvester`/`Resolver`/`KeyboxStore` (M3-1/2/3), `checkAttestationReport` (existing).
- Produces: a screen to pick target apps (reuse the app picker used by App Profile), import a keybox (file picker → `KeyboxStore`, show `KeyboxStatus` with a warning on Revoked/NotGoogleSigned), toggle the feature, show status (injected? targets? last ack), and a "Kiểm tra attestation" button (reuses `AttestationResult`).

- [ ] **Step 1: Write a failing test** `AttestViewModelTest` for the pure state reducer (keybox import result → UI state; a Revoked keybox → a warning flag set).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*AttestViewModel*"` — Expected: FAIL.
- [ ] **Step 3: Implement** the ViewModel/state + the Compose screen (Miuix glass, following `ui/screen/hidingcheck/` and the Features bootloader card). Strings in both `values` and `values-vi`.
- [ ] **Step 4: Run** the test — Expected: PASS. Then full `:app:testDebugUnitTest`. Build the Manager (`libcamd.so` + teesim artifacts staged first) to confirm it compiles: `./gradlew :app:assembleDebug`.
- [ ] **Step 5: Commit** — `manager: Add the attestation screen`.

### Task M3-7: End-to-end pipeline on the emulator

**Files:** none (device procedure); record the outcome in this plan + `docs/CAM_CHANGES.md`.

**Interfaces:** Consumes everything above + the M1 x86_64 artifacts.

> **Safety:** inject can crash-loop keystore2 (recovery: `kill $(pidof keystore2)`). Keep the flag OFF until configured; this is a visible device change — say so, and leave a clean detector scan.

- [ ] **Step 1:** Build the Manager (debug) with the x86_64 `libcamd.so` + teesim artifacts staged; install on the emulator (`scripts/emu_y700.sh start <apk>` / `adb install -r`).
- [ ] **Step 2:** In the Manager: run a harvest (writes a REAL `boot.json` from the emulator's own RoT), pick a harmless target app, import a keybox (a dummy one is fine to prove the pipeline — a real keybox is the user's), enable the feature.
- [ ] **Step 3: Verify** camd auto-deploys the artifacts, injects, and pushes the Manager-resolved config: logcat shows the daemon inject + a `config` applied with the REAL bootInfo (not the hand-built one from M2-5); keystore2 stable. With a dummy keybox the profile is skipped (applied 0) but the real harvest flows through. Record the result.
- [ ] **Step 4:** (Gated on a real keybox, the user's step) with a valid keybox, the target app's Play Integrity returns `MEETS_STRONG_INTEGRITY` and the Manager's "Kiểm tra attestation" shows the keybox chain + locked/Verified. Document as the acceptance test; do not fake it.
- [ ] **Step 5:** Turn the flag OFF, confirm keystore2 clean, leave a clean `device_detector_scan.py` (if on the tablet). Record M3 done.

---

## M4 — Hardening and shipping

### Task M4-1: Persist the SELinux rule

**Files:**
- Modify: `userspace/camd/src/attest/mod.rs` (apply the rule when the feature starts) OR a shipped `sepolicy.rule` that camd's boot sepolicy loads; `userspace/camd/src/attest/` as needed
- Test: a camd host test for the rule-statement builder (pure string), if applied via the supercall path

**Interfaces:**
- Consumes: the M2-5 finding — `allow keystore adb_data_file file { read open getattr map execute }` (plus the `shell_data_file` staging-fallback pair, and the control-socket rules from spec §6 that a stricter device may need).
- Produces: `fn apply_attest_sepolicy() -> Result<()>` run from `attest::start()` before inject, applying the rules via the same supercall path `cli.rs` `sepolicy patch` uses (`parse_sepolicy` + the `ksu_uapi` cmds), idempotently.

- [ ] **Step 1: Write a failing host test** for the rule set: `attest_sepolicy_includes_keystore_so_read` (the builder's output contains the `allow keystore adb_data_file file` statement with `read open getattr map execute`).
- [ ] **Step 2: Run** `cargo test --target x86_64-unknown-linux-gnu attest` — Expected: FAIL.
- [ ] **Step 3: Implement** `apply_attest_sepolicy` (reuse `sepolicy::parse_sepolicy` + the supercall) and call it from `attest::start()` before `deploy_teesim`/spawn.
- [ ] **Step 4: Run** the test + the Rust gate (`cargo ndk check/clippy/fmt`, host suite).
- [ ] **Step 5: Commit** — `camd: Apply the attest SELinux rules at startup`.

### Task M4-2: Owner-scoped re-attestation of target keys

**Files:**
- Modify: `manager/app/src/main/aidl/cam/caesar/vn/ICamRootService.aidl`, `CamRootService.kt`; create `manager/app/src/main/java/cam/caesar/vn/attest/ReAttest.kt`
- Test: `ReAttestTest.kt` (the target-selection logic, pure)

**Interfaces:**
- Consumes: the resolved target uids (M3-2), keystore2 (via a `app_process`/`seteuid` helper mirroring TEESimulator's `App.runAsOwner`).
- Produces: AIDL `int purgeTargetAttestKeys(in int[] uids);` that, for each target uid, deletes its stale attest key AS ITS OWNER (seteuid → keystore2) so it is regenerated through the TA. Only target uids are touched (Review Focus).

- [ ] **Step 1: Write a failing test** `ReAttestTest.only_target_uids_are_selected` (a uid set + an installed-apps list → exactly the target uids, never others).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*ReAttest*"` — Expected: FAIL.
- [ ] **Step 3: Implement** the selection logic + the owner-scoped delete helper (seteuid child), wired into the AIDL + called after a committed push.
- [ ] **Step 4: Run** the test — Expected: PASS. Then full `:app:testDebugUnitTest`.
- [ ] **Step 5: Commit** — `manager: Re-attest target keys through the simulator after a push`.

### Task M4-3: Boot-prop integration + docs + the default-off gate

**Files:**
- Modify: `userspace/camd/src/attest/mod.rs` (force `ro.boot.vbmeta.*` from the harvest when attest is on, reusing `hide_bootloader`/`resetprop`), `docs/CAM_CHANGES.md` (new row; drop the "Tricky Store không có" note), `modules/sources.json` note if any
- Test: covered by existing hide_bootloader tests; a host test only if new pure logic is added

**Interfaces:**
- Consumes: `boot.json` (the harvested verifiedBoot values), the existing `hide_bootloader`/`vbmeta` prop path.
- Produces: when attest is enabled, camd forces the matching `ro.boot.vbmeta.*` to the attested values (so a prop-reading app agrees with the attested RoT), reusing the M1 bootloader-hiding path; the CAM_CHANGES documentation.

- [ ] **Step 1:** Implement the boot-prop step (reuse `hide_bootloader::apply_if_enabled`'s mechanism, driven by `boot.json`) and the docs. If a new pure helper appears, TDD it; otherwise this is integration + docs.
- [ ] **Step 2: Verify** — Rust gate; `docs/CAM_CHANGES.md` row reads correctly; the "Tricky Store không có vì…" note (CAM_CHANGES ~line 942) is updated to point at this feature.
- [ ] **Step 3: Commit** — `camd: Force vbmeta props from the attest harvest` + `docs: Record the keystore2 attestation feature`.

### Task M4-4: Release regression (device)

**Files:** none (device procedure); record in the plan + `docs/PROJECT_REVIEW.md`.

- [ ] **Step 1:** On the tablet (or emulator for what it covers): a full enable → STRONG → disable cycle with a real keybox; `scripts/device_bootguard_test.sh` with attest on (no permanent crash-loop); `python -X utf8 scripts/device_detector_scan.py` (Duck/Luna/Chunqiu) no worse than the baseline.
- [ ] **Step 2:** Keep the feature default-OFF; only flip default-on (if ever) after a clean regression, recorded here.

---

## Notes

- STRONG end-to-end (M3-7 Step 4, M4-4) is **gated on a real, non-revoked keybox** the user supplies; everything else is buildable and verifiable without one (the pipeline produces a real harvest + resolved config the interceptor accepts, proven with a dummy keybox as in M2-5).
- arm64 (the tablet) uses the same artifacts; build them with `scripts/build_teesim.sh` / CI `teesim.yml` and pack the arm64 `libcamd.so` + teesim `.so` into jniLibs.
- Keep the ledger from the M1-M2 plan's workspace; this plan gets its own `sdd-workspace`.
