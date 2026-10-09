# Cam Kernel SU: các thay đổi so với KernelSU gốc

Tài liệu này ghi lại mọi chỗ Cam Kernel SU khác với upstream (`tiann/KernelSU`), để mỗi lần kéo bản cập nhật về thì biết chỗ nào sẽ conflict và phải giữ lại gì.

- Upstream gốc lúc tách nhánh: commit `08a3b087` (`refactor(ksud): update waitsys and refactor logic (#3755)`)
- Remote: `origin` = `peo1108/Cam-Kernel-SU`, `upstream` = `tiann/KernelSU`
- Nhánh làm việc: `main`. Đợt UI/UX gần nhất nằm trong commit `47a57e48` (bỏ Material, thẻ 3D, slime, quản lý app). Tài liệu cập nhật lần cuối 2026-10-09.
- Mục 3 liệt kê file, mục 4 là quy trình kéo upstream, mục 9 là bản đồ phần slime/3D để sửa cho đúng chỗ, mục 10 là cách tạo lại file vật liệu `roam_jelly.filamat`, **mục 11 là đợt đổi tên sang Cam** (đọc trước khi merge upstream).
- **Từ 2026-10-09 mọi tên đã đổi** (mục 11): `me.weishu.kernelsu` → `cam.su.kernel`, `Ksu`/`Natives`/`KsuService`/`MainActivity` → `Cam`/`CamNative`/`CamRootService`/`CamActivity`, `ksud` → `camd` (`userspace/camd`), `/data/adb/ksu` → `/data/adb/cam`, `kernelsu.ko` → `camsu.ko`. Mục 1-3 viết trước đó nên còn ghi tên cũ; tra bảng đối chiếu ở mục 11.

## 1. Tóm tắt thay đổi

| # | Thay đổi | Lý do |
|---|---|---|
| 1 | Bỏ hẳn việc kernel quét `/data/app` và kiểm tra chữ ký APK để tìm Manager | Không còn "app manager" đặc biệt trong kernel |
| 2 | "Manager" trong kernel = **uid 0** | App Manager là một app có root bình thường |
| 3 | Seed root lúc patch: `ksud boot-patch --seed pkg:appid` ghi `seed=` vào `ksu_config`, kernel cấp root lúc boot | Lần đầu flash, Manager tự có root mà không cần kernel nhận diện |
| 4 | Lệnh `ksud allow add\|remove\|list <pkg>` | Cấp/thu root từ adb su hoặc Termux, không cần flash lại |
| 5 | Manager gọi kernel qua `KsuService` (root service, uid 0) | Kernel chỉ nhận lệnh quản trị từ uid 0 |
| 6 | Màn Install có dialog chọn app được root (seed) | |
| 7 | Tên app `SU Kernel` (trước là `Cam Kernel SU`), gói `cam.su.kernel`, icon và splash là hình tam giác | |
| 8 | `KERNEL_SU_UAPI_VERSION` 4 → 5 | Chặn Manager/ksud bản cũ dùng với kernel mới |
| 9 | Giao diện Miuix thành kính lỏng (Liquid Glass) kiểu iOS, nền là hình nền máy đọc qua root | Spec: `docs/superpowers/specs/2026-10-02-miuix-liquid-glass-design.md` |
| 10 | Kernel luôn cấp root cho gói Manager `cam.su.kernel` theo tên gói, dò lại UID mỗi khi `packages.list` đổi (`ksu_manager_pin_apply` trong `kernel/policy/pkg_tracker.c`) | Gỡ rồi cài lại Manager không mất root; chỉ áp dụng cho đúng gói này |
| 11 | CI: chạy tay được "Build Manager", Release theo tag có quyền ghi và ghi chú tự sinh, khóa ký riêng `su-kernel` | Build bản đầy đủ (8 KMI) và phát hành trên GitHub |
| 12 | **Chỉ còn một giao diện (Miuix kính)**: xoá toàn bộ giao diện Material, `UiMode`, mục "Kiểu giao diện" trong Cài đặt | Một giao diện thì chỉ phải bảo trì một bộ file; upstream vẫn có Material nên merge sẽ gặp conflict "sửa/xoá" (mục 4) |
| 13 | Thẻ trạng thái ở Home là **hộp kính 3D (Filament)** với 4 slime (Mochi, Bơ, Soda, Chanh): 9 cảnh mở màn theo nước, 36 bộ đồ 3D, nhiều kiểu xếp hàng / ăn chữ / hóc / nôn | Thẻ "Đang hoạt động" có hồn, không chỉ là một dòng chữ |
| 14 | **Slime đi dạo khắp app**: lớp 3D trong suốt phủ cả cửa sổ; rời Home thì phá kính chui ra, về Home thì vào cửa; đập tay, cưỡi Bơ, chơi khăm, rượt, đánh nhau; laser bằng ngón tay, lắc máy, ngủ ban đêm | Thay cho việc chỉ có slime trong thẻ; có công tắc trong Chủ đề |
| 15 | Trang **Hồ sơ ứng dụng**: thêm 3 thẻ mở rộng (Thông tin + đường dẫn bấm để copy, Dung lượng, Quản lý: sao lưu APK, xoá cache/dữ liệu, đóng băng, gỡ cài đặt kể cả app hệ thống); menu "⋯" thêm "Thông tin hệ thống"; menu trang Superuser thêm "Cài lại app hệ thống đã gỡ" | Trang này trước đây trống |
| 17 | **Boot guard**: `post-fs-data` đếm số lần boot chưa tới `boot-completed`; tới lần thứ 3 liên tiếp thì ksud tự tắt module mới cài/cập nhật/bật lại (không có thì tắt hết module đang bật). Home hiện thẻ đỏ + hộp thoại "Bật lại / Bỏ qua", thẻ module có chip "Tự tắt do lỗi khởi động" | Cứu máy khi module hỏng làm bootloop mà không cần bấm phím vào safe mode |
| 18 | **Phát hiện xung đột module**: `ksud module conflicts` tìm file (kể cả whiteout), thư mục `.replace` và prop `system.prop` bị nhiều module cùng sửa; thẻ module có chip "Xung đột · N", màn hình flash ghi cảnh báo | Biết vì sao module này làm hỏng module kia |
| 19 | **Tab "Tính năng"** trên thanh điều hướng (giữa Module và Cài đặt): thẻ mở rộng Chống bootloop (bật/tắt, số lần lỗi 1-4, tắt module nghi ngờ trước hay tắt hết, danh sách module bị tự tắt), Chống xung đột module (bật/tắt, cảnh báo khi cài, tính cả prop, danh sách + quét lại), Tự ẩn bootloader (sắp có) | Gom các tính năng riêng của SU Kernel vào một chỗ |
| 20 | **Tự ẩn bootloader** (tầng prop, không cần module): ksud sửa các prop đang lộ trạng thái mở khoá về giá trị của máy đã khoá ở `post-fs-data` và `boot-completed`; thẻ trong tab Tính năng có công tắc, danh sách prop/bootconfig, nút **Kiểm tra attestation** (tự tạo key trong AndroidKeyStore, đọc RootOfTrust) | Ẩn với app chỉ đọc prop; bộ kiểm tra cho thấy app đọc chứng chỉ thấy gì |
| 21 | **Thông báo khi chống bootloop ra tay** (ksud đăng thông báo hệ thống lúc `boot-completed`, dưới uid shell vì Android bỏ thông báo từ uid 0; mỗi lần kích hoạt báo một lần, khoá `notifyPending`), **giữ bản cũ khi cập nhật module** (`/data/adb/ksu/module_backup/<id>`, chép bằng `busybox cp -a` để giữ symlink và whiteout; `ksud module restore <id>` đưa bản cũ vào hàng chờ, có chip "↺ Bản trước" trên thẻ module), tab Tính năng làm mới khi mở lại app | Đi cùng chống bootloop: biết ngay khi module bị tắt và quay lại được bản chạy tốt |
| 16 | Font Baloo 2; bỏ thẻ "Tìm hiểu KernelSU" và "Ủng hộ" ở Home; mặc định cài đặt lấy theo máy của Cam (cử chỉ quay lại dự đoán bật, mô tả module 5 dòng) | Thương hiệu riêng, không còn dấu vết KernelSU trong giao diện |
| 22 | **Đổi tên toàn bộ sang Cam** (mục 11): gói nguồn, class Manager, thư viện JNI (`libcamjni.so`), ksud → `camd` (`libcamd.so`), `/data/adb/ksu` → `/data/adb/cam`, `kernelsu.ko` → `camsu.ko`, SELinux domain `ksu` → `cam`, Magica → jailbreak | Dự án dùng riêng, không còn tên KernelSU ở tầng người dùng/userspace |
| 23 | **Lớp tương thích khi đổi tên**: camd tự chuyển dữ liệu cũ; kernel vẫn khai báo `ksu`/`ksu_file` cùng quyền và chạy profile `u:r:ksu:s0` trong `cam`; camd/caminit nhận `kernelsu.ko` cũ; module được thêm biến `CAM_*` cạnh `KSU_*`, WebUI có bridge `cam` cạnh `ksu`, deep link `cam://` (vẫn nhận `ksu://`) | Cập nhật từ bản cũ không mất root, không mất allowlist/module |

## 2. Lịch sử commit

Danh sách luôn mới nhất:

```bash
git log --oneline 08a3b087..main
```

Các nhóm chính (theo thứ tự thời gian):
- `2051c0bf` … `ce345906`: bỏ nhận diện Manager trong kernel, seed, `ksud allow`, root service, đổi gói.
- `1694c43e` … `f91c98ca`: giao diện kính (nền, card, nút, dialog, popup, cài đặt nền).
- `b7acde6f kernel: keep root for the manager package across reinstalls`.
- `1c27052b` … `11e616ac`: tối ưu kính (vẽ card từ nền đã blur sẵn, chuyển trang, gradient), mép mờ khi cuộn, dropdown kính, About, icon tam giác.
- `c5c94e1e`, `9c7e2031`, `1c3c5037`, `2d449c13`: CI (workflow_dispatch, release, sửa Clippy, sửa phiên bản LKM trên CI).
- `47a57e48 manager: Go glass-only and add the slime arena and app manager`: một commit lớn (178 file) gom toàn bộ đợt UI/UX: bỏ Material, thẻ 3D + slime, quản lý app, cài đặt/mặc định, font.
- `1cfdc6d1` … `0566d61d`: boot guard và phát hiện xung đột module (kế hoạch: `docs/superpowers/plans/2026-10-04-bootguard-and-module-conflicts.md`), kèm `6789fb7b scripts: Keep every shell script LF on Windows checkouts`.
- `43847238` … (sau): tab "Tính năng" (kế hoạch: `docs/superpowers/plans/2026-10-04-features-tab.md`).
- `1b9b0673 kernel: trust the Cam Kernel SU manager signing key`: **đã lỗi thời** (kernel không còn kiểm tra chữ ký).

