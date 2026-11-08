# Giả hardware key attestation (tầng keystore2)

Ngày: 2026-11-08
Nhánh: main
Phạm vi: `userspace/camd/` (module mới `attest/`), `manager/` (UI + harvest + resolve + key ops),
`native/teesim/` (vendored, mới), build CI, `docs/CAM_CHANGES.md` (gỡ ghi chú loại Tricky Store ở mục
danh sách module, ~dòng 942). Không đổi ABI supercall hiện có (nếu cần cờ bật/tắt thì thêm một supercall mới,
mục 9). Đây là "tầng 2" mà plan ẩn bootloader 2026-10-11 cố tình để lại
(`docs/superpowers/plans/done/2026-10-11-bootloader-vbmeta-attestation.md`, dòng 7–8; giới hạn ghi ở
`docs/CAM_CHANGES.md` dòng 343: "không đổi được chứng chỉ attestation (cần tầng keystore2, chưa làm)").

Bản spec này phủ **M1–M4** (tới end-to-end STRONG trên máy). M5 (device-id, xoá RKP, usage, patch-mode
re-sign, dọn UI) tách spec sau.

## 1. Mục tiêu

App mục tiêu do người dùng chọn (ví dụ `com.google.android.gms`, `com.android.vending`) khi gọi
**Android key attestation** sẽ nhận một chuỗi chứng chỉ **ký bằng keybox của người dùng**, với
RootOfTrust **locked / Verified**, patch level và OS version hợp lệ, sinh bởi một **KeyMint phần mềm**
chạy ngay trong `keystore2`. Mọi app khác vẫn dùng phần cứng thật, không đụng tới. Kết quả: app đích
qua được **Play Integrity `MEETS_STRONG_INTEGRITY`**, như Tricky Store / TEESimulator.

Mô hình giống hệt TEESimulator (`JingMatrix/TEESimulator`, nhánh `dev`): Cam lo **cơ chế**, keybox là
của người nạp. Cam **vendor lõi native** của TEESimulator (hai dự án cùng GPL, tương thích), **viết mới
lớp tích hợp** bằng camd + Manager.

### Giới hạn (đã chốt với người dùng, phải ghi rõ để khỏi kỳ vọng sai)

- **Không nâng Widevine L1.** Widevine dùng keybox *mediadrm* riêng, không liên quan attestation keybox.
  Việc cấp lại Widevine đã có riêng (`docs/CAM_CHANGES.md` dòng 65). Tầng này không đụng DRM level.
- **Sống/chết theo keybox.** Google thu hồi keybox rò rỉ qua `android.googleapis.com/attestation/status`
  (chính cái `manager/.../data/model/Revocation.kt` Cam đang đọc). Keybox bị thu hồi thì STRONG sập ngay.
  Cam chỉ lo cơ chế; nguồn/độ sống của keybox là của khách.
- **Chỉ keystore2 (Android 12+).** Khách gần như toàn Lenovo Y700 Gen5 (Android 15/16). **Bỏ hẳn** nhánh
  keystore legacy Android 10/11 của TEESimulator (`keystore/`, static BoringSSL, stub binder) — xem mục 10.

### Quyết định đã chốt

- Giả attestation đầy đủ (KeyMint phần mềm ký bằng keybox), **không** chỉ sửa trường trong attestation thật.
- Keybox do người dùng/khách tự lo; Cam chỉ đọc + validate + kiểm thu hồi, **không** phát keybox.
- Vendor lõi `rust/teesim-km` (TA) + `keymint/` (interceptor) của TEESimulator; chỉ nhúng các `.so`.
- Chia việc theo **Hướng 1**: Manager là bộ não (harvest/resolve/key-ops), camd là tay chân boot
  (inject/push/watch/boot-prop). Cam **không** nhúng daemon Kotlin hay WebUI của TEESimulator.
- Bỏ nhánh Android 10/11.

## 2. Lõi native vendor (3 artifact)

Đặt dưới `native/teesim/` (submodule hoặc cây con copy — mục 8). Build cho **arm64-v8a** (máy thật) và
**x86_64** (emulator Y700). Sản phẩm `.so`/`inject` là binary, gitignore như `libcamd.so`.

