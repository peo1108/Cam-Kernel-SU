# OTA Manager Updates + Changelog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tagging `cam-vX.Y.Z` publishes a GitHub Release whose notes come from `CHANGELOG.md`; installed Managers notice it in the background, show the notes, download, verify and install the APK (root first, system installer as fallback), then show "What's new" once.

**Architecture:** CI side: a Python section extractor gates the Release workflow and feeds `body_path`; version names come from `git describe --match "cam-v*"`. App side: a new `cam.su.kernel.update` package holds pure parsers (`parseReleases`, `ChangelogParser`, `WhatsNew`) with JVM tests, plus Android glue (`UpdateRepository`, `UpdateInstaller`, `UpdateCheckWorker`, `UpdateNotifier`, `UpdatedReceiver`). Home keeps its existing `UpdateCard` and gains a "What's new" dialog.

**Tech Stack:** GitHub Actions, Python 3 (stdlib only), Kotlin/Compose (Miuix), okhttp, org.json, libsu, AndroidX WorkManager, Rust `build.rs` (version string only).

**Spec:** `docs/superpowers/specs/2026-10-10-ota-update-design.md` (Vietnamese). Two deviations agreed after reading the code, already written back into the spec:
- 5.1: the OTA download does not reuse `DownloadService` (it is wired to MediaStore and module-install notifications); `UpdateInstaller` downloads with okhttp into `cacheDir/ota/` itself.
- 5.3: KernelSU's `su` shell stays in the app's cgroup and dies when Android kills the app during self-replacement. The install runs in a detached root shell moved to the root cgroup, writing its result to a file; reopening the app is best effort, and a `MY_PACKAGE_REPLACED` receiver posts an "updated, tap to see what's new" notification as the reliable path.

## Global Constraints

- Release tags: `cam-v*`; first release `cam-v3.0.0`. Upstream tags (`v*`) must never trigger a release.
- Version name = `git describe --tags --always --match "cam-v*"` minus the `cam-v` prefix. versionCode stays `30000 + git rev-list --count HEAD`.
- APK asset name pattern (already produced by `archivesName` + `repack_apk.py`): `^Cam_Kernel_SU_.+_(\d+)-release\.apk$`.
- Releases API: `GET https://api.github.com/repos/peo1108/Cam-Kernel-SU/releases?per_page=30`, header `Accept: application/vnd.github+json`.
- `CHANGELOG.md` at repo root; entry heading `## <major.minor.patch> - <YYYY-MM-DD>`; Vietnamese text; first `- ` line of an entry is the notification summary.
- Package `cam.su.kernel`, activity `cam.su.kernel/.ui.CamActivity`.
- Background check: unique periodic work `cam-update-check`, every 12 h, `NetworkType.CONNECTED`, `ExistingPeriodicWorkPolicy.KEEP`; never retries on failure.
- Notification channel id `app_update`, name from string `update_channel_name` ("App updates" / "Cập nhật ứng dụng").
- Prefs (in the existing `settings` SharedPreferences via `SettingsRepository`): `notified_version_code` (Long, default 0), `last_seen_version` (String?, default null), `asked_notification_permission` (Boolean, default false).
- Never offer a versionCode `<=` the running one.
- UI strings: add to `values/strings.xml` (English) and `values-vi/strings.xml`; other locales fall back to English.
- No kernel, supercall or uapi changes. Never `git commit -a`; stage paths explicitly (the `manager/app/src/main/cpp/uapi` junction shows as deleted and must not be committed).
- Python on this Windows machine: `python3` is the Store stub; use `py` (3.12) or WSL `python3`. CI uses `python3`.
- Checks per `AGENTS.md`: Manager `cd manager && ./gradlew :app:testDebugUnitTest` (Windows: from the `K:` drive, `docs/CAM_CHANGES.md` section 5); camd `cargo ndk -t arm64-v8a check`, `cargo ndk -t arm64-v8a clippy`, `cargo fmt` in WSL.

## Review Focus

1. **CRLF checkouts.** On Windows, `CHANGELOG.md` is checked out with CRLF and Gradle packs that copy; both the Python extractor and `ChangelogParser` must give identical results for CRLF and LF input. Tests in Task 1 and Task 3.
2. **GitHub error bodies.** Rate limiting returns HTTP 403 with a JSON *object* (`{"message":"API rate limit exceeded..."}`), not an array; `parseReleases` must return `null`, not throw. Test in Task 2.
3. **Dev and untagged builds.** Before any `cam-v` tag, the version name is a bare hash (`21536a17`); between tags it is `3.0.0-5-gabc1234`. Neither may show "What's new" or crash semver parsing. Tests in Task 3 and Task 6.
4. **Semver vs. string order.** `3.10.0` is newer than `3.9.0`; skipped versions (3.0.0 → 3.2.0) show 3.2.0 then 3.1.0. Test in Task 3.
5. **Self-replacement.** The app is killed mid-install; the install must still complete and the user must still learn it worked. Covered by the detached shell + `UpdatedReceiver` in Task 5 and the on-device check in Task 7 (not unit-testable).

