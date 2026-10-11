# Nhật ký cập nhật Cam Kernel SU

Mỗi bản phát hành (tag `cam-vX.Y.Z`) cần một mục `## X.Y.Z - YYYY-MM-DD` ở đây, nếu không workflow Release sẽ dừng. Dòng `- ` đầu tiên của mục là câu tóm tắt hiện trong thông báo.

## 3.0.9 - 2026-10-11
### Tự ẩn bootloader
- Ẩn kỹ hơn: khi bootloader để trống dấu vân tay vbmeta (`ro.boot.vbmeta.digest`) dù máy đã khóa, app tự điền lại bằng giá trị thật tính từ chính các phân vùng của máy, cho khớp với máy khóa bình thường (không bịa giá trị, không ghi đè nếu máy đã có sẵn)
- Trang Kiểm tra ẩn root và thẻ Key attestation hiện thêm phiên bản Android và mức vá bảo mật (hệ thống / vendor / boot) mà app đọc được từ chứng chỉ, để biết máy đang khai báo mức vá nào
### Tính năng mới
- Khi cài, có thể chặn một số module nhân nạp lúc khởi động (ví dụ module chống root của hãng): app tự điền sẵn các module đáng ngờ đang nạp, bạn sửa hoặc xóa tùy ý
### Kiểm tra ẩn root
- Theo Duck Detector mới nhất (78aabb41, rule version 5): bản này nâng mục đo thời gian ghi context SELinux lên mức Nguy hiểm, nhưng SU Kernel đã sửa lỗi đó từ trước nên không bị phát hiện
- Che thêm một kênh đo thời gian khi ghi context SELinux (theo bản vá mới của nhân)
### Lưu ý
- App ngân hàng kiểm tra bằng key attestation vẫn thấy máy mở khóa: sửa prop không đổi được chứng chỉ này

## 3.0.8 - 2026-10-11
### Sửa lỗi
- Tự ẩn bootloader không còn báo nhầm "2 mục đang lộ bootloader" khi mọi prop đã được ẩn
- Tham số khởi động (`androidboot.verifiedbootstate`, `androidboot.vbmeta.device_state`) giờ hiện màu xám kèm chú thích "Chỉ root đọc được, app không thấy": app thường không đọc được chúng nên không tính là lộ
### Lưu ý
- App ngân hàng kiểm tra bằng key attestation vẫn thấy máy mở khóa: sửa prop không đổi được chứng chỉ này

## 3.0.7 - 2026-10-11
### Tính năng mới
- Cài trực tiếp an toàn hơn: app tắt hết module đang bật trước khi flash, để lần khởi động đầu với bản mới không bị module làm bootloop
- Máy root bằng khóa AVB tự ký (như avbroot) không còn thấy "Cài đặt trực tiếp" và "Cài vào phân vùng không hoạt động": app hiện cảnh báo đỏ và hướng dẫn vá file rồi ký lại trên máy tính
### Lưu ý
- Sau khi cài trực tiếp và khởi động lại, vào tab Module bật lại các module cần dùng
- Máy mở khóa bootloader thông thường (kể cả kiểu efisp) vẫn cài trực tiếp như cũ

## 3.0.6 - 2026-10-10
### Tính năng mới
- Ủng hộ dự án ngay trong app: chạm thẻ "Ủng hộ tại:" ở Trang chủ để mở mã QR chuyển khoản
- Mã VietQR quét được bằng mọi app ngân hàng và MoMo, tự điền sẵn tài khoản nhận và nội dung
- Nút "Mở kênh" trong cùng cửa sổ để vào kênh Telegram như trước

## 3.0.5 - 2026-10-10
### Tính năng mới
- ADB không dây ngay trong tab Tính năng: bật một công tắc là máy tính kết nối được qua Wi-Fi, không cần cắm dây hay ghép đôi
- Hiện sẵn lệnh `adb connect <IP>:5555`, chạm để sao chép
- Tự tắt sau 15, 30, 60 hoặc 120 phút (mặc định 30), và luôn tắt khi khởi động lại
- Chạy được cả khi Tùy chọn nhà phát triển đang tắt: app tự bật Gỡ lỗi USB khi cần và tắt lại khi xong
### Lưu ý
- Lần đầu kết nối, chấp nhận máy tính trên điện thoại như khi cắm USB
- Khi ADB đang bật, app ngân hàng có thể phát hiện

## 3.0.4 - 2026-10-10
### Tính năng mới
- Tự kiểm tra ẩn root sau khi cài module: lần khởi động đầu sau khi cài, cập nhật hay bật lại module, app quét một lần và báo nếu module đó làm lộ root
- Chạm thông báo để mở trang Kiểm tra ẩn root, trang tự quét lại và có nút tắt module gây lộ
- Bật/tắt ở mục Tự động trong trang Kiểm tra ẩn root (mặc định bật)
### Thư viện Module
- Thay NeoZygisk bằng Zygisk Next
### Lưu ý
- Android 13 trở lên cần cho phép thông báo thì mới thấy cảnh báo

## 3.0.3 - 2026-10-10
### Sửa lỗi
- Thư viện Module mở lại được: chuyển sang thư viện riêng của Cam, vì kho module của KernelSU đã ngừng hoạt động
- Có 7 module cơ bản: Magic Mount-rs, Hybrid Mount, NeoZygisk, ReZygisk, Vector (LSPosed), Play Integrity Fork, bindhosts; tự cập nhật hằng ngày theo bản mới của từng module
- Khi không tải được thư viện, app báo đúng lý do thay vì luôn báo "Không có kết nối Internet"

## 3.0.2 - 2026-10-10
### Tính năng mới
- Bấm Cập nhật giờ mở hộp thoại tiến trình: Tải về → Kiểm tra → Cài đặt, có thanh chạy theo % và số MB đã tải
- Lỗi khi cập nhật hiện ngay trong hộp thoại, kèm nút Thử lại hoặc Cài bằng trình cài đặt Android

## 3.0.1 - 2026-10-10
### Kiểm tra ẩn root
- Cập nhật rule theo Duck Detector mới nhất (3aa2749c, rule version 4): đã xem hai probe SELinux mới của họ (đếm lượt tra AVC, ghi context có kiểm soát); theo mã nguồn, ẩn SELinux của SU Kernel không bị hai probe này phát hiện (chưa đo trên máy)
### Lưu ý
- Bản đầu tiên cập nhật qua OTA: nếu bạn đang ở 3.0.0, app tự báo và cài bản này.

## 3.0.0 - 2026-10-10
### Tính năng mới
- Tự báo và cài bản cập nhật ngay trong app (OTA)
- Màn "Có gì mới" sau mỗi lần cập nhật
### Lưu ý
- Đây là bản cuối cùng phải cài tay; từ 3.0.1 app tự cập nhật.
