# Cam Kernel SU

Giải pháp root cho Android dựa trên kernel, phát triển từ [KernelSU](https://github.com/tiann/KernelSU).

Cam Kernel SU là một bản fork độc lập của KernelSU. Dự án bổ sung tính năng mới và thường xuyên đồng bộ các cập nhật từ bản gốc.

> [!NOTE]
> Dự án đang trong giai đoạn đầu, chưa có bản phát hành chính thức. Bản build từ CI được ký bằng khóa riêng của dự án.
> Kernel của Cam không nhận diện Manager qua chữ ký APK: Manager là một app có root bình thường, nên không dùng chung được với kernel KernelSU chính thức.

## Khác gì so với KernelSU

- **Tên riêng:** app **SU Kernel** (gói `cam.su.kernel`), daemon `camd`, dữ liệu ở `/data/adb/cam`, module kernel `camsu.ko`. Cập nhật từ bản dùng tên cũ thì dữ liệu được chuyển tự động.
- **Không còn "app manager" đặc biệt trong kernel:** lúc patch, kernel được gieo sẵn quyền root cho Manager; cấp/thu root bằng `camd allow` từ adb hoặc Termux.
- **Giao diện kính (Liquid Glass)** trên Miuix, thẻ trạng thái 3D có slime đi dạo khắp app, trang Hồ sơ ứng dụng có quản lý app (sao lưu APK, xoá dữ liệu, đóng băng, gỡ cả app hệ thống).
- **Tab Tính năng:**
  - **Chống bootloop:** tự tắt module gây lỗi khởi động.
  - **Phát hiện xung đột module.**
  - **Tự ẩn bootloader** ở tầng prop.
  - **Kiểm tra ẩn root:** quét từ góc nhìn root, từ một app không root (isolated process) và theo từng app; chỉ ra module gây lộ; rule phát hiện có chữ ký, tự cập nhật.

Mọi tính năng còn lại giống bản gốc. Hiện trạng, kết quả test và việc nên làm tiếp: [docs/PROJECT_REVIEW.md](docs/PROJECT_REVIEW.md). Danh sách thay đổi đầy đủ, cách build trên Windows và cách kéo cập nhật từ bản gốc nằm trong [docs/CAM_CHANGES.md](docs/CAM_CHANGES.md). Xem tài liệu tại [kernelsu.org](https://kernelsu.org/vi_VN/) và [docs/README_VI.md](docs/README_VI.md).

## Build

- **GitHub Actions:** mỗi lần push lên `main`, workflow *Build Manager* sẽ build kernel module (8 KMI), `camd` và app Manager. File APK nằm trong mục Artifacts của lần chạy đó.
- **Build trên máy:** xem mục 5 của [docs/CAM_CHANGES.md](docs/CAM_CHANGES.md). Manager chỉ đóng gói file `libcamd.so` có sẵn trong `manager/app/src/main/jniLibs/`, nên sửa camd xong phải chép bản mới vào đó trước khi build.

## Test

CI chưa chạy test, nên chạy tay trước khi push (chi tiết ở mục 6 của [docs/CAM_CHANGES.md](docs/CAM_CHANGES.md)):

- camd, trong WSL: `cd userspace/camd && cargo test --target x86_64-unknown-linux-gnu` (2 test `lkm_image*` fail sẵn khi chạy cục bộ).
- Manager, trên Windows: `gradlew :app:testDebugUnitTest` chạy từ ổ `K:`.

## Đồng bộ với KernelSU gốc

Repo có hai remote:

| Remote | Trỏ tới |
| --- | --- |
| `origin` | [peo1108/Cam-Kernel-SU](https://github.com/peo1108/Cam-Kernel-SU) (repo này) |
| `upstream` | [tiann/KernelSU](https://github.com/tiann/KernelSU) (bản gốc) |

Để lấy cập nhật mới nhất từ bản gốc, làm theo mục 4 của [docs/CAM_CHANGES.md](docs/CAM_CHANGES.md): merge trên một nhánh riêng, chạy script đổi tên, rồi kiểm tra theo danh sách trước khi đưa lên `main`. Không merge thẳng `upstream/main` vào `main`, vì code upstream mới vẫn dùng tên KernelSU.

Dùng `merge`, không dùng `rebase`, vì `main` là nhánh public.

Phiên bản được tính từ lịch sử git (`git rev-list --count HEAD` và `git describe --tags`). Vì vậy repo phải giữ đầy đủ lịch sử và tag của bản gốc.

## Giấy phép

- Thư mục `kernel/`: [GPL-2.0](kernel/LICENSE)
- Phần còn lại: [GPL-3.0](LICENSE)

Bản quyền mã nguồn gốc thuộc về [weishu](https://github.com/tiann) và [các tác giả KernelSU](https://github.com/tiann/KernelSU/graphs/contributors).
