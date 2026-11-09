# Plan nợ: Ẩn bootloader + verified-boot (root of trust)

**Ngày:** 2026-11-09
**Nhánh:** main
**Phạm vi:** gom MỌI "nợ" liên quan tới **ẩn bootloader / verified boot / vbmeta** vào một chỗ:
prop `ro.boot.vbmeta.*`, trạng thái locked/Verified, và root of trust mà key attestation ký.
Đây là bản rút gọn + theo dõi; chi tiết end-to-end của tầng attestation vẫn ở
`docs/superpowers/plans/2026-11-08-attestation-keystore2-m3-m4.md` (phần "PARKED"). Nguồn chân lý khi
resume: plan đó + `git log` + memory `attestation-keystore2`.

## Bối cảnh

- **Ẩn bootloader** (`userspace/camd/src/hide_bootloader.rs` + `vbmeta.rs`): trên máy unlock, ép các
  `ro.boot.*` trông như locked/Verified. Đã phát hành (3.0.9; `docs/CAM_CHANGES.md` mục "Tự ẩn bootloader").
- **Tầng 2 attestation** (keystore2, giả hardware key attestation): root of trust mà TA ký **chính là**
  verified boot (key/hash/locked/state). Vì vậy nợ attestation phần nào là nợ bootloader.
- **2026-11-09:** vá 2 gap TEESimulator-parity — commit `194d76d5` (manager: thay verifiedBootKey/Hash
  toàn 0) + `622e8e30` (camd: ép `public_key_digest`+`digest` khớp cert). Unit test xanh.

## Danh sách nợ

### N1 — hide-bootloader standalone chưa điền `ro.boot.vbmeta.public_key_digest` · [XONG 2026-11-09, đã đo TB323FU]

> **2026-11-09 — code + host test xong, đã đo trên TB323FU.** Chi tiết ở
> `docs/superpowers/plans/done/2026-11-09-n1-vbmeta-public-key-digest.md`.
> `vbmeta::public_key_digest` (thuần) + `computed_public_key_digest` (đọc top-level `vbmeta` only);
> `fill_vbmeta_digest` → `fill_vbmeta_props`, luật chung ở hàm thuần `needs_fill`;
> `status` in `computedVbmetaPublicKeyDigest`. 175 test xanh; `fmt`, `cargo ndk check/clippy` và clippy
> host `-D warnings` sạch. Bản đầu đọc nhầm offset 80/88 (`public_key_metadata_*`) thay vì 64/72; đã
> sửa, có test chặn.
> **Trên TB323FU:** prop này **không tồn tại**, TEE attest `verifiedBootKey` = SHA-256 của key rỗng
> (`e3b0c442…`) → N1 không đổi gì trên dòng máy này (đã thử bật/tắt: 758 prop giống hệt). camd đọc
> header thật đúng (`7b34b551…`, khớp parser độc lập).
> **Còn mở (không chặn):** công thức `sha256(key blob)` chưa có máy nào để so; cần một dòng máy khoá
> có prop này.

- Plan 2026-10-11 (Phần A, `docs/superpowers/plans/done/2026-10-11-bootloader-vbmeta-attestation.md`
  dòng 13) định điền **CẢ** `ro.boot.vbmeta.digest` **VÀ** `ro.boot.vbmeta.public_key_digest` bằng giá
  trị thật tính từ phân vùng, chỉ khi prop đang trống/toàn 0. Thực tế `hide_bootloader::fill_vbmeta_digest`
  (`hide_bootloader.rs`) **chỉ điền `digest`**; `vbmeta.rs` không có hàm tính public-key digest.
- Hệ quả: máy unlock bật **ẩn bootloader mà KHÔNG bật attestation** → `public_key_digest` vẫn rỗng/0
  trong khi `ro.boot.vbmeta.device_state` đã giả `locked` → một detector đọc hai prop thấy lệch.
- Lưu ý: khi **attestation BẬT**, đường này đã khớp nhờ `attest::apply_vbmeta_props` (2026-11-09, ép từ
  bootInfo). Nợ N1 chỉ còn ở đường **standalone hide-bootloader**.
- Việc:
  - Thêm vào `vbmeta.rs` hàm tái tạo `public_key_digest`: lấy blob public key trong aux block của
    vbmeta struct top-level (header có `public_key_offset`/`public_key_size`) rồi băm theo thuật toán
    của struct. **Phải so với giá trị thật trên một máy locked** để chốt đúng input/thuật toán (đừng
    đoán — ghi là bước verify trên máy).
  - Trong `fill_vbmeta_digest` (đổi tên cho đúng, vd `fill_vbmeta_props`), điền thêm `public_key_digest`
    giống `digest` (chỉ khi trống/0, không ghi đè giá trị thật).
  - Host-test phần thuần như `attest::boot_vbmeta_props`.
