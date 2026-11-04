# Plan (đã làm): Manager thành gói `cam.caesar.vn`, tên app `Cam`

Bản nháp đầu chỉ đổi applicationId và giữ namespace code. Người dùng chọn đổi hết, từ code
tới tên hiển thị, nên bản làm thật đổi cả namespace, thư mục nguồn, JNI, kernel, camd, script,
CI và trang web.

Lý do, những gì đã đổi, những gì giữ (khoá ký) và cách chuyển máy đang dùng app cũ: mục 11
"Đổi gói lần hai" và dòng 83 của `docs/CAM_CHANGES.md`.

## Kiểm tra

- `make -C kernel/tests`; camd `cargo ndk -t arm64-v8a check`/`clippy`, `cargo fmt`, host test;
  `./gradlew :app:testDebugUnitTest`; `node --test` của `site/api`.
- Trên TB323FU (chưa làm): làm đúng các bước chuyển máy ở mục 11, rồi Luna không còn
  "Risk applications", `device_smoke.sh`, boot guard, Duck.
