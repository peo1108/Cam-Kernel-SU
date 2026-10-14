# Nhật ký cập nhật Cam Kernel SU

Mỗi bản phát hành (tag `cam-vX.Y.Z`) cần một mục `## X.Y.Z - YYYY-MM-DD` ở đây, nếu không workflow Release sẽ dừng. Dòng `- ` đầu tiên của mục là câu tóm tắt hiện trong thông báo.

## 1.1.8 - 2026-10-14
- Chống bootloop cứu được cả lỗi rất sớm lúc khởi động, flash phân vùng an toàn hơn, trang ẩn root có nút sửa ngay, sao lưu cấu hình
### Tính năng mới
- Chống bootloop giờ cứu được cả máy treo rất sớm lúc khởi động do module có init rc (`initrc/*.rc`) hỏng: sau số lần khởi động lỗi đã chọn, lần sau máy bỏ qua init rc của module để lên được, rồi tự tắt module đó và báo như trước, không phải bấm phím vào safe mode
- Sao lưu cấu hình (Cài đặt → Sao lưu cấu hình): quyền root, hồ sơ app, công tắc ẩn root, module đang bật và bookmark tab Tệp vào một file; khôi phục trên máy mới hoặc sau khi flash ROM, xem trước những gì sẽ đổi
- Trang Kiểm tra ẩn root: mỗi mục có nút sửa ngay ("Bật umount module cho app này", bật umount trong hồ sơ mặc định, bật công tắc đang tắt, mở Tuỳ chọn nhà phát triển) và lời khuyên cụ thể cho mục không tự sửa được
### Thay đổi
- Flash vào phân vùng (tab Tệp): kiểm tra image đúng loại trước khi ghi (chặn flash nhầm boot vào init_boot, file zip, file nén), luôn sao lưu phân vùng hiện tại vào CamBackup trước, cảnh báo khi chọn slot máy không chạy, bước cuối phải gõ tên phân vùng
- Chống xung đột module: nhận ra cả thư mục được thay thế bằng xattr của overlayfs (module làm cho metamodule overlay), không chỉ file `.replace`
### Lưu ý
- Bản thử nghiệm: các tính năng trên chưa được thử trên nhiều máy, báo lỗi giúp Cam nếu gặp

## 1.1.7 - 2026-10-14
- Tab Tệp: sửa file lớn, đổi bảng mã, so sánh hai file, terminal root, thùng rác; giao diện mới kiểu iOS
### Tính năng mới
- Trình sửa văn bản: file trên 128 KB giờ sửa được (sửa từng dòng, vẫn cuộn mượt), không còn chỉ đọc
- Mở lại file bằng bảng mã khác (GBK, Big5, Windows-1258...) và lưu sang bảng mã khác; chuyển kiểu xuống dòng sang LF hoặc CRLF
- So sánh hai file: chọn file ở mỗi panel (hoặc hai file trong một panel) rồi bấm "So sánh"; chạm một dòng để mở file đúng dòng đó
- Terminal root ngay trong tab Tệp; chạy script bằng root và gõ trả lời khi script hỏi
- Thùng rác: file xoá trong tab Tệp được giữ lại để khôi phục
- Sao lưu phân vùng và flash ảnh boot từ tab Tệp
- Ảnh thu nhỏ cho ảnh, video và APK; danh sách tự làm mới khi thư mục thay đổi
- Mở file bằng app khác mà không phải chép ra trước
### Thay đổi
- Giao diện kiểu iOS: menu mọc ra từ nút, hộp thoại ở giữa màn hình, thông báo dạng banner, công tắc và thanh trượt mới, ô nhập có nút xoá
- Màn ngang: thanh điều hướng chuyển sang cạnh bên
### Sửa lỗi
- File lẫn CRLF và LF không còn bị đổi hết sang CRLF khi lưu: mỗi dòng giữ kiểu xuống dòng của nó
- Ký tự mà bảng mã của file không ghi được giờ báo lỗi thay vì ghi thành "?"
- Công tắc không còn bật tắt theo giá trị cũ

## 1.1.6 - 2026-10-13
- Tab Tệp mới: quản lý file bằng root kiểu MT Manager, hai panel, ngay trong app
### Tính năng mới
- Tab **Tệp** (giữa Tính năng và Cài đặt): hai panel, sao chép / di chuyển / xoá / đổi tên, quyền, owner, SELinux context, bookmark, tìm kiếm theo tên và nội dung, checksum, xem hex
- Mở file nén như thư mục (zip, apk, tar.gz...), giải nén, nén, sửa trực tiếp trong zip (thêm, xoá, đổi tên, sửa file bên trong)
- Trình sửa văn bản có tô màu (shell, prop, xml, json), tìm / thay thế, đi tới dòng, hoàn tác; file lớn hoặc nhị phân mở ở chế độ chỉ đọc vẫn cuộn mượt
- Cài APK và bộ split (apks, xapk, apkm) bằng root, xem thông tin APK; cài zip làm module ngay từ tab Tệp
- Ghi đè file hệ thống kiểu systemless (cần metamodule), xem file như một app đang chạy nhìn thấy
- Thao tác dài chạy nền với thông báo tiến trình và nút Huỷ
### Sửa lỗi
- ADB không dây không còn bị tắt khi app được cập nhật hoặc mở lại sau khi buộc dừng (Android 15+)
- App được biên dịch sẵn sau khi cài / cập nhật nên lần mở đầu không còn giật

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
