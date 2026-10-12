# Cài đặt

::: danger Đọc trước khi làm
Flash ảnh boot sai bản có thể làm máy không khởi động được. Luôn giữ một bản **ảnh gốc** của đúng bản ROM đang chạy để flash lại khi cần, và sao lưu dữ liệu quan trọng trước.
:::

## Chuẩn bị

- Bootloader **đã mở khoá**.
- Máy tính có [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`, `fastboot`).
- Ảnh **init_boot** (máy xuất xưởng với Android 13 trở lên) hoặc **boot** (máy cũ hơn) gốc, lấy từ firmware của **đúng bản ROM đang chạy**.
- App SU Kernel mới nhất từ trang [Tải về](/tai-ve).

### Kiểm tra KMI

```sh
adb shell uname -r
```

Kết quả có dạng `5.15.148-android13-8-...`: phần `android13` và hai số đầu `5.15` ghép lại là KMI `android13-5.15`. Thường không cần nhớ: app tự chọn đúng module nhân khi vá.

## Cài lần đầu: vá ảnh boot

1. Cài APK SU Kernel lên điện thoại và mở app.
2. Ở màn chính, chạm thẻ **Nhấn để cài đặt**, rồi chọn **Chọn và vá file**.
3. Chọn file `init_boot.img` (hoặc `boot.img`) đã chép vào máy.
4. Hộp **Ứng dụng được cấp root** hiện ra. SU Kernel luôn được chọn; thêm app khác nếu muốn chúng có root ngay lần khởi động đầu (tìm theo tên hoặc thêm bằng tên gói).
5. Tuỳ chọn: **Block kernel modules** chặn một số module nhân nạp lúc khởi động (ví dụ module chống root của hãng). App tự điền sẵn các module đáng ngờ đang nạp, bạn sửa hoặc xoá tuỳ ý.
6. Chạm **Kế tiếp**. App vá ảnh và lưu file `camsu_patched_<ngày>_<giờ>.img` vào thư mục Download.
7. Chép file đó sang máy tính, khởi động điện thoại vào fastboot rồi flash:

```sh
adb reboot bootloader
fastboot flash init_boot camsu_patched_xxx.img   # máy cũ dùng: fastboot flash boot ...
fastboot reboot
```

8. Khởi động xong, **mở SU Kernel một lần**. Lần đầu app cài `/data/adb/camd`; trước đó các app khác chưa gọi được `su`.

::: tip Vá trên máy tính
Không muốn vá trên điện thoại thì dùng `camd` cho máy tính (Windows, Linux, macOS) ở trang Tải về:

```sh
camd boot-patch -b init_boot.img --kmi android14-6.1
```

File vá nằm ở thư mục hiện tại. SU Kernel vẫn có root vì nhân luôn cấp root cho đúng gói `cam.su.kernel` ký bằng khoá chính thức.
:::

## Đã có root: cài trực tiếp

Khi máy đã root (cập nhật LKM, hoặc chuyển từ cách root khác), chọn **Cài đặt trực tiếp (Khuyến nghị)**: app tự lấy ảnh đang dùng, vá và flash luôn, không cần máy tính.

- Trước khi flash, app **tắt mọi module đang bật** để lần khởi động đầu với bản mới sạch sẽ. Tắt lỗi một module là app dừng, không flash.
- Máy dùng **khoá AVB tự ký** (avbroot, trạng thái `yellow`) thì hai cách cài trực tiếp bị ẩn: ảnh vá ngay trên máy không được ký, bootloader đã khoá sẽ từ chối khởi động. Khi đó vá rồi ký lại bằng công cụ của bạn.

## Sau khi cập nhật OTA

OTA ghi hệ thống mới vào slot không hoạt động và làm mất root ở slot đó. Sau khi OTA tải và cài xong (**chưa khởi động lại**), mở SU Kernel và chọn **Cài đặt vào phân vùng không hoạt động (Sau OTA)**, rồi mới khởi động lại.

## Cập nhật app và module nhân

- App tự kiểm tra bản mới mỗi 12 giờ, báo bằng thông báo, kiểm chữ ký rồi cài bằng root.
- Nút **Cài lại LKM** ở màn chính chỉ hiện khi phần nhân trong bản mới thật sự khác bản đang chạy. Không thấy nút thì không cần flash lại.

## Gỡ hoặc cứu máy

- **Bootloop do module:** khởi động lỗi 3 lần liên tiếp thì camd tự tắt module mới cài ([Tab Tính năng](./tinh-nang#chong-bootloop)). Lỗi xảy ra trước `post-fs-data` thì dùng safe mode (giữ phím âm lượng lúc khởi động).
- **Quay về ảnh cũ:** mỗi lần flash, camd giữ ảnh trước đó ở `/data/adb/cam/previous/<phân vùng>.img`. Lần cài đầu thì dùng ảnh gốc bạn đã giữ:

```sh
fastboot flash init_boot init_boot.img
```

- **Gỡ hẳn:** flash lại ảnh gốc rồi gỡ app SU Kernel.
