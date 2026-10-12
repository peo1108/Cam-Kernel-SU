# Module và metamodule

Module là file ZIP thêm hoặc thay file hệ thống, chạy script lúc khởi động, hoặc có giao diện web riêng (WebUI). Cài trong tab **Module** của app: chạm nút cài và chọn file ZIP, hoặc tải từ **Thư viện Module**.

## Metamodule

Cam Kernel SU (giống KernelSU) **không tự mount** module vào `/system`. Việc đó do một **metamodule** đảm nhận: một module đặc biệt có `metamodule=1` trong `module.prop`.

- Chỉ cài **một** metamodule. camd chỉ cho một cái hoạt động và tạo liên kết `/data/adb/metamodule`.
- Không có metamodule thì module vẫn chạy script, nhưng file của module không hiện lên trong `/system`.
- Hai lựa chọn có sẵn trong [thư viện](/module): **Magic Mount-rs** hoặc **Hybrid Mount**. Chọn một, không cài cả hai.

App tô nổi metamodule và cảnh báo khi bạn gỡ nó.

## Thư viện module

[Thư viện module](/module) là danh sách chọn lọc: metamodule để mount, Zygisk (Zygisk Next hoặc ReZygisk, chọn một), Play Integrity Fix, bindhosts... Cùng danh sách với mục **Thư viện Module** trong app.

## Lưu ý khi viết module

- Đường dẫn dữ liệu là `/data/adb/cam`. Module ghi cứng `/data/adb/ksu/...` hoặc `/data/adb/ksud` sẽ lỗi.
- Biến môi trường có cả `KSU*` và `CAM_*`; WebUI có cả bridge `ksu` và `cam`. Module viết theo chuẩn KernelSU vẫn chạy.
- `sepolicy.rule` nhắc tới domain `ksu` chỉ áp cho `ksu`, không áp cho domain `cam`.

## Khi module gây lỗi

- **Bootloop:** xem [Chống bootloop](./tinh-nang#chong-bootloop).
- **Module này làm hỏng module kia:** xem [Phát hiện xung đột](./tinh-nang#phat-hien-xung-dot-module).
- **Cập nhật module bị lỗi:** app giữ bản cũ trước khi cập nhật. Thẻ module có nhãn **Bản trước: (phiên bản)**, chạm để quay lại (hoặc `camd module restore <id>`).
- **Module làm lộ root:** sau khi cài hoặc cập nhật module, lần khởi động đầu app tự quét và báo module nào làm lộ gì ([Kiểm tra ẩn root](./an-root)).
