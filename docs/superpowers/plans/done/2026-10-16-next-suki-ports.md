# Ba tính năng lấy ý từ KernelSU Next và SukiSU Ultra (2026-10-16)

Đọc trên KernelSU Next `4cb8ee6` và SukiSU Ultra `2aed04f` (cùng 2026-10-15). Không lấy gì liên quan SUSFS. Lấy ý tưởng, không chép nguyên: cả ba đều làm lại cho hợp với những gì Cam đã có (rule có chữ ký, chống bootloop, giữ bản cũ của module, sao lưu cấu hình).

Thứ tự làm: 1 → 3 → 2. Mục 2 chờ kiểm trên tablet trước (lý do ở dưới).

**Trạng thái (2026-10-16, tối):**
- Mục 1: xong (dòng 60 của `CAM_CHANGES.md`), rule v9 đã ký; Cam đã duyệt 3 mục trong `moduleRisks`. Test JVM đạt, đã thử trên tablet.
- Mục 3: xong (dòng 61). Test host + JVM đạt, đã thử trên tablet (cả file tar độc).
- Mục 2: **bỏ** (Cam quyết định 2026-10-16): khách không dùng metamodule nên kernel umount không chạy, và chưa thấy module nào của khách tự mount. Thiết kế dưới đây giữ lại để tham khảo.

## 1. Chặn module nguy hiểm (ý từ KernelSU Next `risk.rs`)

**Next làm gì:** `risk/risk.json` là danh sách nhóm `{reason, severity, patterns}`. Lúc `ksud module install`, ksud quét **mọi file** trong zip (tối đa 8 MB mỗi file), chuẩn hoá chữ (NFKC, chữ thường, mọi ký tự không phải chữ/số thành dấu cách) rồi tìm chuỗi từ liên tiếp khớp mẫu. `high`/`extreme` chặn hẳn; `low`/`medium` dừng chờ bấm nút giảm âm lượng trong 5 giây. Danh sách tự tải từ GitHub của Next (không ký, chỉ kiểm JSON hợp lệ); công tắc bật/tắt.

**Cam làm khác:**
- **Danh sách nằm trong `hiding-rules.json`** (trường mới `moduleRisks`), nên có sẵn chữ ký ECDSA, feed `caesarvn.xyz/feed/`, luồng tải 6 giờ một lần và test `HidingRulesRepositoryTest`. Không dựng thêm khoá, file, feed. Manager cũ bỏ qua trường lạ (`JSONObject`), camd cũng không đọc trường này.
- **Kiểm ở Manager, không ở camd.** Mọi đường cài module của người dùng (chọn file, thư viện module, cập nhật module) đều đi qua `flashModule` trong `CamCli.kt`. Kiểm trong Kotlin thì test được trên JVM, không đổi camd, không phải ra LKM. Cài bằng `camd module install` qua adb thì không kiểm (người dùng tự làm, biết mình cài gì).
- **Hai mức thay vì bốn:** `block` (dừng cài, in lý do) và `warn` (in cảnh báo màu đỏ, vẫn cài). Không dùng phím âm lượng.
- **Khớp theo `ids`** (id module chính xác) **và `patterns`** (như Next). `ids` chính xác hơn, ưu tiên dùng.
- **Module đã cài cũng được kiểm** (chỉ `id`, `name`, `author` trong `module.prop`, không quét file): thẻ module có chip đỏ "Nguy hiểm", tab Tính năng có thẻ liệt kê.
- **Công tắc** trong tab Tính năng, mặc định bật. Tắt thì không chặn, chỉ còn chip cảnh báo.

**Định dạng:**
```json
"moduleRisks": [
  {"level": "block", "ids": ["zygisk_floating_menu"], "patterns": ["zygisk floating overlay menu"],
   "reason": {"vi": "...", "en": "..."}, "source": "KernelSU Next risk.json"}
]
```

**Nội dung ban đầu: Cam phải duyệt.** Bản nháp chỉ lấy các mục `high`/`extreme` của Next (spyware, backdoor lấy OTP, lệnh phá phân vùng), để mức `block`. Các mục `low`/`medium` của Next là nhận xét chủ quan ("có dấu hiệu code AI", "có thể bootloop") về module của người khác: không lấy. Đó là lời Next nói về module của người khác, Cam đăng lại là Cam chịu trách nhiệm.

**Việc:**
1. `HidingRules.moduleRisks` (parse, `toJson`), `ModuleRisk` + bộ khớp `ModuleRiskScanner` (Kotlin thuần: chuẩn hoá NFKC như Next, quét zip, khớp `module.prop`). Test JVM: khớp id, khớp mẫu qua dấu câu/hoa thường, không khớp từ rời nhau, giới hạn 8 MB, zip hỏng.
2. `flashModule`: trước `camd module install`, quét zip; `block` thì dừng, `warn` thì in rồi cài tiếp. Gọi `HidingRulesRepository.check(force = false)` trước (tối đa 6 giờ một lần) để có rule mới.
3. Chip trên thẻ module + thẻ "Chặn module nguy hiểm" trong tab Tính năng (công tắc, danh sách module đã cài bị khớp, số mục trong rule, phiên bản rule).
4. Rule v9: thêm `moduleRisks`, ký lại. `sign_hiding_rules.py` kiểm thêm định dạng `moduleRisks` nếu có.
5. `CAM_CHANGES.md`: dòng mới + mục 12.