| Artifact | Nguồn upstream | Vai trò |
|---|---|---|
| `libteesim_km.a` | `rust/teesim-km` (Rust) | Reference KeyMint TA: sinh key, ký attestation bằng keybox, patch RootOfTrust. Phần CBOR/ASN.1 **không được sai một byte**. C ABI trong `rust/teesim-km/include/teesim_km.h`. |
| `libteesim_keymint.so` | `keymint/*` + `common/control.cpp`, `json.cpp`, `km_names.cpp`, `redact.cpp`, `log_context.cpp` | Interceptor hook `AIBinder_transact` trong keystore2 + **server socket control** (`control.cpp`). Export đúng một symbol `entry`. Nhúng `libteesim_km.a` (`--whole-archive`). |
| `inject` | `injector/*` + `common/log_context.cpp`, LSPlt | Bộ nạp ptrace: `inject <pid> <lib.so> entry`. |

**Giữ nguyên `common/control.cpp`** (giao thức control + bind socket): nhờ vậy camd chỉ cần **nói đúng
giao thức**, không phải sửa lõi. Tuỳ chọn: bỏ `libteesim_logcat.so` (log tail cho WebUI) — Cam dùng log
riêng; interceptor vẫn log qua `liblog` bình thường.

## 3. Hợp đồng camd phải khớp (đọc từ upstream, bất biến)

### 3.1 Control socket (lõi dựng, camd là client)
- Đường dẫn cố định trong `control.cpp`: **`/data/misc/keystore/.teesim-ctl`** (filesystem unix socket,
  `SOCK_STREAM`, trong thư mục 0700 của keystore → app không dò được). camd (uid 0) nối tới.
- Lõi gửi `hello` ngay khi nối: `{"type":"hello","role":"lib","protocol":1,"hook":"keymint","androidApi":N,"keystorePid":N}`.
  Đây là **bằng chứng interceptor đã nạp + bind socket** (đợi hello = xác nhận inject thành công).
- Khung: **`[u32 big-endian length][UTF-8 JSON]`**, tối đa 8 MiB/khung.
- Lõi chỉ nhận peer **uid 0** (`SO_PEERCRED`).

### 3.2 Thông điệp camd gửi
- `config` — một lần push đầy đủ. Áp dụng kiểu `begin(bootInfo)` → `add_profile` × N → `commit(epoch)`
  (atomic swap). Lõi trả `ack` `{type,epoch,ok,profilesApplied,profilesFailed}`.
  Hình dạng JSON (từ `ApplyConfig` trong `control.cpp`):
  ```jsonc
  {
    "type": "config", "epoch": <u64 tăng dần>,
    "redactSalt": "<b64, tuỳ chọn>",
    "bootInfo": {
      "verifiedBootKey": "<b64>", "verifiedBootHash": "<b64>", "moduleHash": "<b64, A16>",
      "deviceLocked": true, "verifiedBootState": 0,          // 0 Verified
      "strongBoxAvailable": false,
      "attestVersionTee": 400, "attestVersionStrongBox": 300
    },
    "rkpDeleted": [ ... ],                                    // M5; M1–M4 để []
    "profiles": [ {
      "id": "default", "mode": "generation", "securityLevel": 1,  // 1 TEE
      "keyboxB64": "<b64 XML keybox>",
      "osVersion": <u32>, "osPatchLevel": <YYYYMM>,
      "vendorPatchLevel": <YYYYMMDD>, "bootPatchLevel": <YYYYMMDD>,
      "deviceIds": { ... },                                  // M5; M1–M4 bỏ
      "packages": ["com.google.android.gms"], "packageUsers": [0],
      "uids": [10123], "uidPackages": ["com.google.android.gms"]
    } ]
  }
  ```
- `ping`/`pong` giữ nhịp (tuỳ chọn). `getUsage`, `resign` là M5.
- **Giải quyết (packages→uids), encode patch level, chọn verified-boot: Manager làm** rồi ghi nguyên
  khối này ra `push.json`. camd đọc file, thay `epoch`, gửi. camd **không** tự dựng JSON này.

