# Managerless Kernel + Patch-Time Root Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove kernel-side manager APK detection entirely; the Manager becomes an ordinary root-granted app that gets root from a "seed" list chosen at init_boot patch time.

**Architecture:** The kernel no longer scans `/data/app` or verifies APK signatures; "manager" privilege means uid 0. At patch time the Manager app lets the user pick installed apps (itself always included) and passes them to `ksud boot-patch --seed pkg:appid`, which writes a `seed=` module parameter into the ramdisk `ksu_config`. On boot the kernel grants root to each seed entry whose package+appid matches `packages.list`, once per seed nonce. The Manager then gets root via `libksud.so debug su` (GRANT_ROOT is allowed for allowlisted uids), installs `/data/adb/ksud`, and runs every privileged JNI call inside its existing libsu `KsuService` (uid 0).

**Tech Stack:** Linux kernel C (LKM), Rust (ksud, clap), Kotlin/Compose + libsu RootService + AIDL, JNI C++.

**Spec:** No separate spec file. Decisions agreed with the user (2026-10-02 conversation), copied here:
- LKM (init_boot patch) is the only supported install mode. GKI / late-load / magica seed support is out of scope.
- The old path (kernel scans `/data/app`, checks APK v2 signature) is deleted, not kept behind a flag.
- First flash still happens via PC/fastboot as today.
- At patch time the user picks apps to root: from the installed-apps list or by typing a package name. Typed packages must be installed (appid must be resolvable). The Manager itself is always in the seed.
- After first boot, root grants/revokes are done from the Manager at runtime (no re-flash).
- Module flashing and module WebUI keep working through the Manager's root shell.

## Global Constraints

- Seed module param: name `seed`, value `<nonce>,<pkg>:<appid>[,<pkg>:<appid>...]`.
- `<nonce>`: exactly 16 lowercase hex chars, new random value on every patch that has `--seed`.
- `<pkg>`: 1..=255 chars from `[A-Za-z0-9._]`. `<appid>`: decimal, 10000..=19999. Max 32 entries.
- Seed marker file: `/data/adb/ksu/.seed`, content = the 16-char nonce, no newline.
- A seed is applied only when its nonce differs from the marker; entries are only added, never removed.
- An entry is granted only if `packages.list` has a line with the same package AND uid == appid.
- Granted entries use the default root profile (`allow_su = true`, `rp_config.use_default = true`, `version = KSU_APP_PROFILE_VER`).
- Bump `KERNEL_SU_UAPI_VERSION` in `uapi/supercall.h` from 4 to 5 (shared by kernel, ksud, Manager), so an old Manager or ksud is rejected by the existing UAPI checks.
- Rust: after changes run `cargo ndk -t arm64-v8a check`, `cargo ndk -t arm64-v8a clippy`, `cargo fmt` in `userspace/ksud` (AGENTS.md).
- Kernel: code must pass the repo `clang-format` check (`.github/workflows/clang-format.yml`).
- Commits: `<scope>: <summary>` with scopes `kernel`, `ksud`, `manager` (AGENTS.md).
- Keep edits ASCII.

## Review Focus

1. **Re-patch for an update.** User revoked root from app X in the Manager, later re-patches with X unselected. Expected: X stays revoked. Re-patch with the same seed string but a new nonce re-adds only the selected apps. (Task 2 step "nonce semantics", Task 3 test `build_seed_param_*`.)
2. **Package reinstalled between patch and boot.** The seed package was uninstalled and another app took its appid, or the same name came back with a new appid. Expected: no grant (package and appid must both match). (Task 2 device check 3.)
3. **First boot on a fresh device: `/data/adb/ksu` does not exist yet.** Expected: grants live in memory, so the Manager still gets root. Marker and allowlist writes fail without crashing, and the next boot re-applies the seed idempotently and persists it. (Task 2 device check 4.)
4. **Malformed `seed` param** (hand-edited ramdisk, truncated, bad appid, 40 entries). Expected: kernel logs and skips the bad entries or the whole seed, with no oops. ksud refuses to write a malformed seed. (Task 2 step 2, Task 3 tests.)
5. **Manager opened on a phone without KernelSU, or with root revoked.** Expected: no crash, and in particular no `reboot` syscall from an app uid (seccomp would kill the process). The UI falls back to patch-only mode. (Task 4 step "uid guard", Task 5 device check.)

---

### Task 1: Kernel: delete manager detection, uid 0 is the manager, keep allowlist pruning