- **Không chặn phần cứng**: code + host-test ngay; chỉ cần một máy (locked bất kỳ) để xác nhận công thức.

### N2 — Verify 2 gap vừa vá trên máy unlock · [DEVICE]

- `194d76d5` (thay verifiedBootKey/Hash toàn 0) + `622e8e30` (ép `public_key_digest`+`digest` khớp cert).
  Unit test xanh (`HarvesterTest` 8/8; camd 167 host test), **chưa chạy máy unlock**.
- Verify (ghép với N3): sau khi bật attestation trên máy unlock → "Kiểm tra attestation" trong Manager
  hiện verifiedBootKey/Hash **khác 0**, và `getprop ro.boot.vbmeta.public_key_digest` **==** verifiedBootKey
  trong cert (và `.digest` == verifiedBootHash).
- **Phát hiện khi đo N1 (2026-11-09):** một Y700 khoá thật **không có** prop
  `public_key_digest` và attest `verifiedBootKey = e3b0c442…7852b855` (SHA-256 của key rỗng).
  (a) **Đã sửa trước beta.3:** `attest::apply_vbmeta_props` gọi `set_prop` không kiểm prop có sẵn
  → trên Y700 bật attestation sẽ **thêm** prop mà máy khoá thật không có (cert e3b0… không toàn 0
  nên không bị lọc). Nay `boot_vbmeta_props(push, present)` chỉ ép prop đã có
  (`resetprop::list_props`); test `boot_vbmeta_props_never_adds_an_absent_prop`.
  (b) **Còn mở:** máy unlock → TEE trả key toàn 0 → `chooseBootKeyB64` (Harvester) không có prop
  để lấy → dùng hằng `sha256("Cam/verified_boot_key")`, khác giá trị `e3b0…` mà mọi Y700 khoá thật
  attest. Cân nhắc khi làm N2: dùng `e3b0…` làm fallback cho dòng máy không có prop.

### N3 — M3-7 Step 4: verdict STRONG thật · [DEVICE]

(từ plan attestation) Cài APK → màn attest → harvest thật (`boot.json`) → import keybox → configure →
enable → reboot → xác nhận Play Integrity trả `MEETS_STRONG_INTEGRITY` **và** "Kiểm tra attestation"
hiện chain Google của keybox với locked / Verified / TEE (hoặc StrongBox).

### N4 — M4-4: regression trên máy · [DEVICE]

Chu trình enable→STRONG→disable, rồi `scripts/device_bootguard_test.sh` (attest bật, ~15 reboot tự dừng)
+ `python -X utf8 scripts/device_detector_scan.py` (Duck / Luna / Chunqiu) không tệ hơn
`scripts/detector_baselines/`.

### N5 — Keybox hết hạn 2026-11-13 · [RÀNG BUỘC]

`keybox test/keybox.xml` leaf hết hạn **2026-11-13**. Vì chưa có máy unlock, qua ngày đó keybox này chết →
cần **keybox mới còn hạn**, validate offline lại trước (chain → `Key Attestation CA1`, priv-key khớp leaf,
4 serial **vắng mặt** trong Google attestation status list). Pipeline/UI/cơ chế **không** phải làm lại;
keybox nạp lúc runtime, không build vào APK. KHÔNG commit keybox.

### N6 — Review hình thức màn attest trên máy · [UI, nhẹ, không chặn]

M3-6 ghi "LOOK still wants a visual review on device" — màn `ui/screen/attest/` cần người dùng tự tối ưu
hình thức trên máy thật.

## Tóm tắt chặn

- **Làm được ngay (không cần máy unlock):** N1 (code + host-test; cần một máy locked bất kỳ để chốt
  công thức) — **xong 2026-11-09, đã đo TB323FU** (dòng máy này không có prop), N6 (UI).
- **Chờ MÁY UNLOCK + keybox còn hạn:** N2, N3, N4 (gắn với N5).

## Resume

- Đọc trước: plan attestation `2026-11-08-attestation-keystore2-m3-m4.md` (phần PARKED), `git log`,
  memory `attestation-keystore2`.
- Môi trường build: WSL NDK `/root/ndk/android-ndk-r28c` (`ANDROID_NDK_HOME`, `LIBCLANG_PATH=$NDK/.../lib`,
  `CARGO_TARGET_DIR=/root/ksud-target`) cho `cargo ndk` camd; Gradle Manager chạy từ `K:\manager`.
- Trình tự mỗi mốc: `cargo ndk check/clippy` + `cargo fmt` + host test camd; `:app:testDebugUnitTest`
  Manager; rồi thử trên máy theo bảng. Trước release/đụng hiding: `device_detector_scan.py`.
