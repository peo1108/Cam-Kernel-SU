# Plan: vbmeta digest + patch level cho ẩn bootloader (task 1)

**Ngày:** 2026-10-11
**Bối cảnh:** Học cách TEESimulator (`JingMatrix/TEESimulator`, `VbMeta.kt` / `DeviceProps.kt`) xử lý
verified-boot và patch level, rồi làm lại gọn trong Cam. **Không** đụng keybox / giả chứng chỉ
attestation (tầng keystore2) — đó là việc khác, không nằm trong plan này.

Mở rộng tính năng ẩn bootloader sẵn có (`userspace/camd/src/hide_bootloader.rs`,
`manager/.../ui/util/AttestationCheck.kt`). Hai phần độc lập, làm được riêng:

- **A — prop vbmeta** (camd): điền `ro.boot.vbmeta.digest` và `ro.boot.vbmeta.public_key_digest`
  **bằng giá trị thật tính từ phân vùng của máy**, chỉ khi prop đang trống hoặc toàn 0.
- **B — patch level trên trang Kiểm tra** (Manager): đọc thêm os/vendor/boot patch level từ
  chứng chỉ attestation và hiển thị. Chỉ là thông tin, không sửa gì.

Làm **B trước** (nhỏ, không rủi ro), A sau (cần đọc/parse AVB, phải thử trên máy).

---

## Phần B — patch level trên trang Kiểm tra (nhỏ)

Hiện `AttestationInfo` chỉ có `securityLevel`, `deviceLocked`, `verifiedBootState`. Thêm patch level
mà một app attesting đọc được, để trang chỉ rõ máy đang khai báo mức vá nào.

**File:**
- `manager/app/src/main/java/cam/su/kernel/data/model/KeyAttestation.kt`
  - Thêm vào `AttestationInfo`: `osVersion: Int?`, `osPatchLevel: Int?`, `vendorPatchLevel: Int?`,
    `bootPatchLevel: Int?` (null = chứng chỉ không khai báo tag đó).
  - Trong `parseKeyDescription`, đọc thêm từ `hardwareEnforced` (fallback `softwareEnforced`,
    cùng chỗ với RootOfTrust tag 704) các context tag của AuthorizationList:
    - `705` osVersion, `706` osPatchLevel, `718` vendorPatchLevel, `719` bootPatchLevel.
    - Mỗi tag là `[n] EXPLICIT INTEGER`, đọc như `findContextTag(n)?.children()?.first()?.intValue()`.
      **Phải kiểm bằng chứng chỉ thật** (có cert có thể bọc trực tiếp INTEGER, không bọc thêm) —
      viết parse chịu được cả hai (nếu con đầu là INTEGER thì đọc luôn, nếu là context thì vào trong).
  - Encode theo chuẩn KeyMint: osPatchLevel = `YYYYMM`, vendor/boot = `YYYYMMDD`, osVersion =
    `major*10000 + minor*100 + sub` (tham khảo `DeviceProps.kt`). Chỉ cần hàm format để hiển thị.
- `manager/app/src/main/java/cam/su/kernel/ui/component/AttestationResult.kt`
  - Sau dòng verified boot state, thêm các dòng patch level (chỉ hiện khi không null).
    Đây là dòng thông tin (không có ✓/⚠ "đúng/sai") — hoặc để ✓ trung tính; quyết định khi làm.
  - Dùng lại `AttestationLine`; component này đã chung cho thẻ Tính năng và trang Kiểm tra nên
    cả hai nơi tự có.
- `res/values/strings.xml`, `res/values-vi/strings.xml`: thêm chuỗi
  `features_attestation_os_patch`, `_vendor_patch`, `_boot_patch`, `_os_version` (format có 1 tham số).

**Test:** `HidingCheck*`/attestation không có unit test cho parse; thêm 1 test trong
`manager/.../data/model/` (nếu có sẵn test cho `parseKeyDescription` thì mở rộng) với một
KeyDescription dựng tay chứa các tag patch level. Build `:app:assembleRelease` + mắt thường trên máy.

**Rủi ro:** thấp — chỉ đọc và hiển thị. Sai số tag chỉ làm hiện thiếu/sai dòng, không ảnh hưởng máy.

---

## Phần A — điền prop vbmeta bằng giá trị thật (vừa)

`ro.boot.vbmeta.digest` / `public_key_digest` là **giá trị riêng của từng máy**, không phải hằng số,
nên **không** cho vào `SUFFIX_RULES`/`EXACT_RULES` (đó chỉ nhận giá trị cố định như `green`, `locked`).
Trên máy mở khoá hai prop này có thể trống hoặc toàn 0; bootloader đáng lẽ điền nhưng không điền (#228
của TEESimulator). Cách làm: tự tính digest **thật** từ phân vùng vbmeta của máy rồi điền vào —
không bịa giá trị, chỉ khôi phục giá trị máy lẽ ra phải có.

