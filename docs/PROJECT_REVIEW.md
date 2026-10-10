# Đánh giá dự án Cam Kernel SU

Chấm ngày 2026-10-09 trên commit `99e000ef` (`v3.3.0-174`, 2775 commit). Số liệu bên dưới đo trực tiếp từ repo; điểm là nhận định, thang 10, chấm theo hiện trạng chứ không theo kế hoạch.

## Điểm tổng: 7,1 / 10

| Hạng mục | Điểm | Lý do ngắn |
|---|---|---|
| Ý tưởng và phạm vi | 8,5 | Hướng đi rõ: bỏ "app manager đặc biệt" trong kernel, thêm các tính năng upstream không có (chống bootloop, xung đột module, kiểm tra ẩn root) |
| Kiến trúc | 8,5 | Ranh giới sạch: kernel chỉ nhận lệnh quản trị từ uid 0, Manager đi qua root service, UI chỉ gọi facade `Cam` |
| Chất lượng code | 7,0 | Phần thuần có tách ra để test; nhưng nhiều file rất lớn và còn vài TODO |
| Kiểm thử | 5,5 | 145 test đạt, nhưng kernel không có test và CI không chạy test nào |
| CI và phát hành | 7,0 | 12 workflow, build 8 KMI, ký bằng khóa riêng, Release theo tag; thiếu bước test |
| Tài liệu | 8,0 | `CAM_CHANGES.md` rất đầy đủ, có quy trình merge kèm lệnh kiểm tra; bản dịch README, website, SECURITY còn là của KernelSU |
| Bảo mật | 6,5 | Mô hình uid 0 và rule có chữ ký tốt; ghim root theo tên gói và khóa chỉ ở một máy là điểm yếu có chủ đích |
| Khả năng merge upstream | 7,5 | Có script đổi tên đã kiểm chứng, danh sách file tương thích; chi phí merge vẫn cao vì Cam sửa nhiều file Miuix |
| Kiểm chứng trên máy thật | 6,5 | Mới có 1-2 máy (Y700 Gen5, TB323FU); tính năng mới nhất chưa quét thật |
| Vệ sinh repo | 6,5 | 207 MB `sfs/` và file lạ chưa vào `.gitignore`; junction `cpp/uapi` làm `git status` luôn bẩn |

## Số liệu

| Phần | File | Dòng (C/Rust/Kotlin/…) |
|---|---|---|
| `kernel/` (C) | 89 | 13.152 |
| `userspace/camd` (Rust) | 48 | 16.291 |
| `userspace/caminit` (Rust) | 5 | 656 |
| `manager/app/src/main/java` (Kotlin) | 266 | 51.156 |
| `manager/app/src/main/cpp` (JNI, probe) | 8 | 968 |
| `website/` | 157 | 18.695 (chưa đổi thương hiệu) |
| `docs/` | 22 | 2.827 |

Manager chiếm phần lớn mã (hơn một nửa dòng code của dự án), trong đó cụm slime/3D (`ui/screen/home/arena/`, `ui/slime/`) là phần lớn nhất. File lớn nhất ngoài code upstream: `ArenaShow.kt` (1332 dòng), `RoamWorld.kt` (1162), `ModuleMiuix.kt` (1021).

## Kết quả test (chạy 2026-10-09)

| Phần | Lệnh | Kết quả |
|---|---|---|
| camd (WSL) | `cargo test --target x86_64-unknown-linux-gnu` | 79 test: **77 đạt, 2 fail** (`lkm_image::tests::embedded_module_uses_release_asset_layout` thiếu `.ko` chỉ có trên CI; `lkm_image_btf::tests::rejects_conflicting_loading_module_values`). Cả hai đã fail từ trước, không do Cam |
| Manager (Windows, ổ `K:`) | `gradlew :app:testDebugUnitTest` | 14 lớp, **68 test đạt** |
| Kernel | | Chưa có test trong repo |
| `androidTest` | | Không có |

