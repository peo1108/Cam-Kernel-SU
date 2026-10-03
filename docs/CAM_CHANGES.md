# Cam Kernel SU: các thay đổi so với KernelSU gốc

Tài liệu này ghi lại mọi chỗ Cam Kernel SU khác với upstream (`tiann/KernelSU`), để mỗi lần kéo bản cập nhật về thì biết chỗ nào sẽ conflict và phải giữ lại gì.

- Upstream gốc lúc tách nhánh: commit `08a3b087` (`refactor(ksud): update waitsys and refactor logic (#3755)`)
- Remote: `origin` = `peo1108/Cam-Kernel-SU`, `upstream` = `tiann/KernelSU`

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

- [sửa] `ui/util/BlurExt.kt` (top bar trong suốt, mép mờ khi cuộn, backdrop cho nút trên thanh), `ui/MainActivity.kt` (mỗi trang bọc `GlassPageIfMiuix`, nạp sẵn nền khi splash, blur luôn bật ở Miuix), `component/miuix/SuperSearchBar.kt`, `component/dialog/DialogMiuix.kt`, `component/bottombar/BottomBarMiuix.kt`, `component/bottombar/NavigationRailMiuix.kt`, `component/FloatingBottomBar.kt`, `component/miuix/effect/BgEffectConfig.kt`, `BgEffectBackground.kt`, `screen/about/AboutMiuix.kt`, `screen/colorpalette/ColorPaletteScreenMiuix.kt` (bỏ 2 công tắc blur cũ), `data/repository/SettingsRepository*.kt`, `ui/viewmodel/*`
- [sửa] Thương hiệu: `app/build.gradle.kts` (gói `cam.su.kernel`, tên `SU Kernel`), `gradle.properties` (`KSU_NAME=SU Kernel`), `res/mipmap-anydpi/ic_launcher.xml`, `res/values/colors.xml` (nền icon đen), `res/values*/themes.xml` (splash nền đen + `@drawable/ic_splash_logo`)
- [mới] Icon: `manager/icon/launcher-src.png`, `scripts/gen_launcher_icon.py` sinh `mipmap-*/ic_launcher_logo*.png` và `drawable-xxxhdpi/ic_splash_logo.png`

### CI và công cụ
- [sửa] `.github/workflows/build-manager.yml`: thêm `workflow_dispatch`
- [sửa] `.github/workflows/release.yml`: `permissions: contents: write`, `generate_release_notes: true`
- [sửa] `.github/workflows/ddk-lkm.yml`: `safe.directory "$GITHUB_WORKSPACE"` (thay cho tên repo gốc ghi cứng) và checkout `fetch-depth: 0`. Thiếu hai dòng này module CI báo phiên bản **16**
- [sửa] `.gitattributes`: `scripts/*.sh` luôn LF
- [mới] `scripts/build_lkm_ksud.sh` (build LKM + ksud từ một commit trong WSL)

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
git merge upstream/main
```

Dùng `merge` thay vì `rebase`: chỉ phải giải conflict một lần.

**Đừng** chạy `git checkout -- <thư mục>`, `git clean`, hay `git add manager/app/src/main` (có junction `cpp/uapi`, xem mục 5). Luôn add từng file cụ thể.

### Bước 3: giải conflict

Kernel / ksud:
- **Upstream sửa các file Cam đã xoá** (`apk_sign.c`, `throne_tracker.c`, `manager_observer.h`, `apk_sign.rs`…): giữ trạng thái **xoá** (`git rm <file>`). Nếu upstream thêm tính năng mới vào đó, xem có cần chuyển sang `policy/pkg_tracker.c` không.
- **Upstream sửa `pkg_observer.c` ở chỗ cũ (`kernel/manager/`)**: áp thay đổi đó vào `kernel/policy/pkg_observer.c`.
- **`policy/pkg_tracker.c`**: là file của Cam; giữ `ksu_seed_apply()` và `ksu_manager_pin_apply()`, thứ tự gọi trong `ksu_pkg_tracker_update()`: seed → pin → prune.
- **`manager_identity.h`**: luôn giữ bản của Cam (chỉ có `is_manager()` = uid 0).
- **`Kbuild` / `Kconfig`**: giữ bản đã bỏ `EXPECTED_*`, `DISABLE_MANAGER`, `MANAGER_PACKAGE`; nhận các dòng `kernelsu-objs` mới của upstream. Conflict ở đoạn `KSU_EXPECTED_*` (từ commit lỗi thời `1b9b0673`) thì xoá cả đoạn.
- **`uapi/supercall.h`**: nếu upstream tăng `KERNEL_SU_UAPI_VERSION`, đặt bản Cam = **số của upstream + 1**, để bản Cam và upstream không bao giờ trùng uapi.
- **`ksud/build.rs`**: giữ gói `cam.su.kernel`. **`sepolicy.rs`**: giữ dòng `#![allow(clippy::redundant_field_names)]` ở đầu file.

