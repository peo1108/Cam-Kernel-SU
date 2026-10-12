# Kiểm tra ẩn root

Trang **Kiểm tra ẩn root** (mở từ tab Tính năng, hoặc từ hồ sơ của từng app) cho bạn thấy app ngân hàng hay game nhìn máy mình ra sao, và chỉ ra **module nào** làm lộ.

App chỉ quét khi bạn bấm, không chạy nền. Mỗi lần quét được so với lần trước: **Mới**, **Như cũ**, **Đã ẩn**.

## Ba góc nhìn

1. **Từ root** (`camd hiding-audit`): mount lạ, đường dẫn `su`, prop, SELinux, hồ sơ ứng dụng... kèm module gây ra từng mục.
2. **Từ một app thường:** quét trong một tiến trình cô lập (isolated process) mà nhân luôn gỡ mount module cho nó. Đọc file bằng syscall thô rồi so với libc để bắt module hook libc. Đây là cái app không root thật sự thấy.
3. **Theo từng app:** từ hồ sơ ứng dụng, xem qua tiến trình đang chạy của chính app đó (app phải đang mở).

Trang còn có mục **Đã ẩn tốt**, kiểm tra **hồ sơ tắt umount** (app không root mà nhân vẫn không gỡ mount module), và **chứng thực TEE** để giải thích vì sao app vẫn từ chối khi không còn gì lộ.

## Sửa nhanh

Công tắc **Sửa nhanh** bật cùng lúc: umount của nhân, ẩn SELinux, ẩn bootloader. Mục nào do module gây ra thì có nút tắt module đó.

## Kiểm tra tự động sau khi đổi module

Module chỉ mount từ lần khởi động sau, nên lần khởi động đầu sau khi cài, cập nhật hoặc bật lại module, app tự quét một lần và báo module nào làm lộ gì. Chạm thông báo để mở trang và quét lại. Công tắc ở mục **Tự động** của trang (mặc định bật).

## Rule phát hiện

Danh sách dấu hiệu nằm trong một file rule **có chữ ký** (ECDSA P-256). Mở trang thì app tải bản mới tối đa 6 giờ một lần, chỉ nhận khi đúng chữ ký và số phiên bản mới hơn. Rule chỉ là dữ liệu, không tải code.

Rule đi theo [Duck Detector](https://github.com/eltavine/Duck-Detector-Refactoring), một app phát hiện root phổ biến; trang cho biết bản rule đang theo commit nào của Duck Detector.

## Đo thời gian SELinux

Một số app đo thời gian ghi `/proc/self/attr/current` để đoán có root. Nhân của Cam Kernel SU kiểm quyền trước rồi mới xử lý, giống SELinux gốc, nên không còn chênh lệch. Trang có probe đo giống Duck Detector để bạn tự kiểm tra (kết quả có thể khác khi máy đang bận).

::: info Giới hạn
- Kiểm tra từng app chỉ chạy khi app đó đang mở.
- Tiến trình cô lập luôn được gỡ mount, nên nó đại diện cho app **không** root, không phải app đã được cấp root.
- Key attestation không ẩn được bằng phần mềm.
:::
