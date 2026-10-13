# Đánh giá dự án Cam Kernel SU

Chấm lại ngày 2026-10-13 trên `fe4b7b7f` (sau `cam-v1.1.5-beta.1`, 2875 commit), cộng phần bỏ Chặn RKP chưa commit. Lần chấm trước: 2026-10-12 trên `cam-v3.1.0` (trước khi đánh số lại thành 1.1.x), được **7,9**. Số liệu bên dưới đo trực tiếp từ repo; điểm là nhận định, thang 10, chấm theo hiện trạng chứ không theo kế hoạch.

## Điểm tổng: 7,9 / 10 (trước: 7,9)

Điểm không đổi: việc tồn đọng đã đóng hết, nhưng phần mới từ 1.1.1 đến 1.1.5 (tài khoản, thanh toán, OTA qua caesarvn.xyz, shell adb-only) vẫn chưa có bằng chứng chạy trên máy. Smoke test đã đạt 14/14 trên máy; thêm thử shell adb-only và luồng tài khoản trên máy thì mục "Kiểm chứng trên máy thật" lên khoảng 7,5 và điểm tổng lên khoảng 8,0.

| Hạng mục | Điểm | Trước | Lý do ngắn |
|---|---|---|---|
| Ý tưởng và phạm vi | 8,5 | 8,5 | Như cũ. Bỏ Chặn RKP: tính năng cắt mạng cả máy để che một tín hiệu đo, rủi ro cao hơn giá trị |
| Kiến trúc | 8,5 | 8,5 | Ranh giới vẫn sạch: kernel chỉ nhận lệnh quản trị từ uid 0, Manager đi qua root service, UI chỉ gọi facade `Cam`. Feature mới của upstream (`shell_su_adb_only`) vào không conflict |
| Chất lượng code | 7,5 | 7,5 | Lệnh camd riêng của Cam vẫn nằm ngoài `CamCli.kt`. TODO: 7, đều của upstream (5 kernel, 2 Manager). Cụm `Arena*`/slime vẫn có file trên 1000 dòng |
| Kiểm thử | 7,5 | 7,5 | Kernel 29 test (ASan + UBSan), camd 96 (94 chạy trên CI), Manager 241 (tab Tệp có thêm `DeviceCheck` chạy trên máy). Có thêm smoke test chạy tay trên máy (14/14 đạt trên TB323FU); chưa có `androidTest` |
| CI và phát hành | 8,5 | 8,5 | Release theo tag `cam-v*`, test chặn trước khi build, kênh beta, tag bất biến; 16 workflow. `expected_size`/`expected_hash` giữ có chủ đích (upstream vẫn dùng) |
| Tài liệu | 8,5 | 8,5 | `CAM_CHANGES.md` đồng bộ tới merge `8a8058d9`, ghi rõ phần dọn dẹp của Chặn RKP; ví dụ phát hành đã theo số 1.1.x |
| Bảo mật | 8,0 | 8,0 | Như cũ (ghim Manager theo chứng chỉ trong `packages.xml`, khóa dự phòng, rule ký ECDSA). Thêm tuỳ chọn upstream chặn app chiếm quyền shell leo lên root (mặc định tắt) |
| Khả năng merge upstream | 7,5 | 7,5 | Merge 2026-10-13 không conflict. Chi phí vẫn cao ở các file Miuix |
| Kiểm chứng trên máy thật | 6,5 | 6,5 | Vẫn 1-2 máy. Chưa thử trên máy: shell adb-only, "Chặn module kernel" phải tick, bước dọn Chặn RKP, cùng ba thứ của 3.1.0 |
| Vệ sinh repo | 8,0 | 8,0 | `log/` (dump RKP) đã xoá. Junction `cpp/uapi` vẫn làm `git status` luôn hiện một dòng |

Cách tính: trung bình có trọng số, kiến trúc, kiểm thử và bảo mật tính gấp đôi: (8,5 + 2×8,5 + 7,5 + 2×7,5 + 8,5 + 8,5 + 2×8,0 + 7,5 + 6,5 + 8,0) / 13 = 7,9.

## Số liệu

| Phần | File | Dòng |
|---|---|---|
| `kernel/` (C, không tính `tests/`) | 81 | 14.200 |
| `userspace/camd/src` (Rust) | 37 | 17.140 |
| `userspace/caminit/src` (Rust) | 3 | 645 |
| `manager/app/src/main/java` (Kotlin) | 283 | 54.129 |
| `manager/app/src/main/cpp` (JNI, probe) | 6 | 1.067 |

File lớn nhất của Cam: `ArenaShow.kt` (1332 dòng), `RoamWorld.kt` (1162). `ModuleRepoMiuix.kt` (1178) và `ModuleMiuix.kt` là file upstream có sửa.

