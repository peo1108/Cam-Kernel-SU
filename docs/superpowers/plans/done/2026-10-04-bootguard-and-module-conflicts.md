# Boot Guard và Phát hiện xung đột module — Kế hoạch thực hiện

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ksud tự tắt module khi máy kẹt boot liên tục, và phát hiện các module cùng sửa một file/prop; Manager hiển thị cả hai.

**Architecture:** Logic thuần (quyết định boot guard, so khớp xung đột) nằm trong hai file Rust không gắn `cfg(android)` để chạy `cargo test` trên Linux/WSL. Phần I/O Android (đường dẫn `/data/adb/...`, gọi ở `init_event.rs`, CLI) là lớp mỏng bọc logic đó. Manager gọi ksud qua shell như các lệnh module hiện có, đọc JSON, hiển thị trên Home và thẻ module. Không đụng kernel, IOCTL hay JNI.

**Tech Stack:** Rust (ksud, clap, serde_json, tempfile), Kotlin/Jetpack Compose (Manager, miuix glass UI), org.json.

**Spec:** Mục "Đặc tả" ngay dưới đây (chốt với người dùng trong cuộc trò chuyện ngày 2026-10-04).

## Đặc tả

**Boot Guard**
- Mỗi lần `post-fs-data` (không phải safe mode, không có Magisk), ksud tăng bộ đếm boot thất bại. `boot-completed` reset bộ đếm về 0 và xóa danh sách nghi phạm.
- Nghi phạm = module vừa được cài/cập nhật (được `handle_updated_modules` chuyển sang) hoặc vừa được bật lại bằng `ksud module enable`, kể từ lần boot thành công gần nhất.
- Khi bộ đếm đạt ngưỡng 3 (tức đây là lần boot thứ 3 liên tiếp chưa xong): nếu có nghi phạm đang bật → chỉ tắt các nghi phạm; nếu không → tắt mọi module đang bật. Ghi lại những module bị tắt tự động, reset bộ đếm, xóa nghi phạm.
- Leo thang tự nhiên: nếu tắt nghi phạm mà vẫn loop, 3 lần sau không còn nghi phạm → tắt hết.
- Manager: Home hiện thẻ cảnh báo khi có module bị tắt tự động mà vẫn còn đang tắt; bấm vào mở hộp thoại liệt kê module, mỗi dòng có nút "Bật lại", và nút "Bỏ qua" để ẩn thông báo. Thẻ module có nhãn "Tự tắt do lỗi khởi động".

**Phát hiện xung đột**
- Xét tập module hiệu lực: module trong `/data/adb/modules/` đang bật, không có `remove`, không có `skip_mount` cho phần file; nếu có bản cập nhật chờ ở `/data/adb/modules_update/<id>` thì dùng bản đó thay.
- Xung đột file: ≥2 module cùng có một đường dẫn trong `system/` (file thường, symlink, hoặc whiteout = char device 0:0).
- Xung đột thay thư mục: module A có thư mục chứa `.replace`, module B có bất kỳ mục nào ở đó hoặc bên dưới.
- Xung đột prop: ≥2 module đặt cùng key trong `system.prop` với giá trị khác nhau (áp dụng cả khi có `skip_mount`).
- CLI `ksud module conflicts` in JSON. Manager hiển thị nhãn "Xung đột" trên thẻ module, bấm vào xem danh sách; sau khi flash module thành công, màn hình flash cảnh báo nếu module vừa cài có xung đột.

## Global Constraints