## 3. File bị đổi, theo khu vực

Ký hiệu: **[mới]** file của Cam, upstream không có, không bao giờ conflict. **[xoá]** file upstream mà Cam đã xoá. **[sửa]** file upstream có sửa, dễ conflict.

### Kernel (`kernel/`)
- [xoá] `manager/apk_sign.c`, `manager/apk_sign.h`, `manager/throne_tracker.c`, `manager/throne_tracker.h`, `manager/manager_observer.h`
- [chuyển] `manager/pkg_observer.c` → `policy/pkg_observer.c`
- [mới] `policy/pkg_tracker.c`, `policy/pkg_tracker.h` (đọc `packages.list`, áp seed, **ghim root cho gói Manager**, prune allowlist), `policy/pkg_observer.h`
- [sửa] `manager/manager_identity.h`: chỉ còn `is_manager()` = `current_uid() == 0`
- [sửa] `hook/setuid_hook.c`: bỏ nhánh cài fd cho manager
- [sửa] `policy/allowlist.c/.h`: bỏ các trường hợp đặc biệt cho manager; thêm `ksu_grant_default_root()`
- [sửa] `supercall/dispatch.c`: `GET_MANAGER_APPID` luôn trả -1; bỏ cờ `PR_BUILD`
- [sửa] `core/init.c`, `runtime/boot_event.c`: gọi `ksu_pkg_tracker_update()` thay cho `track_throne()`
- [sửa] `Kbuild`, `Kconfig`: bỏ `CONFIG_KSU_DISABLE_MANAGER`, `KSU_EXPECTED_SIZE/HASH*`, `KSU_MANAGER_PACKAGE`

### UAPI (`uapi/`)
- [sửa] `supercall.h`: `KERNEL_SU_UAPI_VERSION = 5`

### ksud (`userspace/ksud/`)
- [mới] `src/seed.rs` (định dạng seed, có unit test), `src/allow.rs` (lệnh `allow`, có unit test)
- [xoá] `src/apk_sign.rs`
- [sửa] `src/boot_patch.rs`: tham số `--seed`
- [sửa] `src/cli.rs`, `src/cli_non_android.rs`: thêm `allow`, bỏ `debug set-manager` và `get-sign`
- [sửa] `src/debug.rs`: bỏ `set_manager`
- [sửa] `src/ksucalls.rs`: thêm `set_app_profile()`, `uid_granted_root()`
- [sửa] `src/main.rs`: khai báo module
- [sửa] `src/sepolicy.rs`: dòng `#![allow(clippy::redundant_field_names)]` ở đầu file (code do `derive_new` sinh ra bị Clippy mới bắt lỗi)
- [sửa] `build.rs`: `KSU_PACKAGE_NAME` mặc định `cam.su.kernel`
- [mới] `src/boot_guard.rs` (trạng thái + quyết định, thuần, có unit test, file `/data/adb/ksu/bootguard.json`), `src/module_conflicts.rs` (quét cây module, parse `system.prop`, so xung đột, thuần, có unit test). Cả hai **không** gắn `cfg(android)` để `cargo test` chạy trên Linux.
- [sửa] `src/init_event.rs`: `run_boot_guard()` chạy trong `on_post_data_fs` **sau** `handle_updated_modules` và **trước** `prune_modules` / `regenerate_preinit_rc` (module bị tắt không lọt vào `modules.rc`); `on_boot_completed` reset bộ đếm.
- [sửa] `src/module.rs`: `handle_updated_modules()` trả `Vec<String>` id vừa cập nhật (đổi chữ ký, `late_load.rs` vẫn gọi được); `enable_module` gọi `boot_guard::on_module_enabled`; thêm `enabled_module_ids`, `list_module_conflicts`, `boot_guard_status`, `boot_guard_clear`.
- [sửa] `src/cli.rs`: lệnh `boot-guard status|clear|set [--enabled true|false] [--threshold 2-5] [--mode suspects|all]`, `module conflicts`; cấu hình nằm luôn trong `bootguard.json` (khoá `enabled`, `threshold`, `mode`; file cũ thiếu khoá thì ra mặc định bật / 3 / suspects); `src/defs.rs`: `BOOT_GUARD_PATH`; `Cargo.toml`: `serde_json` chuyển sang dependency chung (test host cần).

### Manager (`manager/`): phần root service
- [mới] `Ksu.kt` (facade cho UI), `KsuServiceClient.kt` (bind root service), `ui/screen/install/SeedPicker.kt`
- [sửa] `aidl/.../IKsuInterface.aidl`, `ui/KsuService.kt`: thêm các hàm gọi kernel
- [sửa] `cpp/ksu.cc`: process uid 0 tự xin fd qua reboot magic; bỏ `is_manager()` (cùng `ksu.h`, `jni.cc`)
- [sửa] `Natives.kt`: bỏ `isManager`, `isFullFeatured`
- [sửa] khoảng 25 file UI/viewmodel/repository: `Natives.xxx` → `Ksu.xxx` (đổi máy móc)
- [sửa] `ui/screen/install/InstallScreen.kt`, `ui/screen/flash/FlashUtils.kt`, `ui/util/KsuCli.kt`: truyền seed vào `boot-patch`
- [sửa] `AndroidManifest.xml`: thêm `QUERY_ALL_PACKAGES`
- [sửa] `res/values/strings.xml`, `res/values-vi/strings.xml`: chuỗi `seed_*`, `glass_background*`

### Manager (`manager/`): giao diện kính
- [mới] `ui/component/glass/`: `GlassBackground` (nền, `GlassPage`, cache nạp sẵn), `GlassMaterial` (card vẽ từ nền đã blur sẵn), `GlassCard`, `GlassButton` (giọt nước, `GlassButtonGroup`, `liquidControl`), `GlassOverlay` (`GlassDialog`, `GlassListPopup`, `GlassFab`), `GlassDropdown`, `GlassStandIn`, `GlassContrast`, `GlassImage`, `GlassDefaults` (**mọi thông số kính ở đây**)
- [mới] `ui/component/liquid/GravityHighlight.kt`, `data/repository/WallpaperRepository.kt`, `ui/screen/colorpalette/GlassBackgroundSection.kt`, unit test JVM ở `manager/app/src/test`
- [sửa] mọi file `*Miuix.kt`, đổi máy móc:

  | Component gốc (miuix) | Bản kính |
  |---|---|
  | `Card` | `GlassCard` (item trong `LazyColumn` dài: `GlassListCard`) |
  | `IconButton` (`return@IconButton` → `return@GlassIconButton`) | `GlassIconButton` |
  | nội dung `actions = { … }` của `TopAppBar` | bọc trong `GlassButtonGroup { … }` |
  | `OverlayDialog` | `GlassDialog` |
  | `OverlayListPopup` | `GlassListPopup` |
  | `OverlayDropdownPreference` | `GlassDropdownPreference` |
  | `FloatingActionButton` | `GlassFab` |
  | `Scaffold(` | `Scaffold(containerColor = Color.Transparent,` |
  | `BlurredBar(backdrop) {` | `BlurredBar(backdrop, scrollBehavior = scrollBehavior) {` |

- [sửa] `ui/util/BlurExt.kt` (top bar trong suốt, mép mờ khi cuộn, backdrop cho nút trên thanh), `ui/MainActivity.kt` (mỗi trang bọc `GlassPage`, nạp sẵn nền khi splash, blur luôn bật ở Miuix), `component/miuix/SuperSearchBar.kt`, `component/dialog/DialogMiuix.kt`, `component/bottombar/BottomBarMiuix.kt`, `component/bottombar/NavigationRailMiuix.kt`, `component/FloatingBottomBar.kt`, `component/miuix/effect/BgEffectConfig.kt`, `BgEffectBackground.kt`, `screen/about/AboutMiuix.kt`, `screen/colorpalette/ColorPaletteScreenMiuix.kt` (bỏ 2 công tắc blur cũ), `data/repository/SettingsRepository*.kt`, `ui/viewmodel/*`
- [sửa] Thương hiệu: `app/build.gradle.kts` (gói `cam.su.kernel`, tên `SU Kernel`), `gradle.properties` (`KSU_NAME=SU Kernel`), `res/mipmap-anydpi/ic_launcher.xml`, `res/values/colors.xml` (nền icon đen), `res/values*/themes.xml` (splash nền đen + `@drawable/ic_splash_logo`)
- [mới] Icon: `manager/icon/launcher-src.png`, `scripts/gen_launcher_icon.py` sinh `mipmap-*/ic_launcher_logo*.png` và `drawable-xxxhdpi/ic_splash_logo.png`

### Manager: bỏ giao diện Material (glass-only)
- [xoá] `ui/UiMode.kt`, `ui/theme/MaterialTheme.kt`, cả thư mục `ui/component/material/`, và mọi file `*Material.kt` (màn hình, dialog, bottom bar, rail, profile config, status tag, reboot popup, uninstall dialog, WebUI…), cùng `ui/component/profile/dialogs/` (`MultiSelectDialog`, `SingleSelectDialog`). Toàn bộ nằm trong `manager/app/src/main/java/me/weishu/kernelsu/`.
- [sửa] Mỗi `XxxScreen.kt` / component trước đây chọn bản theo `LocalUiMode` giờ gọi thẳng bản Miuix. Ví dụ `SettingsScreen.kt`: `when (LocalUiMode.current) { Miuix -> SettingPagerMiuix(…); Material -> … }` thành `SettingPagerMiuix(uiState, actions, bottomInnerPadding)`.
- [sửa] `ui/MainActivity.kt`: không còn `LocalUiMode`; `LocalEnableBlur provides true`; mọi route bọc `GlassPage { … }` (không còn `GlassPageIfMiuix`).
- [sửa] `SettingsMiuix.kt`: bỏ dropdown "Kiểu giao diện"; `strings.xml` bỏ `settings_ui_mode*`.
- [sửa] Mọi `res/values*/strings.xml` (khoảng 40 ngôn ngữ): đã xoá `home_learn_kernelsu*`, `home_click_to_learn_kernelsu`, `home_support_*` (không còn thẻ tương ứng ở Home).
- Giữ nguyên dependency `androidx.compose.material3`: vài file vẫn dùng (markdown, Monet/WebUI, `MiuixTheme.kt`, `SeedPicker.kt`…).

