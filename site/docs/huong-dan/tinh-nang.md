# Tab Tính năng

Tab **Tính năng** (giữa Module và Cài đặt) gom các tính năng riêng của SU Kernel.

## Chống bootloop

Mỗi lần khởi động, camd đếm số lần chưa tới được `boot-completed`. Tới lần lỗi thứ 3 liên tiếp, camd tự tắt module **mới cài, mới cập nhật hoặc mới bật lại**. Không có module nào như vậy thì tắt hết module đang bật.

- Màn chính hiện thẻ đỏ và hộp thoại **Bị tắt do lỗi khởi động** để **Bật lại** từng module hoặc **Bỏ qua**; thẻ module có nhãn **Tự tắt do lỗi khởi động**.
- Có thông báo hệ thống khi chống bootloop ra tay.
- Trong thẻ có thể chỉnh số lần lỗi (1 đến 4), chọn tắt module nghi ngờ trước hay tắt hết, và xem danh sách module đã bị tự tắt.

::: warning Giới hạn
Không cứu được bootloop xảy ra **trước** `post-fs-data` (lỗi nhân, `init`, hoặc metamodule mount quá sớm), vì bộ đếm chưa kịp tăng. Khi đó dùng safe mode bằng phím âm lượng.
:::

## Phát hiện xung đột module

camd tìm những file (kể cả whiteout), thư mục `.replace` và prop trong `system.prop` bị **nhiều module cùng sửa**.

- Thẻ module có nhãn **Xung đột**; màn cài module ghi cảnh báo.
- Bật/tắt, cảnh báo khi cài, tính cả prop, danh sách và nút quét lại nằm trong thẻ.
- Chạy tay: `camd module conflicts`.

Xung đột không có nghĩa là lỗi; nó cho biết vì sao module này có thể làm hỏng module kia. Module nào "thắng" tuỳ thứ tự mount của metamodule.

## Tự ẩn bootloader

Ở `post-fs-data` và `boot-completed`, camd đưa các prop đang lộ trạng thái mở khoá (`ro.boot.verifiedbootstate`, `ro.boot.vbmeta.device_state`...) về giá trị của máy đã khoá. Bootloader để trống `ro.boot.vbmeta.digest` thì app điền lại bằng giá trị thật tính từ chính phân vùng của máy.

- Có công tắc, danh sách prop và tham số khởi động. Tham số mà app thường không đọc được hiện màu xám, không tính là lộ.
- Nút **Kiểm tra attestation** tạo khoá trong AndroidKeyStore và đọc RootOfTrust, cho thấy app đọc chứng chỉ sẽ thấy gì.

::: info Không đổi được key attestation
App ngân hàng kiểm tra bằng **key attestation** (chứng chỉ do chip bảo mật ký) vẫn thấy máy mở khoá. Sửa prop không thay đổi được chứng chỉ đó.
:::

## ADB không dây

Bật adbd nghe cổng 5555 bằng root (như `adb tcpip`), không cần ghép đôi. Thẻ hiện lệnh `adb connect <IP Wi-Fi>:5555` để sao chép.

- Tự tắt sau 15, 30, 60, 120 phút hoặc không bao giờ (mặc định 30).
- Khởi động lại hoặc tắt trong thẻ thì cổng đóng lại.
- Khi ADB đang bật, app ngân hàng có thể thấy (mục `adb` trong trang Kiểm tra ẩn root).

## Kiểm tra ẩn root

Mở từ tab này. Xem trang riêng: [Kiểm tra ẩn root](./an-root).
