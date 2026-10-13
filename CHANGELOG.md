# Nhật ký cập nhật Cam Kernel SU

Mỗi bản phát hành (tag `cam-vX.Y.Z`) cần một mục `## X.Y.Z - YYYY-MM-DD` ở đây, nếu không workflow Release sẽ dừng. Dòng `- ` đầu tiên của mục là câu tóm tắt hiện trong thông báo.

## 1.1.5 - 2026-10-13
- Cập nhật theo KernelSU mới nhất: thêm tuỳ chọn chỉ cho Shell lấy root qua ADB
### Tính năng mới
- Cài đặt → "Chỉ cho Shell lấy root qua ADB" (mặc định tắt): khi Shell được cấp root, chỉ phiên `adb shell` thật hoặc tiến trình đã có root (ví dụ module ADB) mới dùng được, app chiếm được quyền shell qua lỗi hệ thống không leo lên root được
### Thay đổi
- Cài đặt (cài vào boot): "Chặn module kernel" giờ phải tự tick mới có tác dụng. Trước đây danh sách được điền sẵn và áp luôn, trên một số máy MediaTek chặn nhầm `mkp` làm máy bootloop
- Mua truy cập sớm hiện thành cửa sổ ngay trên Home thay cho một trang riêng
- Bỏ công tắc "Chặn RKP" của các bản thử trước. Máy đang bật sẽ được tự tắt khi mở app lần đầu sau cập nhật, mạng trở lại bình thường mà không cần khởi động lại

## 1.1.2 - 2026-10-12
- Bản chính thức đầu tiên: cập nhật, tải về và thư viện module đi qua caesarvn.xyz; nhận sớm bản mới ngay trong app
### Tài khoản
- Cài đặt → Tài khoản: liên kết app với tài khoản caesarvn.xyz bằng mã 6 ký tự (không nhập mật khẩu trong app), xem email đã liên kết, huỷ liên kết
- Cập nhật OTA chỉ dành cho thiết bị đã liên kết tài khoản thành viên; bản mới mở cho thành viên sau 20 ngày, hoặc ngay khi mua truy cập sớm
- Công tắc "Nhận bản thử nghiệm" gửi yêu cầu làm beta tester; được duyệt thì nhận mọi bản ngay, cả bản beta
- Thông báo cập nhật báo cả bản chưa mở cho tài khoản; chạm vào để xem ghi chú bản, ngày mở và nhận ngay
- Thanh toán trong app: mã QR (lưu vào thư viện ảnh để app ngân hàng quét), chép số tài khoản và nội dung chuyển khoản, tự xác nhận khi tiền về
- Mua trong app hay trên caesarvn.xyz đều gắn với tài khoản, hai nơi thấy cùng một quyền
- Home có thẻ báo bản mới đang chờ mở; Cài đặt → Tài khoản có nút "Nhận ngay" cho từng bản
### Thay đổi
- Rule kiểm tra ẩn root và thư viện module tải từ caesarvn.xyz