### Manager: thẻ trạng thái 3D và slime
Cam tự viết toàn bộ phần này, upstream không có file nào tương ứng nên **không conflict**, trừ 3 chỗ móc vào file của upstream (ghi bên dưới). Bản đồ chi tiết ở mục 9.
- [mới] `ui/screen/home/arena/` (thẻ trạng thái, 2D + logic cảnh): `StatusArena.kt` (cửa vào, vòng lặp khung hình), `ArenaShow.kt` (các pha của show), `ArenaStage.kt`, `ArenaSlime.kt` (vẽ slime 2D, dùng khi máy không chạy được Filament), `ArenaScenes/Decor/Costumes/Props/Fx/Math.kt`, `ArenaIntros{,2,3}.kt` (9 cảnh mở màn), `ArenaVomit.kt`, `ArenaFeast.kt` (ăn chữ + hóc), `ArenaFormations.kt` (các kiểu xếp hàng).
- [mới] `ui/screen/home/arena/three/` (Filament): `Arena3D.kt` (hộp kính 3D), `SlimeRig.kt` (**mô hình slime dùng chung** cho hộp và cho lớp đi dạo), `Roam3D.kt` (lớp 3D trong suốt phủ cả cửa sổ), `Closet.kt` + `Wardrobe.kt` + `Tailor.kt` (36 bộ đồ), `Glb.kt`, `Shapes3D.kt`, `Morphs.kt`, `Room.kt`, `Grounds.kt`.
- [mới] `ui/slime/`: `RoamWorld.kt` (bộ não + vật lý), `RoamSocial.kt` (hai con chơi với nhau), `SlimeLayer.kt` (host Compose, xử lý chạm, cảm biến lắc), `SlimeHome.kt` (trạng thái hộp: ai ở trong, kính vỡ, cửa, vá kính), `SlimeSurfaces.kt` (`Modifier.slimeSurface()`: thẻ nào đăng ký làm chỗ đứng cho slime).
- [mới] `assets/roam_jelly.filamat` (vật liệu thạch cho lớp đi dạo, biên dịch sẵn; cách tạo lại ở mục 10), `ui/component/motion/CardEntrance.kt` (thẻ trượt vào khi mở trang).
- [sửa] 3 chỗ móc vào file có sẵn, **phải giữ khi merge**:
  - `ui/MainActivity.kt`: bọc `navDisplay()` trong `SlimeLayer(…)`, báo vị trí vuốt của pager (`SlimeHome.pagerPos`) để slime phá kính ngay khi bắt đầu vuốt khỏi Home.
  - `ui/component/glass/GlassCard.kt`: `modifier.slimeSurface().glassMaterial(…)` (mỗi thẻ kính là một chỗ đứng).
  - `ui/screen/home/HomeMiuix.kt`: gọi `StatusArena(…)` thay cho thẻ trạng thái của upstream.
- [sửa] Dependency: `gradle/libs.versions.toml` (`filament = "1.77.1"` + `filament-android`, `filament-gltfio`, `filament-utils`), `app/build.gradle.kts` (3 dòng `implementation`), `app/proguard-rules.pro` (`-keep class com.google.android.filament.** { *; }`).

### Manager: trang Hồ sơ ứng dụng
- [mới] `ui/screen/appprofile/AppManageCards.kt` (3 thẻ Thông tin / Dung lượng / Quản lý + các dialog gỡ cài đặt), `ui/util/AppManager.kt` (lệnh root, đo dung lượng, sao lưu, gỡ, danh sách app được bảo vệ), `ui/screen/superuser/RestoreSystemAppsDialog.kt`, `ui/screen/appprofile/AppProfileEditor.kt` (logic chỉnh hồ sơ dùng chung), `ui/screen/superuser/InlineAppProfile.kt` (hồ sơ sửa ngay trong thẻ Superuser mở rộng).
- [sửa] `AppProfileMiuix.kt` (gắn 3 thẻ, thêm "Thông tin hệ thống" vào menu "⋯", **không vẽ thẻ rỗng khi hồ sơ để "Mặc định"**: trước đây thẻ rỗng hiện thành một đường kẻ đen), `AppProfileScreen.kt`, `AppProfileUiState.kt` (thêm `onOpenSystemInfo`, `onAppChanged`, `onAppRemoved`), `SuperUserMiuix.kt` (mục menu "Cài lại app hệ thống đã gỡ", thẻ mở rộng).
- [sửa] `res/values/strings.xml`, `res/values-vi/strings.xml`: thêm `app_*` (78 chuỗi). Ngôn ngữ khác sẽ hiện tiếng Anh.
- Điểm dễ vỡ, **đừng sửa nếu chưa hiểu**:
  - `AppManager.kt` chạy mọi lệnh root bằng `getRootShell(globalMnt = true)`. Shell mặc định nằm trong mount namespace riêng của app, Android giấu thư mục dữ liệu của app khác ở đó nên `du` ra 0 B và xoá cache không xoá được gì.
  - Gỡ systemless: tạo zip module `debloat_<gói>` trong cache (có `.replace` đè thư mục app trong ROM) rồi `ksud module install`; sau đó khởi động lại. Tắt hoặc xoá module để khôi phục.
  - `isProtectedApp()` chặn gỡ/đóng băng/xoá dữ liệu cho các gói lõi (`android`, SystemUI, Settings, phone, launcher và bàn phím đang dùng, chính app này…). Upstream không có cơ chế này.
  - Sao lưu APK ghi vào cache rồi chia sẻ qua FileProvider; `res/xml/filepaths.xml` đã có `cache-path`, không cần sửa Manifest.

### Manager: boot guard và xung đột module
- [mới] `data/model/BootGuardStatus.kt`, `data/model/ModuleConflict.kt` (parse JSON của ksud, có unit test), `ui/util/module/ModuleZip.kt` (đọc `id` trong `module.prop` của zip, có unit test), `ui/screen/home/BootGuardNotice.kt` (thẻ + hộp thoại), `ui/screen/module/ModuleStatusBadges.kt` (chip + hộp thoại xung đột).
- [sửa] `ui/util/KsuCli.kt` (`getBootGuardStatus`, `clearBootGuard`, `listModuleConflicts`, cảnh báo xung đột trong `flashModule`), `data/model/Module.kt` (`autoDisabled`, `conflicts`), `data/repository/ModuleRepositoryImpl.kt`, `HomeUiState.kt`, `HomeViewModel.kt`, `HomeScreen.kt`, **một dòng** trong `HomeMiuix.kt` (`BootGuardNotice`) và **một dòng** trong `ModuleMiuix.kt` (`ModuleStatusBadges`). Merge upstream mà hai file Miuix này conflict thì chỉ cần giữ lại hai lời gọi đó.
- [sửa] `build.gradle.kts`, `gradle/libs.versions.toml`: `testImplementation(libs.json.org)` (`org.json` của android.jar chỉ là stub trong unit test).
- Chuỗi mới `boot_guard_*`, `module_badge_*`, `module_conflict_*`, `flash_conflict_warning` (Anh + Việt).
- Điểm dễ vỡ: `lastTrigger` trong `bootguard.json` **không** dùng làm ngày giờ được (lúc `post-fs-data` đồng hồ máy chưa đồng bộ, máy thật ghi `12320886`). Mở Manager là nó cài lại `libksucam.so` của chính nó vào `/data/adb/ksud`: muốn thử ksud mới thì phải thay cả `lib/arm64/libksucam.so` trong thư mục app, hoặc build lại Manager.

### Manager: tab "Tính năng"
- [mới] `ui/screen/features/FeaturesScreen.kt`, `FeaturesMiuix.kt`, `FeaturesUiState.kt`, `ui/viewmodel/FeaturesViewModel.kt`, `ui/viewmodel/ModuleListSignal.kt` (báo trang Module tải lại khi trang khác đổi tuỳ chọn xung đột hoặc bật lại module), `ui/component/glass/GlassExpandableCard.kt` (thẻ mở rộng, chuyển ra từ `AppManageCards.kt` để dùng chung, thêm tham số `enabled`).
- [sửa] `ui/component/bottombar/BottomBarMiuix.kt` (enum `BottomBarDestination` thêm `Features` trước `Setting`), `ui/viewmodel/MainActivityViewModel.kt` (`PAGE_COUNT = 5`), `ui/MainActivity.kt` (`when (page)`: 3 = Tính năng, 4 = Cài đặt; `beyondViewportPageCount = LAST_PAGE_INDEX`), `ui/screen/module/ModuleScreen.kt` (nghe `ModuleListSignal`).
- [sửa] `data/repository/SettingsRepository*.kt`: khoá `conflict_detection`, `conflict_warn_on_flash`, `conflict_include_props` (đều mặc định bật). `ModuleRepositoryImpl` và `flashModule` áp các khoá này qua `List<ModuleConflict>.visible()`.
- Merge upstream: **upstream chỉ có 4 tab**. Code upstream nào ghi cứng chỉ số trang (3 = Cài đặt) phải đổi thành 4. Tìm bằng `rg "PAGE_COUNT|when \(page\)|BottomBarDestination" manager/`.
- Chuỗi mới `features_*` (Anh + Việt).

### Tự ẩn bootloader
- ksud: [mới] `src/hide_bootloader.rs` (bảng quy tắc prop → giá trị an toàn, thuần, có unit test; phần Android `apply_if_enabled`/`status`/`set_enabled`); [sửa] `src/resetprop.rs` (`list_props`, `set_prop`), `src/init_event.rs` (gọi sau `load_system_prop` và trong `on_boot_completed`), `src/cli.rs` (`hide-bootloader status|enable|disable`), `src/defs.rs` (cờ `/data/adb/ksu/.hide_bootloader`). Chỉ sửa prop **có sẵn**, không thêm prop mới; không đụng `ro.oem_unlock_supported`; `/proc/bootconfig` và `/proc/cmdline` chỉ báo.
- Manager: [mới] `data/model/HideBootloaderStatus.kt`, `data/model/KeyAttestation.kt` (bộ đọc DER viết tay cho KeyDescription/RootOfTrust, có unit test), `data/model/Revocation.kt` (đối chiếu serial cả chuỗi chứng chỉ với danh sách thu hồi công khai `https://android.googleapis.com/attestation/status`, có unit test), `ui/util/AttestationCheck.kt`; [sửa] thẻ thứ ba trong `ui/screen/features/FeaturesMiuix.kt`, `FeaturesViewModel.kt`, `KsuCli.kt`; chuỗi `features_bootloader_*`, `features_attestation_*`.
- Giới hạn: **không** đổi được chứng chỉ attestation (cần tầng keystore2, chưa làm). Máy của Cam root bằng EFISP nên bootloader luôn khoá: thử trên máy bằng cách tạm đặt `ro.boot.verifiedbootstate=orange` qua resetprop; attestation thật báo TEE / khoá / Verified.

