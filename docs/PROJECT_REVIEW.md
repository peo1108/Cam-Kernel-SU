# Đánh giá dự án Cam Kernel SU

Chấm lại ngày 2026-10-15 trên `11d748ea` (2973 commit). Lần chấm trước: 2026-10-13 trên `fe4b7b7f`, được **7,9**. Số liệu bên dưới đo trực tiếp từ repo và trên máy; điểm là nhận định, thang 10, chấm theo hiện trạng chứ không theo kế hoạch.

Khách hàng gần như toàn bộ là người dùng riêng, chủ yếu Lenovo Y700 Gen5 (một ít Gen4): một lượt thử trên máy của Cam được coi là phủ khoảng 95% người dùng thật, nên mục "Kiểm chứng trên máy thật" chấm theo máy đó chứ không theo số dòng máy.

## Điểm tổng: 8,3 / 10 (trước: 7,9)

Tăng nhờ một ngày dọn và kiểm chứng: thử trên máy những tính năng chưa từng chạy thật (shell adb-only, Widevine, tài khoản, OTA), test trên máy cho root service và AIDL, sửa một lỗ hổng chiếm quyền thành viên trên `site/api`, nhả bộ nhớ 3D khi app nằm nền, sửa một crash có sẵn ở thẻ trạng thái. Mục Bảo mật tính 8,5 khi bản API mới đã được deploy (việc làm bằng tay, xem cuối trang).

| Hạng mục | Điểm | Trước | Lý do ngắn |
|---|---|---|---|
| Ý tưởng và phạm vi | 8,0 | 8,5 | Lõi (ẩn root, ghim Manager theo chứng chỉ, kiểm tra ẩn root) rõ ràng; phạm vi rộng dần (tab Tệp kiểu MT, Widevine, slime 3D) là thêm việc bảo trì ngoài phần root |
| Kiến trúc | 8,5 | 8,5 | Ranh giới sạch: kernel chỉ nhận lệnh từ uid 0, UI qua facade `Cam` rồi root service. `FilesViewModel` chỉ cho helper mượn đúng thứ cần (`FilesHost`); engine 3D theo vòng đời activity (`rememberBackgroundRelease`) |
| Chất lượng code | 8,0 | 7,5 | Không còn file nào của riêng Cam trên 1000 dòng (lớn nhất: `ArenaDecor.kt` 895); `FilesDialogs`, `FilesViewModel`, `HidingCheckMiuix` đã tách, mỗi lần tách đều đối chiếu từng dòng với bản cũ. TODO: 7, đều của upstream |
| Kiểm thử | 8,5 | 7,5 | Kernel 29 (ASan + UBSan), camd 109, Manager 281 unit + 15 test trên máy (root service, AIDL), `site/api` 19 (có kịch bản tấn công). Test mới đều được thử bằng cách làm hỏng code: chúng bắt được. Script trên máy: smoke 15, shell adb-only 5, boot guard, `DeviceCheck` 23 |
| CI và phát hành | 8,5 | 8,5 | Release theo tag `cam-v*`, test chặn trước khi build, kênh beta, 16 workflow. Test trên máy (androidTest) không chạy trên CI vì CI không có máy |
| Tài liệu | 9,0 | 8,5 | `CAM_CHANGES.md` ghi từng thay đổi kèm kết quả thử trên máy; `AGENTS.md` có lệnh test cho từng phần; cách lấy lại `libcamd.so` đã được ghi |
| Bảo mật | 8,5 | 8,0 | Ghim Manager theo chứng chỉ, khóa dự phòng, rule ký ECDSA. Audit `site/api` (2026-10-15): sửa chiếm quyền thành viên qua email chưa xác minh và chiếm trước tài khoản qua Google, bộ giới hạn đăng nhập không còn bị xả. Shell adb-only đã thử trên máy |
| Khả năng merge upstream | 7,5 | 7,5 | Tên symbol kernel giữ của KernelSU nên merge nhẹ; các file Miuix có sửa (`ModuleRepoMiuix.kt` 1172, `ModuleMiuix.kt` 1029) vẫn tốn công |
| Kiểm chứng trên máy thật | 8,0 | 6,5 | Trên máy của Cam (Android 16): mọi tính năng đã thử thật, kể cả shell adb-only, Widevine, tài khoản và OTA. Chưa thử: cấp lại Widevine, cài một bản OTA thật từ đầu đến cuối, thanh toán bằng tiền thật, bước dọn Chặn RKP khi máy có cờ |
| Vệ sinh repo | 8,5 | 8,0 | `libcamd.so` (gitignore) lấy lại được bằng `scripts/build_camd.sh`, Gradle từ chối file không phải ELF; script mới qua ShellCheck. Junction `cpp/uapi` vẫn làm `git status` hiện một dòng |