---

### Task 1: CHANGELOG.md, section extractor, Release workflow, version names

**Files:**
- Create: `CHANGELOG.md`
- Create: `scripts/changelog_section.py`
- Create: `scripts/test_changelog_section.py`
- Modify: `.github/workflows/release.yml`
- Modify: `.github/workflows/test.yml` (new `scripts` job + paths)
- Modify: `manager/build.gradle.kts:23-35` (`getGitDescribe`, `getVersionName`)
- Modify: `userspace/camd/build.rs:25-32` (`get_git_version` name part)

**Interfaces:**
- Produces: `python3 scripts/changelog_section.py <version> [path]` prints the entry body (lines after the `## ` heading up to the next `## ` heading, leading/trailing blank lines stripped, `\n` line endings) and exits 0; exits 1 with a message on stderr when the entry is missing or empty. Python API `section(text: str, version: str) -> str | None`.
- Produces: `CHANGELOG.md` with a `3.0.0` entry (Task 3 tests read it).

- [ ] **Step 1: Write the failing tests** in `scripts/test_changelog_section.py` (`unittest`, imports `changelog_section` from the same directory via `sys.path`):
  - `test_returns_middle_entry`: text with `## 3.0.1 - 2026-10-20`, `## 3.0.0 - 2026-10-12`; `section(text, "3.0.0")` equals that entry's body only.
  - `test_last_entry_runs_to_eof`.
  - `test_missing_entry_is_none`: `section(text, "9.9.9") is None`.
  - `test_empty_entry_is_none`: heading followed directly by the next heading.
  - `test_crlf_matches_lf`: same text with `\r\n` gives the same result as with `\n`.
  - `test_version_is_not_a_prefix_match`: asking for `3.0.1` does not match a `## 3.0.10 - ...` heading.
  - `test_cli_exit_codes`: `subprocess.run([sys.executable, script, "9.9.9", tmpfile])` returns 1; existing version returns 0 and prints the body.

- [ ] **Step 2: Run to verify they fail**
  Run: `python3 -m unittest scripts/test_changelog_section.py -v`
  Expected: errors (`ModuleNotFoundError: changelog_section`).

- [ ] **Step 3: Implement `scripts/changelog_section.py`**: normalize `\r\n` to `\n`, match headings with `^## (\d+\.\d+\.\d+)(\s|$)`, CLI defaults the path to `CHANGELOG.md` next to the repo root (`Path(__file__).resolve().parent.parent`).

- [ ] **Step 4: Run tests to verify they pass** (same command). Expected: 7 tests OK.

- [ ] **Step 5: Write `CHANGELOG.md`**: title `# Nhật ký cập nhật Cam Kernel SU`, one entry `## 3.0.0 - <date of the tag, leave today's date for now>` with `### Tính năng mới` listing at least "Tự báo và cài bản cập nhật ngay trong app (OTA)" and "Màn \"Có gì mới\" sau mỗi lần cập nhật"; `### Lưu ý`: "Đây là bản cuối cùng phải cài tay; từ 3.0.1 app tự cập nhật." Leave the rest of the 3.0.0 notes for the user to fill (they choose the release contents); verify `python3 scripts/changelog_section.py 3.0.0` prints it.

- [ ] **Step 6: Release workflow** (`.github/workflows/release.yml`):
  - `on.push.tags: ["cam-v*"]`; keep `workflow_dispatch`.
  - New first job `changelog` (`ubuntu-latest`, `contents: read`): fail with a clear message unless `github.ref_name` starts with `cam-v`; checkout; `python3 scripts/changelog_section.py "${GITHUB_REF_NAME#cam-v}" > release-notes.md`; upload artifact `release-notes`.
  - `build-manager.needs: changelog`; `release.needs: [changelog, build-manager]`.
  - In `release`: the existing `actions/download-artifact` already downloads every artifact (so `release-notes/release-notes.md` appears); in `softprops/action-gh-release` remove `generate_release_notes`, add `body_path: release-notes/release-notes.md` and `name: Cam Kernel SU ${{ github.ref_name }}` with the `cam-v` stripped (compute it in a prior step into `$GITHUB_OUTPUT`). Keep `permissions: contents: write` and the `files:` list unchanged.

- [ ] **Step 7: Test workflow**: add job `scripts` to `.github/workflows/test.yml` running `python3 -m unittest scripts/test_changelog_section.py -v`; add `scripts/changelog_section.py`, `scripts/test_changelog_section.py`, `CHANGELOG.md` to both `paths` lists.