**Files:**
- Delete: `kernel/manager/apk_sign.c`, `kernel/manager/apk_sign.h`, `kernel/manager/throne_tracker.c`, `kernel/manager/throne_tracker.h`, `kernel/manager/manager_observer.h`
- Move: `kernel/manager/pkg_observer.c` -> `kernel/policy/pkg_observer.c` (git mv)
- Create: `kernel/policy/pkg_observer.h`, `kernel/policy/pkg_tracker.c`, `kernel/policy/pkg_tracker.h`
- Modify: `kernel/manager/manager_identity.h`, `kernel/Kbuild:29-33,58-60,130-160`, `kernel/Kconfig:21-27`, `kernel/core/init.c:150-215`, `kernel/runtime/boot_event.c:30,69`, `kernel/hook/setuid_hook.c:30-39`, `kernel/policy/allowlist.c:263-266,297-300,344-346,392`, `kernel/supercall/dispatch.c:51-59,82-90,315-325`, `uapi/supercall.h:12`

**Interfaces:**
- Produces: `void ksu_pkg_tracker_update(void);` in `policy/pkg_tracker.h`. It reads `/data/system/packages.list`, then calls `ksu_prune_allowlist`. Task 2 adds seed apply inside it.
- Produces: `int ksu_observer_init(void); void ksu_observer_exit(void);` in `policy/pkg_observer.h`, same behavior as today except the callback calls `ksu_pkg_tracker_update()`.
- Produces: `manager_identity.h` exports only `static inline bool is_manager(void) { return current_uid().val == 0; }`. `is_uid_manager`, `ksu_*manager_appid*` and `ksu_invalidate_manager_uid` no longer exist.

- [ ] **Step 1: Move the packages.list parser into `policy/pkg_tracker.c`.** Take `struct uid_data`, the read loop and `is_uid_exist` from `throne_tracker.c:22-26,238-310` as they are. `ksu_pkg_tracker_update()` = override_creds(ksu_cred), parse into a list, `ksu_prune_allowlist(is_uid_exist, &list)`, free, revert_creds. No `search_manager`, no `crown_manager`, no apk hash cache.
- [ ] **Step 2: git mv `pkg_observer.c` to `policy/`, switch its include to `policy/pkg_tracker.h`, and call `ksu_pkg_tracker_update()` in place of `track_throne(false)`. Move the two prototypes from `manager_observer.h` into `policy/pkg_observer.h` with no `#ifdef`.**
- [ ] **Step 3: Delete the five manager files. Reduce `manager_identity.h` to the single `is_manager()` above. Update callers:**
  - `setuid_hook.c`: remove the `is_uid_manager(new_uid)` branch (lines 30-39) completely. No fd install for setresuid-to-0.
  - `allowlist.c`: remove the three manager special cases (lines 263-266, 297-300, 344-346), and drop `&& !is_uid_manager(...)` at line 392.
  - `dispatch.c`: `do_get_manager_appid` returns `KSU_INVALID_APPID` (-1), and the ioctl stays for ABI. Delete both `#ifdef EXPECTED_SIZE2 ... PR_BUILD` blocks.
  - `core/init.c` and `boot_event.c`: replace `ksu_throne_tracker_init/exit()` (remove), `track_throne(false)` and `track_throne(true)` with `ksu_pkg_tracker_update()`. Includes become `policy/pkg_observer.h` and `policy/pkg_tracker.h`.
- [ ] **Step 4: Kbuild/Kconfig.** Remove the `CONFIG_KSU_DISABLE_MANAGER` object block and ccflag, every `KSU_EXPECTED_SIZE*`, `KSU_EXPECTED_HASH*` and `KSU_MANAGER_PACKAGE` block (lines ~130-160), and the Kconfig entry. Add `policy/pkg_tracker.o` and `policy/pkg_observer.o` to `kernelsu-objs`.
- [ ] **Step 5: Bump `KERNEL_SU_UAPI_VERSION` to 5 in `uapi/supercall.h`.**
- [ ] **Step 6: Verify nothing references the removed symbols.**
  Run: `rg -n "throne|apk_sign|is_uid_manager|manager_appid|EXPECTED_(SIZE|HASH)|KSU_DISABLE_MANAGER|KSU_MANAGER_PACKAGE|manager_observer" kernel`
  Expected: no output, except `do_get_manager_appid` and the `KSU_IOCTL_GET_MANAGER_APPID` table entry in dispatch.c.
