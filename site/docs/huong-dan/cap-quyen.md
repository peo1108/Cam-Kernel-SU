# Cấp quyền root

## Trong app

Mở tab **Superuser**, chọn app rồi bật root. Mỗi app có một **hồ sơ ứng dụng** riêng:

- **Có root:** chọn UID, nhóm, capability và SELinux context mà app nhận khi gọi `su`. Để mặc định là đủ cho hầu hết app.
- **Không root:** quyết định có **gỡ mount module** (umount) cho app đó hay không. Bật umount để app ngân hàng, game không thấy file của module.

Trang hồ sơ còn có thẻ **Thông tin** (đường dẫn, chạm để sao chép), **Dung lượng** và **Quản lý**: sao lưu APK, xoá cache hoặc dữ liệu, đóng băng, gỡ cài đặt (kể cả app hệ thống).

## Từ adb hoặc Termux

Không cần mở app, chạy từ một shell đã có root:

```sh
camd allow add com.termux        # cấp root
camd allow remove com.termux     # thu root
camd allow list                  # các app đang có root
```

## Lúc vá ảnh boot

Hộp **Ứng dụng được cấp root** khi cài cho phép chọn sẵn app có root ngay lần khởi động đầu, không cần mở app quản lý (xem [Cài đặt](./cai-dat#cai-lan-dau-va-anh-boot)).

## Root của chính SU Kernel

Nhân luôn cấp root cho gói `cam.su.kernel`, **chỉ khi** app được ký bằng khoá chính thức của dự án. Nhân đọc chữ ký mà Android đã kiểm lúc cài (`/data/system/packages.xml`), không tự mở file APK.

- Gỡ rồi cài lại app không mất root.
- App giả đặt trùng tên gói không lấy được root; nếu nó nhận lại UID cũ đang có root thì bị thu lại.
- Bản tự build hoặc ký bằng khoá khác cũng **không** có root.

::: info Vì sao không thu được root của SU Kernel
Nhân dò lại UID của app quản lý mỗi khi danh sách gói thay đổi và cấp lại root. Đây là chủ ý, để không bao giờ bị khoá ngoài.
:::
