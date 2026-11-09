# N1 — hide-bootloader standalone điền `ro.boot.vbmeta.public_key_digest` (code + host test)

> **Trạng thái (2026-11-09): code + host test xong, đã đo trên TB323FU** (mục "Verify trên máy").
> TB323FU **không có** prop `ro.boot.vbmeta.public_key_digest` nên N1 không đổi gì trên dòng máy
> này (đã thử bật/tắt ẩn bootloader: không prop nào khác đi). Công thức `sha256(key blob)` chưa có
> máy nào để so (TB323FU attest SHA-256 của key rỗng); camd đọc header đúng (khớp parser độc lập).

**Ngày:** 2026-11-09
**Nhánh:** main
**Phạm vi:** chỉ đường **standalone hide-bootloader**. Đường attestation (`attest::apply_vbmeta_props`,
2026-11-09, commit `622e8e30`) đã khớp prop này từ `bootInfo.verifiedBootKey`; N1 không đụng đường đó.

## Bối cảnh

Plan `done/2026-10-11-bootloader-vbmeta-attestation.md` (Phần A, dòng 13) định điền **cả**
`ro.boot.vbmeta.digest` **và** `ro.boot.vbmeta.public_key_digest` bằng giá trị thật tính từ phân vùng,
chỉ khi prop đang trống/toàn 0. Nhưng dòng 86–88 của chính plan đó ghi quyết định ngược lại: **bỏ**
`public_key_digest`, vì máy TB323FU đang khoá/green thật mà prop này **để trống** → điền vào là lệch
máy khoá thật, tức thành dấu hiệu. ("Để trống" ở đó nghĩa là **không có prop**: `getprop`,
`resetprop` và `/proc/bootconfig` đều không có dòng nào, đo lại 2026-11-09.)

Quyết định đó đúng **cho TB323FU** (EFISP, bootloader thật). Nhưng nó để lại lỗ trên máy khác:
máy unlock bật **ẩn bootloader mà không bật attestation** thì `ro.boot.vbmeta.device_state` đã giả
`locked` trong khi `public_key_digest` vẫn rỗng/0 → detector đọc hai prop thấy lệch. Đó là nợ N1.

```
hide_bootloader::apply()
  ├─ plan(props)          → device_state → "locked"           ✅
  └─ fill_vbmeta_digest() → ro.boot.vbmeta.digest             ✅
                            ro.boot.vbmeta.public_key_digest   ❌ (N1)
```

## Đặc tả — công thức

`AvbVBMetaImageHeader` là 256 byte, **tất cả big-endian**. Các field liên quan:

| Offset | Field | Ghi chú |
|---|---|---|
| 0 | magic | `AVB0` |
| 12 | authentication_data_block_size | |
| 20 | auxiliary_data_block_size | |
| 28 | algorithm_type | `2` = SHA256_RSA4096 (không dùng cho N1) |
| 64 | **public_key_offset** | mới dùng ở N1 — tính từ đầu aux |
| 72 | **public_key_size** | mới dùng ở N1 |
| 80 | public_key_metadata_offset | **không** dùng (bản đầu đọc nhầm field này, sửa 2026-11-09) |
| 88 | public_key_metadata_size | **không** dùng |
| 96 | descriptors_offset | tính từ đầu aux |
| 104 | descriptors_size | |

Công thức: SHA-256 của blob key mà `avb_vbmeta_image_verify` trả ra ở `out_public_key_data`
(định dạng `AvbRSAPublicKeyHeader` của AVB, không phải DER). `avb_slot_verify_data_calculate_vbmeta_digest`
là hàm của `.digest`, không phải prop này.

```
aux_start  = 256 + authentication_data_block_size
key_blob   = blob[aux_start + public_key_offset .. + public_key_size]
prop_value = lowercase_hex( SHA-256(key_blob) )
```

Ba điều **không** làm, vì đã sai theo lý thuyết và sẽ lộ:

1. **Không** hash cả struct vbmeta — `ro.boot.vbmeta.digest` là cái đó, đây là prop khác.
2. **Không** hash `auth` block / chứng chỉ ký — chỉ blob key trong aux.
3. **Không** đi sâu chain. Key của `vbmeta_system`/`vbmeta_vendor` nằm trong **chain descriptor**,
   không ở aux block của nó. Nên chỉ đọc struct **top-level `vbmeta`**.

Luật điền giữ nguyên như `digest`:

- chỉ điền khi prop **đã tồn tại** (không thêm prop máy chưa có — thêm cũng là dấu hiệu);
- chỉ điền khi giá trị hiện tại **trống hoặc toàn `0`**;
- compute lỗi → **không điền** (điền sai tệ hơn để trống);
- `public_key_size == 0` (struct không có key riêng) → `None`. Bootloader trên máy như vậy cũng để
  trống prop, nên bỏ qua chính là khớp.

## Task 1 — `userspace/camd/src/vbmeta.rs`

- `Header` thêm `public_key_offset: u64` (offset 64 là `public_key_offset`, 72 là `public_key_size`,
  theo `AvbVBMetaImageHeader` của AOSP). **Bản đầu đọc nhầm 80/88** (`public_key_metadata_*`): trên
  ảnh avbtool thật, 80 = descriptors + key (khác 0), 88 = 0 → băm descriptors + key và điền giá trị
  rác. Test cũ dựng header bằng cùng offset sai nên không bắt được; test
  `reads_key_fields_not_metadata_fields` dựng header đủ field như avbtool và fail với 80/88.
- `fn public_key_digest(blob: &[u8], h: &Header) -> Option<String>` — **thuần, host-test được**:
  - `key_size == 0` → `None`;
  - `public_key_offset + key_size > aux_size` → `None` (struct hỏng, không hash rác phía sau);
  - `aux_start = HEADER_SIZE + auth_size`, cắt `blob[aux_start + offset .. + size]`, SHA-256, hex
    lowercase (`sha256::digest` của crate `sha256 = "1"`, cùng hàm `computed_digest` đang dùng).
- `pub fn computed_public_key_digest() -> Option<String>` — phần Android: `partition_struct("vbmeta")`
  (đã có, xử lý cả partition có footer) → `parse_header` → `public_key_digest`. **Không** gọi `collect`.
- Không thêm crate/dependency. `#[cfg(target_os = "android")]` như phần còn lại của file.

## Task 2 — `userspace/camd/src/hide_bootloader.rs`

- Thêm const `VBMETA_PUBLIC_KEY_DIGEST = "ro.boot.vbmeta.public_key_digest"` cạnh `VBMETA_DIGEST`.
- `fill_vbmeta_digest` → **`fill_vbmeta_props`**: gọi helper `fill_vbmeta_prop` hai lần, một cho
  `digest` (`vbmeta::computed_digest`), một cho `public_key_digest` (`vbmeta::computed_public_key_digest`).
  Tên cũ không còn đúng việc nó làm.
- `fill_vbmeta_prop(props, prop, compute: fn() -> Option<String>, on_miss: &str)` — luật chung,
  nằm ở hàm thuần `needs_fill(props, prop)` (cấp module, host-test được): prop không có mặt → bỏ qua;
  giá trị khác rỗng và không toàn `0` → bỏ qua (không ghi đè thật); `compute` ra `None` → `warn!` và
  để nguyên; `Ok` → `set_prop` + `info!`. Hai const `VBMETA_DIGEST`/`VBMETA_PUBLIC_KEY_DIGEST` cũng
  lên cấp module để test dùng.
- `status()` in thêm `computedVbmetaPublicKeyDigest` (cạnh `computedVbmetaDigest`) để đối chiếu.

## Task 3 — thứ tự chạy (không đổi, nhưng ghi lại cho rõ)

```
attest::start()        [khi attestation bật]
  ├─ hide_bootloader::apply()      ← điền digest + public_key_digest tự tính
  └─ attest::apply_vbmeta_props()  ← ép lại từ push.json bootInfo, THẮNG
```