### Manager: cài đặt, mặc định, font
- [sửa] `data/repository/SettingsRepository.kt`, `SettingsRepositoryImpl.kt`: thêm 3 khoá `roaming_slimes` (mặc định bật), `roaming_slime_count` (0 = ngẫu nhiên 1-4; hoặc 1..4), `slime_night_nap` (bật). **Đổi mặc định**: `enable_predictive_back` = `true`, `module_description_max_lines` = `5`. Kèm `SettingsUiState.kt`, `MainActivityUiState.kt`, `MainActivityViewModel.kt` (có danh sách `observedKeys`, thêm khoá mới vào đó), `SettingsViewModel.kt`.
- [sửa] Giao diện ba công tắc slime nằm cuối trang **Chủ đề**: `colorpalette/ColorPaletteScreenMiuix.kt`, `ColorPaletteScreen.kt`, `ColorPaletteUiState.kt` (không nằm ở trang Cài đặt).
- [mới] Font: `res/font/baloo2_{regular,medium,semibold,bold}.ttf`, `assets/licenses/Baloo2-OFL.txt`; [sửa] `ui/theme/Type.kt` (`AppFontFamily`), `ui/theme/Theme.kt`, `ui/theme/MiuixTheme.kt`.

### CI và công cụ
- [sửa] `.github/workflows/build-manager.yml`: thêm `workflow_dispatch`
- [sửa] `.github/workflows/release.yml`: `permissions: contents: write`, `generate_release_notes: true`
- [sửa] `.github/workflows/ddk-lkm.yml`: `safe.directory "$GITHUB_WORKSPACE"` (thay cho tên repo gốc ghi cứng) và checkout `fetch-depth: 0`. Thiếu hai dòng này module CI báo phiên bản **16**
- [sửa] `.gitattributes`: **mọi** `*.sh` luôn LF (trước chỉ `scripts/*.sh`; `installer.sh` CRLF bị nhúng vào ksud làm mọi lệnh cài module lỗi `umask: illegal mode: 022\r`)
- [mới] `scripts/build_lkm_camd.sh` (trước là `build_lkm_ksud.sh`; build LKM + camd từ một commit trong WSL)
- [mới] `scripts/cam_rename.sed` + `scripts/cam_rename.skip` (đổi tên KernelSU → Cam cho code upstream mới merge vào; danh sách file tương thích không được chạy qua; mục 11)

## 4. Kéo bản cập nhật upstream (làm theo thứ tự)

### Bước 1: chuẩn bị

Dùng **git của Windows** (Git Bash/PowerShell) cho repo này. Trong WSL chỉ dùng git trên bản clone riêng (xem mục 5).

```bash
git checkout main
git pull origin main          # nếu sửa trên GitHub
git status                    # chỉ được còn " D manager/app/src/main/cpp/uapi" (junction, bình thường)
git fetch upstream
git log --oneline main..upstream/main | wc -l   # upstream có bao nhiêu commit mới
```

### Bước 2: merge trên một nhánh riêng

```bash
git checkout -b merge-upstream-YYYYMMDD
git -c merge.renameLimit=10000 merge upstream/main
```

Dùng `merge` thay vì `rebase`: chỉ phải giải conflict một lần. `renameLimit` để git nhận ra các file đã đổi chỗ trong đợt đổi tên (mục 11); thiếu nó, thay đổi upstream ở `me/weishu/kernelsu/...`, `userspace/ksud/...` sẽ thành conflict "deleted by us" thay vì tự áp vào file mới.

**Đừng** chạy `git checkout -- <thư mục>`, `git clean`, hay `git add manager/app/src/main` (có junction `cpp/uapi`, xem mục 5). Luôn add từng file cụ thể.

### Bước 3: giải conflict

Đổi tên (làm trước, xem mục 11):
- **Upstream sửa/thêm file ở đường dẫn cũ** (`manager/app/src/main/java/me/weishu/kernelsu/...`, `userspace/ksud/...`, `userspace/ksuinit/...`, `kernel/runtime/ksud*`, `.github/workflows/ksud*.yml`): nếu git đã tự áp vào file mới thì thôi. Nếu nó tạo lại file ở đường dẫn cũ, `git mv` file đó sang đường dẫn mới theo bảng ở mục 11 (file đổi tên class thì đổi cả tên file, ví dụ `Natives.kt` → `CamNative.kt`).
- Giải xong conflict thì chạy script đổi tên cho **đúng các file merge mang vào** (không chạy trên cả repo, không chạy trên file tương thích trong `scripts/cam_rename.skip`):

  ```bash
  git diff --name-only ORIG_HEAD HEAD -- manager/app/src userspace kernel .github scripts \
    | grep -v -f scripts/cam_rename.skip | xargs -r sed -i -f scripts/cam_rename.sed
  ```

  Chạy trước khi commit merge thì thay `ORIG_HEAD HEAD` bằng `--cached` (file đã `git add`) hoặc liệt kê tay. File nằm trong `cam_rename.skip` mà upstream có sửa thì nhận thay đổi của upstream bằng tay, giữ nguyên phần tương thích (mục 11).
- Upstream thêm tên mới có chữ `ksu`/`Ksu`/`kernelsu` mà script chưa biết: quyết định đổi hay giữ, rồi thêm luật vào `scripts/cam_rename.sed` cho lần sau.

Kernel / camd (ksud):
- **Upstream sửa các file Cam đã xoá** (`apk_sign.c`, `throne_tracker.c`, `manager_observer.h`, `apk_sign.rs`…): giữ trạng thái **xoá** (`git rm <file>`). Nếu upstream thêm tính năng mới vào đó, xem có cần chuyển sang `policy/pkg_tracker.c` không.
- **Upstream sửa `pkg_observer.c` ở chỗ cũ (`kernel/manager/`)**: áp thay đổi đó vào `kernel/policy/pkg_observer.c`.
- **`policy/pkg_tracker.c`**: là file của Cam; giữ `ksu_seed_apply()` và `ksu_manager_pin_apply()`, thứ tự gọi trong `ksu_pkg_tracker_update()`: seed → pin → prune.
- **`manager_identity.h`**: luôn giữ bản của Cam (chỉ có `is_manager()` = uid 0).
- **`Kbuild` / `Kconfig`**: giữ bản đã bỏ `EXPECTED_*`, `DISABLE_MANAGER`, `MANAGER_PACKAGE`; nhận các dòng `kernelsu-objs` mới của upstream rồi đổi thành `camsu-objs` (module tên `camsu.o`). Conflict ở đoạn `KSU_EXPECTED_*` (từ commit lỗi thời `1b9b0673`) thì xoá cả đoạn.
- **`kernel/selinux/rules.c`**: rule cho domain su nằm trong hàm `add_su_domain()` (gọi 2 lần: `cam` và `ksu`). Upstream thêm rule mới dạng `ksu_allow(db, ..., KERNEL_SU_DOMAIN, ...)` vào `apply_kernelsu_rules()` thì chuyển vào `add_su_domain()` và thay `KERNEL_SU_DOMAIN`/`KERNEL_SU_FILE` bằng `domain`/`file`, để cả hai domain cùng có.
- **`uapi/supercall.h`**: nếu upstream tăng `KERNEL_SU_UAPI_VERSION`, đặt bản Cam = **số của upstream + 1**, để bản Cam và upstream không bao giờ trùng uapi.
- **`userspace/camd/build.rs`**: giữ gói `cam.su.kernel`. **`sepolicy.rs`**: giữ dòng `#![allow(clippy::redundant_field_names)]` ở đầu file.

Manager:
- **File có `Cam.xxx`**: nhận thay đổi của upstream (script đổi `Ksu.` → `Cam.`, `Natives.` → `CamNative.`), rồi đổi mọi lời gọi `CamNative.<hàm>` từ UI thành `Cam.<hàm>`. Upstream thêm hàm mới vào `Natives` thì làm theo "Nếu upstream thêm hàm mới" bên dưới.
- **File `*Miuix.kt`**: nhận thay đổi của upstream, rồi đổi lại component sang bản kính theo bảng ở mục 3. Màn hình hoặc component mới của upstream cũng phải đổi theo bảng đó; quên `Scaffold(containerColor = Color.Transparent)` thì trang che mất hình nền.
- **Upstream sửa hoặc thêm file Material** (`UiMode.kt`, `MaterialTheme.kt`, `ui/component/material/*`, mọi `*Material.kt`): git báo conflict "modified/deleted" → `git rm <file>`, không port. Màn hình mới của upstream thường đi kèm bộ ba `XxxMaterial.kt` + `XxxMiuix.kt` + `XxxScreen.kt` (chọn bản theo `LocalUiMode`): giữ bản Miuix, trong `XxxScreen.kt` gọi thẳng `XxxMiuix(...)`, xoá `XxxMaterial.kt`, bỏ import `LocalUiMode` / `UiMode`. Nếu upstream thêm tham số vào cả hai bản thì chỉ cần giữ tham số ở bản Miuix.
- **`ui/CamActivity.kt`** (upstream: `ui/MainActivity.kt`): route mới upstream thêm vào `NavDisplay` phải bọc `GlassPage { … }` như các `entry<…>` khác. Giữ khối nạp sẵn nền (`GlassBackgroundCache.preload`), `LocalEnableBlur provides true`, **khối `SlimeLayer(…)` bọc `navDisplay()`** và đoạn `LaunchedEffect` báo `SlimeHome.pagerPos`. Không còn `LocalUiMode` / `UiMode`.
- **`ui/component/glass/GlassCard.kt`**: phải còn `.slimeSurface()` trước `.glassMaterial(…)`, không thì slime không có chỗ đứng trên thẻ.
- **`ui/screen/home/HomeMiuix.kt`**: giữ lời gọi `StatusArena(…)`. Upstream thêm trường dữ liệu mới vào thẻ trạng thái thì lấy phần dữ liệu, không lấy phần giao diện.
- **`data/repository/SettingsRepositoryImpl.kt`, `SettingsUiState.kt`, `MainActivityUiState.kt`**: giữ mặc định của Cam (`enable_predictive_back` true, `module_description_max_lines` 5) và 3 khoá slime.
- **`ui/screen/appprofile/*`, `superuser/SuperUserMiuix.kt`**: giữ 3 thẻ quản lý app, `onOpenSystemInfo` và mục menu "Cài lại app hệ thống đã gỡ".
- **`ui/util/BlurExt.kt`, `component/FloatingBottomBar.kt`, `component/miuix/SuperSearchBar.kt`, `component/dialog/DialogMiuix.kt`**: giữ bản của Cam, rồi áp thay đổi của upstream vào bằng tay.
- **`app/build.gradle.kts`, `gradle.properties`, `res/values*/themes.xml`, `res/mipmap-anydpi/ic_launcher.xml`**: giữ gói `cam.su.kernel`, tên `SU Kernel`, icon/splash của Cam. Giữ 3 dòng `filament` trong `build.gradle.kts`, 4 dòng trong `libs.versions.toml` và dòng `-keep class com.google.android.filament.**` trong `proguard-rules.pro`.
- **`res/values*/strings.xml`**: nhận chuỗi mới của upstream, giữ `seed_*`, `glass_background*`, `settings_slime*`, `app_*`. Merge có thể **đưa lại** các chuỗi Cam đã xoá (`home_learn_kernelsu*`, `home_support_*`, `settings_ui_mode*`): xoá lại cho sạch, không bắt buộc.

