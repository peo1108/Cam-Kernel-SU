# OTA cập nhật Manager + nhật ký cập nhật

Ngày: 2026-10-10
Nhánh: main
Phạm vi: `manager/`, `.github/workflows/release.yml`, `userspace/camd/build.rs` (chỉ chuỗi phiên bản), `scripts/`, `CHANGELOG.md` (mới), tài liệu. Không đổi kernel, supercall, ABI.

## 1. Mục tiêu

Hiện mỗi lần ra bản phải gửi APK tay cho từng người. Sau thay đổi này: chủ repo gắn tag `cam-v*` và push, CI tạo GitHub Release, máy người dùng tự nhận thông báo, đọc nhật ký cập nhật, bấm một lần để tải và cài. LKM mới đi kèm APK (camd nhúng `camsu.ko`), nên chỉ có một kênh cập nhật là APK; sau khi cài, app nhắc cài lại LKM.

Quyết định đã chốt với người dùng:

- Báo bản mới bằng **thông báo nền** (kiểm tra định kỳ) **và** thẻ trên Trang chủ.
- Nhật ký viết tay bằng tiếng Việt trong `CHANGELOG.md` ở gốc repo; release lấy đúng mục của phiên bản; thiếu mục thì không release.
- Tag riêng `cam-v*`, bản đầu tiên `cam-v3.0.0`. Tag upstream (`v*`) không bao giờ kích hoạt release.
- Hiện màn **"Có gì mới" một lần** sau khi cập nhật (không làm màn lịch sử).
- App tìm bản mới bằng **GitHub Releases API** rồi tự lọc (không dùng `releases/latest`, không dùng manifest ký riêng).

Không làm lần này: kênh beta / pre-release, màn lịch sử nhật ký, bắt buộc cập nhật, cập nhật delta, tách phiên bản LKM khỏi phiên bản app, đổi phần upload Telegram.

## 2. Hiện trạng (lý do phải sửa)

- `checkNewVersion()` trong `manager/app/src/main/java/cam/su/kernel/ui/util/Downloader.kt` gọi `api.github.com/repos/tiann/KernelSU/releases/latest`, và công tắc `check_update` mặc định bật: app đang so phiên bản với KernelSU gốc và có thể bảo người dùng tải APK KernelSU.
- `releases/latest` của `peo1108/Cam-Kernel-SU` hiện là `sfs-archive` (không có APK).
- Tag `v3.2.2` … `v3.3.0` trên `origin` là tag upstream. `release.yml` chạy với mọi tag `v*`, nên đẩy nhầm tag upstream sẽ tạo release (và sau thay đổi này: bắn OTA).
- Tên phiên bản = `git describe --tags` (Manager: `manager/build.gradle.kts`; camd: `userspace/camd/build.rs`), nên đang ra kiểu `v3.3.0-512-g21536a17`, tức số của upstream.
- `generate_release_notes: true` chỉ liệt kê PR; repo commit thẳng lên `main` nên gần như rỗng.
- Đã có sẵn: dialog markdown (`rememberConfirmDialog(... markdown = true)`), `DownloadService` foreground có thông báo tiến độ, `FileProvider`, quyền `POST_NOTIFICATIONS`, root shell libsu (`getRootShell()` trong `ui/util/CamCli.kt`), điều kiện `showLkmUpdate` trong `HomeUiState`.

## 3. Phát hành và đánh số phiên bản

### 3.1 Quy trình ra bản

1. Viết mục mới trên cùng `CHANGELOG.md` (Claude có thể viết nháp từ `git log <tag cam-v trước>..HEAD`).
2. Commit, rồi `git tag cam-v3.0.0 && git push origin main cam-v3.0.0`.
3. Workflow "Release" build và tạo GitHub Release với nội dung = mục nhật ký của phiên bản.

### 3.2 Định dạng `CHANGELOG.md`

```markdown
# Nhật ký cập nhật Cam Kernel SU

## 3.0.1 - 2026-10-20
### Tính năng mới
- ...
### Sửa lỗi
- ...
### Lưu ý
- ...

## 3.0.0 - 2026-10-12
...
```

- Tiêu đề mục: `## <semver> - <YYYY-MM-DD>`. Phần ngày bắt buộc có, chỉ để đọc.
- Nội dung mục: mọi thứ đến trước dòng `## ` kế tiếp. Các mục con (`###`) tự do; thường dùng *Tính năng mới / Sửa lỗi / Lưu ý*.
- Dòng `- ` đầu tiên của mục là câu tóm tắt dùng trong thông báo.

### 3.3 `scripts/changelog_section.py`