## Kết quả test (2026-10-13)

| Phần | Lệnh | Kết quả |
|---|---|---|
| Kernel (WSL) | `make -C kernel/tests` | **29 đạt** (ASan + UBSan) |
| camd (WSL) | `cargo test --target x86_64-unknown-linux-gnu` | 96 test: **94 đạt, 2 fail sẵn** (`embedded_module_uses_release_asset_layout` cần `.ko` chỉ có trên CI; `rejects_conflicting_loading_module_values` lỗi thời từ upstream). CI bỏ qua đúng hai test này |
| camd | `cargo ndk -t arm64-v8a check` / `clippy` / `cargo fmt` | Sạch |
| Manager (ổ `K:`) | `gradlew :app:testDebugUnitTest` | 35 lớp, **241 đạt** (thêm tab Tệp) |
| Trên máy (TB323FU, Android 16) | `scripts/device_smoke.sh` | **14/14 đạt** trên APK CI `1825927c` |
| `androidTest` | | Không có |

## Điểm yếu và rủi ro

| # | Vấn đề | Mức | Tình trạng |
|---|---|---|---|
| 1 | CI không chạy test | Cao | **Xong** (`test.yml`, và Release chạy toàn bộ test trước khi build) |
| 2 | Kernel không có test trong repo | Cao | **Xong** (`kernel/tests`, 29 test) |
| 3 | Ghim root theo tên gói `cam.su.kernel` | Trung bình | **Xong** (ghim theo chứng chỉ ký trong `packages.xml`, dòng 36 mục 1 của `CAM_CHANGES.md`) |
| 4 | Khóa ký chỉ ở một máy | Trung bình | **Xong** (`scripts/backup_signing_keys.sh`, khóa dự phòng `KSU_MANAGER_CERT_BACKUP`) |
| 5 | Tính năng mới chưa thử trên máy | Trung bình | **Một phần.** `scripts/device_smoke.sh` 14/14 trên TB323FU (2026-10-13); còn thiếu thử bật shell adb-only, bước dọn Chặn RKP khi có cờ, và luồng tài khoản/thanh toán |
| 6 | Cụm slime/3D lớn so với giá trị cốt lõi | Trung bình | Còn mở, có chủ đích. Code riêng của Cam nên không làm tăng chi phí merge; tách file `Arena*` khi có dịp sửa |
| 7 | Tài liệu, website, bản dịch còn của KernelSU | Thấp | **Xong** phần README và `SECURITY.md`; APK chỉ đóng gói en/vi |
| 8 | Repo bẩn | Thấp | **Xong** |
| 9 | TODO và mã dở | Thấp | **Xong** phần của Cam; 7 TODO còn lại đều của upstream |
| 10 | Mã chết trong CI (`expected_size`/`expected_hash`) | Thấp | **Đóng, giữ có chủ đích**: upstream vẫn dùng ở cả ba workflow và `Kbuild`, gỡ ra chỉ thêm conflict (mục 7 của `CAM_CHANGES.md`) |
| 11 | Lộ timing `attr/current` | Cao | **Xong** (`a3803700`), có probe timing và probe đếm AVC lookup làm test hồi quy |
| 12 | Chặn RKP: luật DROP có thể kẹt, cắt mạng cả máy | Trung bình | **Xong**: bỏ tính năng (2026-10-13); `legacy::remove_block_rkp` dọn cờ và luật trên máy từng bật |

## Việc nên làm tiếp, theo thứ tự

1. **Thử bằng tay phần smoke test không phủ**: bật "Chỉ cho Shell lấy root qua ADB" rồi `adb shell su -c id` vẫn ra uid 0; trên một máy từng bật Chặn RKP, cập nhật rồi mở app, mục cuối của smoke test phải PASS. Ghi kết quả vào mục 6 của `CAM_CHANGES.md`.
2. **Gắn tag `cam-v1.1.5`** lên `main` (đã sau `cam-v1.1.5-beta.1`, mục 8 của `CAM_CHANGES.md`).
3. **Tách các file `Arena*`/`slime`** khi có dịp sửa tới (không gấp).

## Cách chấm

- 10 = dùng được trong sản xuất, kiểm chứng đủ, không có việc tồn đọng đáng kể. 7 = chạy tốt, có chỗ hổng đã biết. 5 = chạy được nhưng thiếu nền tảng để tin cậy.
- Điểm tổng là trung bình có trọng số: kiến trúc, kiểm thử và bảo mật tính gấp đôi, vì sai ở ba chỗ này thì máy người dùng chịu hậu quả.
- Đánh giá này không thay cho review bảo mật kernel độc lập; chưa đọc kỹ từng dòng của `kernel/` và `lkm_image.rs` (phần lớn là code upstream).