CI:
- **`.github/workflows/build-manager.yml`**: giữ dòng `workflow_dispatch:`. **`release.yml`**: giữ `permissions: contents: write` và `generate_release_notes: true`.
- **`.github/workflows/ddk-lkm.yml`**: giữ `safe.directory "$GITHUB_WORKSPACE"` và `fetch-depth: 0` (nếu upstream đổi lại `/__w/KernelSU/KernelSU` thì module CI sẽ báo phiên bản 16).

Xong thì `git add <từng file>` rồi `git commit` (giữ message merge mặc định).

### Bước 4: kiểm tra sau khi merge

Chạy từng lệnh, kết quả phải đúng như ghi chú:

```bash
# 1. Không còn code tìm manager theo APK. Chỉ được còn: do_get_manager_appid trong dispatch.c,
#    và KSU_MANAGER_PACKAGE trong policy/pkg_tracker.c (ghim root cho gói Manager, của Cam)
rg -n "throne|apk_sign|is_uid_manager|manager_appid|EXPECTED_(SIZE|HASH)|KSU_DISABLE_MANAGER|KSU_MANAGER_PACKAGE" kernel

# 2. Code mới của upstream có dùng khái niệm manager không? Xem kỹ từng chỗ
rg -n "is_manager\(|only_manager|manager_or_root" kernel

# 3. UI không gọi thẳng CamNative (chỉ được ra CamNative.kt, CamRootService.kt, Cam.kt, managerUAPIVersion)
rg -n "CamNative\.(version|kernelUAPIVersion|is[A-Z]|get[A-Z]|set[A-Z]|uid)" manager/app/src/main/java

# 4. Gói vẫn là cam.su.kernel; pin Manager vẫn được gọi
rg -n "cam.su.kernel" manager/app/build.gradle.kts userspace/camd/build.rs kernel/policy/pkg_tracker.c
rg -n "ksu_manager_pin_apply" kernel/policy/pkg_tracker.c        # phải ra 2 dòng: định nghĩa + lời gọi

# 4b. Tên cũ không lọt lại (mục 11). Phải rỗng, trừ file binary userspace/camd/bin/*/waitsys
#     (chứa chuỗi "ksud-waitsys" của upstream, bình thường). Git Bash không có rg thì dùng:
#     git grep -nP "<cùng biểu thức>" -- manager/app/src userspace kernel .github scripts ':!manager/app/src/main/res/values-*'
rg -n "me\.weishu|me/weishu|\bksud\b|\bksuinit\b|/data/adb/ksu\b|kernelsu\.ko|\bNatives\b|\bKsu(Service|Cli)?\b|MainActivity|libksud" \
   manager/app/src userspace kernel .github scripts --glob '!**/res/values-*/**' \
   | rg -v -f scripts/cam_rename.skip | rg -v "^scripts/cam_rename"
ls userspace    # phải là camd, caminit (không còn ksud, ksuinit)

# 5. Giao diện Miuix không còn component gốc lọt vào (phải rỗng; webui/ không tính)
rg -n "[^A-Za-z.](Card|IconButton|OverlayDialog|OverlayListPopup|OverlayDropdownPreference|FloatingActionButton)\(" manager/app/src/main/java --glob "*Miuix.kt" --glob "!**/webui/**"
rg -n "Scaffold\(" manager/app/src/main/java --glob "*Miuix.kt" -A1 | rg -v "containerColor|Scaffold\(|^--"   # phải rỗng

# 6. Không còn giao diện Material (cả hai lệnh phải rỗng; GlassMaterial.kt là file của Cam)
git ls-files manager/app/src/main | rg "/[A-Za-z]+Material\.kt$" | rg -v GlassMaterial
rg -n "LocalUiMode|UiMode\b" manager/app/src/main/java

# 7. Filament và vật liệu còn đủ (phải ra kết quả ở cả ba file, và file .filamat phải tồn tại)
rg -n "filament" manager/gradle/libs.versions.toml manager/app/build.gradle.kts manager/app/proguard-rules.pro
ls manager/app/src/main/assets/roam_jelly.filamat

# 8. Test và build (Windows: chạy từ ổ K:, xem mục 5)
cd manager && ./gradlew :app:testDebugUnitTest :app:assembleRelease
# camd/caminit (WSL): cargo ndk -t arm64-v8a check && cargo ndk -t arm64-v8a clippy && cargo fmt --check
```

**Kiểm tra bằng mắt trên máy** (build xong chạy được chưa chắc đã đúng, phần này compile không bắt được):
1. **Home**: hộp 3D có 4 slime, show chạy; không có slime nào chạy ngoài hộp.
2. **Vuốt Home → Superuser**: kính nứt, 4 slime văng ra, chạy trên mép các thẻ. **Vuốt về Home**: cửa mở, chúng vào lại, kính liền, show tiếp tục.
3. **Hồ sơ ứng dụng** của một app (Superuser → mở một dòng → "Mở hồ sơ đầy đủ"): 3 thẻ mở được; Dung lượng **không** ra 0 B cho cả dữ liệu lẫn cache (nếu 0 B là lệnh root đang chạy sai mount namespace, xem mục 3).
4. **Chủ đề**: thẻ "Slime đi dạo" ở cuối; tắt thì slime biến mất, bật lại thì quay về.
5. Thử một lệnh Quản lý với app **không quan trọng** (xoá cache), không thử trên app của hệ thống.
Mẹo test bằng adb: `adb -s <serial> exec-out screencap -p`, `adb shell screenrecord`, `adb shell input swipe/tap`. Màn hình máy phải sáng, nếu tắt thì app không vẽ gì. Trên Git Bash nhớ `export MSYS_NO_PATHCONV=1` khi dùng đường dẫn `/sdcard`.

**Nếu upstream thêm hàm mới vào `Natives` (bên Cam là `CamNative`):**
1. Thêm hàm tương ứng vào `ICamRootService.aidl`.
2. Cài đặt hàm đó trong `CamRootService.Stub` (gọi `CamNative`).
3. Thêm hàm cùng tên vào `Cam.kt` (gọi qua `CamRootClient`, trả giá trị mặc định khi chưa kết nối).
4. Cho UI gọi `Cam.<hàm>`.
5. Hàm JNI mới trong `cpp/jni.cc` phải tên `Java_cam_su_kernel_CamNative_<hàm>` (script mục 11 tự đổi); sai tên thì app chỉ crash lúc gọi, build không báo.

Lý do: app process không có quyền gọi kernel, chỉ root service gọi được.

**Nếu upstream thêm ioctl mới chỉ cho manager (`only_manager`):** với Cam, ioctl đó nghĩa là "chỉ uid 0", nên phải gọi từ `CamRootService` theo đúng các bước trên.

### Bước 5: đưa lên GitHub, build và cài

```bash
git checkout main
git merge --ff-only merge-upstream-YYYYMMDD
git push origin main          # CI "Build Manager" tự chạy: APK ký khóa release + LKM 8 KMI
```

1. Chờ CI xanh (Actions trên GitHub, hoặc `gh run list -R peo1108/Cam-Kernel-SU --branch main`). Mở log bước build LKM, phải thấy `KernelSU version: 3xxxx` (không phải 16).
2. Tải APK ở mục Artifacts (`manager`), **cài đè** lên máy (cùng khóa release nên không mất dữ liệu).
3. Mở app → bấm thẻ "Hiện có phiên bản LKM tích hợp mới hơn" → **Cài đặt trực tiếp** → khởi động lại. Sau đó app và LKM cùng một số phiên bản. Cách làm bằng adb (đã dùng 2026-10-09): `su -c "/data/adb/camd boot-patch --flash -o /data/local/tmp"` rồi reboot; nên `dd` sao lưu `init_boot_<slot>` về PC trước.
4. Kiểm tra sau khi boot: `su -c id` ra `context=u:r:cam:s0`; `grep camsu /proc/modules`; `/data/adb/cam/.services_started` bằng `/proc/sys/kernel/random/boot_id`; `ls /data/adb` chỉ còn `cam camd modules modules_update`.
5. Ra bản cho người dùng: gắn tag (mục 8).

Muốn build trên máy thay vì CI (chỉ 2 KMI): mục 5, "Lệnh build đã dùng".

## 5. Build trên máy Windows: những bẫy đã gặp

| Bẫy | Hậu quả | Cách tránh |
|---|---|---|
| `core.autocrlf=true` | `installer.sh` bị nhúng vào ksud với CRLF → **cài module lỗi** `syntax error ... expecting "do"` | Đã sửa: `.gitattributes` có `*.sh text eol=lf`. Checkout cũ còn CRLF thì xoá các file `.sh` rồi `git checkout` lại đúng các file đó |
| `core.symlinks=false` | `manager/app/src/main/cpp/uapi` là file text, CMake báo `uapi/ksu.h not found` | Tạo junction cục bộ (PowerShell): `New-Item -ItemType Junction -Path "...\manager\app\src\main\cpp\uapi" -Target "...\uapi"`. `git status` sẽ luôn hiện ` D manager/app/src/main/cpp/uapi`: bình thường, **không commit, không add**. **Không bao giờ** chạy `git checkout -- manager/...`, `git stash`, `git clean` trên đường dẫn này: git ghi đè junction và **xoá luôn `uapi/*.h` ở gốc repo** (đã xảy ra 2026-10-02). Lỡ bị thì `git checkout -- uapi` rồi tạo lại junction |
| CI: LKM báo phiên bản **16** (Home ghi `16-5`) | `ddk-lkm.yml` của upstream ghi cứng `safe.directory /__w/KernelSU/KernelSU`; repo fork khác tên nên git trong container DDK từ chối đọc repo | Đã sửa: `safe.directory "$GITHUB_WORKSPACE"` + checkout `fetch-depth: 0`. Merge upstream mà `ddk-lkm.yml` conflict thì giữ hai dòng này; sau mỗi lần CI chạy, log bước LKM phải ghi `KernelSU version: 3xxxx` |
| Build kernel từ `git archive` (không có `.git`) | `kernelsu.ko` báo version 16 → Manager coi là kernel quá cũ | Build từ thư mục có `.git` (git clone) |
| Chạy `git` của WSL trên repo `/mnt/c/...` | Làm hỏng junction `cpp/uapi` | Chỉ dùng git của Windows cho repo này; trong WSL chỉ dùng git trên bản clone riêng |
| `cargo ndk` không cài được trên Windows (thiếu `dlltool`) | | Build Rust trong WSL với clang Android + sysroot NDK |
| Nâng `filament` trong `libs.versions.toml` | `assets/roam_jelly.filamat` được biên dịch bằng đúng phiên bản 1.77.1; khác phiên bản thì Filament có thể từ chối nạp (lỗi bắt được thì lớp đi dạo rơi về vẽ 2D, nhưng cũng có thể app thoát; chưa thử) | Sau khi nâng, tạo lại file theo mục 10 rồi mở app, vào Superuser xem slime có hiện không |
| `gradlew.bat` chạy trong thư mục có dấu (`dự án đã done`) | `Unable to access jarfile ...gradle-wrapper.jar` (cả `./gradlew` trong Git Bash) | Ổ `K:` là `subst` của repo: PowerShell `Push-Location K:\manager; & K:\manager\gradlew.bat :app:assembleDebug` |
| `adb pull/push /data/...` trong Git Bash | Git Bash đổi `/data/...` thành `C:/Program Files/Git/data/...` | `MSYS_NO_PATHCONV=1 adb ...` |
| `git mv` thư mục báo `Permission denied` | Shell (hoặc IDE) đang đứng trong thư mục đó, Windows khoá nó | `cd` ra ngoài rồi chạy lại |
| `cargo test` của camd | 2 test `lkm_image` / `lkm_image_btf` fail | Đã fail từ trước đợt đổi tên (thiếu `.ko` android12 cục bộ, BTF), không phải lỗi mới |
| Mở bản release xong thấy slime ngoài hộp biến mất, chỉ còn vẽ 2D phẳng | `Roam3D.createOrNull()` trả `null` (Filament không khởi tạo được, hoặc thiếu `roam_jelly.filamat`) | Xem `logcat` có dòng `Filament`; kiểm tra file `.filamat` còn trong `assets/` |