- Không sửa `kernel/`, không thêm IOCTL, không sửa `manager/app/src/main/cpp/ksu.cc`.
- Không thêm crate mới vào ksud; JSON dựng bằng `serde_json::json!`/`Value` (crate không có `serde` derive).
- Ngưỡng boot guard: `BOOT_GUARD_THRESHOLD: u32 = 3`. File trạng thái: `/data/adb/ksu/bootguard.json`.
- Ghi file trạng thái phải bền: ghi file tạm cùng thư mục → `sync_all()` → `rename`. File hỏng/không đọc được = trạng thái mặc định, không bao giờ làm hỏng boot.
- Mọi lỗi của boot guard/conflicts trong luồng boot chỉ `warn!`, không `?` ra ngoài `on_post_data_fs`.
- Chuỗi giao diện mới thêm vào `values/strings.xml` (tiếng Anh) và `values-vi/strings.xml`; locale khác tự fallback.
- Rust: sau mỗi task chạy `cargo ndk -t arm64-v8a check`, `cargo ndk -t arm64-v8a clippy`, `cargo fmt` trong `userspace/ksud` (AGENTS.md). Máy này chưa có `cargo-ndk`: cài trong WSL (`cargo install cargo-ndk`, `rustup target add aarch64-linux-android`, đặt `ANDROID_NDK_HOME`) trước Task 1; nếu không cài được thì ghi rõ và dựa vào workflow `clippy.yml` trên CI.
- Unit test Rust chạy trong WSL: `wsl -e bash -lc 'cd "/mnt/c/Users/cam/Desktop/Cam Kernel SU/userspace/ksud" && cargo test <filter>'` (cargo trên Windows host thiếu dlltool).
- Commit theo kiểu `<scope>: <Summary>` (ví dụ `ksud: Add boot guard state machine`), kết thúc bằng dòng `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Máy loop trước `post-fs-data`** (ví dụ do `modules.rc` trong `/metadata` hoặc metamodule mount sớm) — bộ đếm không tăng. Người dùng kỳ vọng tối thiểu: khi ksud có chạy được thì `regenerate_preinit_rc()` chạy *sau* quyết định tắt module, để lần boot tiếp không nạp rc của module đã tắt. Test: Task 2 Step 1 kiểm tra thứ tự gọi bằng đọc code (assert trong review), và Task 1 test `trigger_disables_suspects_only`.
2. **File `bootguard.json` hỏng hoặc rỗng** (mất điện giữa lúc ghi) — phải coi là trạng thái mặc định, không panic. Test: `load_corrupt_file_returns_default` ở Task 1.
3. **Nghi phạm đã bị gỡ/không còn tồn tại hoặc đã bị tắt sẵn** — không được tính là "có nghi phạm" khiến bỏ qua bước tắt hết. Test: `stale_suspects_fall_back_to_all` ở Task 1.
4. **Người dùng tự bật lại module bị tắt tự động** — thẻ Home phải biến mất mà không cần bấm "Bỏ qua". Test: `status_hides_reenabled_modules` ở Task 1.
5. **Module có `system/` rất lớn hoặc chứa symlink vòng** — duyệt không được theo symlink. Test: `walker_does_not_follow_symlinks` ở Task 3.

---

## File Structure

| File | Trách nhiệm |
|---|---|
| `userspace/ksud/src/boot_guard.rs` (mới, không gate) | Struct trạng thái, đọc/ghi JSON bền, hàm quyết định thuần, unit test |
| `userspace/ksud/src/module_conflicts.rs` (mới, không gate) | Duyệt cây `system/`, parse `system.prop`, so khớp xung đột, unit test |
| `userspace/ksud/src/main.rs` | Khai báo 2 mod mới |
| `userspace/ksud/src/defs.rs` | `BOOT_GUARD_PATH` |
| `userspace/ksud/src/module.rs` | `handle_updated_modules` trả id; `enable_module` đánh dấu nghi phạm; `list_module_conflicts()` |
| `userspace/ksud/src/init_event.rs` | Gọi boot guard trong `on_post_data_fs` và `on_boot_completed` |
| `userspace/ksud/src/cli.rs` | `ksud boot-guard status|clear`, `ksud module conflicts` |
| `manager/.../data/model/BootGuardStatus.kt`, `ModuleConflict.kt` (mới) | Model + hàm parse JSON thuần |
| `manager/.../ui/util/KsuCli.kt` | `getBootGuardStatus()`, `clearBootGuard()`, `listModuleConflicts()` |
| `manager/.../ui/viewmodel/HomeViewModel.kt`, `ui/screen/home/HomeUiState.kt`, `HomeMiuix.kt` | Thẻ + hộp thoại boot guard |
| `manager/.../data/repository/ModuleRepository*.kt`, `ui/viewmodel/ModuleViewModel.kt`, `ui/screen/module/ModuleMiuix.kt` | Nhãn auto-disabled và xung đột trên thẻ module |
| `manager/.../ui/screen/flash/FlashScreen.kt` / `FlashMiuix.kt` | Cảnh báo xung đột sau khi flash |

(`manager/...` = `manager/app/src/main/java/me/weishu/kernelsu`)

---

### Task 1: Logic boot guard (Rust, thuần)

**Files:**
- Create: `userspace/ksud/src/boot_guard.rs`
- Modify: `userspace/ksud/src/main.rs` (thêm `mod boot_guard;` không gate, cạnh `mod seed;`)

**Interfaces:**
- Produces:
  - `pub const BOOT_GUARD_THRESHOLD: u32 = 3;`
  - `#[derive(Debug, Default, Clone, PartialEq, Eq)] pub struct BootGuardState { pub fail_count: u32, pub suspects: Vec<String>, pub auto_disabled: Vec<String>, pub last_trigger: Option<u64> }` (giữ `suspects`/`auto_disabled` đã sort, không trùng)
  - `pub fn load(path: &Path) -> BootGuardState` — không tồn tại/hỏng → `Default`
  - `pub fn save(path: &Path, state: &BootGuardState) -> anyhow::Result<()>` — tạm + `sync_all` + `rename`; JSON key: `failCount`, `suspects`, `autoDisabled`, `lastTrigger`
  - `pub enum Decision { Continue, Disable(Vec<String>) }`
  - `pub fn on_boot_start(state: &mut BootGuardState, new_suspects: &[String], enabled: &[String], now: u64) -> Decision` — thêm nghi phạm, tăng `fail_count`; nếu `fail_count >= THRESHOLD`: danh sách tắt = nghi phạm ∩ `enabled` (rỗng → toàn bộ `enabled`), thêm vào `auto_disabled`, `fail_count = 0`, xóa `suspects`, `last_trigger = Some(now)`. Nếu `enabled` rỗng → `Continue`.
  - `pub fn on_boot_completed(state: &mut BootGuardState)` — `fail_count = 0`, xóa `suspects`
  - `pub fn add_suspect(state: &mut BootGuardState, id: &str)`
  - `pub fn status_json(state: &BootGuardState, still_disabled: &dyn Fn(&str) -> bool) -> serde_json::Value` — `{"failCount","threshold","suspects","autoDisabled"(chỉ id còn tắt),"lastTrigger"}`
  - `pub fn clear_notice(state: &mut BootGuardState)` — xóa `auto_disabled`, `last_trigger`

