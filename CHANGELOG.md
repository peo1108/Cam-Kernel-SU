# Nhật ký cập nhật Cam Kernel SU

Mỗi bản phát hành (tag `cam-vX.Y.Z`) cần một mục `## X.Y.Z - YYYY-MM-DD` ở đây, nếu không workflow Release sẽ dừng. Dòng `- ` đầu tiên của mục là câu tóm tắt hiện trong thông báo.

## 1.1.5 - 2026-10-13
- Cập nhật theo KernelSU mới nhất: thêm tuỳ chọn chỉ cho Shell lấy root qua ADB
### Tính năng mới
- Cài đặt → "Chỉ cho Shell lấy root qua ADB" (mặc định tắt): khi Shell được cấp root, chỉ phiên `adb shell` thật hoặc tiến trình đã có root (ví dụ module ADB) mới dùng được, app chiếm được quyền shell qua lỗi hệ thống không leo lên root được
### Thay đổi
- Cài đặt (cài vào boot): "Chặn module kernel" giờ phải tự tick mới có tác dụng. Trước đây danh sách được điền sẵn và áp luôn, trên một số máy MediaTek chặn nhầm `mkp` làm máy bootloop

## 1.1.4 - 2026-10-13
- "Chặn RKP" nay chỉ ngắt mạng khi bạn mở Duck Detector, thoát ra là mạng trở lại ngay
### Thay đổi
- Khi Duck Detector đang mở, cả máy tạm mất internet (Wi-Fi vẫn kết nối nhưng không ra mạng) nên app không đo được độ trễ của đường kiểm tra keybox; rời khỏi Duck Detector là mạng về ngay. Trước đây chỉ chặn cố định mạng của vài app

## 1.1.3 - 2026-10-12
- Công tắc mới "Chặn RKP" trong Tính năng → Tự ẩn bootloader
### Tính năng mới
- Chặn RKP: cắt mạng dịch vụ cấp khoá RKP và Duck Detector để kiểm tra keybox không lộ độ trễ; tự bật lại sau mỗi lần khởi động. Chỉ dùng cho máy không cấp được khoá RKP
### Thay đổi
- Mua truy cập sớm hiện thành cửa sổ ngay trên Home thay cho một trang riêng

## 1.1.2 - 2026-10-12
- Nhận sớm bản mới ngay trong app: xem có gì mới, quét mã QR chuyển khoản, tự xác nhận rồi cập nhật
### Tài khoản
- Thông báo cập nhật báo cả bản chưa mở cho tài khoản; chạm vào để xem ghi chú bản, ngày mở và nhận ngay
- Thanh toán trong app: mã QR (lưu vào thư viện ảnh để app ngân hàng quét), chép số tài khoản và nội dung chuyển khoản, tự xác nhận khi tiền về
- Mua trong app hay trên caesarvn.xyz đều gắn với tài khoản, hai nơi thấy cùng một quyền
- Home có thẻ báo bản mới đang chờ mở; Cài đặt → Tài khoản có nút "Nhận ngay" cho từng bản

## 1.1.1 - 2026-10-12
- Bản chính thức đầu tiên: cập nhật, tải về và thư viện module giờ đi qua caesarvn.xyz, nhận cập nhật bằng tài khoản thành viên
### Tài khoản
- Cài đặt → Tài khoản: liên kết app với tài khoản caesarvn.xyz bằng mã 6 ký tự (không nhập mật khẩu trong app), xem email đã liên kết, huỷ liên kết
- Cập nhật OTA chỉ dành cho thiết bị đã liên kết tài khoản thành viên; bản mới mở cho thành viên sau 20 ngày, hoặc ngay khi mua truy cập sớm
- Công tắc "Nhận bản thử nghiệm" gửi yêu cầu làm beta tester; được duyệt thì nhận mọi bản ngay, cả bản beta
### Thay đổi
- Rule kiểm tra ẩn root và thư viện module tải từ caesarvn.xyz
- Đánh số lại phiên bản từ 1.1.1; cập nhật từ các bản 3.x vẫn bình thường
- App chỉ còn tiếng Việt và tiếng Anh