- [ ] **Step 8: Version names**
  - `manager/build.gradle.kts`: `getGitDescribe()` runs `git describe --tags --always --match cam-v*` (pass the pattern as its own argv element, no shell quoting); `getVersionName()` returns it with `removePrefix("cam-v")`.
  - `userspace/camd/build.rs`: same `--match cam-v*` args; replace `trim_start_matches('v')` with stripping the `cam-v` prefix (`trim().strip_prefix("cam-v").unwrap_or(trimmed)`).

- [ ] **Step 9: Verify**
  - `git describe --tags --always --match "cam-v*"` prints a bare hash today (no `cam-v` tag yet).
  - WSL, `userspace/camd`: `cargo ndk -t arm64-v8a check`, `cargo ndk -t arm64-v8a clippy` (no new warnings), `cargo fmt --check`.
  - `cd manager && ./gradlew :app:assembleDebug` succeeds; `aapt2 dump badging` (or the build output file name) shows versionName = the hash.
  - Workflow YAML: run `actionlint` if available, else re-read both files for indentation.

- [ ] **Step 10: Commit**
```bash
git add CHANGELOG.md scripts/changelog_section.py scripts/test_changelog_section.py .github/workflows/release.yml .github/workflows/test.yml manager/build.gradle.kts userspace/camd/build.rs
git commit -m "ci: Release from cam-v tags with notes from CHANGELOG.md"
```

---

### Task 2: Release parsing (`UpdateInfo`, `parseReleases`)

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/update/UpdateInfo.kt`
- Test: `manager/app/src/test/java/cam/su/kernel/update/UpdateParserTest.kt`

**Interfaces:**
- Produces:
```kotlin
package cam.su.kernel.update

data class UpdateInfo(
    val versionName: String,   // tag minus "cam-v", e.g. "3.0.1"
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,        // browser_download_url
    val apkSize: Long,
    val sha256: String?,       // lowercase hex from asset "digest": "sha256:<hex>", else null
    val changelog: String,     // release "body", "" if absent
) {
    /** First "- " line of the changelog without the marker, or null. */
    val summary: String?
}

fun parseReleases(json: String, currentVersionCode: Long): UpdateInfo?
```

- [ ] **Step 1: Write the failing tests** (JUnit4, build JSON inline with small helpers; `org.json` test dependency already exists):
  - `picksHighestCamRelease`: releases `cam-v3.0.1` (code 32900) listed *before* `cam-v3.0.2` (32950); current 32800 → `versionName == "3.0.2"`, `versionCode == 32950L`.
  - `ignoresNonCamTags`: only `sfs-32787` and `v3.3.0` releases with matching APK names → `null`.
  - `ignoresDraftAndPrerelease`.
  - `ignoresReleaseWithoutMatchingApk`: `cam-v3.0.1` whose only asset is `camd-aarch64-linux-android` → `null`.
  - `notNewerReturnsNull`: release code equal to current → `null`; lower → `null`.
  - `readsDigestAndSize`: asset `"digest": "sha256:ABCDEF..."`, `"size": 1234` → `sha256 == "abcdef..."`, `apkSize == 1234L`; asset without `digest` → `sha256 == null`.
  - `rateLimitObjectReturnsNull`: `{"message":"API rate limit exceeded for 1.2.3.4."}` → `null`.
  - `garbageReturnsNull`: `"not json"` → `null`.
  - `summaryIsFirstBullet`: changelog `"### Tính năng mới\r\n- Thêm OTA\n- Khác"` → `summary == "Thêm OTA"`; no bullet → `null`.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd manager && ./gradlew :app:testDebugUnitTest --tests cam.su.kernel.update.UpdateParserTest`
  Expected: compilation failure (`UpdateInfo` unresolved).

- [ ] **Step 3: Implement** in `UpdateInfo.kt` with `org.json`; wrap parsing in `runCatching { ... }.getOrNull()`; the APK regex from Global Constraints.

- [ ] **Step 4: Run tests to verify they pass** (same command). Expected: 9 tests PASS.

- [ ] **Step 5: Commit**
```bash
git add manager/app/src/main/java/cam/su/kernel/update/UpdateInfo.kt manager/app/src/test/java/cam/su/kernel/update/UpdateParserTest.kt
git commit -m "manager: Parse Cam releases from the GitHub API"
```

---

### Task 3: Changelog parsing + packing CHANGELOG.md into the APK

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/update/ChangelogParser.kt`
- Modify: `manager/app/build.gradle.kts` (copy task + assets source dir)
- Test: `manager/app/src/test/java/cam/su/kernel/update/ChangelogParserTest.kt`

**Interfaces:**
- Consumes: `CHANGELOG.md` (Task 1).
- Produces:
```kotlin
package cam.su.kernel.update

data class ChangelogEntry(val version: String, val date: String, val body: String)

