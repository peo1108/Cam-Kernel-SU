# Nhật ký cập nhật Cam Kernel SU

Mỗi bản phát hành (tag `cam-vX.Y.Z`) cần một mục `## X.Y.Z - YYYY-MM-DD` ở đây, nếu không workflow Release sẽ dừng. Dòng `- ` đầu tiên của mục là câu tóm tắt hiện trong thông báo.

## 1.2.1 - 2026-10-23
- Rule ẩn root v17 theo Duck Detector mới nhất; ẩn SELinux đã được thử lại với cách chấm mới của Duck Detector, kể cả khi máy đổi nhân CPU giữa lúc đo
### Thay đổi
- Rule ẩn root v17: theo Duck Detector tới bản nightly 23/10 (e0a57418). Duck Detector giờ vẫn chấm phép đo thời gian khi App Zygote ghi context SELinux kể cả khi máy đổi nhân CPU giữa lúc đo (trước đó lượt đo như vậy bị bỏ). Đã thử với kernel 1.2.0, có cả những lượt cố ý chuyển Duck Detector qua lại giữa các nhân CPU: thẻ SELinux vẫn sạch, không cần sửa kernel. Trang Kiểm tra ẩn root vẫn chưa có cách đo này và ghi nó trong mục Duck Detector
- Duck Detector bản mới không còn tính "OEM unlock allowed" là dấu hiệu. Trên Android 16, bản cũ báo nguy hiểm mỗi khi có `sys.oem_unlock_allowed`, kể cả giá trị 0 do Ẩn bootloader đặt; giờ chỉ hiện giá trị. Trang Kiểm tra ẩn root cũng thôi báo lộ khi máy cho phép mở khóa OEM (Ẩn bootloader vẫn sửa giá trị này như cũ)
### Lưu ý
- Bản này không đổi kernel: không cần cài lại LKM. Ai chưa cài lại LKM sau khi lên 1.2.0 thì vẫn nên cài lại để có phần sửa ẩn SELinux của 1.2.0