- [ ] **Step 1: Viết test trong `#[cfg(test)] mod tests` của `boot_guard.rs`**

```rust
fn ids(v: &[&str]) -> Vec<String> { v.iter().map(|s| (*s).to_owned()).collect() }

#[test]
fn counts_up_below_threshold() {
    let mut s = BootGuardState::default();
    assert_eq!(on_boot_start(&mut s, &[], &ids(&["a"]), 1), Decision::Continue);
    assert_eq!(on_boot_start(&mut s, &[], &ids(&["a"]), 2), Decision::Continue);
    assert_eq!(s.fail_count, 2);
}

#[test]
fn trigger_disables_suspects_only() {
    let mut s = BootGuardState::default();
    let en = ids(&["a", "b", "c"]);
    on_boot_start(&mut s, &ids(&["b"]), &en, 1);
    on_boot_start(&mut s, &[], &en, 2);
    assert_eq!(on_boot_start(&mut s, &[], &en, 3), Decision::Disable(ids(&["b"])));
    assert_eq!(s.fail_count, 0);
    assert!(s.suspects.is_empty());
    assert_eq!(s.auto_disabled, ids(&["b"]));
    assert_eq!(s.last_trigger, Some(3));
}

#[test]
fn no_suspects_disables_all_enabled() {
    let mut s = BootGuardState { fail_count: 2, ..Default::default() };
    assert_eq!(on_boot_start(&mut s, &[], &ids(&["a", "c"]), 9), Decision::Disable(ids(&["a", "c"])));
}

#[test]
fn stale_suspects_fall_back_to_all() {
    let mut s = BootGuardState { fail_count: 2, suspects: ids(&["gone"]), ..Default::default() };
    assert_eq!(on_boot_start(&mut s, &[], &ids(&["a"]), 9), Decision::Disable(ids(&["a"])));
}

#[test]
fn nothing_enabled_continues() {
    let mut s = BootGuardState { fail_count: 2, ..Default::default() };
    assert_eq!(on_boot_start(&mut s, &[], &[], 9), Decision::Continue);
}

#[test]
fn boot_completed_resets() {
    let mut s = BootGuardState { fail_count: 2, suspects: ids(&["a"]), auto_disabled: ids(&["x"]), ..Default::default() };
    on_boot_completed(&mut s);
    assert_eq!(s.fail_count, 0);
    assert!(s.suspects.is_empty());
    assert_eq!(s.auto_disabled, ids(&["x"]));
}

#[test]
fn save_load_roundtrip() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("bootguard.json");
    let s = BootGuardState { fail_count: 1, suspects: ids(&["a"]), auto_disabled: ids(&["b"]), last_trigger: Some(5) };
    save(&p, &s).unwrap();
    assert_eq!(load(&p), s);
}

#[test]
fn load_corrupt_file_returns_default() {
    let dir = tempfile::tempdir().unwrap();
    let p = dir.path().join("bootguard.json");
    std::fs::write(&p, b"{\"failCo").unwrap();
    assert_eq!(load(&p), BootGuardState::default());
    std::fs::write(&p, b"").unwrap();
    assert_eq!(load(&p), BootGuardState::default());
}

#[test]
fn status_hides_reenabled_modules() {
    let s = BootGuardState { auto_disabled: ids(&["a", "b"]), ..Default::default() };
    let v = status_json(&s, &|id| id == "b");
    assert_eq!(v["autoDisabled"], serde_json::json!(["b"]));
    assert_eq!(v["threshold"], serde_json::json!(3));
}
```

