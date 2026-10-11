# Đánh giá dự án Cam Kernel SU

Chấm lại ngày 2026-10-12 trên `cam-v3.1.0` (`0cc55d7a`, 2836 commit). Lần chấm trước: 2026-10-09 trên `99e000ef`, được **7,1**. Số liệu bên dưới đo trực tiếp từ repo; điểm là nhận định, thang 10, chấm theo hiện trạng chứ không theo kế hoạch.

## Điểm tổng: 7,9 / 10 (trước: 7,1)

Nếu thử trên máy thật ở việc 1 bên dưới mà không phát hiện lỗi, mục "Kiểm chứng trên máy thật" lên khoảng 8 và điểm tổng lên khoảng 8,0.

| Hạng mục | Điểm | Trước | Lý do ngắn |
|---|---|---|---|
| Ý tưởng và phạm vi | 8,5 | 8,5 | Như cũ: bỏ "app manager đặc biệt" trong kernel, thêm tính năng upstream không có (chống bootloop, xung đột module, kiểm tra ẩn root, tự ẩn bootloader, OTA) |
| Kiến trúc | 8,5 | 8,5 | Ranh giới vẫn sạch: kernel chỉ nhận lệnh quản trị từ uid 0, Manager đi qua root service, UI chỉ gọi facade `Cam`. Ioctl riêng của Cam đánh số xa dãy của upstream |
| Chất lượng code | 7,5 | 7,0 | Đã tách các lệnh camd riêng của Cam ra khỏi `CamCli.kt` (từ 827 xuống 734 dòng, giờ gần như chỉ còn code của upstream); TODO còn 2 (đều của upstream). Cụm `Arena*`/slime vẫn có file trên 1000 dòng |
| Kiểm thử | 7,5 | 5,5 | CI chạy test trước mọi release: kernel 29 test (ASan + UBSan), camd 96 (94 chạy trên CI), Manager 131. Còn thiếu: không có test trên thiết bị (`androidTest`), CI chỉ build LKM chứ không nạp thử |
| CI và phát hành | 8,5 | 7,0 | Release theo tag `cam-v*`, có test chặn trước khi build, kênh beta, tag bất biến và chỉ admin tạo được; 15 workflow. Còn mã chết `expected_size`/`expected_hash` |
| Tài liệu | 8,5 | 8,0 | `CAM_CHANGES.md` (742 dòng) đã sạch tên cũ ở mục 1 và 3; plan đã xong chuyển vào `plans/done/`; README dịch và `SECURITY.md` của KernelSU đã thay |
| Bảo mật | 8,0 | 6,5 | Ghim root cho Manager theo chứng chỉ ký mà PackageManager đã kiểm (không đọc APK), có khóa dự phòng cất ngoại tuyến; khóa đã sao lưu ra ngoài máy; rule ẩn root ký ECDSA |
| Khả năng merge upstream | 7,5 | 7,5 | Có script đổi tên, danh sách file tương thích; bản dịch ngoài en/vi giữ nguyên như upstream nên không conflict. Chi phí vẫn cao ở các file Miuix |
| Kiểm chứng trên máy thật | 6,5 | 6,5 | Vẫn 1-2 máy. Ba thay đổi của 3.1.0 chưa thử trên máy (probe AVC, nút cài lại LKM theo source id, kênh beta) |
| Vệ sinh repo | 8,0 | 6,5 | File riêng (`sfs/`, ảnh, bản sao lưu khóa `cam-signing-keys-*.enc`) đã vào `.gitignore`; junction `cpp/uapi` vẫn làm `git status` luôn hiện một dòng |

Cách tính: trung bình có trọng số, kiến trúc, kiểm thử và bảo mật tính gấp đôi: (8,5 + 2×8,5 + 7,5 + 2×7,5 + 8,5 + 8,5 + 2×8,0 + 7,5 + 6,5 + 8,0) / 13 = 7,9.

## Số liệu

| Phần | File | Dòng |
|---|---|---|
| `kernel/` (C, không tính `tests/`) | 79 | 14.058 |
| `userspace/camd/src` (Rust) | 37 | 17.096 |
| `userspace/caminit/src` (Rust) | 3 | 645 |
| `manager/app/src/main/java` (Kotlin) | 280 | 52.880 |
| `manager/app/src/main/cpp` (JNI, probe) | 6 | 1.067 |

File lớn nhất của Cam: `ArenaShow.kt` (1332 dòng), `RoamWorld.kt` (1162). `ModuleRepoMiuix.kt` (1178) và `ModuleMiuix.kt` (1021) là file upstream có sửa.

## Kết quả test (2026-10-12)

