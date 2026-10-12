# Giới thiệu

**Cam Kernel SU** đặt root vào trong nhân Android. App đi kèm tên **SU Kernel**.

## Trong nhân

Phần cấp quyền là một module nhân (`camsu.ko`) nạp từ ảnh `init_boot` hoặc `boot`.

- `/system` không bị sửa. Module của bạn được mount lúc khởi động.
- Nhân quyết định app nào có root, theo **hồ sơ ứng dụng** bạn đặt.
- App không có root thì không thấy module.

## Có gì

- Giao diện kính, slime đi dạo khắp app.
- [Chống bootloop](./tinh-nang#chong-bootloop) và [phát hiện xung đột module](./tinh-nang#phat-hien-xung-dot-module).
- [Tự ẩn bootloader](./tinh-nang#tu-an-bootloader).
- [Kiểm tra ẩn root](./an-root): nhìn máy bằng mắt một app thường.
- Tự cập nhật.

Daemon là `camd`, dữ liệu nằm ở `/data/adb/cam`. Module viết theo chuẩn KernelSU vẫn chạy.

::: warning Một cách root mỗi lúc
Gỡ KernelSU, Magisk hay APatch trước khi cài.
:::

## Máy hỗ trợ

- Nhân **GKI 5.10 trở lên**: android12-5.10, android13-5.10, android13-5.15, android14-5.15, android14-6.1, android15-6.6, android16-6.12, android17-6.18.
- `aarch64` và `x86_64`.
- Bootloader đã mở khoá.

Tiếp theo: [Cài đặt](./cai-dat).