- [ ] **Step 2: Chạy test, xác nhận FAIL** — `wsl ... cargo test boot_guard` → lỗi biên dịch "cannot find function `on_boot_start`".
- [ ] **Step 3: Cài đặt các hàm trong Interfaces.** Hàm thuần không dùng gì từ module gate Android.
- [ ] **Step 4: Chạy test, xác nhận PASS** — `cargo test boot_guard` → 9 passed.
- [ ] **Step 5: `cargo ndk` check + clippy + fmt, rồi commit** `ksud: Add boot guard state machine`

### Task 2: Gắn boot guard vào luồng boot và CLI

**Files:**
- Modify: `userspace/ksud/src/defs.rs` (trong `mod android`: `pub const BOOT_GUARD_PATH: &str = concatcp!(WORKING_DIR, "bootguard.json");`)
- Modify: `userspace/ksud/src/module.rs` (`handle_updated_modules`, `enable_module`, thêm `enabled_module_ids`)
- Modify: `userspace/ksud/src/init_event.rs`
- Modify: `userspace/ksud/src/cli.rs`

**Interfaces:**
- Consumes: mọi thứ Task 1 Produces.
- Produces:
  - `pub fn handle_updated_modules() -> Result<Vec<String>>` — trả id các module vừa được chuyển sang (kể cả cái mang cờ disable/remove; lọc ở bước quyết định nhờ `enabled`).
  - `pub fn enabled_module_ids() -> Vec<String>` trong `module.rs` — id thư mục trong `MODULE_DIR` có `module.prop`, không có `disable`, không có `remove`; sort.
  - CLI: `ksud boot-guard status` in `status_json` (pretty); `ksud boot-guard clear` gọi `clear_notice` + `save`. Thêm `Commands::BootGuard { #[command(subcommand)] command: BootGuard }` với `enum BootGuard { Status, Clear }`.

- [ ] **Step 1: Sửa `on_post_data_fs`** — thay khối `handle_updated_modules()` hiện tại bằng: lấy `updated` (lỗi → `warn!` + `vec![]`); `let mut st = boot_guard::load(BOOT_GUARD_PATH.as_ref())`; `let d = boot_guard::on_boot_start(&mut st, &updated, &module::enabled_module_ids(), now_unix_secs)`; **`save` trước khi tắt** để bộ đếm bền dù bước sau crash; nếu `Disable(ids)` thì gọi `module::disable_module(id)` từng cái (lỗi → `warn!`) và `warn!("boot guard: disabled {ids:?} after {THRESHOLD} failed boots")`. Khối này nằm sau nhánh `safe_mode` và **trước** `prune_modules()`/`regenerate_preinit_rc()` (giữ thứ tự hiện có cho phần còn lại). Không có `?`.
- [ ] **Step 2: Sửa `on_boot_completed`** — sau `report_boot_complete()`: load → `on_boot_completed` → save; lỗi chỉ `warn!`. Chạy cả trong safe mode (hàm hiện tại không chặn safe mode, giữ nguyên).
- [ ] **Step 3: Sửa `enable_module`** — sau khi xóa file `disable` thành công: load → `add_suspect(id)` → save (lỗi `warn!`).
- [ ] **Step 4: Thêm CLI `boot-guard`** — `status` truyền closure `|id| Path::new(MODULE_DIR).join(id).join(DISABLE_FILE_NAME).exists()`.
- [ ] **Step 5: `cargo ndk -t arm64-v8a check`, `clippy`, `cargo fmt`** — không lỗi, không warning mới.
- [ ] **Step 6: Kiểm tra trên máy thật (nếu có thiết bị)** — `adb shell su -c 'ksud boot-guard status'` in JSON có `"threshold": 3`. Mô phỏng: ghi `{"failCount":2}` vào `/data/adb/ksu/bootguard.json`, reboot, chặn boot-completed không được thì bỏ qua; tối thiểu xác nhận sau một lần boot bình thường `failCount` = 0. Nếu không có máy, ghi rõ trong báo cáo là chưa test thực tế.
- [ ] **Step 7: Commit** `ksud: Auto-disable modules after repeated failed boots`