Cách tính: trung bình có trọng số, kiến trúc, kiểm thử và bảo mật tính gấp đôi: (8,0 + 2×8,5 + 8,0 + 2×8,5 + 8,5 + 9,0 + 2×8,5 + 7,5 + 8,0 + 8,5) / 13 = 8,3.

## Số liệu

| Phần | File | Dòng |
|---|---|---|
| `kernel/` (C, không tính `tests/`) | 100 | 14.200 |
| `userspace/camd/src` (Rust) | 38 | 18.188 |
| `userspace/caminit/src` (Rust) | 3 | 645 |
| `manager/app/src/main/java` (Kotlin) | 354 | 68.988 |
| `manager/app/src/main/cpp` (JNI, probe) | 6 | 1.067 |
| `site/api` (Node, không dependency) | 15 | 2.936 |

## Kết quả test (2026-10-15)

| Phần | Lệnh | Kết quả |
|---|---|---|
| Kernel (WSL) | `make -C kernel/tests` | **29 đạt** (ASan + UBSan; code không đổi từ 2026-10-13) |
| camd (WSL) | `cargo test --target x86_64-unknown-linux-gnu` | **109 đạt** (2026-10-14; code không đổi từ đó) |
| Manager | `gradlew :app:testDebugUnitTest` | 41 lớp, **281 đạt** |
| Manager trên máy | `scripts/device_android_test.sh` | **15/15** (`AidlParcelTest`, `CamRootServiceTest`, `CamFileServiceTest`) |
| `site/api` | `node --test site/api/test/api.test.mjs site/api/test/business.test.mjs` | **19 đạt** |
| Trên máy | `scripts/device_smoke.sh` | **15/15** (thêm: Widevine HAL chạy, có chứng chỉ DRM) |
| Trên máy | `scripts/device_shell_adb_only_test.sh` | **5/5**: tắt thì tiến trình shell mồ côi có root; bật thì `adb shell` vẫn có, tiến trình mồ côi bị từ chối, shell do root sinh ra vẫn có; trả lại như cũ |
| Trên máy | `DeviceCheck` (tab Tệp) | **23/23** |

## Hiệu năng trên máy (2026-10-15, bản debug)

| Đo | Kết quả |
|---|---|
| Khung hình khi cuộn (6 tab) | Giật 0,1–0,8%, p90 10–13 ms (Home có slime 3D: 0,78%) |
| Mở nguội | Khoảng 1,45 s (bản debug; bản release có R8) |
| Bộ nhớ khi mở | PSS khoảng 1,1 GB, phần lớn là đồ họa 3D và kính (máy 11,5 GB RAM) |
| Bộ nhớ sau 10 s nằm nền | Đồ họa 478 MB → **13 MB**, PSS 843 MB → **353 MB** (trước 2026-10-15 không nhả gì). Quay lại: slime 3D hiện lại, cùng tiến trình, 5 vòng không crash |

## Điểm yếu và rủi ro

