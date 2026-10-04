# Tab "Tính năng" — Kế hoạch thực hiện

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm tab "Tính năng" trên thanh điều hướng, gom các tính năng riêng của SU Kernel thành thẻ mở rộng có công tắc và tùy chọn.

**Architecture:** Cấu hình chống bootloop phải đọc được lúc `post-fs-data`, nên nằm trong `bootguard.json` của ksud và đổi qua lệnh `ksud boot-guard set`. Cấu hình chống xung đột chỉ ảnh hưởng Manager (chip trên thẻ module, cảnh báo khi flash), nên lưu trong SharedPreferences của Manager. Trang mới là một pager thứ 5 dùng lại `ExpandableCard` kiểu trang Hồ sơ ứng dụng.

**Tech Stack:** Rust (ksud), Kotlin/Compose + miuix glass.

**Spec:** Mục "Đặc tả" dưới đây (yêu cầu của Cam ngày 2026-10-04: "1 trang mới trên bar", thẻ ấn vào mở rộng để bật tắt và chỉnh tùy chọn; gồm anti xung đột module, anti bootloop, auto hide bootloader làm sau). Xây trên `docs/superpowers/plans/2026-10-04-bootguard-and-module-conflicts.md`.

## Đặc tả

- Tab mới **"Tính năng"** (en "Features"), icon `Icons.Rounded.AutoAwesome`, nằm giữa Module và Cài đặt. Tiêu đề trang "Tính năng".
- **Thẻ "Chống bootloop"** (icon `Icons.Rounded.HealthAndSafety`). Dòng tóm tắt: đang bật → "Tắt module sau %d lần khởi động lỗi"; đang tắt → "Đang tắt". Mở rộng:
  - Công tắc bật/tắt (mặc định bật). Tắt thì ksud không đếm, không tắt module.
  - Dropdown "Số lần khởi động lỗi trước khi can thiệp": 1, 2, 3, 4 (mặc định 2). Lưu trong ksud dưới dạng `threshold = số lần lỗi + 1` (giá trị 2..5, mặc định 3 như hiện tại).
  - Dropdown "Khi can thiệp": "Tắt module mới cài / bật lại trước" (mặc định) hoặc "Tắt tất cả module".
  - Danh sách module đã bị tự tắt (nếu có), mỗi dòng có nút "Bật lại", cuối có nút "Bỏ qua" (giống hộp thoại ở Home).
- **Thẻ "Chống xung đột module"** (icon `Icons.Rounded.Layers`). Tóm tắt: đang bật → "%d xung đột" hoặc "Không có xung đột"; đang tắt → "Đang tắt". Mở rộng:
  - Công tắc "Phát hiện xung đột" (mặc định bật). Tắt thì không chip trên thẻ module, không cảnh báo khi flash, không quét.
  - Công tắc "Cảnh báo khi cài module" (mặc định bật).
  - Công tắc "Tính cả prop (system.prop)" (mặc định bật). Tắt thì bỏ các xung đột loại prop ở mọi nơi.
  - Danh sách xung đột hiện tại (một dòng mỗi mục, ghi các module liên quan) và nút "Quét lại".
- **Thẻ "Tự ẩn bootloader"** (icon `Icons.Rounded.VisibilityOff`): tóm tắt "Sắp có", không mở rộng được.
- Thẻ cảnh báo ở Home vẫn giữ nguyên.

## Global Constraints

- Không sửa kernel, IOCTL, JNI. Không thêm crate.
- `bootguard.json` cũ (chưa có khóa cấu hình) đọc ra mặc định `enabled=true`, `threshold=3`, `mode=suspects` — hành vi giống hệt bản trước.
- `threshold` luôn bị kẹp vào 2..=5 cả khi đọc file lẫn khi `set`.
- Chuỗi mới: `values/strings.xml` + `values-vi/strings.xml`.
- Rust: `ksud.sh check/clippy/fmt` + `cargo test boot_guard` trong WSL (như kế hoạch trước). Manager: `./gradlew :app:testDebugUnitTest` và `:app:assembleDebug`.
- Commit `<scope>: <Summary>` + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **File trạng thái cũ** không có khóa `enabled`/`threshold`/`mode` → phải ra mặc định, không tắt boot guard. Test `load_old_file_keeps_defaults`.
2. **Tắt boot guard giữa lúc đang đếm** (failCount=2) rồi bật lại → không được kích hoạt ngay lần boot sau. Test `disabled_resets_count`.
3. **Hạ ngưỡng khi failCount đã lớn hơn ngưỡng mới** → lần boot sau kích hoạt (đúng: máy đã lỗi đủ số lần). Test `lower_threshold_triggers_next_boot`.
4. **Tắt "Tính cả prop"** → thẻ module không còn đếm xung đột prop. Test `visibleConflictsDropsProps`.
5. **Tab mới làm lệch chỉ số trang**: trang Cài đặt cũ ở index 3 nay là 4; trang đã lưu `selectedMainPage=3` mở ra tab Tính năng (chấp nhận). Kiểm tra trên máy: bấm từng tab ra đúng trang.

---

### Task 1: ksud — cấu hình boot guard

