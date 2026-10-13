# Tab "Tệp" (trình quản lý file kiểu MT Manager) — Kế hoạch thực hiện

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm tab "Tệp" trên thanh điều hướng: trình quản lý file chạy bằng quyền root, thao tác và tương tác giống MT Manager (hai panel, nhấn giữ ra lưới thao tác, copy/move sang panel kia), cộng các tính năng riêng của Cam (ghi đè systemless, xem theo mount namespace của app, cài zip như module).

**Architecture:** Mọi thao tác file chạy trong một RootService riêng (`CamFileService`, uid 0, AIDL `ICamFileService`), tách khỏi `CamRootService` (dịch vụ đó chỉ lo supercall). Mỗi lần mở thư mục chỉ tốn một lần IPC: `listDir` trả về danh sách đã kèm `lstat` và SELinux context. Nội dung file đi qua `ParcelFileDescriptor` (pipe), không nạp nguyên file vào Binder. Copy/move/xoá chạy trong root service, báo tiến độ qua callback `oneway`; phía app có foreground service giữ notification. UI là trang thứ 6 của pager chính, Miuix glass.

**Tech Stack:** Kotlin/Compose + Miuix glass, libsu `service` (RootService), `android.system.Os` (lstat, chmod, chown, getxattr/setxattr), commons-compress (đã có), Sora Editor (thư viện mới, chỉ cho trình sửa text).

**Spec:** Yêu cầu của Cam ngày 2026-10-13: "1 cái files manager tương tự MT Manager tích hợp trực tiếp trong Cam KSU, thao tác sử dụng và tương tác giống y hệt MT Manager". Quyết định đã chốt cùng ngày:
- Bỏ chỉnh sửa APK (dex/arsc/xml binary/ký lại), chỉ xem thông tin APK.
- Tab riêng trên bottom nav, nằm **giữa Tính năng và Cài đặt**.
- **Miễn phí toàn bộ**, không khoá tính năng nào theo thành viên.
- Bookmark sẵn có thêm `/data/local/tmp`.

## Đặc tả

### Bố cục

- Tab **"Tệp"** (en "Files"), icon `Icons.Rounded.Folder`, thứ tự: Home, Superuser, Module, Tính năng, **Tệp**, Cài đặt.
- **Hai panel** (trái/phải), mỗi panel có thư mục hiện tại, lịch sử back/forward, vị trí cuộn theo từng thư mục và tập file đang chọn.
  - Màn hình dọc: mỗi lúc hiện một panel, vuốt ngang trên vùng danh sách hoặc chạm vào thanh panel ở đầu trang để chuyển. Panel đang hiện là "panel hiện tại", panel còn lại là "panel đích".
  - Màn hình ngang hoặc màn rộng (`shouldShowSplitPane()`): hai panel cạnh nhau, panel hiện tại có viền nhấn.
  - Lưu ý: vuốt ngang trên danh sách không được chuyển tab của pager chính. Pager chính chỉ chuyển trang khi vuốt từ mép, hoặc tắt `userScrollEnabled` khi đang ở tab Tệp.
- **Thanh đường dẫn (breadcrumb)**: chạm một đoạn để nhảy về thư mục đó, nhấn giữ để gõ đường dẫn tay (có gợi ý tự hoàn thành).
- **Mỗi dòng file**: icon theo loại, tên, dòng phụ "kích thước · thời gian sửa · `rwxr-x---`". Tuỳ chọn hiện owner:group và SELinux context. Symlink ghi `→ đích`, symlink gãy tô đỏ.
- **Thanh công cụ dưới**: Tạo mới (file / thư mục / symlink) · Làm mới · Chọn tất cả · Sắp xếp & lọc · Tìm kiếm · ⋮ (hiện file ẩn, hiện context, kích thước chữ). Khi đang chọn nhiều, thanh này đổi thành: Copy · Move · Xoá · Nén · ⋮ (thuộc tính, chọn ngược, bỏ chọn).

### Tương tác (giống MT)

| Thao tác | Kết quả |
|---|---|
| Chạm item | Mở thư mục; với file thì mở bằng trình xem trong app theo loại (text, ảnh, zip, APK, còn lại hỏi "Mở bằng") |
| Chạm icon của item | Chọn/bỏ chọn, vào chế độ chọn nhiều |
| Nhấn giữ item | Dialog lưới thao tác (mục bên dưới) |
| Vuốt từ mép trái hoặc nút ☰ | Drawer: gốc, bookmark, công cụ |
| Back | Lùi lịch sử panel → thoát chế độ chọn → về trang Home như các tab khác |
| Kéo xuống | Làm mới |