### 3.3 Inject (camd lái binary)
- Vòng của camd lặp lại `app/.../Injector.kt`: tìm pid tiến trình tên `keystore2`
  (`/proc/<pid>/cmdline` basename), đợi service `android.system.keystore2.IKeystoreService/default`,
  chạy `inject <pid> <abi>/libteesim_keymint.so entry`, giữ qua mỗi lần keystore2 restart (pid đổi →
  inject lại), backoff khi lỗi. Xác nhận bằng **hello** đến trên control socket (không inject lại nếu
  đã hello — tránh hook đôi).

## 4. Kiến trúc (Hướng 1) — vì sao chia thế này

Ba việc nặng nhất của control daemon TEESimulator **bị trói vào Android framework/JVM**, Rust không làm
tự nhiên được, nên về Manager (đã có sẵn đồ nghề: keystore2 access, parse attestation DER trong
`KeyAttestation.kt`, đọc revocation trong `Revocation.kt`, root service `CamRootService`):

| Việc | Vì sao cần framework | Nơi làm |
|---|---|---|
| **Harvest** verified-boot key/hash/state, patch, attest version, module hash | Tạo hardware key qua AndroidKeyStore rồi đọc attestation; TEESimulator bootstrap `ActivityThread` giả trong `app_process` | **Manager** |
| **Resolve** tên gói → uid (per-user) | PackageManager | **Manager** |
| **Key ops owner-scoped** (purge/re-attest key app đích) | keystore2 ủy quyền theo **euid** → phải `seteuid` sang uid app rồi gọi keystore2 | **Manager** (qua root service) |

camd giữ đúng phần boot-headless nó vốn giỏi (spawn process, unix socket, JSON, inotify, ép prop). Cam
"native hoá hoàn toàn" vẫn đúng: không một dòng Kotlin daemon / WebUI nào của TEESimulator được nhúng.

### 4.1 Luồng dữ liệu
```
Lần đầu / khi build fingerprint đổi:
  Manager.Harvester → /data/adb/cam/attest/boot.json   (RoT, patch, attest version — ĐÓNG BĂNG)

Mỗi lần người dùng sửa cấu hình (chọn app, nạp keybox):
  Manager.Resolver → /data/adb/cam/attest/push.json    (bootInfo + profiles[] đã có uid)  ──┐
                                                                                            │ inotify
Mỗi boot (caminit nạp LKM → camd):                                                          ▼
  camd.attest: đọc boot.json + push.json
    → inject libteesim_keymint.so vào keystore2 (giữ qua restart)
    → nối .teesim-ctl → đợi hello → push (epoch++) → nhận ack
    → ép ro.boot.vbmeta.* (tái dùng hide_bootloader.rs)
    → watch push.json → đẩy lại khi đổi

Manager mở sau một lần:
    → re-attest/purge key app đích (owner-scoped) để chúng sinh lại qua TA
```

`boot.json` **đóng băng** vì verified-boot key/hash nuôi KEK derivation trong TA (blob phải giải mã được
qua các lần reboot). Harvest là việc một-lần-mỗi-build. Thiếu `boot.json`/`push.json` hoặc keybox →
interceptor **inert** (README upstream: "no keybox loaded → interceptor is a no-op"), máy an toàn.

### 4.2 camd — module mới `userspace/camd/src/attest/`
- `mod.rs` — nối vào `init_event.rs` (`on_post_fs_data`/`on_services`): nếu có `attest/push.json` thì
  khởi động; đọc cache; spawn vòng inject + client control; watch file.
- `inject.rs` — vòng theo pid keystore2, chạy `inject` (đường dẫn `.so`/`inject` dưới `LIBRARY_DIR`/
  `BINARY_DIR` hoặc jniLibs Manager), backoff, nhận biết restart.
- `control.rs` — client socket: khung `[u32 BE][JSON]`, đợi hello, gửi `config`, đọc `ack`, `ping`.
  Thuần Rust (`std::os::unix::net::UnixStream`), test host được phần codec.