## 1.2.0 - 2026-10-23
- Ẩn SELinux trước cách đo mới của Duck Detector; kiểm tra ẩn root chính xác hơn: hết báo nhầm file của WebView, bắt thêm vài kiểu file root trước đây lọt qua
### Sửa lỗi
- Ẩn root: Duck Detector mới thêm cách đo thời gian ghi context SELinux từ App Zygote. Phần ẩn SELinux của SU Kernel làm lần ghi sai chậm hơn máy gốc 0,1-0,26 µs, đủ để đo ra (Duck Detector hiện số đo này như thông tin, chưa tính là dấu hiệu root). Giờ chênh dưới 0,02 µs, Duck Detector không phân biệt được với máy gốc. Có hiệu lực khi cài lại LKM
- Ẩn root: phần ẩn SELinux giờ cũng khớp thời gian máy gốc khi app ghi context hoặc hỏi quyền qua selinuxfs, không chỉ riêng lần ghi attr/current. Có hiệu lực khi cài lại LKM
- Ẩn SELinux: ở bản 1.2.0-beta.4, bật ẩn SELinux sau khi máy đã khởi động xong thì công tắc tự tắt lại khi lưu, nên khởi động lại xong vẫn không ẩn. Giờ công tắc giữ trạng thái bật và có hiệu lực từ lần khởi động lại tiếp theo như thông báo; trước lần đó app vẫn thấy trạng thái SELinux thật. Ai đã bật ở beta.4 mà sau khi khởi động lại thấy tắt thì bật lại một lần rồi khởi động lại. Có hiệu lực khi cài lại LKM
- Ẩn SELinux: thêm hai chỗ khớp máy gốc — (1) khi app kiểm context qua selinuxfs, bảng sid của chính sách đang chạy nay vẫn lớn lên như máy gốc (bản 1.2.0-beta.4 bỏ bước này, app đọc được chênh lệch qua `ss/sidtab_hash_stats`); (2) đường ghi context qua `lsm_set_self_attr` (lời gọi hệ thống mới, nhân 6.8 trở lên) nay cũng được ẩn như ghi `attr/current`. Có hiệu lực khi cài lại LKM
- Kiểm tra ẩn root: vẫn còn báo nhầm "App đang nạp file root trong bộ nhớ" với file của WebView khi mã ngẫu nhiên trong tên thư mục cài app có chữ giống "Riru" (bản 1.1.9 mới sửa được trường hợp "edxp"). Giờ mã ngẫu nhiên được nhận đúng và bỏ qua, chỉ xét tên app, cả trong tên vùng nhớ Android đặt theo app (dex và app image giải nén trong bộ nhớ)
- Kiểm tra ẩn root: chỉ bỏ qua mã ngẫu nhiên trong thư mục cài app. File root đặt ở chỗ khác mà tên trông giống mã ngẫu nhiên vẫn bị báo
- Kiểm tra ẩn root: phần "nhìn từ trong app" giờ bắt được vùng nhớ ẩn danh có dấu cách trong tên và tên viết bằng chữ giống chữ thường (như ký hiệu Kelvin thay cho chữ k), giống kết quả của camd. Một thư viện đã bị xoá không còn hiện hai lần
- Kiểm tra ẩn root: một tên file hay tên tiến trình có ký tự lạ (không phải UTF-8) không còn khiến camd bỏ qua cả app đó hay cả danh sách mount
- Kiểm tra ẩn root: phần "nhìn từ trong app" luôn báo file trong `/data/adb` và `/debug_ramdisk`, kể cả khi rule tải về không liệt kê
- Kiểm tra ẩn root: lần quét đầu sau khi cập nhật hiện như lần đầu thay vì so với lần quét cũ (tên mục đã đổi), nên không còn đánh dấu "mới" nhầm
### Thay đổi
- Rule ẩn root v15: theo Duck Detector tới bản nightly 22/10. Cách đo thời gian SELinux mới của Duck Detector đã được xử lý ở kernel; trang Kiểm tra ẩn root chưa có cách đo này và ghi nó trong mục Duck Detector
### Lưu ý
- Bản này có thay đổi kernel: sau khi cập nhật, bấm "Cài lại LKM" (thẻ trên trang chủ) rồi khởi động lại. Chưa cài lại LKM thì phần sửa ẩn SELinux chưa có hiệu lực