- [ ] **Step 7: Build the LKM.** Push the branch and run the `build-lkm` workflow (or `ddk-lkm.yml` locally if the DDK container is set up). Run the `clang-format` workflow or `clang-format --dry-run --Werror` on changed files.
  Expected: build green for every KMI in the matrix, and clang-format clean.
- [ ] **Step 8: Commit**
```bash
git add -A kernel uapi/supercall.h
git commit -m "kernel: drop manager apk detection, uid 0 is the manager"
```

### Task 2: Kernel: `seed` module param, grant on boot once per nonce

**Files:**
- Modify: `kernel/policy/pkg_tracker.c`, `kernel/policy/pkg_tracker.h`

**Interfaces:**
- Consumes: `ksu_pkg_tracker_update()` and its parsed `uid_data` list (Task 1). `ksu_set_app_profile(struct app_profile *)` and `ksu_persistent_allow_list()` from `policy/allowlist.h`. `ksu_boot_completed`.
- Produces: module param `seed` (charp, perm 0) in the format from Global Constraints. ksud (Task 3) writes it, and ksuinit passes it unchanged via `/ksu_config`.

- [ ] **Step 1: Declare `static char *ksu_seed; module_param_named(seed, ksu_seed, charp, 0);` in `pkg_tracker.c`.**
- [ ] **Step 2: Implement `static void ksu_seed_apply(struct list_head *pkgs)` in `pkg_tracker.c`.** Order:
  1. Return if `!ksu_seed`, `!ksu_boot_completed`, or the seed fails validation. Validate on a `kstrdup` copy, never modify `ksu_seed`. Validation uses the exact rules in Global Constraints. If the nonce or the entry count is invalid, reject the whole seed. If one entry is invalid, skip only that entry. Log with `pr_warn("seed: ...")`.
  2. Read `/data/adb/ksu/.seed` (16 bytes). If it equals the nonce, log `seed: nonce %s already applied` and return.
  3. For each entry: if `pkgs` has the same package with `uid == appid`, build the default root profile (Global Constraints) and call `ksu_set_app_profile`, then log `seed: granted %s(%u)`. Otherwise log `seed: skip %s(%u): not installed or uid mismatch`.
  4. If anything was granted, call `ksu_persistent_allow_list()`. Then write the nonce to `/data/adb/ksu/.seed` (O_WRONLY|O_CREAT|O_TRUNC, 0600, under `ksu_cred`, same pattern as `do_persistent_allow_list` in allowlist.c:411). If the write fails, log and continue: the next boot re-applies the seed, and re-granting is idempotent.