## 3. Sao lưu cả module (ý từ KernelSU Next `BackupRestore.kt`)

**Next làm gì:** `busybox tar -cpf` cả `/data/adb/modules`; khôi phục thì `tar -xpf` vào `/data/adb/modules_update` rồi khởi động lại. Không kiểm gì trong file tar (đường dẫn `..`, file lạ), không có cờ `update` nên camd/ksud không biết có module chờ.

**Cam làm khác:**
- **camd làm việc nặng** (cần root, cần giữ quyền, symlink, whiteout như `copy_module_tree`):
  - `camd module export <out.tar> [id...]`: `busybox tar` các thư mục `/data/adb/modules/<id>` (mặc định mọi module không chờ gỡ), kèm `cam-modules.json` (`format: cam-modules`, `version: 1`, ngày, mỗi module `id`/`name`/`version`/`versionCode`/`enabled`). Bỏ cờ `update`/`remove`.
  - `camd module import <in.tar> [--only id,...] [--list]`: `--list` đọc manifest, in JSON (Manager hiện cho chọn). Nhập: liệt kê entry trước (`tar -tf`), từ chối đường dẫn tuyệt đối, `..`, entry nằm ngoài `<id>/` của id trong manifest; giải nén vào thư mục tạm trong `/data/adb/cam/`; mỗi module kiểm `module.prop` có `id` đúng tên thư mục (`validate_module_id`); rồi **đi đúng đường khôi phục bản cũ đang có** (`restore` trong `module.rs`: chép vào `modules_update/<id>`, cờ `update`), nên boot sau camd thay thư mục như một lần cập nhật bình thường, chống bootloop coi là module mới đổi, tự quét ẩn root chạy.
  - Không chạy lại `customize.sh` (thư mục đã cài không còn bố cục zip gốc, chạy lại có thể hỏng).
- **Manager:** trang Sao lưu cấu hình (Cài đặt → Sao lưu cấu hình) thêm phần "Module": "Sao lưu module" (SAF, file `cam-modules-<ngày>.tar`), "Khôi phục module" (chọn file → danh sách module trong file có ô chọn, đánh dấu module đã có / khác phiên bản → nhập → mời khởi động lại). Khi khôi phục cấu hình mà thiếu module (`config_backup_missing_modules`), gợi ý khôi phục module.
- Module bị `moduleRisks` chặn thì không cho nhập (dùng chung bộ khớp `module.prop` ở mục 1).

**Việc:** camd (`module_archive.rs` mới, lệnh CLI, test host cho phần kiểm entry và manifest), Manager (CLI wrapper trong file Cli riêng của Cam, UI, chuỗi vi/en), `CAM_CHANGES.md`. Thử trên máy: sao lưu → gỡ module → khôi phục → khởi động lại → module chạy, quyền file/SELinux đúng (`ls -lZ`).

## 2. Umount tùy chỉnh có lưu (ý từ SukiSU `umount.rs` + `UmountManager`)

**Suki làm gì:** thêm ioctl liệt kê danh sách umount của kernel, lưu danh sách ra file nhị phân, nạp lại lúc boot; màn quản lý thêm/xoá đường dẫn và flag.

**Vấn đề riêng của Cam** (`2026-10-15-kernel-umount-review.md`): không có metamodule thì `ksu_module_mounted` = false và kernel umount không chạy gì. Thêm đường dẫn vào danh sách mà không báo `EVENT_MODULE_MOUNTED` thì vô dụng. Mặt khác, chỗ mục này có ích là đúng chỗ review còn bỏ ngỏ: **module tự `mount`** (không qua metamodule) thì kernel không gỡ, trang Kiểm tra chỉ khuyên tắt module (`hiding_advice_module_mounts`).

**Thiết kế:**
- camd lưu `/data/adb/cam/umount.json` (`[{path, flags}]`); `camd umount add/del` thêm `--persist` (Manager luôn dùng), thêm `camd umount list` (đọc file, không cần ioctl mới, không đổi kernel/uapi).
- `post-fs-data` (trước zygote): nạp danh sách vào kernel; danh sách khác rỗng thì gọi `report_module_mounted` để kernel umount chạy.
- An toàn (mục 1 và 3 của review): từ chối `/`, `/system`, `/vendor`, `/product`, `/system_ext`, `/odm`, `/data`, `/apex`, `/sdcard`, `/storage` và mọi đường dẫn không tuyệt đối/có `..`; chỉ cho flag 0 hoặc `MNT_DETACH` (2).
- Manager: thẻ "Umount tùy chỉnh" trong tab Tính năng (danh sách, thêm, xoá; ghi chú "có hiệu lực với app mở sau đó", cần bật Kernel umount). Trang Kiểm tra: phát hiện `moduleMounts` có nút "Thêm vào umount" với các đường dẫn đó.

**Chưa làm vì:** chưa biết có module nào của khách tự mount không (Zygisk Next, module gyro). Kiểm trên tablet trước: `grep` mountinfo của zygote và app, đọc script của module gyro. Không có ai tự mount thì làm thẻ cũng không ai dùng; khi đó chỉ làm phần camd (rẻ) hoặc bỏ. Tablet không vào được adb lúc lên kế hoạch (2026-10-16).