### Task 3: Phát hiện xung đột module (Rust)

**Files:**
- Create: `userspace/ksud/src/module_conflicts.rs` (không gate)
- Modify: `userspace/ksud/src/main.rs`, `userspace/ksud/src/module.rs`, `userspace/ksud/src/cli.rs`

**Interfaces:**
- Produces:
  - `#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)] pub enum EntryKind { File, Whiteout, ReplaceDir }`
  - `#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)] pub enum ConflictKind { File, Replace, Prop }` — JSON `"file" | "replace" | "prop"`
  - `#[derive(Debug, Clone, PartialEq, Eq)] pub struct Conflict { pub kind: ConflictKind, pub path: String, pub modules: Vec<String> }` — `path` dạng `/system/...` hoặc key prop; `modules` sort
  - `pub struct ModuleInput { pub id: String, pub entries: Vec<(String, EntryKind)>, pub props: Vec<(String, String)> }`
  - `pub fn scan_system_dir(system_dir: &Path) -> Vec<(String, EntryKind)>` — đường dẫn tương đối bắt đầu bằng `/system/`; dùng `symlink_metadata`, không theo symlink; thư mục có file `.replace` → một mục `ReplaceDir` cho chính thư mục đó, vẫn duyệt tiếp con; char device có `rdev() == 0` → `Whiteout` (chỉ dưới `#[cfg(unix)]`); file thường/symlink → `File`; bỏ qua chính file `.replace`.
  - `pub fn parse_system_prop(text: &str) -> Vec<(String, String)>` — bỏ dòng trống/`#`, tách ở dấu `=` đầu tiên, trim hai bên.
  - `pub fn find_conflicts(modules: &[ModuleInput]) -> Vec<Conflict>` — sort theo `(kind, path)`.
  - `pub fn conflicts_json(c: &[Conflict]) -> serde_json::Value`
  - Trong `module.rs` (android): `pub fn list_module_conflicts() -> Result<()>` — dựng `ModuleInput` cho mọi module đang bật, ưu tiên `MODULE_UPDATE_DIR/<id>` nếu tồn tại; module mới hoàn toàn chỉ có trong `MODULE_UPDATE_DIR` cũng được tính; bỏ `entries` nếu có `skip_mount`; in JSON pretty.
  - CLI: `Module::Conflicts` → `module::list_module_conflicts()`.

Quy tắc `find_conflicts`:
- `File`: đường dẫn có ≥2 module có mục `File`/`Whiteout` (không tính `ReplaceDir`).
- `Replace`: với mỗi `ReplaceDir` của module A tại `d`, mọi module B ≠ A có mục ở `d` hoặc dưới `d/` → một conflict `{Replace, d, [A, B...]}` (gộp B theo `d`, A có trong danh sách).
- `Prop`: key xuất hiện ở ≥2 module với ≥2 giá trị khác nhau; `modules` = mọi module đặt key đó.

- [ ] **Step 1: Viết test**