Manager:
- **File có `Ksu.xxx`**: nhận thay đổi của upstream, rồi đổi lại mọi `Natives.<hàm>` thành `Ksu.<hàm>`. Upstream thêm hàm mới vào `Natives` thì làm theo "Nếu upstream thêm hàm mới" bên dưới.
- **File `*Miuix.kt`**: nhận thay đổi của upstream, rồi đổi lại component sang bản kính theo bảng ở mục 3. Màn hình hoặc component mới của upstream cũng phải đổi theo bảng đó; quên `Scaffold(containerColor = Color.Transparent)` thì trang che mất hình nền.
- **`ui/MainActivity.kt`**: route mới upstream thêm vào `NavDisplay` phải bọc `GlassPageIfMiuix { … }` như các `entry<…>` khác. Giữ khối nạp sẵn nền (`GlassBackgroundCache.preload`) và `LocalEnableBlur provides (uiMode == UiMode.Miuix || …)`.
- **`ui/util/BlurExt.kt`, `component/FloatingBottomBar.kt`, `component/miuix/SuperSearchBar.kt`, `component/dialog/DialogMiuix.kt`**: giữ bản của Cam, rồi áp thay đổi của upstream vào bằng tay.
- **`app/build.gradle.kts`, `gradle.properties`, `res/values*/themes.xml`, `res/mipmap-anydpi/ic_launcher.xml`**: giữ gói `cam.su.kernel`, tên `SU Kernel`, icon/splash của Cam.
- **`res/values*/strings.xml`**: nhận chuỗi mới của upstream, giữ `seed_*` và `glass_background*`.

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

# 3. UI không gọi thẳng Natives (chỉ được ra Natives.kt, KsuService.kt, Ksu.kt, managerUAPIVersion)
rg -n "Natives\.(version|kernelUAPIVersion|is[A-Z]|get[A-Z]|set[A-Z]|uid)" manager/app/src/main/java

# 4. Gói vẫn là cam.su.kernel; pin Manager vẫn được gọi
rg -n "cam.su.kernel" manager/app/build.gradle.kts userspace/ksud/build.rs kernel/policy/pkg_tracker.c
rg -n "ksu_manager_pin_apply" kernel/policy/pkg_tracker.c        # phải ra 2 dòng: định nghĩa + lời gọi

# 5. Giao diện Miuix không còn component gốc lọt vào (phải rỗng; webui/ không tính)
rg -n "[^A-Za-z.](Card|IconButton|OverlayDialog|OverlayListPopup|OverlayDropdownPreference|FloatingActionButton)\(" manager/app/src/main/java --glob "*Miuix.kt" --glob "!**/webui/**"
rg -n "Scaffold\(" manager/app/src/main/java --glob "*Miuix.kt" -A1 | rg -v "containerColor|Scaffold\(|^--"   # phải rỗng

# 6. Test và build
cd manager && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

**Nếu upstream thêm hàm mới vào `Natives`:**
1. Thêm hàm tương ứng vào `IKsuInterface.aidl`.
2. Cài đặt hàm đó trong `KsuService.Stub` (gọi `Natives`).
3. Thêm hàm cùng tên vào `Ksu.kt` (gọi qua `KsuServiceClient`, trả giá trị mặc định khi chưa kết nối).
4. Cho UI gọi `Ksu.<hàm>`.