## 1.1.9 - 2026-10-21
- Chặn module nguy hiểm, sao lưu và khôi phục chính các module, cài module không cần khởi động lại (với module có hỗ trợ)
### Tính năng mới
- Chặn module nguy hiểm (tab Tính năng): SU Kernel không cài module nằm trong danh sách mã độc hoặc phá máy. Danh sách có chữ ký và tự cập nhật cùng rule ẩn root. Module đã cài mà nằm trong danh sách có nhãn đỏ "Nguy hiểm", bấm vào để xem lý do và tắt. Tắt công tắc "Từ chối module nguy hiểm" thì chỉ cảnh báo rồi vẫn cài
- Sao lưu module (Cài đặt → Sao lưu cấu hình → Module): lưu chính các module đã cài vào một file .tar. Khôi phục thì chọn từng module, module được thay ở lần khởi động sau như một lần cập nhật và giữ trạng thái bật hay tắt như lúc sao lưu
- Cài module không cần khởi động lại, với module tự khai hỗ trợ (hot install, từ KernelSU). Module khác vẫn cần khởi động lại như cũ
- Intro khi mở app: logo Inner Veterans được ráp lại từ từng mảnh giáp, đạn găm vào khung, điện chạy quanh viền rồi nổ tung, mở ra trang chủ với slime đã chạy sẵn. Chạm để bỏ qua, tắt trong Cài đặt → Chủ đề (tắt thì màn hình chờ có logo như cũ)
- Nền kính mới "Hầm chỉ huy Inner Veterans" (Cài đặt → Chủ đề → Nền kính): logo làm nền sống động, ánh sáng lạnh lướt trên các mảnh thép và viền chrome, thỉnh thoảng một viên đạn găm vào khung. Nghiêng máy thấy chiều sâu và vệt phản chiếu trượt theo, kéo để làm mới thì viền nung sáng rồi chớp, cấp root hay cài module xong thì tâm ngắm khoá mục tiêu
- Hầm chỉ huy làm hình nền máy: chọn nền "Hầm chỉ huy Inner Veterans" rồi bấm "Đặt làm hình nền máy". Hình nền động, logo nằm phía trên thanh dock nên không bị che, chỉ chạy khi đang nhìn thấy
- Module preinit (từ KernelSU): module có thư mục `preinit/` chạy được vài việc trước khi Android nạp SELinux (nạp kernel module, chạy chương trình, ghi vào /proc, /sys). Khởi động lỗi 3 lần liên tiếp thì preinit tự tắt; module nào không chạy được preinit có dòng cảnh báo đỏ dưới thẻ module
### Thay đổi
- Hiệu ứng chuyển trang mới: trang mở ra từ đúng dòng hoặc thẻ vừa bấm (Cài đặt, Superuser, Module, Thư viện module, Trang chủ, Tính năng, Mẫu hồ sơ), các thẻ bay ra lần lượt, trang cũ lùi xuống phía sau. Quay lại thì trang thu về chỗ cũ, theo cả thao tác vuốt
- Superuser, Module và Thư viện module hiện các thẻ mờ trong lúc tải lần đầu thay cho vòng xoay
- Bấm "Mở hồ sơ đầy đủ" trong Superuser: icon và tên app bay từ thẻ sang trang hồ sơ, quay lại thì bay về thẻ (theo cả thao tác vuốt)
- Mở app nhanh hơn một chút sau mỗi lần cập nhật: các phần hay dùng được biên dịch sẵn
- Bật/tắt "Hiện app hệ thống" trong Superuser nhanh hơn
### Sửa lỗi
- Kiểm tra ẩn root: sau khi mở WebUI của module (hoặc app có WebView), trang báo "App đang nạp file root trong bộ nhớ" với các file của WebView. Đó là báo nhầm: tên thư mục cài app có mã ngẫu nhiên trùng chữ "edxp" (EdXposed). Giờ chỉ xét tên app, không xét mã ngẫu nhiên
- Cài một file zip module bị hỏng giờ báo lỗi thay vì làm camd dừng đột ngột
- Chặn module kernel (trang Cài đặt LKM): chặn nhầm module mà máy cần ngay lúc mới khởi động không còn làm máy bootloop. SU Kernel để module đó nạp bình thường và chỉ chặn những module chặn được an toàn. Có hiệu lực khi cài lại LKM
- Quyền root và hồ sơ của các app không còn có thể bị xoá hàng loạt khi danh sách gói của Android có một dòng đọc không được
- Root của SU Kernel chỉ được cấp lại khi chữ ký khớp đúng bản app đang cài (chặt hơn lúc gỡ rồi cài lại app)
- Máy Android 17 QPR3 khởi động được với SU Kernel (từ KernelSU)
- Ẩn root: trên máy có file init.rc được gắn từ chỗ khác, phần kernel chạy lúc khởi động giờ cũng được gỡ sau khi máy lên như các máy khác, để app không đo được nó (Duck Detector mới thêm cách đo này; sửa từ KernelSU). Đã đo trên máy thử, chưa thử trên máy có init.rc gắn từ chỗ khác. Có hiệu lực khi cài lại LKM
- Ẩn root: bỏ một quyền mà thư viện AndroidX tự thêm vào app. Duck Detector dò quyền này để tìm app quản lý của KernelSU gốc; SU Kernel chưa từng bị tìm ra theo cách này, bỏ đi để sau này cũng không bị (từ KernelSU)
- Kiểm tra ẩn root: mục Duck Detector luôn báo "không kiểm tra được" từ khi Duck Detector đổi cách phát hành bản nightly (18/10), và có máy báo nhầm "có bản nightly mới" với một bản cũ hơn rule đang dùng. Giờ SU Kernel lấy bản nightly mới nhất từ caesarvn.xyz (không hỏi GitHub nữa) và so theo ngày, chỉ báo bản mới khi Duck Detector thật sự có bản mới hơn. Không lấy được thì dòng Duck Detector ghi rõ "không kiểm tra được bản nightly mới nhất"
### Lưu ý
- Bản này có thay đổi kernel: sau khi cập nhật, bấm "Cài lại LKM" (thẻ trên trang chủ) rồi khởi động lại. Chưa cài lại LKM thì module sẽ không chạy ở lần khởi động sau

