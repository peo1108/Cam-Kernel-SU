# Câu hỏi thường gặp

## Dùng chung với KernelSU, Magisk, APatch được không?

Không. Mỗi lúc chỉ dùng một cách root. App SU Kernel cũng không làm việc với nhân KernelSU chính thức. Gỡ cách root cũ (flash lại ảnh gốc) trước khi cài.

## Máy tôi có dùng được không?

Cần nhân GKI 5.10 trở lên và bootloader đã mở khoá. Chạy `adb shell uname -r`: thấy `android12` tới `android17` cùng nhân 5.10 trở lên là được. Xem [Giới thiệu](./#may-ho-tro).

## Mở app thấy "chưa cài đặt" dù đã flash?

- Kiểm tra đã flash đúng phân vùng: máy xuất xưởng với Android 13 trở lên thường là `init_boot`, máy cũ là `boot`.
- Ảnh phải là ảnh gốc của **đúng** bản ROM đang chạy.
- App phải là bản chính thức tải từ trang [Tải về](/tai-ve). Bản tự build hoặc ký khoá khác không có root.

## App khác gọi `su` không được?

Lần khởi động đầu sau khi flash, mở SU Kernel một lần để app cài `/data/adb/camd`. Sau đó cấp root cho app trong tab Superuser hoặc bằng `camd allow add <gói>`.

## Cài module xong nhưng file không hiện trong /system?

Bạn chưa có metamodule. Cài **một** metamodule (Magic Mount-rs hoặc Hybrid Mount) từ [thư viện module](/module) rồi khởi động lại.

## App ngân hàng vẫn phát hiện?

1. Bật umount trong hồ sơ của app đó (không cấp root cho nó).
2. Mở trang [Kiểm tra ẩn root](./an-root), xem mục nào lộ và do module nào.
3. Nếu không còn gì lộ mà app vẫn từ chối, nhiều khả năng app kiểm tra bằng **key attestation**: chứng chỉ do chip bảo mật ký cho biết bootloader mở khoá, phần mềm không sửa được.

## Có cần flash lại mỗi khi cập nhật app không?

Không. App tự cập nhật. Chỉ khi màn chính hiện nút **Cài lại LKM** (phần nhân thật sự thay đổi) thì mới cần cài lại, và có thể dùng **Cài đặt trực tiếp** ngay trên máy.

## Mã nguồn?

Gửi yêu cầu ở trang [Mã nguồn](/ma-nguon).