Lý do: app process không có quyền gọi kernel, chỉ root service gọi được.

**Nếu upstream thêm ioctl mới chỉ cho manager (`only_manager`):** với Cam, ioctl đó nghĩa là "chỉ uid 0", nên phải gọi từ `KsuService` theo đúng 4 bước trên.

### Bước 5: đưa lên GitHub, build và cài

```bash
git checkout main
git merge --ff-only merge-upstream-YYYYMMDD
git push origin main          # CI "Build Manager" tự chạy: APK ký khóa release + LKM 8 KMI
```

1. Chờ CI xanh (Actions trên GitHub, hoặc `gh run list -R peo1108/Cam-Kernel-SU --branch main`). Mở log bước build LKM, phải thấy `KernelSU version: 3xxxx` (không phải 16).
2. Tải APK ở mục Artifacts (`manager`), **cài đè** lên máy (cùng khóa release nên không mất dữ liệu).
3. Mở app → bấm thẻ "Hiện có phiên bản LKM tích hợp mới hơn" → **Cài đặt trực tiếp** → khởi động lại. Sau đó app và LKM cùng một số phiên bản.
4. Ra bản cho người dùng: gắn tag (mục 8).

Muốn build trên máy thay vì CI (chỉ 2 KMI): mục 5, "Lệnh build đã dùng".

## 5. Build trên máy Windows: những bẫy đã gặp

| Bẫy | Hậu quả | Cách tránh |
|---|---|---|
| `core.autocrlf=true` | `installer.sh` bị nhúng vào ksud với CRLF → **cài module lỗi** `syntax error ... expecting "do"` | Build ksud trong WSL từ một bản `git clone` (LF); hoặc thêm `*.sh eol=lf` vào `.gitattributes` |
| `core.symlinks=false` | `manager/app/src/main/cpp/uapi` là file text, CMake báo `uapi/ksu.h not found` | Tạo junction cục bộ (PowerShell): `New-Item -ItemType Junction -Path "...\manager\app\src\main\cpp\uapi" -Target "...\uapi"`. `git status` sẽ luôn hiện ` D manager/app/src/main/cpp/uapi`: bình thường, **không commit, không add**. **Không bao giờ** chạy `git checkout -- manager/...`, `git stash`, `git clean` trên đường dẫn này: git ghi đè junction và **xoá luôn `uapi/*.h` ở gốc repo** (đã xảy ra 2026-10-02). Lỡ bị thì `git checkout -- uapi` rồi tạo lại junction |
| CI: LKM báo phiên bản **16** (Home ghi `16-5`) | `ddk-lkm.yml` của upstream ghi cứng `safe.directory /__w/KernelSU/KernelSU`; repo fork khác tên nên git trong container DDK từ chối đọc repo | Đã sửa: `safe.directory "$GITHUB_WORKSPACE"` + checkout `fetch-depth: 0`. Merge upstream mà `ddk-lkm.yml` conflict thì giữ hai dòng này; sau mỗi lần CI chạy, log bước LKM phải ghi `KernelSU version: 3xxxx` |
| Build kernel từ `git archive` (không có `.git`) | `kernelsu.ko` báo version 16 → Manager coi là kernel quá cũ | Build từ thư mục có `.git` (git clone) |
| Chạy `git` của WSL trên repo `/mnt/c/...` | Làm hỏng junction `cpp/uapi` | Chỉ dùng git của Windows cho repo này; trong WSL chỉ dùng git trên bản clone riêng |
| `cargo ndk` không cài được trên Windows (thiếu `dlltool`) | | Build Rust trong WSL với clang Android + sysroot NDK |

### Lệnh build đã dùng (WSL, máy của Cam)

Một lệnh build cả `kernelsu.ko` (từng KMI) lẫn ksud **từ cùng một commit**, rồi chép về repo Windows:

```bash
# trong WSL (root); tham số là nhánh cần build, mặc định feat/managerless-seed
bash "/mnt/c/Users/cam/Desktop/Cam Kernel SU/scripts/build_lkm_ksud.sh" main
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
| Không thấy file `ksud` sau khi build | ksud là thành viên workspace: file nằm ở `target/` của **gốc repo**, không phải `userspace/ksud/target/` |
| `ksuinit` không có trong git | Script lấy bản trong `userspace/ksud/bin/aarch64/` của repo Windows |

## 6. Test

- **Kernel (logic seed):** harness chạy trên host với header giả, gồm 11 test, chạy dưới ASan. Harness đang nằm ngoài repo (thư mục scratchpad), chưa đưa vào repo.
- **ksud:** `cargo test seed:: allow::` (chạy trên Linux/WSL). Hai test `lkm_image` / `lkm_image_btf` vốn đã fail sẵn trên upstream khi thiếu asset CI, không liên quan.
- **Trên máy thật (Y700 Gen5, 2026-10-02):** boot OK; seed cấp root cho Manager; seed không áp lại khi nonce giữ nguyên; prune xoá quyền khi gỡ app; cài module + WebUI OK; dialog chọn app OK; `ksud allow` OK.

## 7. Giới hạn đã biết

- **Chế độ late-load / magica không có seed.** Chỉ hỗ trợ LKM qua patch init_boot.
- **Gỡ Manager rồi cài lại** không còn mất root: kernel cấp lại root cho `cam.su.kernel` ngay khi `packages.list` đổi (cần LKM có commit "keep root for the manager package"). Cũng vì vậy **không thu root của chính Manager được**: lần thay đổi gói kế tiếp kernel sẽ cấp lại. Đánh đổi có chủ đích: app lạ đặt trùng tên gói `cam.su.kernel` cũng sẽ có root.
- **Lần boot đầu sau flash,** app khác chỉ dùng `su` được sau khi mở Manager một lần (Manager cài `/data/adb/ksud`).
- **ROM ZUI (Lenovo):** dialog chọn app cần thêm quyền riêng `GET_INSTALLED_APP` của ROM. Lần đầu ROM sẽ hỏi; sau khi cho phép phải mở lại dialog.

## 8. Phát hành bản mới (GitHub Actions)

CI build trên GitHub là bản đầy đủ: module kernel cho cả 8 KMI (`android12-5.10` → `android17-6.18`), ksud nhiều kiến trúc, Manager ký bằng khóa release.

- **Push lên `main`**: workflow "Build Manager" tự build và ký; tải file ở mục Artifacts của lượt chạy. Chạy tay: Actions → Build Manager → Run workflow.
- **Ra bản cho người dùng**: gắn tag rồi push tag, workflow "Release" tạo GitHub Release kèm APK, `lkm-*_kernelsu.ko`, ksud, ksuinit và ghi chú tự sinh:

```bash
git tag v1.0.0
git push origin v1.0.0
```

  Tên phiên bản trong app lấy từ `git describe --tags` (ví dụ `v1.0.0`); mã phiên bản vẫn là `30000 + số commit`. Đừng dùng lại tên tag đã có của upstream (`v3.x`).
- **Khóa ký**: 4 secret `KEYSTORE` (file `.jks` dạng base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Bản gốc của khóa nằm ở `C:\Users\cam\.android-keys\su-kernel-release.jks` (mật khẩu trong `C:\Users\cam\.gradle\gradle.properties`); khóa cũ "Cam Kernel SU" ở `Documents\Cam-Kernel-SU-keys\cam-kernel-su.p12`. **Mất khóa thì người dùng không cập nhật đè được nữa**: luôn giữ bản sao lưu.
- Vì `~/.gradle/gradle.properties` có khóa release, build trên máy (kể cả debug) cũng ký bằng khóa release. Cần bản ký khóa debug để cài đè bản debug cũ thì thêm `-PKEYSTORE_FILE=C:/Users/cam/.android/debug.keystore -PKEYSTORE_PASSWORD=android -PKEY_ALIAS=androiddebugkey -PKEY_PASSWORD=android`.