/** major.minor.patch of a pure semver string, or null ("3.0.0-5-gabc", hashes, "v3.0.0"). */
fun parseSemver(version: String): Triple<Int, Int, Int>?   // compare with compareValuesBy(first, second, third)

object ChangelogParser {
    const val ASSET_PATH = "changelog/CHANGELOG.md"
    fun parse(markdown: String): List<ChangelogEntry>              // file order, CRLF-safe
    /** Entries with lastSeen < version <= current, newest first. lastSeen null = no lower bound. */
    fun entriesNewerThan(entries: List<ChangelogEntry>, lastSeen: String?, current: String): List<ChangelogEntry>
}
```

- [ ] **Step 1: Write the failing tests:**
  - `parsesEntriesInFileOrder`: two entries → versions `["3.0.1", "3.0.0"]`, dates, bodies without the heading line.
  - `crlfMatchesLf`.
  - `semverRejectsNonPure`: `parseSemver("3.0.0-5-gabc1234") == null`, `parseSemver("21536a17") == null`, `parseSemver("3.10.0") == Triple(3, 10, 0)`.
  - `newerThanOrdersBySemver`: entries in file order `3.9.0, 3.10.0, 3.2.0` (deliberately unsorted); `entriesNewerThan(e, "3.2.0", "3.10.0")` → versions `["3.10.0", "3.9.0"]`.
  - `skippedVersionsAreAggregated`: `lastSeen "3.0.0"`, `current "3.2.0"`, entries `3.2.0, 3.1.0, 3.0.0` → `["3.2.0", "3.1.0"]`.
  - `nonSemverCurrentGivesNothing`: `current "3.0.0-5-gabc"` → empty.
  - `shippedChangelogParses`: `ChangelogParser.parse(File("../../CHANGELOG.md").readText())` is non-empty, first entry version passes `parseSemver`, its body is non-blank (runs from `manager/app`, like `HidingRulesRepositoryTest`).

- [ ] **Step 2: Run to verify they fail**
  Run: `cd manager && ./gradlew :app:testDebugUnitTest --tests cam.su.kernel.update.ChangelogParserTest`
  Expected: compilation failure.

- [ ] **Step 3: Implement `ChangelogParser.kt`.** Heading regex `^## (\d+\.\d+\.\d+)\s+-\s+(\S+)`; body trimmed of blank lines at both ends.

- [ ] **Step 4: Run tests to verify they pass.** Expected: 7 tests PASS.

- [ ] **Step 5: Pack the file.** In `manager/app/build.gradle.kts`: register `val copyChangelog by tasks.registering(Sync::class)` from `rootProject.file("../CHANGELOG.md")` into `layout.buildDirectory.dir("generated/changelog/changelog")`; add `layout.buildDirectory.dir("generated/changelog")` to `android.sourceSets["main"].assets` dirs; make `preBuild` depend on `copyChangelog`.

- [ ] **Step 6: Verify packing.** `./gradlew :app:assembleDebug`, then `unzip -l app/build/outputs/apk/debug/*.apk | grep changelog/CHANGELOG.md` shows the file.

- [ ] **Step 7: Commit**
```bash
git add manager/app/src/main/java/cam/su/kernel/update/ChangelogParser.kt manager/app/src/test/java/cam/su/kernel/update/ChangelogParserTest.kt manager/app/build.gradle.kts
git commit -m "manager: Parse CHANGELOG.md and pack it into the APK"
```

---