```rust
fn m(id: &str, entries: &[(&str, EntryKind)], props: &[(&str, &str)]) -> ModuleInput {
    ModuleInput {
        id: id.into(),
        entries: entries.iter().map(|(p, k)| ((*p).into(), *k)).collect(),
        props: props.iter().map(|(k, v)| ((*k).into(), (*v).into())).collect(),
    }
}

#[test]
fn same_file_in_two_modules() {
    let c = find_conflicts(&[
        m("a", &[("/system/etc/hosts", EntryKind::File)], &[]),
        m("b", &[("/system/etc/hosts", EntryKind::File)], &[]),
        m("c", &[("/system/etc/other", EntryKind::File)], &[]),
    ]);
    assert_eq!(c, vec![Conflict { kind: ConflictKind::File, path: "/system/etc/hosts".into(), modules: vec!["a".into(), "b".into()] }]);
}

#[test]
fn whiteout_vs_file_conflicts() {
    let c = find_conflicts(&[
        m("a", &[("/system/app/Foo", EntryKind::Whiteout)], &[]),
        m("b", &[("/system/app/Foo", EntryKind::File)], &[]),
    ]);
    assert_eq!(c.len(), 1);
    assert_eq!(c[0].kind, ConflictKind::File);
}

#[test]
fn replace_dir_vs_child() {
    let c = find_conflicts(&[
        m("a", &[("/system/media/audio", EntryKind::ReplaceDir)], &[]),
        m("b", &[("/system/media/audio/ui/x.ogg", EntryKind::File)], &[]),
        m("c", &[("/system/media/audiox", EntryKind::File)], &[]),
    ]);
    assert_eq!(c, vec![Conflict { kind: ConflictKind::Replace, path: "/system/media/audio".into(), modules: vec!["a".into(), "b".into()] }]);
}

#[test]
fn prop_conflict_only_when_values_differ() {
    let c = find_conflicts(&[
        m("a", &[], &[("ro.x", "1"), ("ro.same", "z")]),
        m("b", &[], &[("ro.x", "2"), ("ro.same", "z")]),
    ]);
    assert_eq!(c, vec![Conflict { kind: ConflictKind::Prop, path: "ro.x".into(), modules: vec!["a".into(), "b".into()] }]);
}

#[test]
fn parses_system_prop() {
    assert_eq!(
        parse_system_prop("# c\n\n ro.a = 1 \nro.b=x=y\nbad\n"),
        vec![("ro.a".into(), "1".into()), ("ro.b".into(), "x=y".into())]
    );
}

#[test]
fn scans_tree_with_replace() {
    let d = tempfile::tempdir().unwrap();
    let sys = d.path().join("system");
    std::fs::create_dir_all(sys.join("etc")).unwrap();
    std::fs::write(sys.join("etc/hosts"), "").unwrap();
    std::fs::create_dir_all(sys.join("media/audio")).unwrap();
    std::fs::write(sys.join("media/audio/.replace"), "").unwrap();
    let mut e = scan_system_dir(&sys);
    e.sort();
    assert_eq!(e, vec![
        ("/system/etc/hosts".into(), EntryKind::File),
        ("/system/media/audio".into(), EntryKind::ReplaceDir),
    ]);
}

#[cfg(unix)]
#[test]
fn walker_does_not_follow_symlinks() {
    let d = tempfile::tempdir().unwrap();
    let sys = d.path().join("system");
    std::fs::create_dir_all(sys.join("lib")).unwrap();
    std::os::unix::fs::symlink(&sys, sys.join("lib/loop")).unwrap();
    assert_eq!(scan_system_dir(&sys), vec![("/system/lib/loop".into(), EntryKind::File)]);
}

#[test]
fn json_shape() {
    let v = conflicts_json(&[Conflict { kind: ConflictKind::Prop, path: "ro.x".into(), modules: vec!["a".into()] }]);
    assert_eq!(v, serde_json::json!([{ "kind": "prop", "path": "ro.x", "modules": ["a"] }]));
}
```

- [ ] **Step 2: Chạy `cargo test module_conflicts`, xác nhận FAIL** (chưa có hàm).
- [ ] **Step 3: Cài đặt `module_conflicts.rs`** theo Interfaces và quy tắc trên. Dùng `HashMap<String, Vec<String>>` cho path→modules và `BTreeMap` khi xuất để có thứ tự ổn định.
- [ ] **Step 4: Chạy test, xác nhận PASS** (8 passed trên Linux).
- [ ] **Step 5: Thêm `list_module_conflicts()` và `Module::Conflicts`** (doc comment `/// list files and props that several modules override`).
- [ ] **Step 6: `cargo ndk` check + clippy + fmt.** Trên máy thật (nếu có): `ksud module conflicts` in `[]` khi không có xung đột.
- [ ] **Step 7: Commit** `ksud: Detect modules that override the same file or prop`