**File mới:** `userspace/camd/src/vbmeta.rs` — làm lại logic từ `VbMeta.kt` của TEESimulator
(viết lại, không chép; TEESimulator là GPLv3, Cam cũng GPL nên tương thích, nhưng vẫn viết mới):
- `compute_digest() -> Option<Vec<u8>>`: hash nối các struct vbmeta, bắt đầu từ `vbmeta<slot>`, rồi
  từng phân vùng chained theo thứ tự descriptor, depth-first. Hash chọn theo algorithm của struct
  top-level (SHA-256 mặc định, SHA-512 nếu algorithm là biến thể 512; ảnh NONE → SHA-256).
- `public_key_digest() -> Option<Vec<u8>>`: digest của public key AVB dùng để verify vbmeta
  top-level (lấy từ vùng aux theo `public_key_offset`/`public_key_size` trong header AVB0).
  **Phải xác nhận hàm hash** mà bootloader dùng cho `ro.boot.vbmeta.public_key_digest` trước khi
  tin (AVB thường SHA-256) — nếu không chắc thì **không điền** prop này, chỉ làm digest.
- Đọc phân vùng: `/dev/block/by-name/<tên><slot>`, slot lấy như `boot_patch.rs::get_slot_suffix`.
  Phân vùng chuyên dụng (`vbmeta`, `vbmeta_system`…) struct ở offset 0; phân vùng có footer
  (`boot`, `system`…) struct nằm qua `AvbFooter` ở 64 byte cuối. Giới hạn số phân vùng để tránh
  chain lặp vô hạn. Mọi lỗi đọc/parse → trả `None` (không điền còn hơn điền sai).

**Tích hợp:** trong `hide_bootloader::apply_if_enabled` (sau vòng `plan`), thêm bước "điền vbmeta":
cho từng prop trong hai prop, nếu giá trị hiện tại trống hoặc toàn `0`/`00..`, tính giá trị thật,
có thì `set_prop`. **Không bao giờ ghi đè** giá trị khác 0 đang có.
- Mở `#[cfg(target_os = "android")]` như phần còn lại; `vbmeta.rs` tách hàm parse (thuần, test host
  được) khỏi phần đọc `/dev/block` (Android).
- `status_json`: thêm hai prop này vào danh sách báo cáo để trang Kiểm tra hiện trạng thái
  (giống các prop khác, đếm vào số mục khi đã khớp).

**Đã chốt + đo trên máy TB323FU (2026-10-11):**
1. **Chỉ điền `ro.boot.vbmeta.digest`**, **bỏ `public_key_digest`**. Máy TB323FU đang khoá/green
   (EFISP, bootloader thật) nhưng `ro.boot.vbmeta.public_key_digest` **để trống** → "trông giống
   máy khoá" của dòng này là prop đó trống; điền vào sẽ lệch máy khoá thật. Nên không đụng.
2. **Thuật toán digest đã validate khớp 100%**: script `scratchpad/vbval.py` dựng lại từ các phân
   vùng (`vbmeta` → chain `boot`, `recovery`, `vbmeta_system`, hash SHA-256 theo algorithm_type=2)
   ra đúng `ab3cdba0…207` = `ro.boot.vbmeta.digest` của máy. `boot`/`recovery` lấy struct qua
   AvbFooter (vbmeta nằm ngay sau ảnh gốc, không ở cuối phân vùng), `vbmeta_system` ở offset 0.
3. **Đã làm** (module `userspace/camd/src/vbmeta.rs`, nối vào `hide_bootloader`): `computed_digest()`
   tính digest; `apply_if_enabled` điền `ro.boot.vbmeta.digest` chỉ khi prop **có mặt và trống/toàn 0**
   (không ghi đè giá trị thật, không thêm prop máy chưa có); `status` in thêm `computedVbmetaDigest`
   để đối chiếu. TB323FU prop đã có sẵn giá trị thật nên không bị ghi đè — kiểm bằng `status`.

**Test:**
- Host (WSL): `cargo test --target x86_64-unknown-linux-gnu` với vbmeta blob dựng tay cho hàm parse
  (header AVB0, 1 descriptor chain, footer) → digest khớp giá trị tính tay bằng `avbtool`.
- `cargo ndk -t arm64-v8a check` / `clippy` / `cargo fmt` (bắt buộc theo AGENTS.md).
- **Trên máy thật (bắt buộc trước khi tin):** máy mở khoá có prop trống/0 → bật ẩn bootloader →
  `getprop ro.boot.vbmeta.digest` ra đúng giá trị `avbtool calculate_vbmeta_digest` tính; máy đã có
  sẵn digest thật → không bị ghi đè. Chưa thử trên máy thì ghi rõ "chưa kiểm" trong CAM_CHANGES.

**Rủi ro:** điền digest sai còn tệ hơn để trống (thành dấu hiệu). Vì vậy: chỉ điền khi trống/0,
parse lỗi thì bỏ, và phải đối chiếu với `avbtool` trên máy trước khi bật mặc định.

---

## Ghi tài liệu khi xong
- `docs/CAM_CHANGES.md` mục "Tự ẩn bootloader" (dòng 184–187): ghi hai prop mới + patch level, và
  phần nào đã/chưa kiểm trên máy.
- Mục 6 (Test): thêm lệnh test vbmeta nếu có.