### Task 4: UpdateRepository and Home update card

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/data/repository/UpdateRepository.kt`, `UpdateRepositoryImpl.kt`
- Modify: `manager/app/src/main/java/cam/su/kernel/ui/util/Downloader.kt` (delete `checkNewVersion`)
- Delete: `manager/app/src/main/java/cam/su/kernel/ui/util/module/LatestVersionInfo.kt`
- Modify: `ui/screen/home/HomeUiState.kt` (`latestVersionInfo: LatestVersionInfo` → `update: UpdateInfo? = null`; `hasUpdate` = `update != null`), `ui/viewmodel/HomeViewModel.kt:44-51,95`, `ui/screen/home/HomeMiuix.kt:198-230,616-617` (previews too)

**Interfaces:**
- Consumes: `parseReleases`, `UpdateInfo` (Task 2).
- Produces:
```kotlin
interface UpdateRepository { suspend fun fetchLatest(): Result<UpdateInfo?> }
class UpdateRepositoryImpl : UpdateRepository  // okhttp via camApp.okhttpClient, Dispatchers.IO, BuildConfig.VERSION_CODE as current
```
  `HomeUiState.update: UpdateInfo?`; `HomeActions.onUpdateClick: () -> Unit = {}` (wired in Task 5).

- [ ] **Step 1: Implement `UpdateRepository`/`UpdateRepositoryImpl`.** No network (`isNetworkAvailable(camApp)` false) → `Result.success(null)`; non-2xx → `Result.failure(IOException("HTTP <code>"))`; body → `parseReleases`. Bypass the okhttp disk cache for this call (`CacheControl.FORCE_NETWORK`) so a stale cached list cannot hide a release.

- [ ] **Step 2: Replace the old checker.** Delete `checkNewVersion()` and `LatestVersionInfo`; `HomeViewModel(settingsRepo, updateRepo: UpdateRepository = UpdateRepositoryImpl())` sets `update = updateRepo.fetchLatest().getOrNull()` when `checkUpdateEnabled`.

- [ ] **Step 3: Card text and dialog.** `UpdateCard` message = `stringResource(R.string.new_version_available, update.versionName)`; tapping shows the existing markdown confirm dialog (title `R.string.module_changelog`, content `update.changelog`, confirm `R.string.module_update`) whose confirm calls `actions.onUpdateClick` (no longer `onOpenUrl`). Empty changelog: confirm dialog still shown with content `null`.

- [ ] **Step 4: Verify.** `./gradlew :app:testDebugUnitTest` (all existing + new tests pass) and `./gradlew :app:assembleDebug`. `rg "tiann/KernelSU/releases|LatestVersionInfo" manager/app/src` returns nothing.

- [ ] **Step 5: Commit**
```bash
git add manager/app/src/main/java/cam/su/kernel/data/repository/UpdateRepository.kt manager/app/src/main/java/cam/su/kernel/data/repository/UpdateRepositoryImpl.kt manager/app/src/main/java/cam/su/kernel/ui/util/Downloader.kt manager/app/src/main/java/cam/su/kernel/ui/util/module/LatestVersionInfo.kt manager/app/src/main/java/cam/su/kernel/ui/screen/home/HomeUiState.kt manager/app/src/main/java/cam/su/kernel/ui/screen/home/HomeMiuix.kt manager/app/src/main/java/cam/su/kernel/ui/viewmodel/HomeViewModel.kt
git commit -m "manager: Check Cam releases instead of upstream KernelSU"
```

---

### Task 5: Download, verify and install

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/update/UpdateInstaller.kt`
- Create: `manager/app/src/main/java/cam/su/kernel/update/UpdatedReceiver.kt`
- Create: `manager/app/src/main/java/cam/su/kernel/update/UpdateNotifier.kt`
- Modify: `manager/app/src/main/AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES`; receiver for `android.intent.action.MY_PACKAGE_REPLACED`, `exported="false"`)
- Modify: `CamApplication.kt` (call `UpdateInstaller.cleanup(this)` and `UpdateNotifier.createChannel(this)` after the isolated/locked early return)
- Modify: `ui/screen/home/HomeScreen.kt` (wire `onUpdateClick`, `onSystemInstallClick`), `HomeUiState.kt` (`installState: UpdateState = UpdateState.Idle`), `HomeMiuix.kt` (`UpdateCard` shows progress / error), `HomeViewModel.kt` (collect `UpdateInstaller.state`)
- Modify: `values/strings.xml`, `values-vi/strings.xml`
- Test: `manager/app/src/test/java/cam/su/kernel/update/UpdateInstallerTest.kt`

**Interfaces:**
- Consumes: `UpdateInfo` (Task 2).
- Produces:
```kotlin
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Verifying : UpdateState
    data object Installing : UpdateState
    data class Failed(val reason: UpdateFailure, val detail: String? = null) : UpdateState
}
enum class UpdateFailure { DOWNLOAD, CHECKSUM, PACKAGE, SIGNATURE, VERSION, INSTALL }

object UpdateInstaller {
    val state: StateFlow<UpdateState>
    fun start(context: Context, info: UpdateInfo)          // no-op unless state is Idle or Failed
    fun installWithSystemInstaller(context: Context)        // uses the verified APK still in cacheDir/ota
    fun cleanup(context: Context)                           // deletes cacheDir/ota/*
}
internal fun sha256Hex(file: File): String                   // lowercase hex
internal fun rootInstallScript(apk: File, size: Long, result: File): String

object UpdateNotifier {
    const val CHANNEL_ID = "app_update"
    const val EXTRA_SHOW_UPDATE = "cam.su.kernel.extra.SHOW_UPDATE"
    fun createChannel(context: Context)
    fun notifyAvailable(context: Context, info: UpdateInfo)  // Task 6 uses it
    fun notifyUpdated(context: Context, versionName: String)
}
```

- [ ] **Step 1: Write the failing tests:**
  - `sha256OfKnownBytes`: temp file containing `abc` → `"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"`.
  - `installScriptShape`: `rootInstallScript(File("/data/x/ota/a.apk"), 1234, File("/data/x/ota/result"))` contains `cgroup.procs`, `cat '/data/x/ota/a.apk' | pm install -r -S 1234`, writes to `'/data/x/ota/result'`, and `am start -n cam.su.kernel/.ui.CamActivity` appears only after the install succeeds (`&&` follows the `grep -q Success` check).

