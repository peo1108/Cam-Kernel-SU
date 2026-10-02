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
| 7 | Tên app `SU Kernel` (trước là `Cam Kernel SU`), gói `cam.su.kernel`, icon logo mới | |
| 8 | `KERNEL_SU_UAPI_VERSION` 4 → 5 | Chặn Manager/ksud bản cũ dùng với kernel mới |
| 9 | Giao diện Miuix thành kính lỏng (Liquid Glass) kiểu iOS, nền là hình nền máy đọc qua root | Theo yêu cầu của Cam; spec `docs/superpowers/specs/2026-10-02-miuix-liquid-glass-design.md` |

## 2. Danh sách commit (theo thứ tự)

```
73b4951c docs: add Cam Kernel SU README
a06407f8 manager: set app name to Cam Kernel SU
1b9b0673 kernel: trust the Cam Kernel SU manager signing key   (đã lỗi thời, xem mục 4)
8c2ab771 docs: add managerless seed allowlist plan
2051c0bf kernel: drop manager apk detection, uid 0 is the manager
1675afc0 kernel: grant root to patch-time seed packages on boot
0d48dcb1 ksud: add boot-patch --seed and drop manager debug commands
cb45e947 manager: run privileged ksu calls in root service
c5d7c16f manager: gate features on root access instead of manager identity
3b4d27b3 manager: choose root apps when patching init_boot
1f916fc6 kernel: apply root seed from post-fs-data on first boot
03654076 ksud: add allow command to grant root without re-flashing
ce345906 manager: rename app package to cam.su.kernel
1694c43e manager: Tint background effect from a seed color
ebf59abc manager: Add root wallpaper repository for glass background
3cb40554 manager: Draw device wallpaper behind Miuix pages
610926d6 manager: Add glass cards and apply them to Home
945c31fe manager: Rebrand app as SU Kernel with new launcher icon
a20456a6 manager: Tune glass defaults
01e54b05 manager: Make top bars transparent with glass icon buttons
2b17b68d manager: Apply glass cards to all Miuix screens
6c1ae181 manager: Add specular light and lens to glass controls
574de4ab manager: Let bar controls refract content scrolling beneath
587c1233 manager: Group adjacent toolbar buttons into one glass capsule
52e9853d manager: Add glass dialogs, popups, FAB and navigation rail
f91c98ca manager: Add glass background settings page
```

Xem lại bất cứ lúc nào: `git log --oneline 08a3b087..HEAD`

## 3. File bị đổi, theo khu vực

Ký hiệu: **[mới]** file của Cam, upstream không có, không bao giờ conflict. **[xoá]** file upstream mà Cam đã xoá. **[sửa]** file upstream có sửa, dễ conflict.

### Kernel (`kernel/`)
- [xoá] `manager/apk_sign.c`, `manager/apk_sign.h`, `manager/throne_tracker.c`, `manager/throne_tracker.h`, `manager/manager_observer.h`
- [chuyển] `manager/pkg_observer.c` → `policy/pkg_observer.c`
- [mới] `policy/pkg_tracker.c`, `policy/pkg_tracker.h` (đọc `packages.list`, áp seed, prune allowlist), `policy/pkg_observer.h`
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
- [sửa] `build.rs`: `KSU_PACKAGE_NAME` mặc định `cam.su.kernel`

### Manager (`manager/`)
- [mới] `Ksu.kt` (facade cho UI), `KsuServiceClient.kt` (bind root service), `ui/screen/install/SeedPicker.kt`
- [sửa] `aidl/.../IKsuInterface.aidl`, `ui/KsuService.kt`: thêm các hàm gọi kernel
- [sửa] `cpp/ksu.cc`: process uid 0 tự xin fd qua reboot magic; bỏ `is_manager()` (cùng `ksu.h`, `jni.cc`)
- [sửa] `Natives.kt`: bỏ `isManager`, `isFullFeatured`
- [sửa] khoảng 25 file UI/viewmodel/repository: `Natives.xxx` → `Ksu.xxx` (đổi máy móc)
- [sửa] `ui/MainActivity.kt`: giữ splash tới khi kết nối xong root service
- [sửa] `ui/screen/install/InstallScreen.kt`, `ui/screen/flash/FlashUtils.kt`, `ui/util/KsuCli.kt`: truyền seed vào `boot-patch`
- [sửa] `AndroidManifest.xml`: thêm `QUERY_ALL_PACKAGES`
- [sửa] `res/values/strings.xml`, `res/values-vi/strings.xml`: chuỗi `seed_*`
- [sửa] `app/build.gradle.kts`: gói mặc định `cam.su.kernel`
- [sửa] `gradle.properties`: `KSU_NAME=SU Kernel`
- [mới] Kính lỏng: `ui/component/glass/` (`GlassBackground`, `GlassCard`, `GlassButton`, `GlassOverlay`, `GlassDefaults`, `GlassImage`), `ui/component/liquid/GravityHighlight.kt`, `data/repository/WallpaperRepository.kt`, `ui/screen/colorpalette/GlassBackgroundSection.kt`; mọi thông số kính nằm ở `GlassDefaults`
- [sửa] mọi file `*Miuix.kt`: `Card` → `GlassCard`/`GlassListCard`, `IconButton` → `GlassIconButton`, `actions` của top bar bọc `GlassButtonGroup`, `OverlayDialog` → `GlassDialog`, `OverlayListPopup` → `GlassListPopup`, `FloatingActionButton` → `GlassFab`, `Scaffold(containerColor = Color.Transparent)` (đổi máy móc)
- [sửa] `ui/util/BlurExt.kt` (top bar trong suốt, cấp backdrop gộp cho nút trên bar), `ui/MainActivity.kt` (mỗi trang bọc `GlassPage`), `component/miuix/SuperSearchBar.kt`, `component/dialog/DialogMiuix.kt`, `component/bottombar/NavigationRailMiuix.kt`
- [mới] Icon: `manager/icon/launcher-src.png`, `scripts/gen_launcher_icon.py` sinh `mipmap-*/ic_launcher_logo*.png`
- [mới] Unit test JVM: `manager/app/src/test` (`./gradlew :app:testDebugUnitTest`)