| # | Vấn đề | Mức | Tình trạng |
|---|---|---|---|
| 1 | Quyền cấp theo email nhận bởi ai đăng ký email đó trước (đăng ký không xác minh email) | Cao | **Sửa trong code** (2026-10-15, dòng 54 của `CAM_CHANGES.md`): quyền theo email chỉ có hiệu lực khi email đã được chứng minh; Google lấy lại email bị đăng ký trước. **Đã deploy** 2026-10-16 (dòng 64) |
| 2 | Bộ giới hạn đăng nhập reset khi đầy 20.000 khóa (botnet đoán mật khẩu một tài khoản) | Trung bình | **Xong**, deploy cùng mục 1 |
| 3 | Hai engine Filament giữ khoảng 470 MB đồ họa khi app nằm nền | Trung bình | **Xong** (dòng 53) |
| 4 | Crash thẻ trạng thái khi cache vẽ dựng lại giữa đoạn mở màn (xoay máy, đổi kích thước, engine 3D làm lại) | Trung bình | **Xong** (dòng 53) |
| 5 | `libcamd.so` gitignore, mất là không lấy lại được | Trung bình | **Xong** (dòng 52) |
| 6 | Root service và AIDL chỉ có test JVM | Trung bình | **Xong** (dòng 51), 15 test trên máy |
| 7 | Chưa thử: cấp lại Widevine, OTA từ đầu đến cuối, thanh toán thật | Thấp | Còn mở. Cấp lại Widevine có sao lưu và tự khôi phục; OTA và thanh toán có test phía server |
| 8 | Cụm slime/3D lớn so với giá trị cốt lõi | Thấp | Có chủ đích; giờ nhả bộ nhớ khi nằm nền |
| 9 | File Miuix của upstream trên 1000 dòng | Thấp | Giữ nguyên để merge dễ |
| 10 | Kernel umount (code upstream): có thể gỡ nhầm nguyên phân vùng nếu metamodule đăng ký sai, log `pr_info` ở mỗi `setresuid`, không kiểm flag | Thấp–Trung bình | **Chưa làm** (review 2026-10-15): chờ kiểm tra trên máy, `superpowers/plans/2026-10-15-kernel-umount-review.md` |
| 11 | Ẩn SELinux (code upstream): kiểm hợp lệ nhầm `ctx`/`orig_ctx`, free trang status giả khi gỡ LKM, race khi tắt tính năng (gọi con trỏ NULL → panic) ở `write_op` và hook `status`, bỏ bản sao lưu lúc hook đang dùng, lộ qua netlink policyload, gọi hàm static qua kallsyms | Trung bình | **Xong** (dòng 56, 58, 59 của `CAM_CHANGES.md`): race tái hiện được trên tablet với LKM cũ, LKM `32981` qua stress (ghi selinuxfs + mở `status`); dò 8 đường từ ba domain app không lộ; netlink policyload gate khi ẩn chạy (`app_zygote` nghe được, đã đo); đường `access` bỏ kallsyms, dùng bản nội bộ (12 lớp khớp đường thật); `camd hiding-audit --apply` xử lý `-EAGAIN`. `superpowers/plans/done/2026-10-15-selinux-hide-review.md` |

## Việc nên làm tiếp, theo thứ tự

1. **Deploy API** (`site/scripts/deploy-api.sh`): migration 4 tự chạy, tài khoản cũ được coi là đã xác minh nên không ai mất quyền. Push `site/**` tự deploy giao diện; giao diện chỉ hiện nhắc xác minh khi API mới báo `verified: false`, nên thứ tự deploy nào cũng được.
2. **Một lượt OTA thật**: gắn tag beta, để máy của Cam nhận và cài qua OTA, ghi kết quả vào mục 6 của `CAM_CHANGES.md`.
3. **Thử cấp lại Widevine** trên một máy chịu được rủi ro (có sao lưu và tự khôi phục, nhưng xóa `/data/vendor/mediadrm` là thật).
4. **Kiểm tra kernel umount trên máy** (khoảng 10 phút, chỉ đọc; mục 10): xem hybrid_mount đăng ký gì và đo thời gian mở app, rồi mới quyết có sửa không. Các bước nằm trong `superpowers/plans/2026-10-15-kernel-umount-review.md`.

## Cách chấm

- 10 = dùng được trong sản xuất, kiểm chứng đủ, không có việc tồn đọng đáng kể. 7 = chạy tốt, có chỗ hổng đã biết. 5 = chạy được nhưng thiếu nền tảng để tin cậy.
- Điểm tổng là trung bình có trọng số: kiến trúc, kiểm thử và bảo mật tính gấp đôi, vì sai ở ba chỗ này thì máy người dùng chịu hậu quả.
- Đánh giá này không thay cho review bảo mật kernel độc lập; chưa đọc kỹ từng dòng của `kernel/` và `lkm_image.rs` (phần lớn là code upstream). Audit `site/api` ngày 2026-10-15 đọc toàn bộ route và phần xác thực, chưa gồm cấu hình Caddy trên VPS.