`apply_vbmeta_props` chạy **sau** nên vẫn là nguồn chân lý khi attestation bật. N1 chỉ cứu đường
standalone. Không đảo thứ tự này.

## Test

Host (WSL, `CARGO_TARGET_DIR=/root/ksud-target`):

```bash
cd userspace/camd
cargo fmt -p camd
cargo test --bins
```

8 test mới (6 trong `vbmeta::tests`, 2 trong `hide_bootloader::tests`):

| Test | Chốt điều gì |
|---|---|
| `hashes_only_the_public_key_blob` | hash đúng blob 300 byte, không parse/canonicalise |
| `public_key_offset_is_relative_to_aux_not_header` | offset tính từ đầu aux; auth block 64 + 40 byte đệm đi trước đều bị bỏ qua |
| `no_key_yields_none` | `public_key_size == 0` → `None`, không bịa prop |
| `key_outside_aux_yields_none` | size vượt `aux_size` → `None`, không hash rác |
| `truncated_struct_yields_none` | aux bị cắt → `None` |
| `reads_key_fields_not_metadata_fields` | header đủ field như avbtool (metadata_offset = sau key, size 0, aux pad 64); fail nếu đọc 80/88 thay vì 64/72 (đã thử: offset đọc ra 0 thay vì 40) |
| `needs_fill_only_present_and_blank` | rỗng / toàn `0` → điền; giá trị thật → không ghi đè; không có prop → không thêm |
| `genuine_tb323fu_gets_nothing_filled` | bộ prop `ro.boot.vbmeta.*` thật của TB323FU (2026-11-09) → không điền gì, `plan` rỗng |

Kết quả: **175 passed / 0 failed** (167 test cũ + 8 mới). `cargo fmt` sạch.

`cargo clippy --target x86_64-unknown-linux-gnu --all-targets -- -D warnings`: trước fail 38 lỗi
(dead code của các hàm thuần chỉ dùng trên Android trong `vbmeta.rs`, `attest/`, `allow.rs`,
`defs::source_id`; `cast_lossless` trong helper test `b64_encode`). Đã dọn 2026-11-09 theo mẫu sẵn
có của repo (`#![cfg_attr(not(target_os = "android"), allow(dead_code))]`, `u32::from`): **0 lỗi**.

`cargo ndk -t arm64-v8a check/clippy` (WSL, NDK `/root/ndk/android-ndk-r28c`): sạch, không cảnh
báo (2026-11-09).

## Verify trên máy — đã làm trên TB323FU (2026-11-09)

Kết quả trên TB323FU (Android 16, slot `_b`, khoá/green thật qua EFISP, camd 1.2.6-beta.1 + camd thử
build từ cây làm việc đặt ở `/data/local/tmp`, không ghi đè `/data/adb/camd`):

| Nguồn | Giá trị |
|---|---|
| `getprop` / `resetprop` / `/proc/bootconfig` `…public_key_digest` | **không có prop** |
| `ro.boot.vbmeta.digest` | `ab3cdba0…5b24e207` |
| Header `vbmeta_b` (parser Python độc lập) | `public_key_offset=7464`, `public_key_size=1032`, metadata `offset=8496`/`size=0`, descriptors `7464` byte |
| `sha256(key blob)`: Python / `camd hide-bootloader status` | `7b34b551…6c819281` / `7b34b551…6c819281` (khớp) |
| Bản nháp 80/88 sẽ băm | 8496 byte (descriptors + key) → `133c6f86…` (rác) |
| TEE thật, RootOfTrust `verifiedBootHash` | `ab3cdba0…5b24e207` = `.digest` = `computedVbmetaDigest` |
| TEE thật, RootOfTrust `verifiedBootKey` | `e3b0c442…7852b855` = **SHA-256 của chuỗi rỗng** |
| `camd hide-bootloader enable` rồi `disable` (camd thử) | 758 prop trước/sau **giống hệt**; cờ tạo rồi xoá |