- `python3 scripts/changelog_section.py <version> [CHANGELOG.md]`: in nội dung mục `<version>` (không kèm dòng `## `) ra stdout; không có mục hoặc mục rỗng thì thoát mã 1 kèm thông báo lỗi.
- Test: `scripts/test_changelog_section.py` (`unittest`, không cần thư viện ngoài).

### 3.4 `release.yml`

- Trigger: `push: tags: ["cam-v*"]`. Giữ `workflow_dispatch` (chạy tay phải chọn ref là một tag `cam-v*`; job kiểm tra `github.ref_name` bắt đầu bằng `cam-v`, không thì dừng).
- Job mới `changelog` (chạy trước `build-manager`, để thiếu nhật ký thì dừng sớm, không tốn build): checkout, `python3 scripts/changelog_section.py "${GITHUB_REF_NAME#cam-v}" > release-notes.md`, upload làm artifact.
- Bước release: bỏ `generate_release_notes`, thêm `body_path: release-notes.md`, `name: Cam Kernel SU <version>`. Giữ `permissions: contents: write`.
- Không đổi danh sách file đính kèm (APK, `lkm-*_camsu.ko`, `camd-*`, `caminit/*`).

### 3.5 Tên phiên bản

- `manager/build.gradle.kts` `getGitDescribe()` và `userspace/camd/build.rs`: `git describe --tags --always --match "cam-v*"`, rồi bỏ tiền tố `cam-v`.
  - Đúng commit có tag: `3.0.0`. Giữa hai tag: `3.0.0-5-gabc1234`. Chưa có tag `cam-v` nào: chỉ hash (`--always`).
- versionCode giữ `30000 + số commit` (so mới/cũ dựa vào số này).
- Tên APK tự thành `Cam_Kernel_SU_3.0.0_<code>-release.apk` (đã có sẵn từ `archivesName` + `repack_apk.py`).

### 3.6 Đóng gói nhật ký vào APK

- Gradle (task trong `manager/app/build.gradle.kts`) chép `../../CHANGELOG.md` vào thư mục assets sinh ra lúc build (`changelog/CHANGELOG.md`), không commit bản sao vào `src/main/assets`.

## 4. Kiểm tra bản mới

### 4.1 `UpdateRepository`

- File: `manager/app/src/main/java/cam/su/kernel/data/repository/UpdateRepository.kt` (+ `UpdateRepositoryImpl.kt`), theo kiểu các repository hiện có.
- `suspend fun fetchLatest(): Result<UpdateInfo?>`: gọi `GET https://api.github.com/repos/peo1108/Cam-Kernel-SU/releases?per_page=30` bằng `camApp.okhttpClient`, header `Accept: application/vnd.github+json`. Trả `null` khi không có bản mới hơn bản đang chạy.
- Hàm thuần (unit test được), file `data/model/UpdateInfo.kt` hoặc cùng file repository:

```kotlin
data class UpdateInfo(
    val versionName: String,   // "3.0.1"
    val versionCode: Long,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,       // từ asset.digest "sha256:<hex>", null nếu không có
    val changelog: String,     // release.body
)

fun parseReleases(json: String, currentVersionCode: Long): UpdateInfo?
```

- Luật lọc của `parseReleases`: bỏ `draft == true`, bỏ `prerelease == true`, chỉ giữ `tag_name` bắt đầu bằng `cam-v`; trong mỗi release tìm asset tên khớp `^Cam_Kernel_SU_.+_(\d+)-release\.apk$`, lấy versionCode từ nhóm số; release không có asset khớp thì bỏ. Chọn release có versionCode lớn nhất; chỉ trả về nếu `> currentVersionCode`. `versionName` = `tag_name` bỏ `cam-v`. JSON lỗi thì trả `null`.
- Xóa `checkNewVersion()` và `LatestVersionInfo`; `HomeViewModel` / `HomeUiState` / `HomeMiuix.kt` dùng `UpdateInfo?`.

### 4.2 Kiểm tra định kỳ

- Thêm `androidx.work:work-runtime-ktx` vào `gradle/libs.versions.toml` và `app/build.gradle.kts`.
- `UpdateCheckWorker` (`manager/app/src/main/java/cam/su/kernel/update/UpdateCheckWorker.kt`): `CoroutineWorker`, gọi `fetchLatest()`; lỗi hoặc không có bản mới thì `Result.success()` (không retry).
- Lên lịch: trong `CamApplication.onCreate`, nếu `SettingsRepository.checkUpdate` bật thì `enqueueUniquePeriodicWork("cam-update-check", KEEP, 12h, constraint NetworkType.CONNECTED)`; khi người dùng tắt công tắc thì `cancelUniqueWork`, bật lại thì enqueue.

### 4.3 Thông báo