**Lưới thao tác khi nhấn giữ** (ẩn mục không áp dụng): Mở bằng · Copy → panel kia · Move → panel kia · Đổi tên · Xoá · Nén · Giải nén (sang đây / sang panel kia) · Thuộc tính · Quyền · Tạo symlink ở panel kia · Bookmark · Checksum · Sao chép đường dẫn · Chia sẻ · **Cài như module** (file `.zip`) · **Thay bằng module** (file dưới `/system`, `/vendor`, `/product`, `/system_ext`, `/odm`).

**Copy/Move**: đích mặc định là thư mục của panel kia, dialog xác nhận cho đổi đích. Trùng tên → hỏi Ghi đè / Bỏ qua / Đổi tên tự động / Gộp thư mục, kèm ô "Áp dụng cho tất cả".

### Drawer

- **Gốc**: `/`, Bộ nhớ trong (`/sdcard`), `/data`, `/system`, `/vendor`.
- **Bookmark có sẵn** (xoá được, khôi phục được trong ⋮): `/data/adb/modules`, `/data/adb/cam`, `/data/adb/service.d`, `/data/adb/post-fs-data.d`, `/data/local/tmp`.
- **Bookmark người dùng** (thêm từ lưới thao tác, sắp xếp lại bằng kéo thả).
- **Công cụ**: Tìm file, Xem theo app (namespace).

### Trình xem / sửa

- **Text**: Sora Editor, tô màu cú pháp cho sh, prop, rc, xml, json, ini, kotlin/java (cơ bản). Tìm/thay thế có regex, nhảy dòng, xuống dòng mềm, chọn encoding (UTF-8, GBK, UTF-16, ISO-8859-1), tự nhận diện CRLF/LF và giữ nguyên khi lưu. File > 10 MB mở chế độ chỉ đọc kèm cảnh báo. File nhị phân → gợi ý mở hex.
- **Hex**: chỉ đọc, phân trang theo khối 64 KB, nhảy offset, tìm chuỗi hex/ASCII.
- **Ảnh**: xem, phóng to.
- **ZIP / TAR / TAR.GZ / TAR.XZ**: duyệt như thư mục (chỉ đọc), giải nén một phần hoặc toàn bộ.
- **APK**: package, phiên bản, minSdk/targetSdk, chữ ký (sha256), quyền, nút Cài đặt (gọi trình cài của hệ thống).

### Tính năng riêng của Cam

- **Cài như module**: mở màn hình Flash có sẵn (`Route.Flash`) với file zip đó.
- **Thay bằng module**: chép file đang chọn (hoặc file người dùng chọn làm bản thay) vào module dùng chung `cam_file_override` tại `/data/adb/modules/cam_file_override/<đường dẫn gốc>`, tạo `module.prop` nếu chưa có. Danh sách file đã ghi đè xem được trong trang chi tiết module. Có hiệu lực sau khởi động lại. **Chỉ chạy khi đang có metamodule** (Cam không tự mount `system/`); không có thì dialog giải thích và không làm gì.
- **Xem theo app**: chọn một app đang chạy → panel duyệt qua `/proc/<pid>/root/<đường dẫn>`, tức nhìn đúng cây file trong mount namespace của app đó (không cần `setns`). Thanh panel ghi rõ "Đang xem như: <tên app>". Chỉ đọc. App chết thì báo và quay về chế độ thường.
- **Cảnh báo vùng nguy hiểm**: sửa, xoá, đổi quyền dưới `/data/adb/cam`, `/system`, `/vendor`, `/product`, `/proc`, `/sys`, `/dev` → dialog xác nhận màu đỏ. Từ chối hẳn việc xoá `/` và các thư mục gốc cấp 1.

## Global Constraints