- Hằng số trong `defs.rs`: `ATTEST_DIR = /data/adb/cam/attest/`, `ATTEST_PUSH`, `ATTEST_BOOT`,
  `TEESIM_CTL = /data/misc/keystore/.teesim-ctl`.

### 4.3 Manager — mới
- `ui/screen/attest/` — màn liệt kê app đích, nạp keybox, trạng thái (đã inject? app đích mấy cái?),
  nút "Kiểm tra attestation" (tái dùng `AttestationCheck.kt`) để tự xác minh STRONG.
- `attest/Harvester.kt` — sinh throwaway key, đọc RoT/patch/attest version/module hash, ghi `boot.json`;
  tái harvest khi `ro.build.fingerprint` đổi.
- `attest/Resolver.kt` — tên gói → uid/user (PackageManager), encode patch level theo KeyMint
  (osPatchLevel `YYYYMM`, vendor/boot `YYYYMMDD`, osVersion `major*10000+minor*100+sub`), dựng `push.json`.
- `attest/KeyboxStore.kt` — parse keybox XML (RSA + ECDSA), validate chuỗi, **kiểm thu hồi** qua
  `Revocation.kt` trước khi dùng; cảnh báo nếu đã thu hồi.
- Key ops owner-scoped: thêm vào `CamRootService` (AIDL `ICamRootService`) các lệnh purge/re-attest key
  theo uid chủ (service chạy root, `seteuid` sang uid app rồi gọi keystore2 như `App.runAsOwner`).

## 5. Build

- `scripts/build_teesim.sh` (WSL, như `scripts/build_camd.sh`): chạy CMake của `native/teesim/` chỉ với
  target `inject`, `teesim_keymint`, `rust_ta`; xuất theo ABI. Cần SDK (aidl ≥ build-tools 35, xem
  `CMakeLists.txt` dòng 60–114) + NDK; build **không phụ thuộc máy**.
- Đặt `.so`/`inject` vào nơi Manager đóng gói (jniLibs theo ABI) để có **đường dẫn filesystem** cho
  `inject` nạp vào keystore2 (linker namespace của keystore2 chỉ nạp qua fd-passing từ `/data/adb`/
  jniLibs, không nạp path `/data/local/tmp` — `injector/README.md`).
- CI: thêm job build teesim (như camd), gắn artifact; Manager build đòi artifact có mặt (như
  `check<Variant>CamdSource` đòi `libcamd.so`).

## 6. Kernel / SELinux (phải xác minh trên máy)

- **sepolicy** cần đúng bộ như `module/sepolicy.rule` của TEESimulator, dịch sang domain Cam:
  keystore `create/bind` sock_file trong `keystore_data_file`; domain daemon Cam `connectto` keystore +
  `dir search`/`sock_file` trong `keystore_data_file`; `crash_dump` ptrace keystore (`process`);
  keystore đọc `.so` dưới `adb_data_file`. Cam cài sepolicy qua cơ chế supercall sẵn có.
- **Điểm mong manh nhất:** `inject` ptrace `keystore2`. Domain camd/inject phải được phép ptrace
  keystore2 (upstream chạy inject trong domain `crash_dump`). **Phải thử thật trên Y700 Gen5**; nếu
  domain Cam không ptrace được keystore2, có thể cần cho `inject` transition sang `crash_dump` hoặc một
  domain tương đương.

## 7. Rủi ro

- **Crash-loop keystore2 = máy gần như gạch.** Sai TA/interceptor → keystore2 chết lặp. Đường lùi:
  `kill $(pidof keystore2)` cho daemon mới sạch (không inject tới khi camd inject lại); cờ tắt nhanh
  (mục 9). **Bắt buộc** thử dưới `scripts/device_bootguard_test.sh` trước khi tin.
- **ABI lệch giữa C++ caller và Rust TA** → SIGSEGV (bài học `CMakeLists.txt` dòng 148–152: stale `.a`
  14-arg vs 16-arg). Build phải luôn cho cargo quyết định relink; CI build cả hai từ cùng commit.