| Phần | Lệnh | Kết quả |
|---|---|---|
| Kernel (WSL) | `make -C kernel/tests` | **29 đạt** (ASan + UBSan) |
| camd (WSL) | `cargo test --target x86_64-unknown-linux-gnu` | 96 test: **94 đạt, 2 fail sẵn** (`embedded_module_uses_release_asset_layout` cần `.ko` chỉ có trên CI; `rejects_conflicting_loading_module_values` lỗi thời từ upstream). CI bỏ qua đúng hai test này |
| camd | `cargo ndk -t arm64-v8a check` / `clippy` / `cargo fmt` | Sạch |
| Manager (ổ `K:`) | `gradlew :app:testDebugUnitTest` | 24 lớp, **131 đạt** |
| CI | Release `cam-v3.1.0` | Xanh: test 4 nhóm, 8 KMI × 2 kiến trúc LKM, camd, caminit, APK |
| `androidTest` | | Không có |

## Điểm yếu và rủi ro

| # | Vấn đề | Mức | Tình trạng |
|---|---|---|---|
| 1 | CI không chạy test | Cao | **Xong** (`test.yml`, và Release chạy toàn bộ test trước khi build) |
| 2 | Kernel không có test trong repo | Cao | **Xong** (`kernel/tests`, 29 test) |
| 3 | Ghim root theo tên gói `cam.su.kernel` | Trung bình | **Xong** (2026-10-11: ghim theo chứng chỉ ký trong `packages.xml`, dòng 36 mục 1 của `CAM_CHANGES.md`) |
| 4 | Khóa ký chỉ ở một máy | Trung bình | **Xong** (`scripts/backup_signing_keys.sh`, bản mã hóa đã chép ra ngoài; khóa dự phòng `KSU_MANAGER_CERT_BACKUP`) |
| 5 | Tính năng mới chưa thử trên máy | Trung bình | **Còn mở.** Trang Kiểm tra ẩn root đã thử một phần; 3.1.0 thêm ba thứ chưa thử (xem việc 1) |
| 6 | Cụm slime/3D lớn so với giá trị cốt lõi | Trung bình | Còn mở, có chủ đích. Code riêng của Cam nên không làm tăng chi phí merge; tách file `Arena*` để sau |
| 7 | Tài liệu, website, bản dịch còn của KernelSU | Thấp | **Xong** phần README và `SECURITY.md`; website chỉ deploy tay. Bản dịch: APK chỉ đóng gói en/vi (`localeFilters`), thư mục dịch khác giữ nguyên cho dễ merge |
| 8 | Repo bẩn | Thấp | **Xong** |
| 9 | TODO và mã dở | Thấp | **Xong** phần của Cam; còn 2 TODO của upstream không hiện ra giao diện |
| 10 | Mã chết trong CI (`expected_size`/`expected_hash`) | Thấp | Còn mở |
| 11 | Lộ timing `attr/current` | Cao | **Xong** (`a3803700`), có probe timing và từ 3.1.0 thêm probe đếm AVC lookup làm test hồi quy |

## Việc nên làm tiếp, theo thứ tự

1. **Thử 3.1.0 trên máy thật**: cài lại LKM một lần; mở trang chủ sau một bản cập nhật chỉ đổi app để chắc nút "Cài lại LKM" không hiện; chạy `camd debug avc-lookup` (mong đợi `A/B = 1/1`; nếu app_zygote không đọc được `cache_stats` thì chỉ có timing); bật kênh beta và thử một tag `cam-vX.Y.Z-beta.N`. Ghi kết quả vào mục 6 của `CAM_CHANGES.md`.
2. **Bỏ mã chết `expected_size`/`expected_hash`** trong `build-manager.yml`, `build-lkm.yml`, `ddk-lkm.yml` (kernel không đọc nữa).
3. **Một test trên thiết bị tối thiểu** (hoặc script `adb` chạy tay): mở app, gọi `Cam.version`, chạy `camd hiding-audit`, so JSON. Đây là chỗ CI chưa phủ.
4. **Tách các file `Arena*`/`slime`** khi có dịp sửa tới (không gấp).

## Cách chấm

- 10 = dùng được trong sản xuất, kiểm chứng đủ, không có việc tồn đọng đáng kể. 7 = chạy tốt, có chỗ hổng đã biết. 5 = chạy được nhưng thiếu nền tảng để tin cậy.
- Điểm tổng là trung bình có trọng số: kiến trúc, kiểm thử và bảo mật tính gấp đôi, vì sai ở ba chỗ này thì máy người dùng chịu hậu quả.
- Đánh giá này không thay cho review bảo mật kernel độc lập; chưa đọc kỹ từng dòng của `kernel/` và `lkm_image.rs` (phần lớn là code upstream).
