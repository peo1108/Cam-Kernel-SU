# Cam Kernel SU

Giải pháp root cho Android dựa trên kernel, phát triển từ [KernelSU](https://github.com/tiann/KernelSU).

Cam Kernel SU là một bản fork độc lập của KernelSU. Dự án bổ sung tính năng mới và thường xuyên đồng bộ các cập nhật từ bản gốc.

> [!NOTE]
> Dự án đang trong giai đoạn đầu, chưa có bản phát hành.
> App Manager build từ CI hiện chưa được ký bằng key riêng của dự án. Vì vậy kernel KernelSU chính thức sẽ không nhận app này làm trình quản lý.

## Khác gì so với KernelSU

- App Manager hiển thị tên **Cam Kernel SU**.
- Các tính năng mới sẽ được liệt kê tại đây.

Mọi tính năng còn lại giống bản gốc. Xem tài liệu tại [kernelsu.org](https://kernelsu.org/vi_VN/) và [docs/README_VI.md](docs/README_VI.md).

## Build

- **GitHub Actions:** mỗi lần push lên `main`, workflow *Build Manager* sẽ build kernel module, `ksud` và app Manager. File APK nằm trong mục Artifacts của lần chạy đó.
- **Build trên máy:** làm theo [hướng dẫn build của KernelSU](https://kernelsu.org/vi_VN/guide/how-to-build.html).

## Đồng bộ với KernelSU gốc

Repo có hai remote:

| Remote | Trỏ tới |
| --- | --- |
| `origin` | [peo1108/Cam-Kernel-SU](https://github.com/peo1108/Cam-Kernel-SU) (repo này) |
| `upstream` | [tiann/KernelSU](https://github.com/tiann/KernelSU) (bản gốc) |

Để lấy cập nhật mới nhất từ bản gốc:

```bash
git checkout main
git fetch upstream --tags
git merge upstream/main
git push origin main --tags
```

Dùng `merge`, không dùng `rebase`, vì `main` là nhánh public.

Phiên bản được tính từ lịch sử git (`git rev-list --count HEAD` và `git describe --tags`). Vì vậy repo phải giữ đầy đủ lịch sử và tag của bản gốc.

## Giấy phép

- Thư mục `kernel/`: [GPL-2.0](kernel/LICENSE)
- Phần còn lại: [GPL-3.0](LICENSE)

Bản quyền mã nguồn gốc thuộc về [weishu](https://github.com/tiann) và [các tác giả KernelSU](https://github.com/tiann/KernelSU/graphs/contributors).