Chưa chạy: `cargo ndk check/clippy`, build Gradle release, build LKM. Hai test camd fail là lệch giữa môi trường cục bộ và CI, nhưng đang làm `cargo test` không bao giờ xanh nên dễ che lỗi thật.

## Điểm mạnh

1. **Thiết kế kernel gọn.** Bỏ hẳn việc quét `/data/app` và kiểm tra chữ ký APK; "manager" = uid 0. Giảm bề mặt tấn công trong kernel, và Manager chỉ là một app root bình thường.
2. **Giữ được khả năng merge.** Kernel giữ tên `ksu_*` có chủ đích; phần đổi tên ở userspace/Manager có `scripts/cam_rename.sed` được thử lại (chạy trên code trước đổi tên ra giống hệt code sau đổi tên ở 341 file). Upstream hiện không có commit nào mới hơn.
3. **Tính năng thực dụng, có phần thuần để test.** `boot_guard.rs`, `module_conflicts.rs`, `hide_bootloader.rs`, `hiding_audit.rs`, `KeyAttestation.kt`, `Revocation.kt` tách logic khỏi phần Android nên test được trên máy tính.
4. **Rule phát hiện có chữ ký.** ECDSA P-256, chỉ nhận dữ liệu, không tải code, tự kiểm `version`; có test kiểm chữ ký trên chính file trong `assets/`.
5. **Tài liệu đã đủ để quay lại sau vài tháng.** Mục 4 của `CAM_CHANGES.md` là quy trình merge từng bước, kèm lệnh `rg` cho biết merge sai ở đâu; mục 5 liệt kê các bẫy build trên Windows đã gặp thật.
6. **Ghi nhận trung thực những gì chưa thử** (mục 6, 7 của `CAM_CHANGES.md`), không tô hồng.

## Điểm yếu và rủi ro

| # | Vấn đề | Mức | Chi tiết |
|---|---|---|---|
| 1 | **CI không chạy test** | Cao | `build-manager.yml` chỉ `assembleRelease`. Sửa `hiding-rules.json` mà quên ký lại thì CI vẫn xanh, app ra bản không nhận rule. Không có `cargo test` hay `testDebugUnitTest` trong bất kỳ workflow nào |
| 2 | **Kernel không có test trong repo** | Cao | Phần seed, pin Manager, prune allowlist (code Cam tự viết, chạy ở ring 0) chỉ có harness nằm ngoài repo (scratchpad, 11 test). Lỗi ở đây là panic hoặc cấp root nhầm |
| 3 | **Ghim root theo tên gói** `cam.su.kernel` | Trung bình | Đánh đổi có chủ đích (mục 7): app khác đặt trùng tên gói sẽ có root. Nếu máy chưa cài Manager, ai cài trước app trùng tên sẽ chiếm quyền. Nên kèm kiểm tra chữ ký ở userspace hoặc ghim UID đã thấy lần đầu |
| 4 | **Khóa ký chỉ ở một máy** | Trung bình | `su-kernel-release.jks` và `hiding-rules-key.pem` nằm trong thư mục người dùng. Mất khóa APK thì không cập nhật đè được; mất khóa rule thì phải ra bản app mới. Chưa có nơi sao lưu thứ hai được ghi lại |
| 5 | **Kiểm tra ẩn root chưa được thử thật** | Trung bình | Mới cài và mở không crash. Chưa quét thật, chưa thử góc nhìn app, tắt module, rule tải về. Đây là tính năng lớn nhất của đợt gần đây |
| 6 | **Cụm slime/3D lớn so với giá trị cốt lõi** | Trung bình | Hơn 10 file `Arena*` và `slime/*`, hai engine Filament riêng, một file `.filamat` biên dịch sẵn gắn chặt với phiên bản 1.77.1. Mỗi lần nâng Filament hoặc đổi Compose đều phải mở lại phần này. Vẫn là điểm nhận diện của app |
| 7 | **Tài liệu bản dịch, website, SECURITY còn là của KernelSU** | Thấp | 15 `docs/README_*.md`, 157 file `website/`, `SECURITY.md` (liên hệ weishu). Người ngoài vào repo sẽ thấy hai dự án lẫn lộn. `deploy-website.yml` còn hoạt động |
| 8 | **Repo bẩn** | Thấp | `sfs/` 207 MB, `IMG_7454.PNG`, `logo mẫu/` chưa vào `.gitignore`; `AGENTS.md` nằm trong `.gitignore` nhưng đang được theo dõi |
| 9 | **TODO và mã dở** | Thấp | `UninstallDialogMiuix.kt:47` hiện Toast "TODO"; `BaseFieldFilter.kt:19`; kernel còn 5 TODO (phần lớn của upstream). `build_lkm_camd.sh` mặc định build nhánh `feat/managerless-seed` đã cũ |
| 10 | **Mã chết trong CI** | Thấp | `expected_size` / `expected_hash` vẫn được tính và truyền xuống dù kernel không đọc nữa |
| 11 | **Kernel Cam lộ qua timing `attr/current`** | Cao | Probe thử nghiệm của Duck Detector (`b77fef8d`) thấy chênh lệch thời gian khi ghi vào `/proc/thread-self/attr/current` trên máy chạy kernel Cam. Nhắm đúng phần SELinux hide. Chưa rõ nguyên nhân; chi tiết ở mục 12 của `CAM_CHANGES.md` |