**Files:** Modify `userspace/ksud/src/boot_guard.rs`, `userspace/ksud/src/module.rs`, `userspace/ksud/src/cli.rs`

**Interfaces:**
- Produces:
  - `BootGuardState` thêm `pub enabled: bool`, `pub threshold: u32`, `pub disable_all: bool`; `Default` = `true, 3, false` (viết `impl Default` tay).
  - `pub const MIN_THRESHOLD: u32 = 2; pub const MAX_THRESHOLD: u32 = 5;` (giữ `BOOT_GUARD_THRESHOLD = 3` làm mặc định).
  - JSON khóa mới: `"enabled"`, `"threshold"`, `"mode": "suspects" | "all"`.
  - `pub fn set_config(state: &mut BootGuardState, enabled: Option<bool>, threshold: Option<u32>, disable_all: Option<bool>)` — kẹp threshold; tắt thì `fail_count = 0`.
  - `on_boot_start`: `!enabled` → `fail_count = 0`, `Continue` (vẫn ghi nghi phạm mới); so với `state.threshold`; `disable_all` → bỏ qua nghi phạm, tắt mọi module đang bật.
  - `status_json` thêm `enabled`, `threshold`, `mode`.
  - CLI: `ksud boot-guard set [--enabled <true|false>] [--threshold <N>] [--mode <suspects|all>]` → `module::boot_guard_set(...)`.
- [ ] Test (RED): `load_old_file_keeps_defaults`, `disabled_resets_count`, `lower_threshold_triggers_next_boot`, `disable_all_ignores_suspects`, `set_config_clamps_threshold` (1→2, 9→5), `status_reports_config` (`mode` = `"all"`), và sửa test cũ dùng `BOOT_GUARD_THRESHOLD` cho đúng.
- [ ] Cài đặt, test GREEN, clippy, fmt, commit `ksud: Make the boot guard configurable`.

### Task 2: Manager — dữ liệu và tùy chọn

**Files:** Modify `data/model/BootGuardStatus.kt`, `data/model/ModuleConflict.kt`, `ui/util/KsuCli.kt`, `data/repository/SettingsRepository.kt` + `Impl`, `data/repository/ModuleRepositoryImpl.kt`; tests.

**Interfaces:**
- `BootGuardStatus` thêm `enabled: Boolean = true`, `threshold: Int = 3`, `disableAll: Boolean = false`.
- `fun setBootGuardConfig(enabled: Boolean? = null, threshold: Int? = null, disableAll: Boolean? = null): Boolean` trong KsuCli.
- `fun List<ModuleConflict>.visible(detection: Boolean, includeProps: Boolean): List<ModuleConflict>`.
- Settings: `conflictDetection` (`conflict_detection`, true), `conflictWarnOnFlash` (`conflict_warn_on_flash`, true), `conflictIncludeProps` (`conflict_include_props`, true).
- `ModuleRepositoryImpl` và `flashModule` dùng `visible(...)`; khi `conflictDetection=false` không gọi `ksud module conflicts`; `flashModule` bỏ cảnh báo khi `conflictWarnOnFlash=false`.
- [ ] Test (RED): `parsesConfig` (enabled false, threshold 4, mode all), `oldStatusKeepsDefaults`, `visibleConflictsDropsProps`, `detectionOffHidesAll`.
- [ ] Cài đặt, test GREEN, commit `manager: Add boot guard and conflict options`.

### Task 3: Manager — tab và trang

**Files:** Create `ui/screen/features/FeaturesScreen.kt`, `FeaturesMiuix.kt`, `ui/viewmodel/FeaturesViewModel.kt`; move `ExpandableCard` từ `AppManageCards.kt` ra `ui/component/glass/GlassExpandableCard.kt` (public, giữ nguyên tham số); modify `BottomBarMiuix.kt` (enum), `MainActivityViewModel.kt` (`PAGE_COUNT = 5`), `MainActivity.kt` (`when (page)`, `beyondViewportPageCount`), strings.
- `FeaturesUiState(bootGuard: BootGuardStatus, conflicts: List<ModuleConflict>, conflictDetection, conflictWarnOnFlash, conflictIncludeProps, loading)`.
- `FeaturesViewModel`: `refresh()`, `setBootGuardEnabled(Boolean)`, `setBootGuardFailures(Int /*1..4*/)` (gửi `threshold = failures + 1`), `setBootGuardDisableAll(Boolean)`, `reenableModule(id)`, `dismissBootGuard()`, `setConflictDetection/WarnOnFlash/IncludeProps(Boolean)`, `rescanConflicts()`. Mọi lệnh shell chạy trên `Dispatchers.IO`.
- [ ] Build `:app:assembleDebug`, unit test toàn bộ vẫn pass.
- [ ] Trên máy: đủ 5 tab đúng trang; mở từng thẻ; đổi ngưỡng/chế độ → `ksud boot-guard status` phản ánh; tắt "Tính cả prop" → chip cf-a còn "Xung đột · 2"; tắt phát hiện → chip mất.
- [ ] Commit `manager: Add the Features tab`.

### Task 4: Ghi chép
- [ ] `docs/CAM_CHANGES.md`: dòng tóm tắt, file mới, lệnh `boot-guard set`, chỉ số tab đổi. Commit `docs: Record the Features tab`.
