# Review ẩn SELinux (2026-10-15, tối): đã sửa mục 1 + 2 + observer, đã kiểm trên máy, xong

Đọc trên `9d31e218`. Kiểm trên tablet TB323FU (Android 16, kernel `6.12.30-android16-5`) cùng ngày, LKM `32974`. Điểm phần này: 8 / 10 (mục 6 đã kiểm xong, không lộ). Trạng thái: mục 1 chạy đúng trên máy; mục 2 + observer đã sửa (giữ); panic `camd unload` là giới hạn upstream, quyết định không sửa (xem cuối file).

## Hiện trạng

- `kernel/feature/selinux_hide.c` giống hệt upstream `a85dcbcf` (#3877); `upstream/main` chưa sửa thêm (fetch 2026-10-15). Mọi vấn đề dưới đây là thừa hưởng từ KernelSU.
- Luồng: `apply_kernelsu_rules` (`selinux/rules.c`) chụp `backup_sepolicy` trước khi thêm domain `ksu`. Khi bật, với uid ≥ 10000: ghi `/sys/fs/selinux/context` và `access` (vá `write_op`), ghi `attr/current` (hook LSM `setprocattr`) đều tính trên bản sao lưu; chuỗi context được tra song song cả hai policy rồi chèn vào sidtab thật. Trang `/sys/fs/selinux/status` là trang giả chụp lúc second stage (hook `sel_open_handle_status`), `avd.seqno` ghim 1. `on_boot_completed` bỏ bản sao lưu nếu tính năng đang tắt.
- Điểm tốt: `setprocattr` kiểm `setcurrent` trước khi parse như SELinux gốc (Duck đo đường bị từ chối, không lộ); hook này thật sự cần ở `app_zygote` (có `setcurrent` theo AOSP); sidtab thật vẫn khớp với máy gốc (#3873); bật/tắt có mutex.

## Kiểm trên máy (2026-10-15)

Công cụ tạm (không vào repo): một chương trình chạy bằng root, hạ xuống uid 10310 rồi đặt context `untrusted_app` hoặc `app_zygote` trước khi gọi; và một bộ tiêm syscall bằng ptrace vào một app thật để đi qua seccomp của app.

| Thử | Kết quả |
|---|---|
| Seccomp của app (tiêm vào `by4a.setedit22`): `lsm_get_self_attr` (459), `lsm_set_self_attr` (460), `lsm_list_modules` (461) | `SIGSYS` cả ba, như syscall số 999. App không gọi được |
| `untrusted_app` mở `/sys/fs/selinux/{context,access,create,relabel,member,user}` | `EACCES` ngay ở `open` (Android 16 không cho app thường đọc ghi selinuxfs). Chỉ `app_zygote` tới được các hook |
| `app_zygote`: ghi `create`, `relabel`, `member`, `user` | `EACCES` (kiểm `compute_*` trước khi parse, như gốc); `validatetrans` bị từ chối ngay ở `open` |
| `app_zygote`, ẩn bật: `context` / `access` / `attr/current` với `u:r:ksu:s0` | `EINVAL` cả ba; context chuẩn trả chuỗi chuẩn |
| `app_zygote`, ẩn tắt (đối chứng) | `context` nhận `u:r:ksu:s0`, `access` trả kết quả, `attr/current` ra `EACCES`: chương trình kiểm phân biệt được |
| Phép đếm sidtab của Duck (`ss/sidtab_hash_stats`, 4 context `app_zygote` mới qua `context` rồi `attr/current`) | context +4, attr +0, `attr/current` bị `EACCES`: đúng mẫu "không thấy khác biệt" của Duck |

Sau khi thử, tính năng vẫn bật như cũ (không `feature save`), đã xoá công cụ khỏi `/data/local/tmp`.

## Vấn đề

| # | Vấn đề | Chỗ | Mức |
|---|---|---|---|
| 1 | Kiểm hợp lệ cho policy thật dùng nhầm `ctx` thay vì `orig_ctx`, và dòng 693 ghi đè lỗi MLS của policy thật (`orig_rc`). Nay vô hại (hai policy cùng số). Khi hai policy lệch (loader nạp lại, late-load), `orig_ctx` thiếu user/role hoặc MLS hỏng vẫn được coi là hợp lệ, chèn vào sidtab thật; ở `setprocattr` task có thể nhận SID rác | `selinux_hide.c:686-698` `string_to_context_struct` | Trung bình |
| 2 | `__free_page(fake_status)` khi gỡ LKM trong khi app còn mmap `/sys/fs/selinux/status` (`remap_pfn_range` không giữ tham chiếu) hoặc còn fd có `private_data` trỏ vào: app đọc trang kernel đã cấp lại. Chỉ xảy ra khi chạy tay `camd unload` | `selinux_hide.c:580-584` `ksu_selinux_hide_exit` | Trung bình, hiếm |
| 3 | Bật sau boot_completed lúc đã bỏ bản sao lưu: `-EAGAIN`, nhưng `get` vẫn trả 1 và trang status giả vẫn được phục vụ, còn context/access/attr thì không. **Đây là chủ ý**: Manager (`SettingsViewModel.setSelinuxHideEnabled`, `HidingCheckViewModel.writeFix`) gọi `feature save` sau khi bật, và lần boot sau bật được là nhờ `get` trả 1. Manager đã báo "cần khởi động lại" khi gặp `-EAGAIN`. Trạng thái nửa vời chỉ kéo dài tới lần khởi động lại, và dù sao cũng lộ y như khi tắt | `selinux_hide.c:492` | Thấp |
| 3b | `camd hiding-audit --apply`: `enable_feature` dùng `?`, gặp `-EAGAIN` thì thoát luôn: không lưu cấu hình, bỏ các fix sau (`hideBootloader`), không in JSON. Manager bù được vì gọi `writeFix` cho cả `pendingFixes` sau đó; chạy tay bằng dòng lệnh thì hỏng | `hiding_audit.rs:618-623`, `:663` | Thấp |
| 4 | `sidtab_context_to_sid` trên sidtab thật trả `-ESTALE` khi policy đang nạp lại; gốc thử lại, ở đây trả thẳng cho app. Policy chỉ nạp lại lúc boot | `selinux_hide.c:756` | Rất thấp |
| 5 | Ghi context hợp lệ tốn gấp đôi (hai lần parse, `kmemdup`, chèn sidtab). Muốn dò phải có số đo nền của từng máy; Duck chưa đo đường này | `my_write_context`, `my_setprocattr` | Thấp |
| 6 | ~~Chưa hook `SEL_CREATE`, `RELABEL`, `MEMBER`, `USER`, `VALIDATE_TRANS`, syscall `lsm_set_self_attr`~~ Đã kiểm: không lộ (bảng trên). Ghi chú cũ "Y700 Gen4/Gen5 dưới 6.8" sai: TB323FU chạy 6.12, nhưng seccomp chặn | | Đóng |
| 7 | Thiết kế: trang status đóng băng (`setenforce 0` thì app vẫn thấy `enforcing=1`); late-load luôn ép `enforcing=1`; uid < 10000 (shell) thấy policy thật (đã ghi ở `CAM_CHANGES.md`) | | Ghi nhận |

## Nên làm

1. **Mục 1** (đã sửa trong code, chưa commit): cuối `string_to_context_struct`, `orig_rc` chỉ thành 0 khi bản sao lưu nhận context (`rc == 0`), MLS của policy thật đạt và `policydb_context_isvalid(orig_pol, orig_ctx)`. Cách này cũng bịt chỗ rò `orig_ctx` khi bản sao lưu từ chối mà policy thật nhận. Không gửi upstream.
2. **Mục 2** (đã sửa trong code, chưa commit): `exit` không `__free_page` (rò 4 KB, chỉ khi gỡ LKM). Sửa thêm trong cùng hàm: init cài hook `sel_handle_status_ops.open` kể cả khi tính năng tắt, mà `exit` chỉ gỡ khi tính năng đang chạy, nên `camd unload` lúc ẩn SELinux tắt để lại con trỏ vào module đã gỡ (mở `/sys/fs/selinux/status` là panic). Nay `exit` luôn gọi `unhook_selinux_status_open()`.
3. **Mục 3b** (tùy): `enable_feature` coi `-EAGAIN` của `SelinuxHide` là đã bật, chờ khởi động lại (vẫn lưu cấu hình, chạy tiếp các fix sau).
4. Không làm: mục 3 phía kernel (đừng đổi thành "chỉ đặt cờ khi bật thành công": Manager sẽ không lưu được lựa chọn; cho trang status xét `running` cũng không giảm lộ), mục 4, 5, 6.
5. `CAM_CHANGES.md`: dòng 56, danh sách file kernel, hướng dẫn merge `selinux_hide.c` (nhận bản upstream rồi áp lại ba sửa của Cam).

## Kết quả thử trên máy (2026-10-15, tối)

LKM mới build android16-6.12 (clang-r536225, version `32974`), vá `init_boot_a` bằng `camd boot-patch -f` rồi khởi động lại. `init_boot_a` gốc (LKM `32960`, sha256 `4927c81f…`) đã sao lưu; máy cũng giữ `/data/adb/cam/previous/init_boot_a.img`.

- **Mục 1 — chạy đúng.** Từ `app_zygote` (uid app): `ksu`, `cam`, `cam_file` đều `EINVAL` ở `context` / `access` / `attr/current`; context chuẩn vẫn trả chuỗi chuẩn; `sidtab` +4 / +0; đổi context được phép (`app_zygote` → `isolated_app`) vẫn thành công, không được phép thì `EACCES`. `camd hiding-audit` AVC lookup 1/1. `scripts/device_smoke.sh` 15/15.
- **Mục 2 — không kiểm được bằng `camd unload`, và không cần.** Gắn mốc log vào `kernelsu_exit`: in đủ 1→15, `kernelsu_exit()` chạy trọn (gồm `ksu_selinux_hide_exit`), rồi panic **sau đó** ở `kernfs_remove_by_name_ns` trong `free_module` của kernel. Gốc: `kobject_del(&THIS_MODULE->mkobj.kobj)` ở `init.c:194` (module tự ẩn, **giống hệt upstream**) làm hỏng trạng thái sysfs; `delete_module` dọn sysfs trên trạng thái đó là panic. Trúng mọi bản release (không `CONFIG_KSU_DEBUG`), cả upstream. Vì panic xảy ra trước, và `camd unload` không ai chạy bình thường (LKM trong `init_boot`, reboot là nạp lại), lỗi dùng-sau-giải-phóng `fake_status` mà mục 2 lo **không có đường kích hoạt thực tế**. Bản sửa mục 2 vẫn đúng, giữ.
- **Observer `free_mark`.** Lần gỡ đầu panic ở `fsnotify_mark_destroy_workfn`: group fsnotify của `pkg_observer.c` thiếu `.free_mark`. Đã sửa (thêm `free_mark`, đổi `fsnotify_put_group` → `fsnotify_destroy_group`); sau đó qua được chỗ này, lộ ra panic `free_module` ở trên. Đây là crash **đầu tiên** nếu sau này ai thực sự làm `camd unload` chạy được, nên giữ bản sửa.

## Quyết định (2026-10-15)

- **Không sửa panic `camd unload`.** Là giới hạn cố hữu của KernelSU (module tự ẩn), bám theo upstream. Sửa đòi dựng lại kobject + dọn kobject con ở `exit` — phẫu thuật nội bộ module của kernel, mong manh, phụ thuộc phiên bản; mà dù sửa xong `unload` vẫn chỉ gỡ tạm cho một lần boot. Ghi nhận giới hạn, không làm.
- **Giữ và commit:** mục 1, mục 2 (`selinux_hide.c`), observer `free_mark` (`pkg_observer.c`). Không gửi upstream.
- Mục 3b (`enable_feature` coi `-EAGAIN` là đã bật) chưa làm — để lần sau nếu cần.

## Review lần hai (2026-10-15, khuya): race khi tắt, các đường chưa đo

Đọc lại trên `256ec433`. Điểm phần này vẫn 8 / 10.

**Mục 8 (mới, đã sửa, dòng 58 của `CAM_CHANGES.md`).** `ksu_selinux_hide_unhook` trả slot `write_op` rồi đặt ngay `orig_context_write` / `orig_access_write` = NULL. Tiến trình uid < 10000 đã vào `my_write_*` trước lúc trả slot gọi NULL. Sửa: không bao giờ xoá con trỏ gốc, trạng thái vá nằm ở `context_write_hooked` / `access_write_hooked`; vá lại thì bỏ qua slot còn hook (trả gốc thất bại) để không lấy chính hook làm gốc. Hook `setprocattr` không bị: `ksu_lsm_unhook` giữ `original` và có `synchronize_rcu()`.

| Thử trên TB323FU | Kết quả |
|---|---|
| LKM `32974`, 7 luồng root ghi `access`/`context`, tắt/bật liền nhau, 120 s | Không crash (3845 lần): sau `stop_machine` luồng bị ngắt chạy tiếp và qua cửa sổ trước khi NULL được ghi |
| LKM `32974`, 32 luồng (gấp 4 số nhân), giữ trạng thái tắt 0,1 s mỗi lần | **Panic sau khoảng 5 s**: `my_write_access+0x48`, `x8 = 0` nạp từ `orig_access_write`, kCFI đọc `[NULL-4]` (DABT) |
| LKM `32976` (bản sửa), cùng bài, 300 s | Không crash: 355 triệu lần ghi, 1567 lần tắt/bật, boot_id giữ nguyên, tính năng vẫn bật |
| `scripts/device_smoke.sh` trên `32976` | 15/15 |

**Mục 6 mở rộng: các đường parse context chưa hook.** Công cụ tạm (không vào repo): root, mỗi phép thử một tiến trình con hạ xuống uid 10310 rồi đổi sang domain app, ghi chuỗi `ksu` / `cam` / type không tồn tại và so errno. Ẩn bật:

| Đường | `untrusted_app` | `app_zygote` | `isolated_app` |
|---|---|---|---|
| `/sys/fs/selinux/context`, `access` | `EACCES` ở `open` | `EINVAL` cả ba chuỗi | `EACCES` ở `open` |
| `attr/current` | `EACCES` | `EINVAL` cả ba | `EACCES` |
| `attr/exec`, `fscreate`, `sockcreate`, `keycreate` | `EACCES` | `EACCES` | `EACCES` |
| `fsetxattr security.selinux` lên file `app_data_file` của chính uid | `EACCES` | `EACCES` | `EACCES` |

Đối chứng (ẩn tắt, `app_zygote`): `context` và `access` nhận `ksu`/`cam`, `attr/current` trả `EACCES` thay vì `EINVAL`, nên công cụ báo 3 chỗ lộ. Bốn đường `attr/*` còn lại và `setxattr` bị từ chối trước khi parse ở cả ba domain, như SELinux gốc: không lộ, không cần hook. Syscall `lsm_set_self_attr` vẫn chỉ được chặn nhờ seccomp của app (đã đo lần đầu); nếu Android sau này mở syscall đó thì phải hook `setselfattr`.

Còn mở: mục 3b. Rủi ro rất thấp, không sửa: `drop_backup_if_unused` giải phóng `backup_sepolicy` mà không chờ hook đang dở (chỉ trúng khi tắt đúng lúc `boot_completed`).