- Channel mới `app_update` ("Cập nhật ứng dụng"), tạo cùng chỗ với lên lịch.
- Nội dung: tiêu đề "Cam Kernel SU <versionName> đã có", dòng phụ = dòng `- ` đầu tiên của `changelog` (bỏ `- `).
- Mỗi versionCode chỉ báo một lần: pref `notified_version_code` (trong `SettingsRepository`); chỉ báo khi `info.versionCode > notified_version_code`.
- Bấm thông báo: mở `CamActivity` với extra `EXTRA_SHOW_UPDATE = true`; Trang chủ thấy extra thì mở dialog nhật ký + nút Cập nhật.
- Không có quyền thông báo thì bỏ qua bước báo (thẻ Home vẫn hiện).

### 4.4 Xin quyền thông báo

- Android 13+: lần đầu vào Trang chủ khi `checkUpdate` bật và chưa có quyền, xin `POST_NOTIFICATIONS` một lần (pref `asked_notification_permission`). Từ chối thì không hỏi lại.

### 4.5 Thẻ trên Trang chủ

- Giữ `UpdateCard`; chữ đổi thành "Cam Kernel SU <versionName> đã có" (thay cho versionCode). Bấm: dialog nhật ký (markdown) với nút **Cập nhật** (vào mục 5) thay cho mở URL trong trình duyệt.
- Đang tải: thẻ hiện tiến độ (từ `DownloadManager.downloads`). Tải lỗi: "Tải thất bại, bấm để thử lại".

## 5. Tải và cài

Code đặt trong `manager/app/src/main/java/cam/su/kernel/update/` (`UpdateInstaller.kt`), gọi từ `HomeViewModel`.

### 5.1 Tải

- Dùng `DownloadManager.enqueue`/`DownloadService` hiện có, thêm tham số đích `Destination.PublicDownloads` (mặc định, giữ hành vi cũ cho module) / `Destination.Cache(dir)`. OTA dùng `cacheDir/ota/`, tên file như asset.
- Trước khi tải: xóa mọi file trong `cacheDir/ota/`.

### 5.2 Kiểm tra file

1. Nếu `sha256 != null`: tính SHA-256 file, so sánh; lệch thì xóa, báo "Tải về bị lỗi, thử lại".
2. `packageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`: `packageName == "cam.su.kernel"`, `longVersionCode == info.versionCode`, bộ chứng chỉ ký trùng với app đang chạy. Sai điều nào thì xóa file, báo đúng lý do.

### 5.3 Cài bằng root (cách chính)

- Qua `getRootShell()` (libsu): `cat '<apk>' | pm install -r -S <size> && am start -n cam.su.kernel/.ui.CamActivity`.
  - Đẩy qua stdin để `system_server` không phải đọc file trong thư mục riêng của app (tránh SELinux).
  - Shell chạy dưới su của camd chứ không thuộc tiến trình app, nên vẫn chạy tiếp khi Android tắt app để thay bản; `am start` mở lại app, app hiện "Có gì mới".
- `pm` trả lỗi (không có `Success`): hiện dòng lỗi đầu tiên và nút "Cài bằng trình cài đặt Android" (5.4).

### 5.4 Cài bằng trình cài đặt Android (dự phòng)

- Dùng khi không có root (`Shell.isAppGrantedRoot() != true`) hoặc khi 5.3 lỗi và người dùng bấm nút dự phòng.
- Thêm quyền `android.permission.REQUEST_INSTALL_PACKAGES`. Thêm `cache-path` cho `ota/` vào `res/xml` của `FileProvider` nếu chưa có. `ACTION_VIEW` với URI `FileProvider`, MIME `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`.

### 5.5 Dọn dẹp

- Khi app khởi động: nếu có file trong `cacheDir/ota/` thì xóa (bản đã cài xong hoặc bị bỏ dở).

## 6. Màn "Có gì mới"

### 6.1 Đọc nhật ký đóng gói

- `ChangelogParser` (`manager/app/src/main/java/cam/su/kernel/update/ChangelogParser.kt`), hàm thuần:
  - `fun parse(markdown: String): List<ChangelogEntry>` với `ChangelogEntry(version: String, date: String, body: String)`.
  - `fun entriesNewerThan(entries, lastSeen: String?, current: String): List<ChangelogEntry>`: các mục có `lastSeen < version <= current` theo semver (so từng số `major.minor.patch`), sắp mới nhất trước.
- Đọc file từ assets `changelog/CHANGELOG.md`.

### 6.2 Khi nào hiện