- Không sửa kernel, IOCTL, JNI hay camd. Toàn bộ nằm trong `manager/`.
- Thêm một thư viện: Sora Editor (`io.github.rosemoe:editor` + `language-textmate`). Xem phiên bản mới nhất qua Context7 khi làm Task 6, ghi vào `gradle/libs.versions.toml`. Nén/giải nén dùng commons-compress + xz đã có.
- **Xoá và copy không bao giờ đi theo symlink**: dùng `lstat`; symlink thì chép/xoá chính cái link. Đây là ràng buộc an toàn quan trọng nhất (xoá đệ quy một thư mục có link trỏ ra `/data` không được xoá luôn đích).
- **Ghi file giữ nguyên metadata**: mode, uid, gid, SELinux context của file cũ. Ghi ra file tạm cùng thư mục, `fsync`, áp metadata rồi `rename` đè lên, để mất điện giữa chừng không làm hỏng file.
- Move qua hai filesystem khác nhau (`rename` lỗi `EXDEV`) → chép xong rồi xoá nguồn; chép lỗi thì giữ nguồn.
- UI chỉ dùng Miuix glass; chuỗi mới vào `values/strings.xml` và `values-vi/strings.xml`.
- Logic không phụ thuộc Android (đường dẫn, sắp xếp, quyền, giải quyết trùng tên, lịch sử panel, engine copy) nằm trong lớp Kotlin thuần để chạy được unit test JVM. Test dùng symlink thì `assumeTrue` khi hệ điều hành không hỗ trợ (CI chạy Linux nên vẫn chạy).
- Manager: `./gradlew :app:testDebugUnitTest` và `:app:assembleDebug` (chạy từ ổ `K:` trên Windows, mục 5 của `docs/CAM_CHANGES.md`).
- Commit `<scope>: <Summary>` (scope `manager`) + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage file theo đường dẫn, không dùng `git commit -a`.

## Review Focus

1. **Xoá đệ quy gặp symlink** trỏ ra ngoài cây → chỉ xoá link, đích còn nguyên. Test `deleteDoesNotFollowSymlink`.
2. **Copy vòng lặp**: copy một thư mục vào chính nó hoặc vào con của nó → từ chối trước khi chạy. Test `copyIntoSelfRejected`.
3. **Lưu file cấu hình hệ thống** (vd `/data/adb/service.d/x.sh` mode 0755, context `adb_data_file`) → sau khi lưu mode, owner, context không đổi. Test `atomicWriteKeepsMetadata` (mode/owner trên JVM), kiểm context trên máy.
4. **Move khác filesystem** (`/sdcard` → `/data/local/tmp`) → nguồn chỉ bị xoá khi chép xong hết. Test `moveFallsBackToCopyOnExdev` (giả lập EXDEV).
5. **Huỷ giữa chừng** → không để lại file chép dở mang tên thật (file dở là `.camtmp` và bị dọn). Test `cancelCleansPartialFile`.
6. **Chỉ số trang dịch đi**: Cài đặt từ index 4 thành 5. `selectedMainPage=4` đã lưu sẽ mở tab Tệp (chấp nhận). Kiểm trên máy: bấm từng tab ra đúng trang, badge Superuser/Module vẫn đúng tab (badge tra theo `ordinal` nên tự đúng).
7. **Thanh nav 6 mục** trên màn 360 dp và chế độ thanh nổi: nhãn không bị cắt hoặc xuống dòng.
8. **Vuốt ngang trong tab Tệp** chuyển panel, không chuyển tab pager chính.
9. **Thư mục rất lớn** (`/data/app` hoặc thư mục 10.000 file): `listDir` vượt 1 MB Binder vẫn chạy (dùng `ParcelableListSlice`), danh sách cuộn mượt.

---

## Giai đoạn 1 — MVP

### Task 1: `ICamFileService` và `CamFileService`

**Files:** Create `manager/app/src/main/aidl/cam/su/kernel/files/ICamFileService.aidl`, `IFileOpCallback.aidl`, `FileEntry.aidl`; `manager/app/src/main/java/cam/su/kernel/files/FileEntry.kt` (Parcelable), `CamFileService.kt`, `CamFileClient.kt`, `RootFs.kt`. Modify `AndroidManifest.xml` nếu libsu yêu cầu khai báo service.