## 4. Cách kéo bản cập nhật upstream

```bash
git checkout main
git fetch upstream
git checkout -b merge-upstream-YYYYMMDD
git merge upstream/main
```

Dùng `merge` thay vì `rebase` cho dễ: chỉ phải giải conflict một lần.

### Quy tắc khi giải conflict

- **Upstream sửa các file Cam đã xoá** (`apk_sign.c`, `throne_tracker.c`, `manager_observer.h`, `apk_sign.rs`…): giữ trạng thái **xoá** (`git rm <file>`). Nếu upstream thêm tính năng mới vào đó, xem nó có cần chuyển sang `policy/pkg_tracker.c` không.
- **Upstream sửa `pkg_observer.c` ở chỗ cũ (`kernel/manager/`)**: áp thay đổi đó vào `kernel/policy/pkg_observer.c`.
- **`manager_identity.h`**: luôn giữ bản của Cam (chỉ có `is_manager()` = uid 0).
- **`Kbuild` / `Kconfig`**: giữ bản đã bỏ `EXPECTED_*`, `DISABLE_MANAGER`, `MANAGER_PACKAGE`; nhận các dòng `kernelsu-objs` mới của upstream.
- **`uapi/supercall.h`**: nếu upstream tăng `KERNEL_SU_UAPI_VERSION`, đặt bản Cam = **số của upstream + 1**, để bản Cam và upstream không bao giờ trùng uapi.
- **File Manager có `Ksu.xxx`**: nhận thay đổi của upstream, rồi đổi lại mọi `Natives.<hàm>` thành `Ksu.<hàm>` (xem mục 5).
- **`app/build.gradle.kts`, `ksud/build.rs`**: giữ gói `cam.su.kernel`.
- **File `*Miuix.kt`**: nhận thay đổi của upstream, rồi đổi lại các component sang bản kính (`Card` → `GlassCard`, `IconButton` → `GlassIconButton`… xem mục 3, Manager). Card mới upstream thêm vào thì cũng đổi sang `GlassCard`; item trong danh sách dài dùng `GlassListCard`. Đừng để lọt `Scaffold` thiếu `containerColor = Color.Transparent`, nếu không trang đó sẽ che mất hình nền.
- Commit `1b9b0673` (key ký của Cam trong `Kbuild`) đã lỗi thời vì kernel không còn kiểm tra chữ ký. Conflict ở đoạn `KSU_EXPECTED_*` thì cứ xoá cả đoạn.

### Kiểm tra sau khi merge

Chạy từng lệnh, kết quả phải đúng như ghi chú:

```bash
# 1. Không còn code tìm manager theo APK (chỉ được còn do_get_manager_appid trong dispatch.c)
rg -n "throne|apk_sign|is_uid_manager|manager_appid|EXPECTED_(SIZE|HASH)|KSU_DISABLE_MANAGER|KSU_MANAGER_PACKAGE" kernel

# 2. Code mới của upstream có dùng khái niệm manager không? Xem kỹ từng chỗ
rg -n "is_manager\(|only_manager|manager_or_root" kernel

# 3. UI không gọi thẳng Natives (chỉ được ra Natives.kt, KsuService.kt, Ksu.kt, managerUAPIVersion)
rg -n "Natives\.(version|kernelUAPIVersion|is[A-Z]|get[A-Z]|set[A-Z]|uid)" manager/app/src/main/java

# 4. Gói vẫn là cam.su.kernel
rg -n "cam.su.kernel" manager/app/build.gradle.kts userspace/ksud/build.rs
```