- Pref `last_seen_version` (tên phiên bản, ví dụ `3.0.0`).
- Khi mở Trang chủ, với `current = BuildConfig.VERSION_NAME`:
  - `current` không phải semver thuần (`3.0.0-5-gabc`, hash): không làm gì (không hiện, không ghi).
  - `last_seen_version` chưa có (cài mới): ghi `current`, không hiện.
  - `current > last_seen_version` và `entriesNewerThan` không rỗng: hiện dialog, rồi ghi `current`.
  - Còn lại: ghi `current` nếu khác, không hiện.

### 6.3 Nội dung dialog

- Tiêu đề "Có gì mới". Thân: các mục nối nhau, mỗi mục bắt đầu bằng `### <version>`, render bằng markdown sẵn có trong dialog Miuix.
- Nếu `state.showLkmUpdate`: thêm dòng "Bản này đi kèm LKM mới" và nút **Cài lại LKM** (gọi `actions.onInstallClick`) cạnh nút **Đóng**. Không thì chỉ **Đóng**.
- Thẻ LKM trên Home giữ nguyên. Dialog không chặn các thẻ khác.
- Ghi chú: phiên bản LKM cũng là `30000 + số commit`, nên gần như mọi bản OTA đều làm `showLkmUpdate` đúng. Đây là hành vi sẵn có, không đổi trong phạm vi này.

## 7. Xử lý lỗi

| Tình huống | Cách xử lý |
|---|---|
| Mất mạng, HTTP 403/429, JSON hỏng (worker) | Im lặng, `Result.success()`, đợi chu kỳ sau |
| Mất mạng, HTTP lỗi (Home) | Không hiện thẻ |
| Không có release `cam-v*` / không có asset APK khớp | Không có bản mới |
| versionCode trên mạng ≤ bản đang chạy | Không báo, không bao giờ hạ cấp |
| Tải thất bại | Thẻ Home "Tải thất bại, bấm để thử lại" |
| SHA-256 lệch, sai package, sai chứng chỉ, sai versionCode | Xóa file, báo đúng lý do |
| Asset không có `digest` | Bỏ bước SHA-256, vẫn kiểm tra package + chứng chỉ + versionCode |
| `pm install` lỗi | Hiện dòng lỗi của `pm` + nút cài bằng trình cài đặt Android |
| Không có mục changelog cho tag (CI) | Workflow dừng ở job `changelog`, không tạo release |

## 8. Test

- Kotlin unit test (chạy trong `./gradlew :app:testDebugUnitTest`, CI `test.yml` đã chạy):
  - `UpdateParserTest`: lọc `cam-v*`; bỏ `sfs-*`, `v3.3.0`; bỏ draft, prerelease; chọn versionCode lớn nhất kể cả khi không đứng đầu danh sách; bỏ release không có APK khớp; trả `null` khi không mới hơn; đọc `digest` và thiếu `digest`; JSON hỏng.
  - `ChangelogParserTest`: tách nhiều mục; so semver (`3.10.0 > 3.9.0`); gộp khi nhảy cóc; `lastSeen` null; phiên bản không có mục; tên bản build thử.
- Python: `python3 -m unittest scripts/test_changelog_section.py` (có mục, không có mục, mục rỗng, mục cuối file). Thêm bước chạy vào `test.yml` khi đổi `scripts/changelog_section.py` hoặc `CHANGELOG.md`.
- Kiểm tra build: theo `AGENTS.md` (camd: `cargo ndk check`/`clippy`/`fmt` vì đổi `build.rs`; Manager: `testDebugUnitTest` + `assembleRelease`).
- Thử thật: xem mục 9.

## 9. Bước khởi động

- Người dùng hiện tại đang cầm app hỏi tiann/KernelSU, nên **`cam-v3.0.0` phải gửi tay lần cuối**.
- `cam-v3.0.1` là lần thử toàn bộ đường OTA trên máy chủ repo: thông báo nền, thẻ Home, tải, kiểm tra, cài bằng root, app tự mở lại, "Có gì mới", nút Cài lại LKM.
- Manager luôn có root khi LKM đã nạp (kernel ghim Manager), nên nhánh dự phòng (5.4) chỉ thử được trên máy chưa nạp LKM. Nên thử nếu tiện, không bắt buộc cho 3.0.1.

## 10. Tài liệu

- `docs/CAM_CHANGES.md`: thêm dòng vào bảng thay đổi; viết lại mục ra bản (mục 8: tag `cam-v*`, `CHANGELOG.md`, Claude viết nháp); thêm vào phần "giữ khi merge upstream": `release.yml` trigger `cam-v*` + job `changelog` + `body_path`; `build.gradle.kts`/`build.rs` `--match "cam-v*"`; `checkNewVersion()` đã bị xóa (upstream sửa file này thì giữ bản của Cam).
- `AGENTS.md`: thêm một dòng về cách ra bản (tag `cam-v*`, mục `CHANGELOG.md` bắt buộc).