### Lệnh build đã dùng (WSL, máy của Cam)

Một lệnh build cả `camsu.ko` (từng KMI) lẫn camd **từ cùng một commit**, rồi chép về repo Windows (`userspace/camd/bin/aarch64/<kmi>_camsu.ko` và `manager/app/src/main/jniLibs/arm64-v8a/libcamd.so`):

```bash
# trong WSL (root); tham số là nhánh cần build, mặc định feat/managerless-seed
bash "/mnt/c/Users/cam/Desktop/dự án đã done/Cam Kernel SU/scripts/build_lkm_camd.sh" main
# sau đó trên Windows, KHÔNG commit gì thêm ở giữa:
cd manager && ./gradlew :app:assembleRelease
```

Phiên bản = `30000 + số commit`, nên LKM và Manager chỉ khớp nhau khi build từ cùng một commit. Commit thêm bất cứ thứ gì giữa hai bước là Manager lệch 1. Bản đầy đủ (8 KMI, ký bằng khóa release) thì để GitHub Actions build: push lên `main` hoặc gắn tag (mục 8).

Những bẫy khi build ksud trong WSL (script đã xử lý sẵn):

| Bẫy | Cách xử lý |
|---|---|
| WSL không có NDK cho Linux | Dùng clang của AOSP (`clang-r536225`) làm linker, `--sysroot` + `libunwind` lấy từ NDK **Windows** (các file này không phụ thuộc hệ điều hành) |
| `build.rs` báo `llvm-mc: No such file` (assemble LKM bootstrap) | `KSU_LKM_BOOTSTRAP_CC=<clang AOSP>` |
| `bindgen`: `Unable to find libclang` | `LIBCLANG_PATH=<clang AOSP>/lib` và `BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=... --sysroot=..."` |
| Không thấy file `camd` sau khi build | camd là thành viên workspace: file nằm ở `target/` của **gốc repo**, không phải `userspace/camd/target/` |
| `caminit` không có trong git | Script lấy bản trong `userspace/camd/bin/aarch64/` của repo Windows (file tên `caminit`, LKM tên `<kmi>_camsu.ko`; tên cũ `ksuinit` / `_kernelsu.ko` sẽ không được nhúng) |

Kiểm tra nhanh camd không cần script (WSL, như trong `AGENTS.md`): trong `userspace/camd` chạy `cargo ndk -t arm64-v8a check`, `cargo ndk -t arm64-v8a clippy`, `cargo fmt`, với `LIBCLANG_PATH=<NDK>/toolchains/llvm/prebuilt/linux-x86_64/lib` và `CARGO_TARGET_DIR` nằm ngoài `/mnt/c` cho nhanh.

## 6. Test

- **Kernel (logic seed):** harness chạy trên host với header giả, gồm 11 test, chạy dưới ASan. Harness đang nằm ngoài repo (thư mục scratchpad), chưa đưa vào repo.
- **Trên máy thật (Y700 Gen5, 2026-10-09), đợt đổi tên:** cài Manager mới khi máy còn LKM cũ → camd chuyển `/data/adb/ksu` → `/data/adb/cam`, `ksud` → `camd`, `/metadata/watchdog/ksu` → `cam`, allowlist giữ nguyên, nhãn file vẫn `ksu_file`; reboot với LKM cũ (chạy qua symlink) → root OK, giai đoạn services chạy. Flash LKM `camsu.ko` → `su` ra `u:r:cam:s0`, module tên `camsu`, `camd` được gắn nhãn `cam_file`, `u:r:ksu:s0`/`ksu_file` vẫn hợp lệ trong policy; camd mới xoá symlink cũ, reboot lại vẫn root. **Chưa thử:** app uid ≥ 10000 dò domain `ksu` khi bật SELinux hide; tính năng jailbreak.
- **camd (ksud):** `cargo test seed:: allow:: boot_guard module_conflicts` (trong `userspace/camd`) (chạy trên Linux/WSL; build Android trong WSL cần `LIBCLANG_PATH` và `BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android="--target=aarch64-linux-android26 --sysroot=<NDK>/toolchains/llvm/prebuilt/windows-x86_64/sysroot"`). Hai test `lkm_image` / `lkm_image_btf` vốn đã fail sẵn trên upstream khi thiếu asset CI, không liên quan.
- **Trên máy thật (Y700 Gen5, 2026-10-03/04), phần UI mới:** đã thấy chạy đúng: phá kính khi vuốt khỏi Home, cửa + vá kính khi về, slime 3D chạy trên mép thẻ, đập tay / cưỡi Bơ / chơi khăm / rượt / đánh nhau, laser bằng ngón tay giữ yên, ngủ ban đêm, bật/tắt slime trong Chủ đề, 3 thẻ Hồ sơ ứng dụng (Thông tin, đường dẫn, Dung lượng) trên Chrome. Khung hình trung bình khoảng 9 ms, GPU khoảng 6 ms. **Chưa thử trên máy:** lắc máy, tự ẩn khi Flash / cài module, các nút Quản lý (sao lưu, xoá cache/dữ liệu, đóng băng, gỡ), nút copy đường dẫn.
- **Trên máy thật (Y700 Gen5, 2026-10-04), boot guard và xung đột:** module thử `bg-test` reboot trong `service.sh` (tự dừng sau 5 lần) → 2 lần reboot, lần boot thứ 3 ksud tắt `bg-test`, máy lên bình thường; reboot thường thì `failCount` về 0; module `scune-support` đang tắt không bị đụng. Hai module `cf-a`/`cf-b` (cùng file `/system/etc`, file vendor đã bị installer chuyển ra `<module>/vendor`, prop khác giá trị) → báo đúng 3 xung đột, prop cùng giá trị không báo. Manager: thẻ Home, hộp thoại Bật lại / Bỏ qua, chip trên thẻ module, hộp thoại xung đột, dòng cảnh báo khi flash.
- **Trên máy thật (Y700 Gen5, 2026-10-02):** boot OK; seed cấp root cho Manager; seed không áp lại khi nonce giữ nguyên; prune xoá quyền khi gỡ app; cài module + WebUI OK; dialog chọn app OK; `ksud allow` OK.

## 7. Giới hạn đã biết

- **Chế độ late-load / jailbreak (trước là "magica") không có seed.** Chỉ hỗ trợ LKM qua patch init_boot. Phần jailbreak không được test sau đợt đổi tên (khách dùng EFISP, không cần).
- **Gỡ Manager rồi cài lại** không còn mất root: kernel cấp lại root cho `cam.su.kernel` ngay khi `packages.list` đổi (cần LKM có commit "keep root for the manager package"). Cũng vì vậy **không thu root của chính Manager được**: lần thay đổi gói kế tiếp kernel sẽ cấp lại. Đánh đổi có chủ đích: app lạ đặt trùng tên gói `cam.su.kernel` cũng sẽ có root.
- **Lần boot đầu sau flash,** app khác chỉ dùng `su` được sau khi mở Manager một lần (Manager cài `/data/adb/camd`).
- **Module ghi cứng `/data/adb/ksu/...` hoặc `/data/adb/ksud`** sẽ lỗi trên kernel `cam`: camd xoá các symlink tên cũ (Cam chọn vậy, dự án dùng cho việc riêng). Biến `KSU*`, bridge WebUI `ksu`, thư mục `/data/adb/modules` vẫn còn nên module viết đúng chuẩn vẫn chạy. `sepolicy.rule` của module nhắc domain `ksu` chỉ áp cho domain `ksu`, không áp cho `cam`.
- **ROM ZUI (Lenovo):** dialog chọn app cần thêm quyền riêng `GET_INSTALLED_APP` của ROM. Lần đầu ROM sẽ hỏi; sau khi cho phép phải mở lại dialog.
- **Slime đi dạo bị tắt hẳn** khi máy bật "giảm chuyển động" (animator scale = 0): khi đó cả 4 ở yên trong hộp. Chủ ý.
- **Chạm vào slime ở lớp ngoài** chỉ lấy touch khi trúng thân; chạm hụt đi thẳng xuống app. Giữ yên ngón tay nửa giây thì thành laser và lớp slime **giữ luôn** thao tác đó (không cuộn trang, không bấm nhầm) tới khi nhấc tay.
- **Màu thạch bên ngoài không khúc xạ** như trong hộp (lớp phủ không có cảnh phía sau để khúc xạ), nên là thạch bóng hơi trong. Đã chỉnh vài lần, **Cam đã nói không cần chỉnh màu nữa**.
- **Nhóm app dùng chung UID và mục đặc biệt (WebView zygote)** không hiện 3 thẻ quản lý app (không rõ thao tác áp lên app nào).
- **Gỡ systemless** cần khởi động lại mới có hiệu lực; chỉ hiện khi app có đường dẫn gốc trong ROM.
- Chuỗi mới (`settings_slime*`, `app_*`) chỉ có tiếng Anh và tiếng Việt.
- **Boot guard không cứu được** máy loop *trước* `post-fs-data` (kernel, `init`, hoặc metamodule mount sớm qua `modules.rc`): bộ đếm không tăng. Khi đó vẫn dùng safe mode (phím âm lượng). Ngưỡng cố định 3; người dùng reboot giữa chừng lúc đang boot 2 lần liên tiếp cũng tính là boot lỗi.
- **Phát hiện xung đột** chỉ đọc file `.replace`, không đọc xattr `trusted.overlay.opaque`; không biết module nào "thắng" (tuỳ thứ tự mount của metamodule).