- **Keybox thu hồi** → STRONG sập; Manager phải cảnh báo rõ (không phải lỗi Cam).
- **keystore2 restart thường xuyên** → vòng re-inject phải chắc; cửa sổ giữa restart và re-inject là
  lúc app đích tạm mất key của ta (upstream xử bằng park op + báo hardware unavailable).
- **Phủ thiết bị** là hạng yếu nhất của dự án (`customer-devices`): chỉ thật sự đúng khi thử trên Y700
  Gen5, không phải emulator.

## 8. Vendor như thế nào

Ưu tiên **git submodule** `native/teesim/` ghim một commit `dev` đã kiểm (hiện `06f9f2ee`,
`.github/upstream-watch.json`), kèm `--recurse-submodules` cho third_party của nó (kmr-ta, boringssl
headers, LSPlt, interfaces). Nếu submodule lồng nhau phiền cho CI/Windows junction, phương án 2: copy
cây con cần thiết vào `native/teesim/` (ghi rõ commit nguồn + script đồng bộ). `upstream-watch.yml` đã
theo dõi TEESimulator → khi nó ra commit mới, cập nhật ghim có kiểm soát.

## 9. Cờ bật/tắt

Một cờ `attest_sim` bật/tắt toàn tầng (mặc định **tắt** tới khi người dùng cấu hình). Lưu như
`HIDE_BOOTLOADER_FLAG` (file cờ dưới `WORKING_DIR`) **hoặc** thêm một supercall `KSU_FEATURE_*` mới nếu
muốn kernel biết (mục này quyết khi làm; mặc định file cờ, không đụng ABI). Tắt = camd không inject +
không push; đã inject thì push config rỗng (interceptor inert).

## 10. Bỏ khỏi phạm vi M1–M4

Nhánh keystore Android 10/11 (`keystore/`); device-id attestation (`deviceIds`); xoá/khôi phục RKP-only
prop (`rkpDeleted`, `rkp.json`); usage stats (`getUsage`); patch-mode re-sign WebUI (`resign`); màn key
admin (list/inspect/delete key của TA); log tail WebUI. Tất cả là **M5**, spec riêng.

## 11. Lộ trình & kiểm thử

| Mốc | Nội dung | Kiểm thử |
|---|---|---|
| **M1** | Vendor + build `inject`/`teesim_keymint`/TA cho arm64 (+x86_64). | Host test TA (`rust/teesim-km`) pass; `.so` ra đúng ABI. Chưa đụng máy. |
| **M2** | camd inject + push một `push.json` dựng tay (1 keybox, target GMS). | Y700 Gen5: interceptor **hello** qua socket, `ack` ok, **không crash-loop**; `device_bootguard_test.sh`. |
| **M3** | Manager harvest + resolve + nạp keybox, tự dựng `boot.json`/`push.json`. | Y700: `Play Integrity` của app đích lên **STRONG**; `AttestationCheck` trong Manager xác nhận chuỗi keybox + locked/Verified. |
| **M4** | Re-attest owner-scoped, watch file, ép boot prop, sepolicy gọn, cờ tắt. | Y700: đổi cấu hình → push lại không reboot; tắt cờ → inert; `device_detector_scan.py` (Duck/Luna/Chunqiu) không xấu đi so baseline. |

Mỗi mốc: `cargo ndk check/clippy/fmt` + host test cho camd; `:app:testDebugUnitTest` cho Manager; thử
trên máy theo bảng. Trước khi bật mặc định: quét detector + bootguard.

## 12. Ghi tài liệu khi xong

- `docs/CAM_CHANGES.md`: mục mới cho tầng attestation keystore2; cập nhật dòng 343 (bỏ "chưa làm"); gỡ
  ghi chú loại Tricky Store ở `modules/sources.json` dòng 947 (giờ Cam tự làm).
- `keo-update-3-upstreams` (memory): từ nay theo TEESimulator không chỉ để báo commit mà để đồng bộ lõi
  đã vendor.
- Mục 6 (Test) của `docs/CAM_CHANGES.md`: thêm `scripts/build_teesim.sh` + lệnh thử.