## Việc nên làm tiếp, theo thứ tự

1. **Thêm job test vào CI** (nửa ngày): `cargo test --target x86_64-unknown-linux-gnu` cho camd (bỏ hoặc đánh dấu `#[ignore]` hai test fail) và `gradlew :app:testDebugUnitTest` cho Manager. Làm xong thì mục 1 xong.
2. **Đưa harness test kernel vào repo** (`kernel/tests/` hoặc `scripts/`), chạy dưới ASan trong CI. Xong thì mục 2 xong.
3. **Thử thật trang Kiểm tra ẩn root** trên máy đã root: quét, kiểm tra từng app, tắt module, tải rule. Sửa chỗ nào sai rồi ghi vào mục 6 của `CAM_CHANGES.md`.
4. **Sao lưu hai khóa ký** sang nơi thứ hai (không phải trong repo) và ghi chỗ cất vào mục 8.
5. **Thêm `sfs/`, `IMG_7454.PNG` vào `.gitignore`** (hoặc chuyển ra ngoài repo), sửa mặc định nhánh trong `build_lkm_camd.sh` thành `main`, sửa hai TODO còn hiện ra giao diện.
6. **Quyết định số phận tài liệu của KernelSU:** thay `docs/README*.md` bằng một bản của Cam (hoặc xoá 15 bản dịch), viết lại `SECURITY.md`, tắt hoặc đổi `deploy-website.yml`.
7. **Cân nhắc siết ghim root cho Manager** (mục 3 trong bảng trên).
8. **Sửa lộ SELinux theo timing** (mục 11 trong bảng): tìm đường nào của kernel Cam làm chậm lần ghi context hợp lệ, sửa, rồi port probe vào trang Kiểm tra để có test hồi quy. Làm tiếp sau khi CI build xong.

## Cách chấm

- 10 = dùng được trong sản xuất, kiểm chứng đủ, không có việc tồn đọng đáng kể. 7 = chạy tốt, có chỗ hổng đã biết. 5 = chạy được nhưng thiếu nền tảng để tin cậy.
- Điểm tổng là trung bình có trọng số: kiến trúc, kiểm thử và bảo mật tính gấp đôi (trung bình thường ra 7,15), vì sai ở ba chỗ này thì máy người dùng chịu hậu quả.
- Đánh giá này không thay cho review bảo mật kernel độc lập; chưa đọc kỹ từng dòng của `kernel/` và `lkm_image.rs` (phần phần lớn là code upstream).
