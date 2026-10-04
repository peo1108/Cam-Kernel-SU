# Tự ẩn bootloader (tầng 1 + tự kiểm tra) — Kế hoạch thực hiện

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thẻ "Tự ẩn bootloader" trong tab Tính năng: ksud tự sửa các prop lộ trạng thái mở khóa (không cần module), và Manager có bộ tự kiểm tra cho thấy app nhìn thấy gì (prop, bootconfig, chứng chỉ attestation).

**Architecture:** Bảng quy tắc prop → giá trị an toàn là code thuần trong `hide_bootloader.rs` (test trên host). Phần Android dùng `prop_rs_android::ResetProp` (`list_all`, `set`) có sẵn trong ksud, chạy ở `post-fs-data` và `boot-completed` khi bật cờ `/data/adb/ksu/.hide_bootloader`. Manager đọc `ksud hide-bootloader status` (JSON) và tự tạo một key attestation trong AndroidKeyStore, giải mã RootOfTrust bằng một bộ đọc DER nhỏ viết tay (thuần, có unit test).

**Tech Stack:** Rust (ksud), Kotlin (Manager, AndroidKeyStore, X509Certificate).

**Spec:** Chốt với Cam ngày 2026-10-04: "không cài module"; làm tầng 1 (prop) + bộ tự kiểm tra trước; tầng 2 (keystore2) và tầng 3 (kernel) để sau.

## Đặc tả

- Mặc định **tắt**. Bật: tạo cờ, sửa ngay prop đang lộ. Tắt: xoá cờ; prop gốc về lại sau khi khởi động lại (ghi rõ trong UI).
- Chỉ sửa prop **đang tồn tại** và có giá trị khác giá trị an toàn; không bao giờ thêm prop mới (thêm prop lạ cũng là dấu hiệu bị phát hiện).
- Quy tắc theo hậu tố (khớp khi tên bằng hậu tố hoặc kết thúc bằng `.` + hậu tố): `verifiedbootstate`→`green`, `vbmeta.device_state`→`locked`, `flash.locked`→`1`, `veritymode`→`enforcing`, `warranty_bit`→`0`, `build.tags`→`release-keys`, `build.type`→`user`. Quy tắc chính xác: `sys.oem_unlock_allowed`→`0`, `ro.secureboot.lockstate`→`locked`, `ro.debuggable`→`0`, `ro.secure`→`1`. **Không** đụng `ro.oem_unlock_supported` (máy zin cũng là 1).
- Áp ở `post-fs-data` (sau `load_system_prop`) và lại ở `boot-completed` (framework đặt lại `sys.oem_unlock_allowed` sau boot).
- `ksud hide-bootloader status|enable|disable`: `status` in JSON `{"enabled", "props":[{"name","current","safe","ok"}], "bootconfig":[{"name","value","safe","ok"}]}` — `bootconfig` đọc `/proc/bootconfig` (`androidboot.x = "v"`) và `/proc/cmdline` (`androidboot.x=v`), chỉ báo, không sửa (tầng 3).
- Thẻ trong tab Tính năng (bỏ trạng thái "Sắp có"): công tắc, danh sách prop/bootconfig có dấu ✓ / ⚠, nút "Kiểm tra attestation" hiện: mức bảo mật (Phần mềm / TEE / StrongBox), Bootloader (Khóa / Mở khóa), trạng thái boot (Verified / SelfSigned / Unverified / Failed); nếu mở khóa thì ghi "App kiểm tra chứng chỉ vẫn thấy máy mở khóa (cần tầng 2)".

## Global Constraints
- Không module ngoài, không sửa kernel. Không thêm crate/dependency.
- Rust/Manager: như hai kế hoạch trước (WSL `ksud.sh`, `cargo test hide_bootloader`, Gradle unit test + assembleDebug). Commit `<scope>: <Summary>` + Co-Authored-By.

## Review Focus
1. Prop tên gần giống nhưng không phải (`ro.boot.veritymode.managed`, `debug.tracing.device_state`) không được sửa. Test `does_not_touch_lookalikes`.
2. Prop không tồn tại không được thêm. Test `plan_only_fixes_present_props`.
3. Chứng chỉ attestation không có RootOfTrust hoặc DER hỏng → "Không đọc được", không crash. Test `missingRootOfTrustIsNull`, `garbageIsNull`.
4. Tag DER số lớn (704, dạng nhiều byte) đọc đúng. Test `parsesRootOfTrustFromTeeList`.
5. Bật rồi tắt khi chưa reboot: UI ghi rõ cần khởi động lại để về prop gốc.

---

### Task 1: ksud `hide_bootloader`
**Files:** Create `userspace/ksud/src/hide_bootloader.rs`; modify `main.rs`, `defs.rs` (`HIDE_BOOTLOADER_FLAG`), `resetprop.rs` (`list_props()`, `set_prop()`), `init_event.rs`, `cli.rs`.
- Thuần: `pub fn safe_value(name: &str) -> Option<&'static str>`, `pub fn plan(props: &[(String, String)]) -> Vec<(String, &'static str)>`, `pub fn parse_boot_args(bootconfig: &str, cmdline: &str) -> Vec<(String, String)>`, `pub fn status_json(enabled: bool, props: &[(String, String)], boot_args: &[(String, String)]) -> Value`.
- Android: `pub fn apply_if_enabled()`, `pub fn status()`, `pub fn set_enabled(bool)`.
- [ ] Test RED: `suffix_rules`, `exact_rules`, `does_not_touch_lookalikes`, `plan_only_fixes_present_props`, `parses_bootconfig_and_cmdline`, `status_shape`. GREEN, clippy, fmt, thử trên máy, commit `ksud: Hide bootloader state in system properties`.

### Task 2: Manager dữ liệu + attestation
**Files:** Create `data/model/HideBootloaderStatus.kt`, `ui/util/Attestation.kt` (DER + `checkAttestation()`), tests; modify `KsuCli.kt` (`getHideBootloaderStatus()`, `setHideBootloader(Boolean)`).
- `data class AttestationInfo(val securityLevel: Int, val deviceLocked: Boolean, val verifiedBootState: Int)`; `fun parseKeyDescription(extension: ByteArray): AttestationInfo?` (nhận cả giá trị bọc OCTET STRING từ `getExtensionValue`).
- [ ] Test RED: `parsesStatus`, `parsesRootOfTrustFromTeeList`, `fallsBackToSoftwareList`, `missingRootOfTrustIsNull`, `garbageIsNull`. GREEN, commit `manager: Read hide-bootloader status and key attestation`.

### Task 3: Thẻ UI
**Files:** modify `FeaturesUiState.kt`, `FeaturesViewModel.kt`, `FeaturesScreen.kt`, `FeaturesMiuix.kt`, strings.
- [ ] Build, thử trên máy (bật/tắt, danh sách prop, nút kiểm tra attestation), commit `manager: Add the hide bootloader card`.

### Task 4: `docs/CAM_CHANGES.md`, commit `docs: Record hide bootloader`.