- [ ] **Step 3: Call `ksu_seed_apply(&list)` in `ksu_pkg_tracker_update()` after parsing and before `ksu_prune_allowlist`.**
- [ ] **Step 4: Build, as in Task 1 step 7.** Expected: green, and clang-format clean.
- [ ] **Step 5: Device checks** (any arm64 GKI device; patch with Task 3's `ksud boot-patch --seed`, or by hand-editing `ksu_config` in the ramdisk with magiskboot). Read `dmesg | grep seed:`:
  1. Seed `<termux>:<its appid>`, boot: expect `seed: granted`, and `su` works in Termux.
  2. Reboot without re-patching: expect `already applied`. Revoke Termux in the Manager, reboot: expect Termux still revoked.
  3. Seed with a wrong appid for an installed package: expect `skip ... uid mismatch`, and no grant.
  4. Fresh device (`rm -rf /data/adb/ksu` then reboot): expect `granted` plus a marker-write error log, and no oops. After the Manager runs `ksud install`, reboot: expect `granted` again and `/data/adb/ksu/.seed` present.
  5. Malformed `seed=zz,foo` (bad nonce): expect a warn and no grant, and no oops.
- [ ] **Step 6: Commit**
```bash
git add kernel/policy/pkg_tracker.c kernel/policy/pkg_tracker.h
git commit -m "kernel: grant root to patch-time seed packages on boot"
```

### Task 3: ksud: `boot-patch --seed`, remove manager debug commands

**Files:**
- Create: `userspace/ksud/src/seed.rs`
- Modify: `userspace/ksud/src/main.rs` (add `mod seed;`), `userspace/ksud/src/boot_patch.rs:420-515,731-763`, `userspace/ksud/src/cli.rs:183-196,701-702`, `userspace/ksud/src/debug.rs` (remove `set_manager` and its appid helper)
- Delete: `userspace/ksud/src/apk_sign.rs` and the `Debug::GetSign` arm (no remaining consumer)

**Interfaces:**
- Produces (in `seed.rs`):
  - `#[derive(Clone, Debug, PartialEq)] pub struct SeedEntry { pub package: String, pub appid: u32 }`
  - `pub fn parse_seed_entry(s: &str) -> anyhow::Result<SeedEntry>`: parses `pkg:appid` and applies the Global Constraints rules. Also used as the clap `value_parser`.
  - `pub fn build_seed_param(nonce: &str, entries: &[SeedEntry]) -> anyhow::Result<String>`: returns `seed=<nonce>,pkg:appid,...`. Errors if `entries` is empty, has more than 32 entries, or the nonce is not 16 lowercase hex.
  - `pub fn random_nonce() -> anyhow::Result<String>`: 8 bytes from `/dev/urandom`, hex encoded.
- Produces CLI: `ksud boot-patch ... --seed <PKG:APPID>` (repeatable). The Manager (Task 6) passes one `--seed` per app.

- [ ] **Step 1: Write the failing tests in `seed.rs` `#[cfg(test)] mod tests`:**
```rust
#[test] fn parse_ok() { assert_eq!(parse_seed_entry("com.termux:10234").unwrap(), SeedEntry { package: "com.termux".into(), appid: 10234 }); }
#[test] fn parse_rejects() {
    for bad in ["com.termux", "com termux:10234", "a:9999", "a:20000", ":10001", "a:", "a:1x", "a-b:10001"] {
        assert!(parse_seed_entry(bad).is_err(), "{bad}");
    }
    assert!(parse_seed_entry(&format!("{}:10001", "a".repeat(256))).is_err());
}
#[test] fn build_seed_param_ok() {
    let e = |p: &str, a| SeedEntry { package: p.into(), appid: a };
    assert_eq!(build_seed_param("0123456789abcdef", &[e("a", 10001), e("b.c", 10002)]).unwrap(),
               "seed=0123456789abcdef,a:10001,b.c:10002");
}
#[test] fn build_seed_param_rejects() {
    let e = SeedEntry { package: "a".into(), appid: 10001 };
    assert!(build_seed_param("0123456789abcdef", &[]).is_err());
    assert!(build_seed_param("0123456789abcdef", &vec![e.clone(); 33]).is_err());
    assert!(build_seed_param("0123456789ABCDEF", &[e.clone()]).is_err());
    assert!(build_seed_param("short", &[e]).is_err());
}
#[test] fn nonce_shape() {
    let (a, b) = (random_nonce().unwrap(), random_nonce().unwrap());
    assert_eq!(a.len(), 16); assert!(a.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())); assert_ne!(a, b);
}
```
- [ ] **Step 2: Run the tests on a Linux host (WSL is fine) and check they fail.**
  Run: `cd userspace/ksud && cargo test seed::`
  Expected: compile failure, because the functions are not defined yet.
- [ ] **Step 3: Implement the three functions in `seed.rs`.**
- [ ] **Step 4: Run the tests again.** Run: `cargo test seed::`. Expected: 5 passed.
- [ ] **Step 5: Wire into boot-patch.** Add to `BootPatchArgs`: `#[arg(long = "seed", value_parser = crate::seed::parse_seed_entry)] seed: Vec<SeedEntry>`. In the `ksu_config` block (boot_patch.rs:731-763), when `seed` is non-empty, do `ksu_config.retain(|v| !v.starts_with("seed="))`, push `build_seed_param(&random_nonce()?, &seed)?`, and print `- Adding seed for N app(s)`. When `seed` is empty, leave any existing `seed=` entry untouched.
- [ ] **Step 6: Remove `Debug::SetManager`, `Debug::GetSign`, `debug::set_manager` and `apk_sign.rs`.**
- [ ] **Step 7: Run the AGENTS.md checks.** Run `cargo ndk -t arm64-v8a check`, then `cargo ndk -t arm64-v8a clippy`, then `cargo fmt`, all in `userspace/ksud`. Expected: no errors and no warnings.
- [ ] **Step 8: Run a manual patch on a stock init_boot.img** (host or device): `ksud boot-patch -b init_boot.img --seed com.termux:10234 --kmi <kmi> -o out/`. Check the result with `magiskboot unpack` + `magiskboot cpio ramdisk.cpio "extract ksu_config ksu_config"`. Expected: the file contains `seed=<16hex>,com.termux:10234`.
- [ ] **Step 9: Commit**
```bash
git add -A userspace/ksud
git commit -m "ksud: add boot-patch --seed and drop manager debug commands"
```

### Task 4: Manager: privileged JNI runs only inside KsuService (uid 0)

**Files:**
- Modify: `manager/app/src/main/cpp/ksu.cc:21-75`, `manager/app/src/main/aidl/me/weishu/kernelsu/IKsuInterface.aidl`, `manager/app/src/main/java/me/weishu/kernelsu/ui/KsuService.kt`
- Create: `manager/app/src/main/java/me/weishu/kernelsu/Ksu.kt` (UI facade), `manager/app/src/main/java/me/weishu/kernelsu/KsuServiceClient.kt` (binder holder)
- Modify: every non-service caller of `Natives.<fun/property>` (list: `rg -l "Natives\.[a-z]" manager/app/src/main/java`), plus `data/repository/SuperUserRepositoryImpl.kt:24-150` (use `KsuServiceClient` in place of its private `connectKsuService`)

**Interfaces:**
- Consumes: kernel `is_manager()` == uid 0 (Task 1). `KSU_INSTALL_MAGIC1/2` from `uapi/supercall.h`.
- Produces:
  - AIDL `IKsuInterface`: keep `getPackages` and `getUserIds`, and add one method per `Natives` external, except `isManager`, `getUserName` and `managerUAPIVersion`, which stay in-process. Names: `getVersion()`, `getKernelUapiVersion()`, `isSafeMode()`, `isLkmMode()`, `isLkmBundled()`, `isLateLoadMode()`, `isPrBuild()`, `uidShouldUmount(int)`, `String getAppProfile(String key, int uid)`, `boolean setAppProfile(String profileJson)`, `isSuEnabled()/setSuEnabled(boolean)`, `isKernelUmountEnabled()/setKernelUmountEnabled(boolean)`, `isSelinuxHideEnabled()/int setSelinuxHideEnabled(boolean)`, `int getSuperuserCount()`. `Profile` crosses the binder as JSON via `kotlinx.serialization` (`Natives.Profile` is already `@Serializable`).
  - `object KsuServiceClient { val service: IKsuInterface?; suspend fun connect(): Boolean }`. It binds `KsuService` once and returns false if root is unavailable.
  - `object Ksu`: has the same names as the old `Natives` runtime API (`version`, `isSafeMode`, `getAppProfile(key, uid): Natives.Profile`, ...). It delegates to `KsuServiceClient.service` and returns safe defaults (0 / false / an empty `Profile`) when the service is null or a `RemoteException` occurs. It adds `val isAvailable: Boolean` (service bound and `getVersion() > 0`). `fun isFullFeatured() = isAvailable && kernelUAPIVersion == Natives.managerUAPIVersion`.
  - `Natives` keeps its JNI externals, constants and `Profile`. Only `KsuService` calls its externals.

- [ ] **Step 1: uid guard plus fd install in `ksu.cc`.** In `ksuctl`, after `scan_driver_fd()` fails, only if `getuid() == 0`, call `syscall(SYS_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &fd)`. This mirrors `init_driver_fd` in `userspace/ksud/src/ksucalls.rs:120`. Never issue `reboot` from a non-zero uid, because seccomp kills app processes.
- [ ] **Step 2: Extend `IKsuInterface.aidl` and implement it in `KsuService.Stub` by calling `Natives`.**
- [ ] **Step 3: Add `KsuServiceClient` and `Ksu`. Point `SuperUserRepositoryImpl` at `KsuServiceClient`.**
- [ ] **Step 4: Replace UI call sites `Natives.<runtime api>` with `Ksu.<same name>`.** Leave `Natives.Profile`, `Natives.ROOT_*`, `Natives.KERNEL_SU_DOMAIN` and `Natives.managerUAPIVersion` unchanged. Then run `rg -n "Natives\.(version|is[A-Z]|get|set|uid)" manager/app/src/main/java | rg -v "ui/KsuService.kt|Natives.kt|managerUAPIVersion"`. Expected: no output.
- [ ] **Step 5: Build.** Put `libksud.so` in `jniLibs` first (AGENTS.md), then run `cd manager && ./gradlew assembleRelease`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Commit**
```bash
git add -A manager
git commit -m "manager: run privileged ksu calls in root service"
```

### Task 5: Manager: "has root" replaces "is manager"

**Files:**
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt:131-132`, `ui/component/KsuValidCheck.kt`, `ui/navigation3/IntentDispatcher.kt:154`, `ui/viewmodel/HomeViewModel.kt:49`, `Natives.kt` (delete `isManager` and `isFullFeatured`), `cpp/jni.cc` (delete the `isManager` JNI export)

**Interfaces:**
- Consumes: `Ksu.isAvailable`, `Ksu.isFullFeatured()`, `KsuServiceClient.connect()` (Task 4). `install()` and `rootAvailable()` from `ui/util/KsuCli.kt`.

- [ ] **Step 1: MainActivity startup.** Replace the synchronous `isManager && uapi -> install()` with a coroutine on `Dispatchers.IO`: `if (rootAvailable() && KsuServiceClient.connect() && Ksu.isFullFeatured()) install()`. On first boot, `install()` is what creates `/data/adb/ksud`.
- [ ] **Step 2: Replace the remaining `Natives.isManager` reads with `Ksu.isAvailable`. Delete `Natives.isManager` and its JNI export.**
- [ ] **Step 3: Build, as in Task 4 step 5.**
- [ ] **Step 4: Device check.**
  - (a) Install the APK on a phone without KernelSU and open it: expect no crash, the "not installed" home state, and the Install screen reachable.
  - (b) On a patched phone where the Manager was seeded: on first open, expect `/data/adb/ksud` to exist afterwards, the Superuser, Module and Settings tabs enabled, a module zip to install, and its WebUI to open.
  - (c) Revoke the Manager's own root from Superuser, then relaunch: expect patch-only mode and no crash.
- [ ] **Step 5: Commit**
```bash
git add -A manager
git commit -m "manager: gate features on root access instead of manager identity"
```

### Task 6: Manager: pick apps to root at patch time

**Files:**
- Modify: `manager/app/src/main/AndroidManifest.xml` (add `android.permission.QUERY_ALL_PACKAGES`), `ui/util/KsuCli.kt:283-292,293-360,362-450` (`bootPatchFlags`, `installBoot`, `downloadBoot`), `ui/screen/install/InstallMaterial.kt`, `ui/screen/install/InstallMiuix.kt` and their view model/state, `res/values/strings.xml` (+ `values-vi`)
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/install/SeedPicker.kt` (shared state + validation)

**Interfaces:**
- Consumes: `ksud boot-patch --seed <pkg:appid>` (Task 3).
- Produces: `data class SeedApp(val packageName: String, val appId: Int)`, and `fun resolveSeedApp(pm: PackageManager, packageName: String): SeedApp?`, which returns null when the package is not installed. `appId = applicationInfo.uid % 100000`.
- `bootPatchFlags(..., seeds: List<SeedApp>)` appends ` --seed ${it.packageName}:${it.appId}` for each seed. The Manager's own `SeedApp` is always first and cannot be removed.

- [ ] **Step 1: Add `QUERY_ALL_PACKAGES` and `SeedPicker.kt`.** Typed input is validated with the same regex as ksud (`[A-Za-z0-9._]{1,255}`) and then `resolveSeedApp`. Show the strings `seed_not_installed` ("App chua duoc cai, hay cai truoc khi patch") and `seed_invalid_name`. Cap the list at 32 entries, including the Manager.
- [ ] **Step 2: Install screen, both Material and Miuix.** Add a "Root apps" section: a searchable checklist of installed apps (label, package, icon) and a "add by package name" field. The Manager's row is checked and disabled.
- [ ] **Step 3: Thread `seeds` through `installBoot` and `downloadBoot` into `bootPatchFlags`.**
- [ ] **Step 4: Build, as in Task 4 step 5.**
- [ ] **Step 5: Device check.** On an unrooted phone, select Termux and type `com.example.notinstalled`: expect the inline error and the entry not added. Patch a picked `init_boot.img`. The log should show `- Adding seed for 2 app(s)`. Fastboot flash and boot: expect `dmesg | grep seed:` to show both granted, Manager full-featured on first open, and `su` working in Termux.
- [ ] **Step 6: Commit**
```bash
git add -A manager
git commit -m "manager: choose root apps when patching init_boot"
```

### Task 7: End-to-end pass on a clean device

- [ ] **Step 1: Run the full flow on a device wiped of KernelSU:** stock init_boot, then Manager patch with seeds, PC flash, first boot, Manager opens with root, flash a module, open its WebUI, grant root to a new app from Superuser, reboot, and check that the grant persists.
- [ ] **Step 2: Update flow:** with root, use Install, then "Direct install" with a different seed selection, and reboot. Expect newly selected apps granted, previously revoked unselected apps still revoked, and `dmesg` showing the new nonce applied.
- [ ] **Step 3: Record the results (pass/fail per step, dmesg excerpts) in the PR description.**
