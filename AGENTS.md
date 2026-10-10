# Cam Kernel SU Agent Guide

## Agent Quick Start

- For significant features or refactors, sketch an Plan first; keep it updated as you work.
- Use Context7 to pull library/API docs when you touch unfamiliar crates, Android APIs, or JS deps.
- Default to `rg` for searching and keep edits ASCII unless the file already uses non-ASCII.
- Run the component-specific checks below before handing work off; do not skip failing steps.
- When unsure which path to take, favor minimal risk changes that keep kernel/userspace contracts intact.

## Project Overview

Cam Kernel SU is a fork of KernelSU: a kernel-based root solution for Android with a kernel module, Rust userspace daemons, a Kotlin Manager app, and docs/web assets. Everything it changes from upstream, and how to merge upstream, is in `docs/CAM_CHANGES.md`; read it before touching code upstream also changes. Names were changed to Cam (package `cam.su.kernel`, daemon `camd`, data `/data/adb/cam`, LKM `camsu.ko`); kernel-side symbols keep their KernelSU names on purpose (section 11 of that file).

## Repository Structure

```bash
/kernel/                      # Kernel module - C code for Linux kernel integration
/uapi/                        # Kernel/userspace ABI headers (manager/app/src/main/cpp/uapi links here)
/userspace/camd/              # Userspace daemon - Rust binary for userspace-kernel communication
/userspace/caminit/           # Early init helper that loads the LKM - Rust binary
/manager/                     # Android manager app - Kotlin/Jetpack Compose UI (Miuix glass only)
/docs/                        # CAM_CHANGES.md (fork notes, merge guide), translated READMEs, plans
/website/                     # Documentation website - VitePress
/js/                          # JavaScript library for module WebUI
/.github/workflows/           # CI/CD workflows for building and testing
/scripts/                     # Build and maintenance scripts (Python, shell, sed)
```

## Core Concepts