- [ ] **Step 2: Run to verify they fail**
  Run: `cd manager && ./gradlew :app:testDebugUnitTest --tests cam.su.kernel.update.UpdateInstallerTest`
  Expected: compilation failure.

- [ ] **Step 3: Implement `UpdateInstaller`.** Own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`; flow:
  1. `cleanup`, then okhttp GET `info.apkUrl` into `cacheDir/ota/<apkName>`, publishing `Downloading(percent)` (from `apkSize` when content length is unknown). Error → `Failed(DOWNLOAD)`.
  2. `Verifying`: if `info.sha256 != null` and `sha256Hex` differs → delete, `Failed(CHECKSUM)`. `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`: null or package ≠ `context.packageName` → `Failed(PACKAGE)`; `longVersionCode != info.versionCode` → `Failed(VERSION)`; signer set ≠ the installed package's (`getPackageInfo(packageName, GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners`, compare as sets of `Signature`) → `Failed(SIGNATURE)`. Delete the file on each failure.
  3. `Installing`: if `Shell.isAppGrantedRoot() == true`, write `rootInstallScript(...)` to `cacheDir/ota/install.sh` (a file, so its single-quoted paths need no nested quoting) and run `setsid sh '<ota>/install.sh' >/dev/null 2>&1 &` through `getRootShell()`, then poll `result` every 500 ms for up to 120 s. Content containing `Success` → keep state `Installing` (the process is about to be killed). Other content → `Failed(INSTALL, firstLine)`. Timeout → `Failed(INSTALL, null)`. No root → `installWithSystemInstaller`.
  - `rootInstallScript` body (this is the exact shape the test pins; `$$` is the subshell's pid):
```sh
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null; echo $$ > /acct/cgroup.procs 2>/dev/null; cat '<apk>' | pm install -r -S <size> > '<result>' 2>&1; grep -q Success '<result>' && am start -n cam.su.kernel/.ui.CamActivity
```
  - `installWithSystemInstaller`: `ACTION_VIEW`, `FileProvider.getUriForFile(context, "${packageName}.fileprovider", apk)` (existing `cache-path "."` already covers `ota/`), MIME `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK`.

- [ ] **Step 4: Implement `UpdateNotifier` and `UpdatedReceiver`.** Channel `app_update`, importance default. Notifications are skipped when `NotificationManagerCompat.areNotificationsEnabled()` is false. Tapping either notification opens `CamActivity` with `EXTRA_SHOW_UPDATE = true` (`notifyAvailable`) or plain (`notifyUpdated`; Home's "What's new" logic in Task 6 does the rest). `UpdatedReceiver.onReceive` → `notifyUpdated(context, BuildConfig.VERSION_NAME)`.

- [ ] **Step 5: Home wiring.** `onUpdateClick` → `UpdateInstaller.start(context, state.update!!)`. `UpdateCard` while `Downloading` shows `R.string.update_downloading` with the percent; `Verifying`/`Installing` → `R.string.update_installing`; `Failed` → message per reason (strings below) and tapping retries `start`; for `Failed(INSTALL)` also offer `R.string.update_use_system_installer` (confirm dialog) → `installWithSystemInstaller`.
  New strings (en / vi): `update_channel_name` App updates / Cập nhật ứng dụng; `update_downloading` Downloading update… %d%% / Đang tải bản cập nhật… %d%%; `update_installing` Installing update… / Đang cài bản cập nhật…; `update_failed_download` Download failed, tap to retry / Tải thất bại, bấm để thử lại; `update_failed_checksum` The download was corrupted, tap to retry / Tải về bị lỗi, bấm để thử lại; `update_failed_package` The file is not a Cam Kernel SU update / File tải về không phải bản cập nhật Cam Kernel SU; `update_failed_signature` The update is signed with a different key / Bản cập nhật ký bằng khóa khác; `update_failed_version` The file's version does not match the release / Phiên bản trong file không khớp với bản phát hành; `update_failed_install` Install failed: %s / Cài thất bại: %s; `update_use_system_installer` Install with the Android installer / Cài bằng trình cài đặt Android; `update_available_title` Cam Kernel SU %s is available / Cam Kernel SU %s đã có; `update_done_title` Updated to Cam Kernel SU %s / Đã cập nhật lên Cam Kernel SU %s; `update_done_text` Tap to see what's new / Chạm để xem có gì mới.

- [ ] **Step 6: Run tests.** `./gradlew :app:testDebugUnitTest` → all pass; `./gradlew :app:assembleDebug` succeeds.

- [ ] **Step 7: Commit**
```bash
git add manager/app/src/main/java/cam/su/kernel/update/ manager/app/src/test/java/cam/su/kernel/update/UpdateInstallerTest.kt manager/app/src/main/AndroidManifest.xml manager/app/src/main/java/cam/su/kernel/CamApplication.kt manager/app/src/main/java/cam/su/kernel/ui/screen/home/ manager/app/src/main/java/cam/su/kernel/ui/viewmodel/HomeViewModel.kt manager/app/src/main/res/values/strings.xml manager/app/src/main/res/values-vi/strings.xml
git commit -m "manager: Download, verify and install updates in the app"
```

---

### Task 6: Background check, notification permission, "What's new"

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/update/UpdateCheckWorker.kt`
- Create: `manager/app/src/main/java/cam/su/kernel/update/WhatsNew.kt`
- Create: `manager/app/src/main/java/cam/su/kernel/update/UpdateSignal.kt`
- Modify: `manager/gradle/libs.versions.toml`, `manager/app/build.gradle.kts` (WorkManager)
- Modify: `data/repository/SettingsRepository.kt`, `SettingsRepositoryImpl.kt` (three prefs from Global Constraints)
- Modify: `CamApplication.kt` (schedule), `ui/viewmodel/SettingsViewModel.kt:130-133` (`setCheckUpdate` schedules/cancels)
- Modify: `ui/CamActivity.kt:178,332-336` (extra → `UpdateSignal`)
- Modify: `ui/screen/home/HomeScreen.kt`, `HomeMiuix.kt`, `HomeViewModel.kt` (permission ask, update dialog on signal, "What's new" dialog)
- Modify: `values/strings.xml`, `values-vi/strings.xml`
- Test: `manager/app/src/test/java/cam/su/kernel/update/WhatsNewTest.kt`

**Interfaces:**
- Consumes: `ChangelogParser`, `ChangelogEntry`, `parseSemver` (Task 3); `UpdateRepository` (Task 4); `UpdateNotifier` (Task 5).
- Produces:
```kotlin
object UpdateScheduler {                       // in UpdateCheckWorker.kt
    const val WORK_NAME = "cam-update-check"
    fun apply(context: Context, enabled: Boolean)   // enqueueUniquePeriodicWork(KEEP) or cancelUniqueWork
}
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)

/** Pure decision for Home. */
data class WhatsNewDecision(val show: List<ChangelogEntry>, val saveLastSeen: String?)
fun decideWhatsNew(lastSeen: String?, current: String, entries: List<ChangelogEntry>): WhatsNewDecision

object UpdateSignal {                          // process-wide one-shot
    val showUpdateDialog: StateFlow<Boolean>
    fun request()
    fun consume()
}
```

- [ ] **Step 1: Write the failing tests** for `decideWhatsNew` (rules from spec 6.2):
  - `freshInstallSavesWithoutShowing`: `(null, "3.0.1", e)` → `show` empty, `saveLastSeen == "3.0.1"`.
  - `upgradeShowsNewerEntries`: `("3.0.0", "3.2.0", entries 3.2.0/3.1.0/3.0.0)` → show `["3.2.0","3.1.0"]`, save `"3.2.0"`.
  - `devBuildDoesNothing`: `("3.0.0", "3.0.0-5-gabc1234", e)` → empty, `saveLastSeen == null`; same for `"21536a17"`.
  - `sameVersionDoesNothing`: `("3.0.1", "3.0.1", e)` → empty, `saveLastSeen == null`.
  - `upgradeWithoutEntrySavesSilently`: `("3.0.0", "3.0.1", entries only 3.0.0)` → empty, save `"3.0.1"`.
  - `downgradeSavesSilently`: `("3.1.0", "3.0.1", e)` → empty, save `"3.0.1"`.

- [ ] **Step 2: Run to verify they fail**
  Run: `cd manager && ./gradlew :app:testDebugUnitTest --tests cam.su.kernel.update.WhatsNewTest`
  Expected: compilation failure.

- [ ] **Step 3: Implement `WhatsNew.kt`** using `ChangelogParser.entriesNewerThan`.

- [ ] **Step 4: Run tests to verify they pass.** Expected: 6 tests PASS.

- [ ] **Step 5: Worker and scheduling.** Add `androidx-work-runtime-ktx` (`androidx.work:work-runtime-ktx`, newest stable release at least two weeks old; check Google Maven). `UpdateCheckWorker.doWork`: `UpdateRepositoryImpl().fetchLatest().getOrNull()`; if non-null and `versionCode > settings.notifiedVersionCode` → `UpdateNotifier.notifyAvailable`, store the code; always `Result.success()`. `CamApplication.onCreate` (after the early return) calls `UpdateScheduler.apply(this, settings.checkUpdate)`; `SettingsViewModel.setCheckUpdate` calls it with the new value.

- [ ] **Step 6: Notification tap → dialog.** `CamActivity` calls `UpdateSignal.request()` when the launch intent (in `onCreate` with `savedInstanceState == null`, and in `onNewIntent`) has `UpdateNotifier.EXTRA_SHOW_UPDATE`. Home: when `showUpdateDialog` is true and `state.update != null`, open the Task 4 changelog dialog and `consume()`; if `update` is still null (Home's fetch not done), wait for it rather than consuming.

- [ ] **Step 7: Notification permission.** In `HomeScreen`, on SDK ≥ 33, when `checkUpdateEnabled`, permission not granted and `!askedNotificationPermission`: launch `RequestPermission(POST_NOTIFICATIONS)` once and set the pref (same launcher pattern as `ui/screen/module/ModuleScreen.kt:82`).

- [ ] **Step 8: "What's new" dialog.** `HomeViewModel` on first `refresh()`: read `ChangelogParser.ASSET_PATH` from assets, `decideWhatsNew(settings.lastSeenVersion, BuildConfig.VERSION_NAME, entries)`, save `saveLastSeen` when non-null, expose `whatsNew: List<ChangelogEntry>` in `HomeUiState` (default empty). `HomeMiuix`: when non-empty, show the markdown confirm dialog, title `R.string.whats_new_title`, content = entries joined as `"### ${version}\n\n${body}"` separated by blank lines, plus `R.string.whats_new_lkm` appended when `state.showLkmUpdate`; buttons: confirm `R.string.whats_new_reinstall_lkm` (→ `actions.onInstallClick`) and dismiss `R.string.close` when `showLkmUpdate`, else only confirm = close. Dismissing clears `whatsNew` in the ViewModel. Reuse an existing close string if one exists (`rg 'name="close"' manager/app/src/main/res/values/strings.xml`).
  New strings (en / vi): `whats_new_title` What's new / Có gì mới; `whats_new_lkm` This version ships a new LKM. / Bản này đi kèm LKM mới.; `whats_new_reinstall_lkm` Reinstall LKM / Cài lại LKM.

- [ ] **Step 9: Run all tests and build.** `./gradlew :app:testDebugUnitTest` → all pass; `./gradlew :app:assembleRelease` (with `libcamd.so` in `jniLibs`, per `AGENTS.md`) succeeds.

- [ ] **Step 10: Commit**
```bash
git add manager/app/src/main/java/cam/su/kernel/update/ manager/app/src/test/java/cam/su/kernel/update/WhatsNewTest.kt manager/gradle/libs.versions.toml manager/app/build.gradle.kts manager/app/src/main/java/cam/su/kernel/data/repository/SettingsRepository.kt manager/app/src/main/java/cam/su/kernel/data/repository/SettingsRepositoryImpl.kt manager/app/src/main/java/cam/su/kernel/CamApplication.kt manager/app/src/main/java/cam/su/kernel/ui/viewmodel/SettingsViewModel.kt manager/app/src/main/java/cam/su/kernel/ui/CamActivity.kt manager/app/src/main/java/cam/su/kernel/ui/screen/home/ manager/app/src/main/java/cam/su/kernel/ui/viewmodel/HomeViewModel.kt manager/app/src/main/res/values/strings.xml manager/app/src/main/res/values-vi/strings.xml
git commit -m "manager: Check for updates in the background and show what's new"
```

---

### Task 7: Docs and on-device check

**Files:**
- Modify: `docs/CAM_CHANGES.md` (change table row; section 8 release steps; "keep when merging upstream" list: `release.yml` `cam-v*` + `changelog` job + `body_path`, `--match cam-v*` in `manager/build.gradle.kts` and `userspace/camd/build.rs`, `checkNewVersion()` removed from `Downloader.kt`)
- Modify: `AGENTS.md` (one line under Git Commit or a new "Releasing" bullet: tag `cam-v*`, a `CHANGELOG.md` entry is required or the Release workflow stops)

- [ ] **Step 1: Write the docs** in the style of the surrounding text (Vietnamese in `CAM_CHANGES.md`, English in `AGENTS.md`).

- [ ] **Step 2: Full verification.** `python3 -m unittest scripts/test_changelog_section.py`; `cd manager && ./gradlew :app:testDebugUnitTest :app:assembleRelease`; camd `cargo ndk` check/clippy/fmt (WSL). Record the test counts in the hand-off.

- [ ] **Step 3: On-device smoke test without a release** (the real OTA test is `cam-v3.0.1`, spec section 9): install the built APK over the current one and check (a) Home shows no update card (no `cam-v*` release exists, so `parseReleases` returns null), (b) no "What's new" dialog (version name is a hash), (c) `adb shell dumpsys jobscheduler | grep cam-update-check` or WorkManager's `adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p cam.su.kernel` lists the periodic work, (d) Settings toggle off removes it.

- [ ] **Step 4: Commit**
```bash
git add docs/CAM_CHANGES.md AGENTS.md
git commit -m "docs: Record the OTA update flow and the cam-v release steps"
```