**Interfaces:**
- `FileEntry(name, path, type: FILE|DIR|LINK|OTHER, linkTarget: String?, linkBroken, size, mtime, mode, uid, gid, ownerName, groupName, context: String?)`.
- `ICamFileService`:
  - `ParcelableListSlice<FileEntry> listDir(String path)` — `Os.lstat` từng mục, context qua `Os.getxattr(path, "security.selinux")`, tên user/group tra một lần mỗi lượt gọi.
  - `FileEntry stat(String path)`, `boolean exists(String path)`.
  - `void mkdir(String path)`, `void createFile(String path)`, `void symlink(String target, String link)`, `void rename(String from, String to)`.
  - `void chmod(String path, int mode, boolean recursive)`, `void chown(String path, int uid, int gid, boolean recursive)`, `void setContext(String path, String ctx, boolean recursive)`.
  - `ParcelFileDescriptor openRead(String path)` — pipe, root service ghi vào.
  - `ParcelFileDescriptor openWrite(String path)` — pipe; khi đóng thì ghi nguyên tử, giữ metadata (Global Constraints).
  - `int startOp(in Bundle op, IFileOpCallback cb)` (copy/move/delete/extract/compress; trả id), `void cancelOp(int id)`.
- `IFileOpCallback` (`oneway`): `onProgress(id, doneBytes, totalBytes, currentPath)`, `onConflict(id, src, dst)` → trả lời bằng `resolveConflict(id, choice, applyAll)`, `onDone(id, errorMessage?)`.
- Lỗi trả về dạng `ServiceSpecificException` với `errno`, phía app đổi thành chuỗi dễ hiểu.
- `CamFileClient` giống `CamRootClient` (một binding, `StateFlow`), API suspend chạy trên `Dispatchers.IO`.

- [ ] Viết AIDL + `FileEntry` Parcelable
- [ ] `RootFs`: lớp thuần Kotlin bọc `Os`, dùng chung cho service và test
- [ ] `CamFileService` + `CamFileClient`
- [ ] Test `RootFsTest` (list, stat, symlink gãy, ghi nguyên tử giữ mode)

### Task 2: Engine thao tác file

**Files:** Create `files/FileOpEngine.kt`, `files/ConflictPolicy.kt`. Test `FileOpEngineTest.kt`.

- Copy/move/delete đệ quy không đi theo symlink; kiểm tra copy vào chính nó; move dùng `rename` trước, `EXDEV` thì chép + xoá.
- Tổng dung lượng tính trước (một lượt `lstat`) để có phần trăm.
- File đang chép ghi vào `<tên>.camtmp`, xong mới `rename`; huỷ thì xoá `.camtmp`.
- Giữ mode, owner, mtime, context khi chép (bật mặc định, có tuỳ chọn tắt).

- [ ] Engine + chính sách trùng tên
- [ ] Test các mục 1, 2, 4, 5 của Review Focus

### Task 3: Tab và hai panel

**Files:** Create `ui/screen/files/FilesScreen.kt`, `FilesMiuix.kt`, `FilePanel.kt`, `Breadcrumb.kt`, `ui/viewmodel/FilesViewModel.kt`, `files/PanelState.kt` (lịch sử, selection, sort — Kotlin thuần). Modify `BottomBarMiuix.kt` (thêm `Files` vào `BottomBarDestination` giữa `Features` và `Setting`), `CamActivity.kt` (trang 4 = Files, 5 = Setting), `CamActivityViewModel.kt` (`PAGE_COUNT = 6`), `strings.xml` ×2.

- [ ] Thêm tab, dời index, kiểm mục 6, 7 của Review Focus
- [ ] `PanelState` + test (back/forward, giữ vị trí cuộn, sắp xếp tên/kích thước/thời gian/loại, thư mục lên đầu, ẩn file chấm)
- [ ] UI panel: dòng file, breadcrumb, kéo để làm mới, chọn nhiều, chuyển panel (dọc/ngang), chặn vuốt pager chính (mục 8)
- [ ] Trạng thái không có root: thông báo giống các tab khác, không crash

### Task 4: Lưới thao tác và các dialog

**Files:** Create `ui/screen/files/ActionGrid.kt`, `dialog/RenameDialog.kt`, `NewItemDialog.kt`, `PropertiesDialog.kt` (thông tin + quyền dạng ô tick ↔ bát phân + owner + context), `ConflictDialog.kt`, `ConfirmDangerDialog.kt`; `files/Permissions.kt` (đổi `mode` ↔ `rwx` ↔ bát phân, Kotlin thuần) + test.