Kết luận:

- Dòng TB323FU (Y700) không tạo prop này và bootloader đưa key **rỗng** cho TEE. N1 không làm gì trên
  dòng máy này, đúng ý quyết định 2026-10-11; `genuine_tb323fu_gets_nothing_filled` chốt lại.
- Phần đọc header đã đúng trên ảnh thật. Riêng công thức `sha256(key blob)` vẫn **chưa có máy nào để
  so**: cần một dòng máy khoá có prop này (Pixel…). Đến lúc đó so `getprop` với
  `computedVbmetaPublicKeyDigest`; nếu lệch thì kiểm bằng `avbtool info_image` / `extract_public_key`.
- RootOfTrust thật lấy bằng một class dùng một lần chạy qua `app_process` dưới quyền shell
  (tạo khoá EC attest như `AttestationCheck.kt`, in chứng chỉ lá, xoá khoá). File dex đã xoá khỏi máy.

Cách đo lại trên máy khác (chỉ đọc):

```bash
adb shell getprop ro.boot.vbmeta.public_key_digest
adb shell su -c "/data/adb/camd hide-bootloader status" | grep computedVbmetaPublicKeyDigest
```

### Bản cũ của mục này (trước khi đo), giữ để biết đã định cắt lỗi thế nào

Plan mẹ ghi rõ (dòng 40): *"Phải so với giá trị thật trên một máy locked để chốt đúng
input/thuật toán (đừng đoán)"*. Trên **một máy locked bất kỳ** (không cần unlock):

```bash
adb shell getprop ro.boot.vbmeta.public_key_digest
adb shell /data/adb/cam/camd hide-bootloader status | grep computedVbmetaPublicKeyDigest
```

Hai giá trị phải **trùng byte-for-byte**. Nếu lệch, cắt theo thứ tự:

1. **Offset sai gốc** — đang tính từ đầu `blob` thay vì từ đầu aux. Kiểm bằng `avbtool info_image
   --image vbmeta.img` để đọc đúng `Public key offset` / `Public key size` của máy đó.
2. ~~DER wrapper~~ — bỏ: blob key trong aux là `AvbRSAPublicKeyHeader` của AVB, không có lớp ASN.1.
3. **Key ở vbmeta con** — máy có top-level `vbmeta` không mang key, key nằm ở chain descriptor. Trong
   trường hợp đó prop thật cũng thường rỗng → `None` là hành vi đúng, không phải bug.

Nếu máy locked **có** prop và **khớp** → ghi vào `CAM_CHANGES.md`, chốt công thức. Nếu máy locked
**để trống** prop này (như TB323FU) → ghi vào plan mẹ: N1 chỉ có tác dụng trên dòng máy có prop;
dòng để trống thì để trống chính là hành vi đúng.

## Rủi ro

Điền sai tệ hơn để trống (thành dấu hiệu riêng). Ba lớp chặn đã có: chỉ điền khi prop tồn tại và
trống/0, `None` khi parse hỏng, và attestation (nếu bật) ghi đè sau. Trên TB323FU không đổi hành vi
(đã đo, xem trên). Trên một dòng máy khoá thật có prop mà để trống thì camd **sẽ** điền: code không
phân biệt máy khoá thật với máy giả khoá; chưa gặp dòng máy nào như vậy.

Không làm: bỏ qua điền khi máy boot bằng khoá AVB riêng (`yellow`). Ở trạng thái đó TEE attest đúng
hash của khoá riêng ấy, nên giá trị điền vào khớp attestation; khách dùng EFISP, không có khoá riêng.

## Ghi tài liệu

- `docs/superpowers/plans/2026-11-09-bootloader-debt.md`: N1 đánh dấu code xong, còn verify máy.
- `docs/CAM_CHANGES.md` mục "Tự ẩn bootloader": sửa dòng 345 (đang ghi "**Bỏ** `public_key_digest`")
  thành trạng thái mới, kèm chỗ chưa kiểm.