## 1.1.8 - 2026-10-15
- Bản cập nhật quan trọng, nên cài ngay: chống bootloop tắt hết module và chỉ ra module gây lỗi, flash phân vùng an toàn hơn, trang ẩn root có nút sửa ngay, sao lưu cấu hình
### Tính năng mới
- Chống bootloop: khi máy khởi động lỗi liên tục, tất cả module được tắt để máy lên chắc chắn, và SU Kernel chỉ ra module nghi gây lỗi kèm lý do (mới cài hoặc cập nhật, có init rc chạy rất sớm, hay log của lần khởi động lỗi nhắc tới nó, có cả dòng log). Một nút bật lại các module còn lại
- Chống bootloop giờ cứu được cả máy treo rất sớm lúc khởi động do init rc hỏng (của module, hoặc file trong /data/adb/initrc.d): lần khởi động sau bỏ qua các init rc đó để máy lên được, không phải bấm phím vào safe mode
- Thẻ module báo "Có bản mới" khi thư viện module của Cam có phiên bản mới hơn bản đang cài (cả module không tự khai cách cập nhật); bấm vào để cập nhật
- Trang Kiểm tra ẩn root: thêm mục Widevine DRM (mức L1/L3 mà app xem phim dùng để chọn HD hay SD, System ID, phiên bản, HDCP). Tự chẩn đoán vì sao máy dưới L1 (xin thử chứng chỉ từ Google để biết keybox còn hợp lệ không, dịch vụ Widevine có chạy không, bootloader khoá hay mở) và có nút "Cấp lại chứng chỉ DRM": lưu file cũ, xoá, xin chứng chỉ mới, tệ hơn thì tự trả lại
- Sao lưu cấu hình (Cài đặt → Sao lưu cấu hình): quyền root, hồ sơ app, công tắc ẩn root, module đang bật và bookmark tab Tệp vào một file; khôi phục trên máy mới hoặc sau khi flash ROM, xem trước những gì sẽ đổi
- Trang Kiểm tra ẩn root: mỗi mục có nút sửa ngay ("Bật umount module cho app này", bật umount trong hồ sơ mặc định, bật công tắc đang tắt, mở Tuỳ chọn nhà phát triển) và lời khuyên cụ thể cho mục không tự sửa được
### Thay đổi
- Flash vào phân vùng (tab Tệp): kiểm tra image đúng loại trước khi ghi (chặn flash nhầm boot vào init_boot, file zip, file nén), luôn sao lưu phân vùng hiện tại vào CamBackup trước, cảnh báo khi chọn slot máy không chạy, bước cuối phải gõ tên phân vùng
- Chống xung đột module: nhận ra cả thư mục được thay thế bằng xattr của overlayfs (module làm cho metamodule overlay), không chỉ file `.replace`
- Chống bootloop: bỏ lựa chọn "Khi can thiệp", giờ luôn tắt tất cả module
- Tab Tệp: máy tính bảng để dọc cũng hiện hai panel cạnh nhau (từ 600dp), điện thoại vẫn một panel
- Chạy nền nhẹ hơn: slime 3D trả lại bộ nhớ đồ hoạ (khoảng 470 MB) sau 10 giây ở nền, quay lại thì hiện như cũ
### Sửa lỗi
- Văng app hiếm gặp ở thẻ trạng thái khi xoay máy hoặc quay lại app đúng lúc slime đang diễn đoạn mở màn
- Ẩn SELinux: sửa lỗi có thể làm máy khởi động lại khi tắt tính năng lúc app đang đọc selinuxfs, chặn lộ qua thông báo nạp policy (netlink), không còn dò hàm kernel qua kallsyms
### Lưu ý
- Cập nhật quan trọng: sửa lỗi ẩn SELinux có thể làm máy khởi động lại và chống bootloop chắc chắn hơn, Cam khuyến nghị mọi máy cập nhật
- Bản này có thay đổi kernel: sau khi cập nhật, bấm "Cài lại LKM" ở màn "Có gì mới" rồi khởi động lại

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