- [ ] Lưới thao tác khi nhấn giữ và thanh công cụ khi chọn nhiều
- [ ] Các dialog, vùng nguy hiểm, chặn xoá thư mục gốc cấp 1
- [ ] "Mở bằng" / "Chia sẻ": chép file sang cache của app rồi đưa qua `FileProvider` (app ngoài không đọc được file root)

### Task 5: Chạy thao tác dài

**Files:** Create `files/FileOpForegroundService.kt`, `ui/screen/files/OpProgressSheet.kt`. Modify `AndroidManifest.xml` (service `dataSync`, quyền notification đã có thì dùng lại).

- [ ] Foreground service giữ binding và notification (tiến độ, nút Huỷ)
- [ ] Bottom sheet tiến độ trong app, hỏi trùng tên giữa chừng
- [ ] Xong thì làm mới cả hai panel

### Task 6: Trình sửa text

**Files:** Modify `gradle/libs.versions.toml`, `app/build.gradle.kts` (Sora Editor). Create `ui/screen/files/editor/TextEditorScreen.kt`, `EditorViewModel.kt`, `files/TextCodec.kt` (nhận diện encoding, nhị phân, kiểu xuống dòng) + test. Modify `Routes.kt` (`Route.TextEditor(path: String)`).

- [ ] Mở qua `openRead`, lưu qua `openWrite`; hỏi khi thoát mà chưa lưu
- [ ] Tô màu cú pháp, tìm/thay regex, nhảy dòng, encoding
- [ ] File lớn chỉ đọc, file nhị phân gợi ý hex

### Task 7: Drawer và bookmark

**Files:** Create `ui/screen/files/FilesDrawer.kt`, `files/Bookmarks.kt` (lưu SharedPreferences, danh sách có sẵn ở Đặc tả) + test.

- [ ] Gốc, bookmark có sẵn (gồm `/data/local/tmp`), bookmark người dùng, sắp xếp lại
- [ ] Thư mục module trong `/data/adb/modules` có chip Bật/Tắt/Sẽ gỡ (lấy từ `ModuleViewModel`)

## Giai đoạn 2

### Task 8: Nén và giải nén
- [ ] Duyệt ZIP/TAR/TAR.GZ/TAR.XZ như thư mục chỉ đọc (commons-compress, đọc qua `openRead`)
- [ ] Giải nén toàn bộ hoặc mục đã chọn (thao tác dài của Task 5); chặn đường dẫn `../` trong archive (zip slip). Test `extractRejectsZipSlip`
- [ ] Tạo ZIP từ mục đã chọn

### Task 9: Tìm kiếm
- [ ] Theo tên (glob/regex, kích thước, thời gian) và theo nội dung, chạy trong root service, trả kết quả dần, huỷ được

### Task 10: Trình xem khác
- [ ] Hex (phân trang 64 KB), ảnh, thông tin APK (`PackageManager.getPackageArchiveInfo` trên bản chép ở cache), checksum md5/sha1/sha256

## Giai đoạn 3 — riêng của Cam

### Task 11: Cài như module
- [ ] Mục trong lưới cho `.zip`, chuyển sang `Route.Flash` (zip ở vùng root thì chép ra cache trước như luồng cài hiện tại)

### Task 12: Thay bằng module
- [ ] Kiểm tra có metamodule đang bật (dùng cờ camd đã có trong `ModuleViewModel`), không có thì giải thích
- [ ] Ghi vào `cam_file_override`, tạo `module.prop`, giữ mode/context theo file gốc; danh sách file đã ghi đè + gỡ từng file
- [ ] Nhắc khởi động lại

### Task 13: Xem theo app
- [ ] Chọn app đang chạy (pid từ `ActivityManager`/`/proc` qua root service), panel đổi gốc sang `/proc/<pid>/root`, chỉ đọc, nhãn "Đang xem như"
- [ ] Từ trang Hiding Check của app có nút "Duyệt file như app này"

## Hoàn tất

### Task 14: Tài liệu
- [ ] Thêm dòng mới vào bảng mục 1 của `docs/CAM_CHANGES.md` (tab Tệp, `CamFileService`, các file chính, test)
- [ ] Cập nhật số lớp/số test trong `AGENTS.md` và `docs/PROJECT_REVIEW.md`
- [ ] Mục mới trong `CHANGELOG.md` khi phát hành
- [ ] Làm xong: thêm dòng "Trạng thái" và chuyển plan sang `plans/done/`