### Task 4: Manager — model và cầu nối ksud

**Files:**
- Create: `manager/.../data/model/BootGuardStatus.kt`, `manager/.../data/model/ModuleConflict.kt`
- Modify: `manager/.../ui/util/KsuCli.kt`, `manager/app/build.gradle.kts`, `manager/gradle/libs.versions.toml`
- Test: `manager/app/src/test/java/me/weishu/kernelsu/data/model/BootGuardStatusTest.kt`, `ModuleConflictTest.kt`

**Interfaces:**
- Produces:
  - `data class BootGuardStatus(val failCount: Int, val threshold: Int, val autoDisabled: List<String>, val lastTrigger: Long?)` + `companion fun parse(json: String): BootGuardStatus` (JSON lỗi → `BootGuardStatus(0, 3, emptyList(), null)`, hằng `BootGuardStatus.Empty`)
  - `enum class ConflictKind { File, Replace, Prop }`, `data class ModuleConflict(val kind: ConflictKind, val path: String, val modules: List<String>)` + `fun parseModuleConflicts(json: String): List<ModuleConflict>` (lỗi/kind lạ → bỏ qua phần tử, JSON hỏng → `emptyList()`)
  - `fun List<ModuleConflict>.forModule(id: String): List<ModuleConflict>`
  - KsuCli: `fun getBootGuardStatus(): BootGuardStatus` (`boot-guard status`), `fun clearBootGuard(): Boolean` (`execKsud("boot-guard clear", true)`), `fun listModuleConflicts(): List<ModuleConflict>` (`module conflicts`) — đọc stdout theo mẫu `listModules()`.

- [ ] **Step 1: Thêm `testImplementation` cho `org.json:json:20250517`** qua version catalog (`json-org` trong `libs.versions.toml`), vì `org.json` của Android trong unit test JVM chỉ là stub.
- [ ] **Step 2: Viết test**: `parsesStatus` (JSON mẫu từ Task 1 → đúng field), `corruptStatusIsEmpty` (`"{"` → `Empty`), `parsesConflicts` (`[{"kind":"replace","path":"/system/media/audio","modules":["a","b"]}]` → 1 phần tử `Replace`), `unknownKindSkipped` (`kind:"x"` → bỏ), `forModuleFilters` (`forModule("b")` chỉ trả conflict có `"b"`).
- [ ] **Step 3: Chạy `cd manager && ./gradlew :app:testDebugUnitTest --tests '*BootGuardStatusTest*' --tests '*ModuleConflictTest*'`, xác nhận FAIL.** (Cần `libksud.so` trong `jniLibs` theo AGENTS.md trước mọi lệnh Gradle.)
- [ ] **Step 4: Cài đặt model + KsuCli.**
- [ ] **Step 5: Chạy lại, xác nhận PASS.**
- [ ] **Step 6: Commit** `manager: Read boot guard status and module conflicts from ksud`

### Task 5: Manager — giao diện

**Files:**
- Modify: `HomeUiState.kt`, `HomeViewModel.kt`, `HomeMiuix.kt`, `data/model/Module.kt`, `data/repository/ModuleRepository.kt` + `ModuleRepositoryImpl.kt`, `ui/viewmodel/ModuleViewModel.kt`, `ui/screen/module/ModuleMiuix.kt`, `ui/screen/flash/FlashScreen.kt`/`FlashMiuix.kt`
- Modify: `manager/app/src/main/res/values/strings.xml`, `values-vi/strings.xml`