## 8. Phát hành bản mới (GitHub Actions)

CI build trên GitHub là bản đầy đủ: module kernel cho cả 8 KMI (`android12-5.10` → `android17-6.18`), ksud nhiều kiến trúc, Manager ký bằng khóa release.

- **Push lên `main`**: workflow "Build Manager" tự build và ký; tải file ở mục Artifacts của lượt chạy. Chạy tay: Actions → Build Manager → Run workflow.
- **Ra bản cho người dùng**: gắn tag rồi push tag, workflow "Release" tạo GitHub Release kèm APK, `lkm-*_camsu.ko`, camd, caminit và ghi chú tự sinh:

```bash
git tag v1.0.0
git push origin v1.0.0
```

  Tên phiên bản trong app lấy từ `git describe --tags` (ví dụ `v1.0.0`); mã phiên bản vẫn là `30000 + số commit`. Đừng dùng lại tên tag đã có của upstream (`v3.x`).
- **Khóa ký**: 4 secret `KEYSTORE` (file `.jks` dạng base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Bản gốc của khóa nằm ở `C:\Users\cam\.android-keys\su-kernel-release.jks` (mật khẩu trong `C:\Users\cam\.gradle\gradle.properties`); khóa cũ "Cam Kernel SU" ở `Documents\Cam-Kernel-SU-keys\cam-kernel-su.p12`. **Mất khóa thì người dùng không cập nhật đè được nữa**: luôn giữ bản sao lưu.
- Vì `~/.gradle/gradle.properties` có khóa release, build trên máy (kể cả debug) cũng ký bằng khóa release. Cần bản ký khóa debug để cài đè bản debug cũ thì thêm `-PKEYSTORE_FILE=C:/Users/cam/.android/debug.keystore -PKEYSTORE_PASSWORD=android -PKEY_ALIAS=androiddebugkey -PKEY_PASSWORD=android`.

## 9. Bản đồ phần slime / 3D (để sửa đúng chỗ)

Bốn nhân vật: **Mochi** (hồng, hách dịch), **Bơ** (xanh lá to, hay ngủ), **Soda** (xanh dương cao, thích khoe), **Chanh** (vàng nhỏ, hay phá). Chỉ số 0..3 theo thứ tự `MOCHI, BO, SODA, CHANH` (`Cast`).

| Muốn đổi | Sửa ở đâu |
|---|---|
| Kịch bản show trong hộp (các pha Intro / Brawl / Scatter / Recover / Gather / Eat / Choke / Vomit / Settle / Wander) | `arena/ArenaShow.kt`; kiểu nôn `ArenaVomit.kt`; ăn chữ + hóc `ArenaFeast.kt`; xếp hàng lúc rảnh `ArenaFormations.kt` |
| Cảnh mở màn theo nước, đồ thất lạc | `ArenaIntros{,2,3}.kt`, `ArenaProps.kt`, nền + biểu tượng hai bên `ArenaScenes.kt` / `ArenaDecor.kt` |
| Bộ đồ 3D (36 bộ) | `three/Wardrobe.kt` (định nghĩa), `Tailor.kt` (dựng hình), `Closet.kt` (treo lên từng slime, bật/tắt theo pose) |
| Hình dáng thạch, tay co duỗi, khuôn mặt | `three/Shapes3D.kt`, `Morphs.kt`; mặt vẽ lại mỗi khi biểu cảm đổi trong `SlimeRig.paintFace` |
| Phòng kính 3D, ánh sáng, khử răng cưa | `three/Arena3D.kt` (đọc kỹ các comment ở `init`), `Room.kt`, `Grounds.kt` |
| Slime đi dạo: đi, nhảy, rơi, trèo tường, phản ứng khi chạm | `slime/RoamWorld.kt` |
| Hai con chơi với nhau (đập tay, cưỡi Bơ, chơi khăm, rượt, đánh nhau) | `slime/RoamSocial.kt` |
| Phá kính / cửa / vá kính; slime vào lại hộp | `slime/SlimeHome.kt` (trạng thái + vẽ kính), `RoamWorld.breakOut()` / `comeHome()`, `ArenaShow.updatePoses` (đi từ cửa vào chỗ), `StatusArena.kt` (báo vị trí trong hộp, vẽ kính) |
| Chạm / giữ / lắc, chấm laser, ngủ đêm, số lượng mỗi trang | `slime/SlimeLayer.kt` (cử chỉ, cảm biến), `RoamWorld.shoo/laserAt/shake/night`, cài đặt ở mục 3 |
| Màu và độ trong của thạch bên ngoài | `three/SlimeRig.bind` (`tint`, `clarity`, `glow`), đèn trong `three/Roam3D.kt`; công thức vật liệu ở mục 10 |

Những điều rút ra khi làm, đừng làm lại sai:
- **Đừng bật lại SSR (phản xạ màn hình) và SSAO** trong `Arena3D`: SSR là nguyên nhân những khối vuông 8 px trên thân slime ("vỡ vỡ"). Hiện đang dùng MSAA 4x + FXAA, độ nhám thạch 0.03, bóng VSM 1024.
- Vật liệu có `alphaMode = BLEND` **không được dùng sheen** (Filament báo lỗi "sheenColorIndex" và thoát); bộ đồ vải chỉ dùng sheen khi đặc.
- `getEntitiesByPrefix` của gltfio không hoạt động như mong đợi: `Closet` tìm từng mảnh bằng tên chính xác (`fit_<theme>_<slot>_<i>`).
- `Roam3D` và `Arena3D` mỗi cái là **một engine Filament riêng**, cùng dùng `SlimeRig`. Trong `Roam3D.destroy()`, `rig.destroy()` nằm **sau** khi huỷ asset vì nó huỷ các material instance của thạch mà asset còn đang dùng; giữ nguyên thứ tự đó.
- Kéo slime khi `RoamMode.Held`: `SlimeLayer` đón touch ở `PointerEventPass.Initial` và chỉ `consume()` khi trúng slime hoặc đang là laser.

## 10. Tạo lại `roam_jelly.filamat`

File này là vật liệu "thạch trong" cho lớp slime đi dạo, biên dịch sẵn nên APK không phải mang theo bộ biên dịch (`libfilamat-jni.so` khoảng 9 MB mỗi ABI). Cần tạo lại khi: nâng phiên bản `filament`, hoặc muốn đổi công thức vật liệu. **Đổi màu / độ trong / độ sáng thì không cần**: ba thông số `tint`, `clarity`, `glow` đặt lúc chạy trong `SlimeRig.bind`.

Cách đã dùng (chỉ làm tạm trong nhánh riêng, rồi gỡ hết):
1. Thêm dependency tạm vào `app/build.gradle.kts`: `implementation("com.google.android.filament:filamat-android:<đúng phiên bản filament>")`. Bản `filamat-android-lite` **không có** trên Maven.
2. Thêm file tạm dưới đây và gọi `MaterialForge.forge(context)` ở chỗ chạy lúc `SlimeLayer` dựng (ví dụ ngay trong `remember { … }` đang tạo `Roam3D`).
3. Chạy bản release trên máy (**màn hình phải sáng**, vì code chỉ chạy khi `SlimeLayer` được dựng), rồi kéo file ra:
   `adb pull /sdcard/Android/data/cam.su.kernel/files/roam_jelly.filamat manager/app/src/main/assets/roam_jelly.filamat`
4. **Xoá** file tạm, lời gọi và dependency. Build lại, mở app, vào Superuser xem slime ngoài hộp.

```kotlin
// TẠM THỜI: chỉ để sinh roam_jelly.filamat. Không commit.
object MaterialForge {
    fun forge(context: Context) {
        Thread {
            MaterialBuilder.init()
            val pkg = MaterialBuilder()
                .name("roam_jelly")
                .shading(MaterialBuilder.Shading.LIT)
                .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
                .transparencyMode(MaterialBuilder.TransparencyMode.TWO_PASSES_ONE_SIDE)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "tint")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "clarity")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "glow")
                .specularAntiAliasing(true)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        float NoV = shading_NoV;
                        // Trong ở giữa, đậm dần ra rìa như thạch thật.
                        float rim = pow(1.0 - NoV, 2.2);
                        float a = mix(materialParams.clarity, 0.94, rim);
                        vec3 tint = materialParams.tint;
                        material.baseColor = vec4(tint * a, a);
                        material.metallic = 0.0;
                        material.roughness = 0.05;
                        material.reflectance = 0.75;
                        // Phát sáng nhẹ từ bên trong, mạnh nhất ở giữa.
                        material.emissive = vec4(tint * materialParams.glow * (0.3 + 0.7 * NoV * NoV), 0.0);
                    }
                    """.trimIndent()
                )
                .build()
            val buf = pkg.buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            File(context.getExternalFilesDir(null), "roam_jelly.filamat").writeBytes(bytes)
            MaterialBuilder.shutdown()
        }.start()
    }
}
```

## 11. Đổi tên sang Cam (2026-10-09)

Mục tiêu: không còn tên KernelSU ở gói, class, binary, đường dẫn trên máy, tên module kernel và SELinux domain. Tên nội bộ của kernel thì **giữ nguyên có chủ đích** để còn merge upstream được.

Các commit, theo thứ tự:

| Commit | Nội dung |
|---|---|
| `3fc77996` | Gói nguồn `me.weishu.kernelsu` → `cam.su.kernel` (thư mục java/test/aidl, JNI) |
| `c0f76778` | `libksud.so` → `libksucam.so` (sau đó thành `libcamd.so`) |
| `8e8bbfc8` | Class Manager, thư viện JNI `libkernelsu.so` → `libcamjni.so` |
| `414fecd8` | `ksud` → `camd`, `ksuinit` → `caminit`, `/data/adb/ksu` → `/data/adb/cam`, chuyển dữ liệu (`legacy.rs`) |
| `3da955dd` | Biến môi trường `CAM_*` cho module, bridge WebUI `cam`, deep link `cam://` |
| `01c6512f` | `kernelsu.ko` → `camsu.ko`, SELinux domain `ksu` → `cam` (giữ `ksu` song song) |
| `4420ef9f` | Xoá symlink tên cũ khi kernel đã là `cam` |
| `84583443`, `2d6bb63f` | Magica → jailbreak (`CamZygotePreload`, `CamJailbreakService`, `CamBootReceiver`, `--jailbreak`) |

### Bảng đối chiếu tên cũ → mới

Manager (`manager/app/src/main/`):

| Cũ | Mới |
|---|---|
| `java/me/weishu/kernelsu/`, `aidl/me/weishu/kernelsu/`, `test/java/me/weishu/kernelsu/` | `.../cam/su/kernel/` (`namespace = "cam.su.kernel"`) |
| `KernelSUApplication` | `CamApplication` |
| `Ksu` (`Ksu.kt`) | `Cam` (`Cam.kt`) |
| `Natives` (`Natives.kt`) | `CamNative` (`CamNative.kt`) |
| `KsuService` / `KsuServiceClient` / `IKsuInterface` | `CamRootService` / `CamRootClient` / `ICamRootService` |
| `KsuCli` (`ui/util/KsuCli.kt`) | `CamCli` (`ui/util/CamCli.kt`) |
| `KsuValidCheck` / `KsuIsValid` / `KsuDeepLink` | `CamValidCheck` / `CamIsValid` / `CamDeepLink` |
| `MainActivity`, `MainActivityUiState`, `MainActivityViewModel` | `CamActivity`, `CamActivityUiState`, `CamActivityViewModel` |
| `KernelSUTheme`, `MiuixKernelSUTheme`, `rememberKernelSUColorScheme`, style `Theme.KernelSU*` | `CamTheme`, `MiuixCamTheme`, `rememberCamColorScheme`, `Theme.Cam*` |
| `ksuApp`, `ksuVersion`, `ksuReady`… ; `execKsud`, `getKsuDaemonPath` | `camApp`, `camVersion`, `camReady`… ; `execCamd`, `getCamDaemonPath` |
| `magica/AppZygotePreload`, `MagicaService`, `BootCompletedReceiver` | `jailbreak/CamZygotePreload`, `CamJailbreakService`, `CamBootReceiver` |
| `libkernelsu.so` (CMake `project("kernelsu")`), hàm `Java_me_weishu_kernelsu_Natives_*` | `libcamjni.so` (`project("camjni")`), `Java_cam_su_kernel_CamNative_*` |
| `jniLibs/<abi>/libksud.so` | `jniLibs/<abi>/libcamd.so` |
| Log tag `KernelSU`, file `KernelSU_bugreport_*`, User-Agent `KernelSU/…` | `Cam`, `Cam_bugreport_*`, `CamSU/…` |

Userspace, kernel, CI:

| Cũ | Mới |
|---|---|
| `userspace/ksud/` (crate `ksud`), `userspace/ksuinit/` (crate `ksuinit`) | `userspace/camd/` (`camd`), `userspace/caminit/` (`caminit`) |
| `.github/workflows/ksud.yml`, `ksud-extra.yml`, `ksuinit.yml`; artifact `ksud-<target>`, `ksuinit-<arch>` | `camd.yml`, `camd-extra.yml`, `caminit.yml`; `camd-<target>`, `caminit-<arch>` |
| `scripts/build_lkm_ksud.sh` | `scripts/build_lkm_camd.sh` |
| `kernel/runtime/ksud.h`, `ksud_boot.h`, `ksud_integration.c`, `KSUD_PATH` | `camd.h`, `camd_boot.h`, `camd_integration.c`, `CAMD_PATH` |
| `/data/adb/ksud`, `/data/adb/ksu/` (`.allowlist`, `.seed`, `bin/`, `lib/`, `log/`…), `.ksurc`, `ksu_backup_*` | `/data/adb/camd`, `/data/adb/cam/`, `.camrc`, `cam_backup_*` |
| `/metadata/ksu/`, `/metadata/watchdog/ksu/` (`modules.rc`) | `/metadata/cam/`, `/metadata/watchdog/cam/` |
| `kernelsu.ko` (Kbuild `kernelsu-objs`), asset `<kmi>_kernelsu.ko`, file trong ramdisk `/kernelsu.ko` | `camsu.ko` (`camsu-objs`), `<kmi>_camsu.ko`, `/camsu.ko` |
| SELinux `u:r:ksu:s0`, `u:object_r:ksu_file:s0` | `u:r:cam:s0`, `u:object_r:cam_file:s0` (cũ vẫn còn, xem dưới) |
| `ksud late-load --magica / --post-magica`, `magica.rs` | `camd late-load --jailbreak / --post-jailbreak`, `jailbreak.rs` |
| Khoá `ksuMounts` trong JSON `hiding-audit`, chuỗi `audit_ksu_mounts` | `camMounts`, `audit_cam_mounts` |

### Cố ý giữ tên KernelSU

- Kernel: mọi hàm/biến `ksu_*`, macro `KSU_*` và `KERNEL_SU_*`, `kernelsu_init`, `apply_kernelsu_rules`, tên LSM `"ksu"`, tiền tố log `KernelSU:`, tham số ramdisk `ksu_config`. Đổi thì merge upstream gần như không làm được (khoảng 1800 chỗ), người dùng không thấy khác gì.
- `uapi/` (tên struct, ioctl). Số ioctl/magic không đổi nên ABI giữ nguyên.
- Nhãn mount `KSU` của module, biến build `KSU_PACKAGE_NAME`, `kernel/setup.sh` (bản builtin GKI), `js/` (gói npm `kernelsu` cho WebUI).
- Dòng `@author weishu` / `Created by weishu` (giấy phép GPL yêu cầu giữ ghi công).
- Chuỗi giao diện `strings.xml` vẫn còn chữ "KernelSU" / "Magica" (chưa chọn tên hiển thị).

### Lớp tương thích, **không được xoá khi merge**

Các file này nằm trong `scripts/cam_rename.skip` (script đổi tên không chạy qua chúng):

| Chỗ | Làm gì |
|---|---|
| `userspace/camd/src/legacy.rs` (`migrate()`, gọi ở đầu/cuối `utils::install` và đầu `on_post_fs_data`) | Chuyển `/data/adb/ksu`, `/data/adb/ksud`, `/metadata/{,watchdog/}ksu`, `.ksurc`, `ksu_backup_*` sang tên mới. Kernel chưa có domain `cam` (đang cập nhật dở) thì để symlink tên cũ cho kernel cũ chạy; kernel đã là `cam` thì xoá symlink |
| `userspace/camd/src/restorecon.rs` (`set_su_file_con`, `kernel_has_cam_domain`) | Gắn nhãn `cam_file` **chỉ khi** `/sys/fs/selinux/context` chấp nhận, không thì `ksu_file`. Đừng đổi thành "thử ghi rồi lùi": camd có `mac_admin` nên ghi nhãn lạ vẫn thành công và kernel cũ sẽ không chạy được camd lúc boot |
| `kernel/selinux/selinux.h`, `selinux.c`, `rules.c` | `KERNEL_SU_DOMAIN "cam"` + `KSU_LEGACY_*` (`ksu`, `ksu_file`); `add_su_domain()` gọi cho cả hai; `setup_selinux` đổi `u:r:ksu:s0` → `u:r:cam:s0`; `is_task_ksu_domain` nhận cả hai SID |
| `kernel/policy/allowlist.c` | `KSU_DEFAULT_SELINUX_DOMAIN = KSU_LEGACY_CONTEXT`: allowlist trên đĩa vẫn ghi `u:r:ksu:s0` để quay về kernel cũ vẫn đọc được |
| `manager/.../CamNative.kt` (`CAM_DOMAIN = "u:r:ksu:s0"`), `TemplateViewModel.kt` | Cùng lý do: profile/template lưu chữ `ksu`, kernel `cam` tự đổi lúc chạy |
| `userspace/camd/src/boot_patch.rs` (`LKM_NAME`, `LEGACY_LKM_NAME`), `unload.rs`, `userspace/caminit/src/init.rs` | Nhận ramdisk vá bằng bản cũ có `kernelsu.ko` (gỡ đi khi vá lại, nạp nếu chưa có `camsu.ko`); unload thử `camsu` rồi `kernelsu`; `unload.rs` tìm tiến trình ở cả `u:r:cam:s0` và `u:r:ksu:s0` |
| `userspace/camd/src/module.rs` (`CAM_ENV_ALIASES`), `cli.rs`, `feature.rs` | Script module có cả `KSU_*` lẫn `CAM_*`; đọc `CAM_MODULE` trước, `KSU_MODULE` sau |
| `ui/webui/WebViewHelper.kt`, `ui/navigation3/IntentDispatcher.kt`, `ui/util/module/Shortcut.kt`, `AndroidManifest.xml` | Bridge `ksu` + `cam`; scheme `ksu://icon` + `cam://icon`; deep link tạo bằng `cam://`, vẫn nhận `ksu://` (shortcut ghim từ trước) |
| `CamApplication.onCreate` | Bật lại `CamBootReceiver` nếu cài đặt `auto_jailbreak` đang bật (đổi tên component làm mất trạng thái bật) |

### Merge upstream sau khi đổi tên

1. Merge với `-c merge.renameLimit=10000` (mục 4, bước 2).
2. Giải conflict như mục 4, bước 3. File upstream ở đường dẫn cũ → `git mv` sang đường dẫn mới theo bảng trên.
3. Chạy `scripts/cam_rename.sed` trên các file merge mang vào (lệnh ở mục 4, bước 3). Script đã được thử: chạy trên toàn bộ code trước đổi tên (`1dc68cc9`) ra **giống hệt** code sau đổi tên ở 341 file; phần còn lệch đều là logic viết tay (các file ở bảng trên, `defs.rs` `WORKING_DIR`, lời gọi `legacy::migrate`).
4. Chạy lệnh kiểm tra 4b ở mục 4, bước 4 để chắc không còn tên cũ lọt vào.
5. Upstream thêm file mới trong `userspace/ksud/bin/` hoặc asset có tên `ksuinit` / `_kernelsu.ko`: đổi thành `caminit` / `_camsu.ko` (`assets.rs` chỉ nhận tên mới).
6. Upstream tăng `KERNEL_SU_UAPI_VERSION` hay đổi định dạng allowlist: kiểm tra lại `allowlist.c` vẫn dùng `KSU_LEGACY_CONTEXT` làm mặc định.

### Nếu muốn bỏ hẳn tên `ksu` trong SELinux (chưa làm)

SELinux hide đã giấu domain `ksu` khỏi app (trả lời theo policy gốc chụp trước khi thêm rule), nên giữ `ksu` song song không làm lộ thêm. Muốn bỏ hẳn: (1) kernel đổi chữ `u:r:ksu:s0` → `u:r:cam:s0` trong allowlist khi đọc (rồi `KSU_DEFAULT_SELINUX_DOMAIN` = `KERNEL_SU_CONTEXT`, `CAM_DOMAIN` = `u:r:cam:s0`); (2) đợi máy đã chạy kernel đó ít nhất một lần để camd gắn lại nhãn `cam_file`; (3) bỏ dòng `add_su_domain(db, KSU_LEGACY_DOMAIN, …)`. Sau đó không quay về kernel cũ được nữa (allowlist ghi `cam`), và `sepolicy.rule` của module nhắc `ksu` sẽ báo lỗi.
