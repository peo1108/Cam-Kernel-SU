# Review kernel umount (2026-10-15, chiều): chưa làm

Đọc trên `9d31e218`. Chưa sửa gì; chờ kiểm tra trên máy thật rồi mới quyết có làm hay không.

## Hiện trạng

- `kernel/feature/kernel_umount.c` giống hệt upstream (so với `upstream/main` b94e0916, 2026-10-15), chỉ khác dòng include `runtime/camd_boot.h`. Upstream không sửa file này từ #3666. Mọi vấn đề dưới đây là thừa hưởng từ KernelSU.
- Luồng: metamodule (hybrid_mount) gọi `KSU_IOCTL_ADD_TRY_UMOUNT` (`supercall/dispatch.c` `add_try_umount`, chỉ root/manager) để thêm vào `mount_list`; zygote con gọi `setresuid` -> `ksu_hook_setresuid` (sau syscall, ngữ cảnh process, ngủ được) -> `ksu_handle_umount` -> `path_umount` từng mục dưới `ksu_cred`.
- Điều kiện umount: uid là app / isolated / webview_zygote; `ksu_uid_should_umount` (isolated luôn umount); tiến trình cha mang domain `zygote`.
- Điểm tốt: chặn umount nhầm namespace chung nhờ kiểm SID zygote; `path_umount` tự nhả tham chiếu; duyệt LIFO; isolated luôn umount (probe ẩn root dựa vào đây, dòng 25 của `CAM_CHANGES.md`); kernel, camd, JNI, Manager khớp nhau.

## Vấn đề tìm thấy

| # | Vấn đề | Chỗ | Mức |
|---|---|---|---|
| 1 | `try_umount` chỉ kiểm "là gốc một mount". Metamodule đăng ký `/vendor` mà mount hỏng (không DEL) thì app bị `MNT_DETACH` mất phân vùng thật, có thể crash | `kernel_umount.c` `try_umount` | Trung bình, tùy metamodule |
| 2 | `pr_info` không tắt ở bản release (`include/klog.h` không có cổng debug): mọi `setresuid` trong hệ thống in một dòng, mỗi lần mở app in thêm một dòng mỗi mục | `setuid_hook.c:26`, `kernel_umount.c:107/111/118` | Trung bình (kmsg bẩn, tốn thời gian; app thường không đọc được kmsg) |
| 3 | `flags` không kiểm: `MNT_FORCE` gọi `umount_begin` của superblock, với FUSE là cắt kết nối toàn hệ thống | `dispatch.c` `KSU_UMOUNT_ADD` | Thấp (chỉ root) |
| 4 | ADD đọc 256, DEL đọc 255; đường dẫn dài bị cắt im lặng; chuỗi rỗng trả `-EFAULT` | `dispatch.c` `add_try_umount` | Thấp |
| 5 | Code thừa từ bản task_work (`struct umount_tw`, include `task_work.h`/`slab.h`, `old_uid` không dùng), comment "case 4 and 5" sai, cờ bật tắt đọc không `READ_ONCE` | `kernel_umount.c` | Thấp |
| 6 | Giới hạn thiết kế: mỗi mục chỉ gỡ một lớp mount; con isolated của `app_zygote` không được umount (`is_zygote` chỉ nhận `zygote`); đổi hồ sơ chỉ có hiệu lực từ lần mở sau; mỗi lần umount có `synchronize_rcu`, chưa đo ảnh hưởng tới thời gian mở app | | Ghi nhận |
| 7 | Không có host test cho `ksu_uid_should_umount` | `kernel/tests` | Thấp |

## Kết luận: chưa cần sửa

Code chạy ổn ở upstream, chưa có khách báo lỗi. Sửa kernel có rủi ro (app crash, mount lộ lại) và thêm chỗ lệch khi merge upstream. Chỉ làm khi bước kiểm tra trên máy dưới đây cho thấy có vấn đề.

## Kiểm tra trên máy (khoảng 10 phút, chỉ đọc)

1. `dmesg | grep cmd_add_try_umount`: hybrid_mount đăng ký những đường dẫn nào, flag gì, bao nhiêu mục.
2. So với `/proc/<pid>/mountinfo` của một app không root: có mục nào trỏ vào nguyên một phân vùng (`/vendor`, `/system`...) không.
3. `/proc/partitions`, `ls -l /dev/block/dm-* /dev/block/loop*`: phân vùng hệ thống là dm/sda, loop là major 7.
4. `am start -W` khoảng 10 lần với kernel umount bật và tắt; timestamp dmesg từ "handle umount" tới dòng "unmounting" cuối.

Không có gì lạ: để nguyên, ghi kết quả vào `PROJECT_REVIEW.md` và chuyển file này sang `done/`. Có mục trỏ vào nguyên phân vùng hoặc mở app chậm rõ: làm phần dưới.

## Nếu làm

Hướng chọn (B, không lưu trạng thái): trong `try_umount` từ chối gỡ khi mount là nguyên một phân vùng thật:
`mnt->mnt_root == mnt->mnt_sb->s_root && mnt->mnt_sb->s_bdev && MAJOR(s_bdev->bd_dev) != LOOP_MAJOR`.
Overlay/tmpfs không có bdev, bind từ `/data/adb` có `mnt_root != s_root`, image module là loop: đều qua. Không đổi uapi, không thêm trường vào `mount_entry`. Bị chặn thì `pr_warn_ratelimited`.
Có điều kiện này thì gỡ lặp được nhiều lớp (tối đa 8) một cách an toàn: tới lớp phân vùng thật thì dừng (sửa luôn mục 6, lớp chồng).

Hướng bỏ (A, ghi superblock lúc ADD): phụ thuộc thời điểm, metamodule ADD trước khi mount mà mount hỏng thì vẫn gỡ nhầm.

Thứ tự: 5 -> 4 -> 3 (xem flag hybrid_mount dùng trước, chỉ cho `MNT_DETACH | MNT_EXPIRE | UMOUNT_NOFOLLOW`) -> 2 (macro `ksu_dbg()` trong `klog.h`, chỉ in khi `CONFIG_KSU_DEBUG`) -> B kèm gỡ nhiều lớp -> 7 (tách hàm quyết định thuần để `kernel/tests` test được) -> ghi dòng mới vào `CAM_CHANGES.md`.

Thử sau khi sửa: lặp lại bước 2 và 4; `camd kernel umount add /vendor --flags 2` rồi mở app (không crash, có log từ chối), xong `del`; flag 1 trả `-EINVAL`; `scripts/device_smoke.sh`; `make -C kernel/tests`.

Cách khác không làm lệch upstream: gửi PR lên KernelSU cho mục 2 và 3.
