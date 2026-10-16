# Plan: nền "Hầm chỉ huy Inner Veterans" (2026-10-16)

Một loại nền kính mới (thứ 4, cạnh Hình nền / Gradient động / Ảnh tự chọn): logo Inner Veterans nằm làm nền và sống động. Dùng lại ảnh, 22 mảnh đã cắt và hiệu ứng của intro (`ui/intro/`, dòng 68 của `docs/CAM_CHANGES.md`).

## Cảnh

- **Hầm chỉ huy sống.** Logo nằm im. (Bản đầu: điện chạy dọc viền, lúc êm lúc dồn; đã thay bằng ánh sáng kim loại, xem mục dưới.) Tâm ngắm xoay chậm. Bảng RNG đếm tới số mới mỗi vài giây. Một mảnh HUD thỉnh thoảng nháy. Cứ 20–40 s có một sự kiện: một viên đạn găm vào khung tóe lửa, hoặc một mảnh giáp khung ngoài bật ra rồi đóng lại.
- **3D theo con quay.** Mỗi lớp lệch theo độ nghiêng máy với một hệ số riêng: HUD 1,0 (gần nhất), chữ 0,8, khung ngoài 0,6, khung trong 0,4, lõi 0,2, tâm ngắm 0,1. Dùng `rememberDeviceTilt()` của miuix: lấy trọng lực trừ đi trung bình chậm (khoảng 2 s), nên lúc cầm yên các lớp trôi về giữa.
- **Phản ứng với app** (`BunkerSignals`):
  - Chuyển tab (`MainScreen`): cả tam giác quay đủ một vòng theo chiều đổi tab, dừng đúng tư thế gốc (bản đầu 120°, logo nằm lệch nên Cam đổi).
  - Kéo để làm mới (5 trang có `PullToRefresh`): điện nạp dần quanh viền theo độ kéo. Khi làm mới thật thì phóng một luồng từ tâm ngắm, chớp và viền dồn điện.
  - Cấp root (lưu hồ sơ có `allowSu` mới bật) hoặc cài module xong (`FlashingStatus.SUCCESS`): tâm ngắm hiện LOCK, chớp teal.
- **Màu:** luôn là màu gốc của logo (bản đầu có đổi màu theo giờ, đã bỏ; xem mục dưới).

## Nối vào nền kính

- `GlassBackgroundType.BUNKER = 3`; `GlassSource.Bunker`. Prefs chặn `0..3`.
- `GlassBackgroundLayer` vẽ `BunkerBackground`. Thanh điều hướng dùng màu đứng yên thay vì lấy mẫu nền động, như với gradient: `GlassSource.animated` thay cho `is GlassSource.Gradient` ở `CamActivity` và `BlurExt`.
- Thẻ kính vẫn chỉ phủ lớp màu như với gradient. Độ mờ của người dùng áp lên cảnh (`BlurEffect`), độ tối là một lớp phủ đen. Không phủ trắng ở giao diện sáng: logo là ảnh tối, giữ nguyên.
- Ảnh: `loadIntroArt` tách ra khỏi `IntroLogo`. `BunkerArt` giữ một bản cho mọi trang và nhả khi không còn trang nào dùng.
- Hiệu năng: lúc rảnh 30 fps; khi có xoay, sự kiện, kéo, khoá hay đang nghiêng máy thì 60 fps. Dừng lúc trang đang trượt (như gradient), app không ở `RESUMED`, hoặc hoạt ảnh hệ thống tắt (vẽ tĩnh).

## Đã đổi sau khi Cam xem trên máy (2026-10-16)

- Bỏ "chạm chỗ trống thì tâm ngắm trượt tới" (Cam không muốn).
- Bỏ lớp phủ trắng ở giao diện sáng.
- Điện được làm to và lạnh hơn, rồi bỏ hẳn (Cam thấy xấu). Thay bằng ánh sáng sắc lạnh của kim loại: (1) vệt sáng quét chéo trên các mảnh giáp, chỉ sáng trên thép; (2) vệt phản chiếu trượt theo độ nghiêng; (3) viền chrome có đoạn sáng trôi quanh. Kéo làm mới: viền nung trắng từ đỉnh; làm mới: vệt chớp qua cả tam giác; chuyển tab: viền sáng bừng. Có làm sao 4 cánh lóe ở mép và góc, Cam thấy xấu nên bỏ. 

- Bỏ đổi màu theo giờ và đèn báo động đỏ ban đêm (Cam thấy bẩn, rẻ tiền): luôn là màu gốc, ban đêm không dịu đi. `BunkerTime` đã xoá.

## Test

JVM: `BunkerMotionTest` (`surge` trong biên, khoảng cách sự kiện 20–40 s, xoay tab kết thúc đúng góc, mảnh bật về chỗ cũ), `GlassSourceTest` (loại mới).

Trên tablet (release): xoay tab, kéo làm mới ở Superuser và Module, cấp root, nghiêng máy, đo frame time khi để yên.
