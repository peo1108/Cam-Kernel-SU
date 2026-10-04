# Cài đặt SUSFS trong Manager (không cần module susfs4ksu)

Trang **Cài đặt SUSFS** thay toàn bộ chức năng của module susfs4ksu: ksud tự lưu
cấu hình và áp lại mỗi lần khởi động, nên không cần cài module hay tool `ksu_susfs`.

- **Vị trí:** Features → KernelSU GKI → GKI install → Tùy chọn nâng cao → **Cài đặt SUSFS**.
- **Áp dụng cho:** branch `sfs`, kernel GKI có SUSFS (`CONFIG_KSU_SUSFS=y`, đang dùng v2.3.0).
  Kernel không có SUSFS thì trang chỉ hiện thông báo.
- **Căn cứ:** đối chiếu với module mẫu của simonpunk
  ([susfs4ksu](https://gitlab.com/simonpunk/susfs4ksu), nhánh `gki-android16-6.12`) và module WebUI
  của sidex15 ([susfs4ksu-module](https://github.com/sidex15/susfs4ksu-module), v1.5.2-R28).

## Kiến trúc

```
Manager (trang Cài đặt SUSFS)
   │  ghi JSON vào cache của app, gọi: ksud susfs set-config <file>
   ▼
ksud ── kiểm tra ─► lưu /data/adb/ksu/susfs.json ─► áp ngay phần áp được
   │                                                  │
   │  mỗi lần boot đọc susfs.json                     │ reboot syscall (SUSFS_MAGIC)
   ▼                                                  ▼
post-fs-data → service → boot-completed (chạy nền) ──► kernel SUSFS
```

Kernel quên mọi lệnh SUSFS khi reboot. Module cũ chỉ là các script chạy lại `ksu_susfs`
ở từng giai đoạn boot; giờ ksud làm việc đó với cùng thứ tự.

| Giai đoạn | Việc ksud làm |
| --- | --- |
| post-fs-data (sau init features, trước script module) | log SUSFS, ẩn sus mount, AVC spoofing, uname nếu chọn post-fs-data |
| service (trước script service của module) | props verified boot, open_redirect giai đoạn service, ẩn loop, ẩn lineage trong vendor sepolicy / compat matrix |
| boot-completed (fork chạy nền, sau script module) | auto try_umount, open_redirect, sus_map, sus_kstat, try_umount, uname, ẩn custom ROM, ẩn GApps, bootconfig giả, ReVanced, LSPosed, tắt ẩn sus mount nếu chọn "đến khi khởi động xong", **chờ `/sdcard/Android/data` (tối đa 100 giây)**, emulate vold app data, sus_path, sus_path_loop |

Mỗi bước được ghi vào `/data/adb/ksu/log/susfs.log` (xóa khi bắt đầu post-fs-data).
Xem bằng `ksud susfs log` hoặc mục **Nhật ký** trên trang.

## Chức năng: module → trang mới

| Module susfs4ksu | Trên trang | Ghi chú |
| --- | --- | --- |
| `susfs_log` | Chung → Nhật ký kernel của SUSFS | |
| `avc_log_spoofing` | Chung → Giả mạo AVC log | |
| `hide_sus_mnts_for_all_or_non_su_procs` 0/1/2 | Chung → Ẩn sus mount: Tắt / Luôn bật / Đến khi khởi động xong | |
| `spoof_uname`, `kernel_version`, `kernel_build` | Giả mạo uname: release, build, áp lúc boot-completed / post-fs-data | Nút "Dùng ngày build kernel gốc" = `#1 SMP PREEMPT <ro.build.date>` |
| `spoof_cmdline` | Giả mạo bootconfig: Tắt / Tự động / Tự viết | Tự động: `verifiedbootstate=green`, `vbmeta.device_state=locked`, bỏ `verifiedbooterror`/`verifyerrorpart`, `hwname`/`sku` = `ro.product.name` |
| `hide_loops` | Ẩn → Ẩn thiết bị loop | `/proc/fs/jbd2/loop*-8`, `/proc/fs/ext4/loop*` |
| `hide_vendor_sepolicy`, `hide_compat_matrix` | Ẩn → Ẩn custom ROM trong vendor sepolicy / compat matrix | Bind mount bản sao bỏ dòng `lineage`, giữ stat bằng sus_kstat |
| `hide_cusrom` 1–5 | Ẩn → Ẩn file custom ROM (mức 1–5) | Lọc giống hệt regex của module |
| `hide_gapps` | Ẩn → Ẩn gói GApps | |
| `hide_revanced` | Ẩn → Ẩn ReVanced | Đưa đường dẫn `pm path` của YouTube / YT Music vào danh sách umount |
| `force_hide_lsposed` | Ẩn → Ẩn LSPosed triệt để | Umount các dex2oat của ART |
| `emulate_vold_app_data` 1/2 | Ẩn → Giả lập vold app data: sus_path / sus_path_loop | |
| `auto_try_umount`, `skip_legit_mounts`, `legit_mounts.txt` | Ẩn → Tự động try_umount, Bỏ qua mount hợp lệ, danh sách mount hợp lệ | Lấy mount nguồn `KSU` hoặc mount id ≥ 2000000000 trong `/proc/1/mountinfo` |
| `sus_path.txt`, `sus_path_loop.txt` | Danh sách → sus_path, sus_path_loop | Cùng định dạng: `<đường dẫn> [số giây chờ]` |
| `sus_maps.txt` | Danh sách → sus_map | |
| `try_umount.txt` | Danh sách → try_umount | Qua danh sách umount của KernelSU (`MNT_DETACH`) |
| `sus_open_redirect.txt` | Danh sách → open_redirect | `<gốc> <thay thế> <0 boot-completed \| 1 service> [uid scheme]`, kèm sus_kstat cho file thay thế như module |
| `sus_kstat_statically.json` | Giả mạo stat → sus_kstat (tĩnh) | Form 12 trường; để trống = giữ giá trị thật |
| Props trong `service.sh`, `VerifiedBootHash.txt`, `vbmeta_size` | Thuộc tính boot → Giả mạo prop verified boot, kích thước vbmeta, verified boot hash | Mặc định tắt |
| Xuất / nhập / reset cài đặt | Sao lưu → Xuất / Nhập JSON, Khôi phục mặc định | |

Không làm vì kernel SUSFS v2.3.0 đã bỏ các lệnh này: `sus_su`, `sus_mount`, `try_umount` kiểu cũ của
SUSFS, `umount_for_zygote_iso_service`, `set_sdcard_root_path` / `set_android_data_root_path`
(kernel tự theo dõi `/sdcard`). Phần tự cập nhật binary `ksu_susfs` cũng không cần nữa.

## Khi bấm Lưu

ksud so cấu hình cũ với mới và chỉ chạy phần khác nhau:

| Thay đổi | Kết quả |
| --- | --- |
| Bật/tắt log, AVC, ẩn sus mount; đổi uname (để trống = giá trị thật) | Áp ngay |
| Thêm sus_path, sus_path_loop, sus_map, open_redirect, sus_kstat | Áp ngay |
| Thêm hoặc xóa try_umount, bật/tắt ẩn LSPosed | Áp ngay (danh sách umount gỡ được) |
| Bật một mục ẩn (loop, sepolicy, GApps, custom ROM, props…) | Chạy ngay |
| Bật hoặc đổi nội dung bootconfig giả | Áp ngay |
| Xóa đường dẫn đã ẩn, sửa redirect/kstat đã có, tắt bootconfig giả, tắt một mục ẩn, hạ mức custom ROM | **Cần khởi động lại** — trang hỏi có reboot ngay không |

Cấu hình sai (đường dẫn không tuyệt đối, quá 255 byte, uname quá 64 byte, bootconfig quá 8191 byte,
uid scheme ngoài 0–4, trường kstat không phải số…) bị từ chối, không lưu gì, trang liệt kê lỗi.
Lệnh áp ngay nào lỗi thì cấu hình vẫn được lưu và trang liệt kê lệnh lỗi.

## Mặc định và an toàn

- **Chưa từng lưu vẫn có mặc định:** mỗi lần boot ksud bật log SUSFS và ẩn sus mount
  (simonpunk ghi đây là cài đặt nên luôn bật). Muốn tắt thì đổi trên trang rồi lưu.
- **Module susfs4ksu đang bật** (`/data/adb/modules/susfs4ksu` không có file `disable`/`remove`):
  ksud không áp gì, trang hiện cảnh báo; cài đặt vẫn lưu được.
- **Safe mode:** ksud không áp gì.
- **Rời trang khi chưa lưu:** có hộp thoại xác nhận bỏ thay đổi.
- Phần boot-completed chạy trong tiến trình fork riêng nên không chặn script boot-completed của module.

## Thay đổi trong mã nguồn

### ksud (`userspace/ksud`)

| File | Thay đổi |
| --- | --- |
| `src/susfs_config.rs` (mới) | Mô hình cấu hình + JSON, kiểm tra hợp lệ, `boot_actions` (việc từng giai đoạn), `plan_live` (việc khi lưu + có cần reboot), tạo bootconfig khóa, lọc custom ROM, tìm mount KSU. Có 7 unit test |
| `src/susfs.rs` | Thêm struct ABI SUSFS (sus_path, uname, bootconfig, open_redirect, sus_kstat…), thực thi từng hành động, đọc/ghi `susfs.json`, log, phát hiện module, `apply_stage` |
| `src/cli.rs` | Lệnh mới `ksud susfs config`, `set-config <file\|->`, `bootconfig`, `log` |
| `src/init_event.rs` | Gọi `apply_stage` ở post-fs-data, service, boot-completed |
| `src/defs.rs` | `SUSFS_CONFIG_PATH = /data/adb/ksu/susfs.json` |
| `src/main.rs` | Khai báo module `susfs_config` |

Lệnh CLI:

| Lệnh | Việc |
| --- | --- |
| `ksud susfs info [--json]` | Phiên bản, variant, tính năng của kernel (có từ trước) |
| `ksud susfs config` | In `{config, moduleActive, saved}` dạng JSON |
| `ksud susfs set-config <file\|->` | Lưu và áp; in `{saved, errors, failed, rebootNeeded, moduleActive}` |
| `ksud susfs bootconfig` | In bootconfig giả mà chế độ Tự động sẽ dùng |
| `ksud susfs log` | In log lần boot / lần lưu gần nhất |

### Manager (`manager/app/src/main`)

| File | Thay đổi |
| --- | --- |
| `ui/screen/susfs/SusfsSettingsScreen.kt` (mới) | Điều phối: lưu, hộp thoại reboot / lỗi, xuất/nhập JSON qua SAF, chặn thoát khi chưa lưu |
| `ui/screen/susfs/SusfsSettingsMiuix.kt` (mới) | Giao diện: các thẻ Trạng thái, Chung, Uname, Bootconfig, Ẩn, Danh sách, Giả mạo stat, Props, Sao lưu; hộp thoại sửa văn bản và form kstat |
| `ui/screen/susfs/SusfsSettingsUiState.kt` (mới) | State và actions của trang |
| `ui/viewmodel/SusfsSettingsViewModel.kt` (mới) | Tải / lưu qua ksud, giữ bản đang sửa |
| `ui/util/Susfs.kt` (mới) | Mô hình cấu hình phía Kotlin (khớp `susfs_config.rs`), gọi các lệnh `ksud susfs` |
| `ui/util/KsuCli.kt` | `ksudStdout` từ `private` thành `internal` để dùng lại |
| `ui/navigation3/Routes.kt`, `ui/MainActivity.kt` | Route `SusfsSettings` và đăng ký trang |
| `ui/screen/gki/GkiInstall*.kt` | Mục "Cài đặt SUSFS" trong Tùy chọn nâng cao |
| `res/values/strings.xml`, `res/values-vi/strings.xml` | 124 chuỗi `susfs_*` tiếng Anh và tiếng Việt |

### Tài liệu

`sfs/README.md` thêm mục "SUSFS settings" và sửa dòng về ksud; file này mô tả chi tiết.

## Build và kiểm tra

Đã làm:

- `cargo test --bin ksud susfs_config`: 7/7 pass (chạy trên host Linux trong WSL).
- `cargo clippy --target aarch64-linux-android`: sạch (WSL, clang wrapper + sysroot NDK r28c);
  `cargo fmt` đã chạy. Chưa chạy đúng lệnh `cargo ndk` như AGENTS.md vì máy không có NDK Linux.
- Manager: `gradlew :app:compileDebugKotlin` và `processDebugResources` không lỗi, không cảnh báo.

Chưa làm: **chưa thử trên máy thật**. Cách thử:

1. Build ksud mới, chép vào `manager/app/src/main/jniLibs/arm64-v8a/libksud.so`, build và cài Manager
   (ksud cũ không có lệnh `susfs config`/`set-config` nên trang sẽ không lưu được).
2. Mở trang, bật vài mục, Lưu; kiểm tra `ksud susfs log` và `uname -a`.
3. Thêm một sus_path (vd `/system/addon.d`), reboot, xem log boot và kiểm tra bằng app phát hiện root.
4. Thử xóa một sus_path → trang phải hỏi reboot.

## Rủi ro và việc còn lại

- Ẩn custom ROM mức cao, ẩn vendor sepolicy (bind mount) có thể làm ROM hỏng; nếu bootloop, vào
  safe mode (ksud không áp gì) rồi tắt mục đó.
- Ẩn custom ROM quét toàn bộ `/system`, `/vendor`, `/system_ext`, `/product`: lúc lưu có thể mất vài giây.
- Spoof props ghi đè `ro.*` như module; mặc định tắt.
- Khi lên SUSFS bản mới cần so lại số lệnh và struct trong `susfs.rs` với `susfs_def.h` / `susfs.h`.