**Interfaces:**
- Consumes: Task 4.
- Produces:
  - `HomeUiState.bootGuard: BootGuardStatus` (mặc định `Empty`), `val showBootGuardNotice get() = bootGuard.autoDisabled.isNotEmpty()`
  - `HomeViewModel.reenableModule(id: String)` (gọi `toggleModule(id, true)` rồi `refresh()`), `HomeViewModel.dismissBootGuard()` (gọi `clearBootGuard()` rồi `refresh()`), cả hai chạy trên `Dispatchers.IO`.
  - `Module.autoDisabled: Boolean = false`, `Module.conflicts: List<ModuleConflict> = emptyList()` — gán trong `ModuleRepositoryImpl.getModules()` bằng một lần gọi `getBootGuardStatus()` và một lần `listModuleConflicts()` cho cả danh sách (không gọi theo từng module).
  - Strings (en / vi):
    - `boot_guard_notice` = "KernelSU disabled %d module(s) after the device failed to boot %d times in a row" / "KernelSU đã tắt %d module vì máy khởi động lỗi %d lần liên tiếp"
    - `boot_guard_dialog_title` = "Modules disabled by boot guard" / "Module bị tắt do lỗi khởi động"
    - `boot_guard_reenable` = "Enable again" / "Bật lại"
    - `boot_guard_dismiss` = "Dismiss" / "Bỏ qua"
    - `module_badge_auto_disabled` = "Disabled by boot guard" / "Tự tắt do lỗi khởi động"
    - `module_badge_conflict` = "Conflict" / "Xung đột"
    - `module_conflict_title` = "Conflicts with other modules" / "Xung đột với module khác"
    - `module_conflict_file` = "%1$s — also changed by %2$s" / "%1$s — cũng bị sửa bởi %2$s"
    - `module_conflict_replace` = "%1$s is replaced as a whole — %2$s" / "%1$s bị thay toàn bộ — %2$s"
    - `module_conflict_prop` = "Property %1$s set differently by %2$s" / "Prop %1$s được đặt khác nhau bởi %2$s"
    - `flash_conflict_warning` = "This module conflicts with: %s" / "Module này xung đột với: %s"

- [ ] **Step 1: Home** — trong `buildState()` gọi `getBootGuardStatus()` khi `isManager && isRootAvailable`. Trong `HomePagerMiuix`, ngay sau `showRootWarning`, nếu `showBootGuardNotice` hiện `WarningCard(stringResource(R.string.boot_guard_notice, n, threshold), onClick = mở dialog)`. Dialog dùng component dialog glass sẵn có trong `ui/component/dialog/Dialog.kt`, liệt kê id (hoặc tên module nếu tra được từ danh sách module) với nút `boot_guard_reenable`, và nút `boot_guard_dismiss` ở chân. Thêm callback vào `HomeActions`: `onReenableModule: (String) -> Unit = {}`, `onDismissBootGuard: () -> Unit = {}`, nối ở `HomeScreen.kt`.
- [ ] **Step 2: Thẻ module** — trong `ModuleItem` (`ModuleMiuix.kt`), dưới dòng tác giả/phiên bản, hiện nhãn nhỏ cùng kiểu nhãn đang dùng cho metamodule: `module_badge_auto_disabled` khi `module.autoDisabled && !module.enabled`; `module_badge_conflict` khi `module.conflicts.isNotEmpty()`, bấm mở dialog `module_conflict_title` với mỗi dòng theo `kind` (danh sách module khác = `modules - module.id`, nối bằng ", ").
- [ ] **Step 3: Màn hình flash** — khi cài module thành công, gọi `listModuleConflicts()` (ksud tính cả bản trong `modules_update`), lấy `forModule(<id vừa cài>)`; nếu khác rỗng, thêm một dòng `flash_conflict_warning` vào cuối log flash. Nếu id module vừa cài không có sẵn trong flash flow, lấy theo thư mục mới xuất hiện trong `modules_update` là không đáng tin — khi đó bỏ bước này và ghi chú lại trong báo cáo thay vì đoán.
- [ ] **Step 4: Build** — `cd manager && ./gradlew :app:assembleDebug` thành công; `./gradlew :app:testDebugUnitTest` vẫn pass.
- [ ] **Step 5: Kiểm tra trên máy (nếu có):** sửa tay `bootguard.json` thành `{"autoDisabled":["<id đang tắt>"]}` → Home hiện thẻ; "Bật lại" làm thẻ biến mất; cài 2 module cùng có `system/etc/hosts` → cả hai thẻ có nhãn "Xung đột".
- [ ] **Step 6: Commit** `manager: Show boot guard notices and module conflicts`

### Task 6: Ghi chép cho fork

**Files:**
- Modify: `docs/CAM_CHANGES.md`

- [ ] **Step 1:** Thêm mục mô tả Boot Guard và module conflicts: file mới, điểm chạm vào code upstream (`init_event.rs`, `module.rs` đổi chữ ký `handle_updated_modules`, `enable_module`, `cli.rs`, `ModuleMiuix.kt`, `HomeMiuix.kt`) để lần merge upstream sau biết chỗ cần giữ.
- [ ] **Step 2: Commit** `docs: Record boot guard and module conflicts for upstream merges`