**Nếu upstream thêm hàm mới vào `Natives`:**
1. Thêm hàm tương ứng vào `IKsuInterface.aidl`.
2. Cài đặt hàm đó trong `KsuService.Stub` (gọi `Natives`).
3. Thêm hàm cùng tên vào `Ksu.kt` (gọi qua `KsuServiceClient`, trả giá trị mặc định khi chưa kết nối).
4. Cho UI gọi `Ksu.<hàm>`.

Lý do: app process không có quyền gọi kernel, chỉ root service gọi được.

**Nếu upstream thêm ioctl mới chỉ cho manager (`only_manager`):** với Cam, ioctl đó nghĩa là "chỉ uid 0", nên phải gọi từ `KsuService` theo đúng 4 bước trên.

## 5. Build trên máy Windows: những bẫy đã gặp

| Bẫy | Hậu quả | Cách tránh |
|---|---|---|
| `core.autocrlf=true` | `installer.sh` bị nhúng vào ksud với CRLF → **cài module lỗi** `syntax error ... expecting "do"` | Build ksud trong WSL từ một bản `git clone` (LF); hoặc thêm `*.sh eol=lf` vào `.gitattributes` |
| `core.symlinks=false` | `manager/app/src/main/cpp/uapi` là file text, CMake báo `uapi/ksu.h not found` | Tạo junction cục bộ: `New-Item -ItemType Junction -Path ...\cpp\uapi -Target ...\uapi`. **Không commit**. Khôi phục: `git checkout -- manager/app/src/main/cpp/uapi` |
| Build kernel từ `git archive` (không có `.git`) | `kernelsu.ko` báo version 16 → Manager coi là kernel quá cũ | Build từ thư mục có `.git` (git clone) |
| Chạy `git` của WSL trên repo `/mnt/c/...` | Làm hỏng junction `cpp/uapi` | Chỉ dùng git của Windows cho repo này; trong WSL chỉ dùng git trên bản clone riêng |
| `cargo ndk` không cài được trên Windows (thiếu `dlltool`) | | Build Rust trong WSL với clang Android + sysroot NDK |

### Lệnh build đã dùng (WSL, máy của Cam)

```bash
# kernelsu.ko cho android16-6.12 (Y700 Gen5); đổi KMI + clang cho máy khác
git clone --branch <branch> "/mnt/c/Users/cam/Desktop/Cam Kernel SU" /root/ksu-git
cd /root/ksu-git/kernel
PATH=/root/scune-kmod-work/toolchains/clang-r536225/bin:$PATH \
make -C /root/scune-kmod-work/android16-6.12/kernel M=$PWD ARCH=arm64 LLVM=1 LLVM_IAS=1 \
     CONFIG_KSU=m KBUILD_MODPOST_WARN=1 modules

# ksud: chép kernelsu.ko + ksuinit vào userspace/ksud/bin/aarch64/ trước
#   tên file: <kmi>_kernelsu.ko, ví dụ android16-6.12_kernelsu.ko
cd /root/ksu-git/userspace/ksud
cargo build --release --target aarch64-linux-android
```

Rồi chép `ksud` vào `manager/app/src/main/jniLibs/arm64-v8a/libksud.so` và chạy `./gradlew assembleDebug` trên Windows.

## 6. Test

- **Kernel (logic seed):** harness chạy trên host với header giả, gồm 11 test, chạy dưới ASan. Harness đang nằm ngoài repo (thư mục scratchpad), chưa đưa vào repo.
- **ksud:** `cargo test seed:: allow::` (chạy trên Linux/WSL). Hai test `lkm_image` / `lkm_image_btf` vốn đã fail sẵn trên upstream khi thiếu asset CI, không liên quan.
- **Trên máy thật (Y700 Gen5, 2026-10-02):** boot OK; seed cấp root cho Manager; seed không áp lại khi nonce giữ nguyên; prune xoá quyền khi gỡ app; cài module + WebUI OK; dialog chọn app OK; `ksud allow` OK.

## 7. Giới hạn đã biết

- **Chế độ late-load / magica không có seed.** Chỉ hỗ trợ LKM qua patch init_boot.
- **Gỡ Manager hoặc tự thu root của nó** thì Manager mất root. Lấy lại bằng `ksud allow add cam.su.kernel` (từ adb su / Termux), hoặc patch lại với seed mới.
- **Lần boot đầu sau flash,** app khác chỉ dùng `su` được sau khi mở Manager một lần (Manager cài `/data/adb/ksud`).
- **ROM ZUI (Lenovo):** dialog chọn app cần thêm quyền riêng `GET_INSTALLED_APP` của ROM. Lần đầu ROM sẽ hỏi; sau khi cho phép phải mở lại dialog.