- **supercall**: Kernel-side IOCTL interface exposed by the `[ksu_driver]` anon-inode and installed via the reboot kprobe hook in `kernel/supercall/supercall.c`, with handlers in `kernel/supercall/dispatch.c`. It maps commands like allowlist/app-profile management, feature toggles, and sepolicy changes. Rust userspace reaches it through `userspace/camd/src/ksucalls.rs` (scans or installs the FD, wraps IOCTLs), while the Manager JNI bridge mirrors the same IOCTLs in `manager/app/src/main/cpp/ksu.cc`. The kernel takes manager supercalls from uid 0 only, so the Manager makes every kernel call through its root service (`manager/app/src/main/java/cam/su/kernel/ui/CamRootService.kt`, AIDL `ICamRootService`); UI code goes through the `Cam` facade (`Cam.kt`), never `CamNative` directly.
- **module**: A flashable ZIP unpacked by `userspace/camd/src/module.rs` into `/data/adb/modules/` (`userspace/camd/src/defs.rs`), with lifecycle scripts (`post-fs-data.sh`, `service.sh`, etc.) executed by camd init events (`userspace/camd/src/init_event.rs`). The Android Manager surfaces module state from camd in `manager/app/src/main/java/cam/su/kernel/ui/viewmodel/ModuleViewModel.kt`.
- **metamodule**: A special module marked by `metamodule=1` in `module.prop` (installed separately; this repo ships no metamodule of its own). camd enforces a single active metamodule, creates `/data/adb/metamodule -> /data/adb/modules/<id>` symlink, and delegates mounting/meta install hooks via `userspace/camd/src/metamodule.rs`; metamodule scripts run before regular modules in `userspace/camd/src/init_event.rs`. The Manager UI highlights metamodules and warns on uninstall (`manager/app/src/main/java/cam/su/kernel/ui/screen/module/ModuleMiuix.kt`).
- **app profile**: Per-app policy struct defined in `kernel/policy/app_profile.h` and validated/persisted in `kernel/policy/allowlist.c` to control root grants and non-root behavior (e.g., cumulative umount policy). supercall IOCTLs `KSU_IOCTL_GET/SET_APP_PROFILE` live in `kernel/supercall/dispatch.c` and are consumed by camd/Manager via the JNI bridge (`manager/app/src/main/cpp/ksu.cc`) and Kotlin model `CamNative.Profile` (`manager/app/src/main/java/cam/su/kernel/CamNative.kt`).
- **sucompat**: Exec/FS compatibility layer that reroutes `/system/bin/su` to camd for allowed UIDs, keeping legacy “call su to root” flows working. The hooks live in `kernel/feature/sucompat.c` and are registered by the syscall hook manager (`kernel/hook/`); feature toggle `KSU_FEATURE_SU_COMPAT` is exposed through supercalls and surfaced to the Manager via `manager/app/src/main/cpp/ksu.cc` (`is_su_enabled` / `set_su_enabled`).
- **allowlist**: Kernel-managed list of UIDs permitted for root, persisted at `/data/adb/cam/.allowlist` with default root/non-root profiles. Core logic is in `kernel/policy/allowlist.c` (bitmap storage, persistence, default profile caching) and is initialized from `kernel/core/init.c`; supercall handlers in `kernel/supercall/dispatch.c` expose getters, deny-list checks, and “should umount modules” decisions. Manager reads and edits it through the JNI calls in `manager/app/src/main/cpp/ksu.cc` and the `Cam` facade. The Manager package itself is pinned to root by name in `kernel/policy/pkg_tracker.c`.
- **root hiding check**: The Manager page in `manager/app/src/main/java/cam/su/kernel/ui/screen/hidingcheck/` (also opened per app from the App Profile) combines three views: `camd hiding-audit` (`userspace/camd/src/hiding_audit.rs`; `--uid` looks through one running app, `--rules` takes the Manager's rules), an isolated-process probe (`manager/app/src/main/java/cam/su/kernel/hiding/`, raw syscalls in `manager/app/src/main/cpp/hiding_probe.cc`), and app profiles that keep module mounts. What it looks for comes from the signed `manager/app/src/main/assets/hiding-rules.json`; change it only with `scripts/sign_hiding_rules.py` (section 12 of `docs/CAM_CHANGES.md`).

## Component Workflows

### Kernel (`kernel/`)

- Kernel changes are C-only; keep interfaces aligned with supercall and allowlist expectations in userspace/Manager.
- If you alter IOCTLs or profiles, update the corresponding wrappers in camd (`ksucalls.rs`) and Manager JNI (`manager/app/src/main/cpp/ksu.cc`).
- Host tests for Cam's own kernel logic (`policy/pkg_tracker.c`: seed, Manager pin): `make -C kernel/tests` on Linux/WSL (ASan + UBSan). `make check-format` covers `kernel/tests` too.
- `feature/selinux_hide.c` hooks must refuse before doing extra work (permission check first): apps time these paths (Duck Detector's `attr/current` probe; section 12 of `docs/CAM_CHANGES.md`).

### Userspace Rust (`userspace/camd`, `userspace/caminit`)

For Rust projects in `userspace/camd` and `userspace/caminit`, ALWAYS run these commands in sequence after making code changes (on Windows, run them in WSL; `docs/CAM_CHANGES.md` section 5 has the setup):

1. `cargo ndk -t arm64-v8a check` (verify compilation)
2. `cargo ndk -t arm64-v8a clippy` (lints and warnings)
3. `cargo fmt` (format)
4. Fix any errors or warnings before considering the task complete.
5. Host tests (WSL): `cd userspace/camd && cargo test --target x86_64-unknown-linux-gnu`. Two tests fail before any change of yours (`embedded_module_uses_release_asset_layout`: missing CI-only `.ko`; `rejects_conflicting_loading_module_values`: stale upstream test); anything else failing is yours. CI runs the same tests (`.github/workflows/test.yml`, skipping those two).

### Android Manager App (`manager/`)

```bash
cd manager
# Must have camd binaries first!
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../userspace/camd/target/aarch64-linux-android/release/camd app/src/main/jniLibs/arm64-v8a/libcamd.so

# Then build
./gradlew clean assembleRelease
```

Unit tests: `./gradlew :app:testDebugUnitTest` (15 classes, 73 tests, all pass on `76fdc061`; CI runs them in `test.yml`). See `docs/PROJECT_REVIEW.md` for status and open risks.

Important: Manager build REQUIRES camd binaries to be present in `jniLibs` before building. Gradle packs whatever `libcamd.so` is there and never rebuilds it: after changing camd, copy a fresh build in, or the APK ships the old daemon (CI builds both from the same commit). From CI, install the `manager` artifact, never `manager-gradle` (no `libcamd.so` in it). On Windows, run Gradle from the `K:` drive (`docs/CAM_CHANGES.md` section 5).

### Website (`website/`)

```bash
cd website
# Using bun (preferred)
bun install
bun run docs:build  # Production build
```

### JavaScript Web UI (`js/`)

- JS packages back module WebUI pieces; follow existing package manager lockfile and run the relevant lint/test scripts before publishing changes.

## Common Pitfalls

- Only one metamodule can be active; keep meta hooks in sync with camd expectations.
- Manager JNI mirrors every supercall; kernel or camd API changes must be reflected there to avoid runtime drift.
- Do not skip the `cargo ndk` steps; plain `cargo check` will not validate Android targets.
- Manager builds fail if `libcamd.so` is missing; create it before any Gradle command.
- On Windows checkouts `manager/app/src/main/cpp/uapi` is a junction: `git status` always shows it deleted. Never commit it, and never run `git checkout`, `git stash` or `git clean` on it: git would delete `uapi/*.h` at the repo root.
- `hiding-rules.json` is signed byte for byte (`.gitattributes` marks it `-text`): edit it, bump `version`, re-sign, or the Manager rejects the download and `HidingRulesRepositoryTest` fails.

## Git Commit

- Mirror existing history style: `<scope>: <summary>` with a short lowercase scope tied to the touched area (e.g., `kernel`, `camd`, `caminit`, `manager`, `docs`, `scripts`). Keep the summary concise, sentence case, and avoid trailing period.
- Prefer one scope; if multiple areas change, pick the primary one rather than chaining scopes. For doc-only changes use `docs:`; for multi-lang string updates use `translations:` if that matches log history.
- Keep subject lines brief (target ≤72 chars), no body unless necessary. If referencing a PR/issue, append `(#1234)` at the end as seen in history.
- Before committing, glance at recent `git log --oneline` to stay consistent with current prefixes and capitalization used in this repo.
