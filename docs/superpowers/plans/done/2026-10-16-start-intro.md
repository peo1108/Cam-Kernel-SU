# Plan: Intro "Inner Veterans" khi mở app (che lúc 4 con slime khởi động)

> **Trạng thái (2026-10-16): đã làm và thử trên máy (TB323FU, bản release).** Bản dựng khác plan ở vài chỗ lớn (intro chạy trên thread riêng, 22 mảnh thay 3 mặt, bỏ splash logo và scanline): xem mục "Đã làm" ở cuối và dòng 68 của `docs/CAM_CHANGES.md`.

## Context

Mở app xong khoảng 1s sau panel 4 con slime (`Arena3D`, Filament) mới ổn định. Trong 1s đó app phải tạo engine, compile shader ở frame đầu, dựng asset, vẽ panorama đầu tiên trên worker và may outfit trên `arena-closet`. Mục tiêu: một intro khoảng 1.5s dùng logo `IMG_7454.PNG` (tam giác kim loại, HUD teal, 前輩內部 / INNER VETERANS), có điện chạy quanh viền tam giác như ảnh mẫu, đạn trừu tượng bay xoáy rồi găm vào khung, phong cách bùng nổ. **Hết intro là thấy 4 con slime đang chạy luôn.**

Điểm mấu chốt: intro là **lớp phủ đè lên home** chứ không phải một màn chạy trước. Home và Arena3D khởi động bên dưới trong lúc intro chạy. Intro chỉ mở màn khi Arena3D báo "đã ấm", hoặc khi hết thời gian chờ tối đa.

Các quyết định đã chốt với người dùng:
- Đạn trừu tượng, không có hình súng.
- Có điện viền quanh tam giác.
- Có haptic, không có âm thanh.
- Màu đạn găm là đỏ cam.

## Kịch bản (giây, tính từ khung hình đầu của lớp phủ)

| Nhịp | Thời gian | Nội dung |
|---|---|---|
| Khởi động | 0.00–0.20 | Nền đen. Scanline teal quét từ trên xuống, HUD (phần ngoài tam giác, phía trên chữ) lộ dần theo vệt quét và nháy kiểu đèn chập |
| Hỏa lực | 0.20–0.65 | 6 viên đạn, mỗi viên bay khoảng 0.12s, tới đích ở 0.30, 0.36, 0.42, 0.50, 0.56, 0.62. Đạn bay nhanh rồi chậm hẳn trước khi trúng (bullet time), có vệt sáng và sọc xoáy. 3 viên đầu mỗi viên mang về một "mặt" của tam giác: mặt đó đập vào từ hướng đạn bay tới (lệch 15% kích thước, xoay 8°, easeOutBack 0.10s) kèm chớp trắng trên mảnh và tia lửa. Mọi viên đều ở lại găm trên khung, nóng đỏ cam |
| Nạp điện | 0.65–0.95 | Điện bò dọc 3 cạnh từ các điểm trúng tới khi nối kín vòng. Đạn găm lách tách. Vòng tâm ngắm xoay rồi khựng lại, `RNG 0500M` đếm về `0000M`, cạnh `TGT` hiện chữ `LOCK` nháy |
| Kích nổ | 0.95–1.20 | Viên cuối bắn thẳng vào tâm ở 0.97, chớp trắng toàn màn 0.06s, rung màn mạnh. Điện dâng biên độ ×2 và rẽ nhánh phóng ra, thêm 3 tia từ 3 góc vào tâm. Đạn găm nổ dây chuyền, cách nhau 0.03s, thành quả cầu lửa cam-teal kèm mảnh văng. Chữ 前輩內部 hiện ra kiểu glitch tách màu RGB, `INNER VETERANS` sáng từng chữ |
| Giữ | 1.20 → lúc mở màn | Điện chạy viền liên tục, logo đứng yên. Chỉ kéo dài khi Arena chưa ấm |
| Mở màn | 0.30s | Sóng xung kích lan ra từ tâm ngắm thành lỗ tròn, lộ home bên dưới. Mép sóng có viền teal/trắng, điện tắt dần |

Luật mở màn:
- Bắt đầu sớm nhất ở `1.20s`.
- Nếu đang chờ Arena thì đợi tới khi Arena ấm, muộn nhất `1.90s`.
- Tổng thời gian không quá 2.2s.
- Chạm vào màn là nhảy thẳng tới mở màn.

## Kiến trúc

Package mới `manager/app/src/main/java/cam/su/kernel/ui/intro/`:

1. **`IntroTimeline.kt`**: toán thuần, không phụ thuộc Android, để test trên JVM.
   - Hằng số các nhịp: `BOOT_END`, `FIRE_END`, `CHARGE_END`, `BLAST_AT`, `TEXT_END`, `MIN_REVEAL_AT=1.20`, `MAX_REVEAL_AT=1.90`, `REVEAL_LEN=0.30`.
   - `hits`: danh sách 6 `Hit(time, edge, along, fromAngle, shard)`; `shard` là 0..2, hoặc -1 nếu không mang mảnh.
   - `bulletEase(f)`: nhanh rồi chậm, liên tục.
   - `revealStart(now, arenaExpected, warmAt): Float?`: hàm thuần cho luật mở màn ở trên.
   - Hình học trong hệ toạ độ ảnh 1254×1254:
     - 3 đỉnh tam giác, khoảng (627,58), (193,800), (1060,800); đo lại trên ảnh khi làm.
     - Tâm ngắm khoảng (627,547).
     - Vùng HUD, vùng chữ (y 820–1130), ô số `RNG`.
     - 3 mặt tam giác, tách bằng trọng tâm.
   - `fit(screenW, screenH)`: trả về tỉ lệ và vị trí đặt logo vào màn hình. Logo chiếm khoảng 82% của `min(w, h·0.8)`, căn giữa.
2. **`Bolt.kt`**: sinh tia điện bằng midpoint displacement.
   - `build(ax, ay, bx, by, seed, depth = 5, rough, out: FloatArray): Int` cho 33 điểm, độ lệch giảm một nửa mỗi tầng, dùng `hash()` có sẵn.
   - Seed đổi theo `floor(t * 20)` nên điện "giật" khoảng 20 lần/giây.
   - Không cấp phát bộ nhớ mỗi frame.
3. **`IntroFx.kt`**: các hàm `DrawScope.*` vẽ hiệu ứng.
   - `drawBoltPath`: 3 lớp gồm quầng teal `0xFF2EE6D6` dày và mờ, lớp cyan ở giữa, lõi trắng mảnh. Tham khảo cách 3 lớp của `drawLightning` trong [ArenaFx.kt:183](manager/app/src/main/java/cam/su/kernel/ui/screen/home/arena/ArenaFx.kt#L183), nhưng làm hàm mới vì hàm cũ màu vàng hoạt hình và chỉ có 8 điểm.
   - `drawEdgeLightning` (có tham số `grow` và `surge`), `drawBranches`.
   - `drawBullet` (vệt sáng, sọc xoáy), `drawEmbedded` (cam nóng, nhịp sáng).
   - `drawFireball` (dùng lại `drawGlow`, mảnh văng tính giải tích từ t, không giữ state).
   - `drawShockwave`, `drawSparks`.
   - `drawGlitchSlice`: vẽ vùng chữ 3 lần, mỗi lần một `ColorFilter.colorMatrix` giữ riêng kênh R, G hoặc B, lệch ±dx, cộng bằng `BlendMode.Plus` trên nền đen. Thêm 6 dải ngang rung. Không dùng AGSL nên chạy được mọi API.
   - Path dùng lại qua một holder `IntroPaths` được `remember`.
4. **`IntroGate.kt`**: `object` chung (cùng kiểu với `SlimeHome`).
   - `@Volatile` state gồm `Unknown`, `Coming`, `Warm`, kèm hàm `reset()`, `coming()`, `warm()`.
   - `StatusArena` gọi vào đây; lớp phủ đọc ra.
5. **`IntroLogo.kt`**: `preload(context)` giải mã `R.drawable.intro_logo` trên `Dispatchers.IO` thành `ImageBitmap`, cùng cách làm với `GlassBackgroundCache.preload` trong [CamActivity.kt:191](manager/app/src/main/java/cam/su/kernel/ui/CamActivity.kt#L191). Ảnh xong từ lâu trước khi `camInitDone`.
6. **`IntroOverlay.kt`**: `@Composable IntroOverlay(expectArena: Boolean, onFinished: () -> Unit)`.
   - Vòng `withFrameNanos` cập nhật `t`, cùng cách làm với [StatusArena.kt:134-161](manager/app/src/main/java/cam/su/kernel/ui/screen/home/arena/StatusArena.kt#L134-L161).
   - Một `Canvas` toàn màn hình vẽ theo các nhịp, rung màn bằng `translate` với `hash(frame)` giảm dần theo `exp`.
   - `pointerInput` nuốt mọi chạm, chạm thì nhảy tới mở màn.
   - Haptic qua `LocalHapticFeedback`: `ContextClick` ở 3 phát mang mảnh, `LongPress` lúc kích nổ (cùng kiểu với `StatusArena`).
   - Cách vẽ logo:
     - HUD: clip theo vùng HUD trừ tam giác, cộng clip theo scanline.
     - 3 mặt tam giác: `clipPath` từng mặt, rồi `drawImage` cả logo.
     - Vùng chữ: dùng `drawGlitchSlice`, rồi clip kiểu quét để lộ từng chữ.
     - `RNG`: tô đè một ô đen rồi `drawText` monospace.
   - Hết mở màn thì gọi `onFinished`.

Dùng lại từ [ArenaMath.kt](manager/app/src/main/java/cam/su/kernel/ui/screen/home/arena/ArenaMath.kt) (đều là `internal`, cùng module): `hash`, `progress`, `easeInOutCubic`, `easeOutCubic`, `easeOutBack`, `TAU`, `lerp`. Dùng lại `drawGlow` và `drawSparkle` từ `ArenaFx.kt`.

## Tín hiệu "Arena đã ấm"

- [Arena3D.kt](manager/app/src/main/java/cam/su/kernel/ui/screen/home/arena/three/Arena3D.kt):
  - Thêm biến đếm `warmFrames`. Trong `render()`, sau mỗi `endFrame()` thành công, tăng biến này nếu `asset != null && latestPanorama != null && rig.dressed`.
  - Thêm `val warm get() = warmFrames >= 3`: 3 frame để vượt qua lần giật do compile shader.
- `SlimeRig.dressed` chuyển tiếp sang `Closet.settled == SLIME_COUNT`.
  - `settled` tăng trên main thread mỗi khi một bộ outfit xong việc, kể cả khi `sew` lỗi.
  - Nếu chỉ đếm số outfit thành công thì một outfit hỏng sẽ khiến intro chờ tới mức tối đa.
  - Code liên quan: [Closet.kt:50-61](manager/app/src/main/java/cam/su/kernel/ui/screen/home/arena/three/Closet.kt#L50-L61), `hang`.
- [StatusArena.kt](manager/app/src/main/java/cam/su/kernel/ui/screen/home/arena/StatusArena.kt):
  - Khi `arena3d != null` thì `LaunchedEffect(arena3d) { IntroGate.coming() }`.
  - Trong vòng frame, sau `arena3d.render(nanos)`: `if (arena3d.warm) IntroGate.warm()`.

## Gắn vào CamActivity

[CamActivity.kt](manager/app/src/main/java/cam/su/kernel/ui/CamActivity.kt):
- Trong `onCreate`, tính `showIntro` một lần. Intro chỉ chạy khi đủ tất cả các điều kiện:
  - `savedInstanceState == null`;
  - `intent.action == ACTION_MAIN`, có category LAUNCHER (mở từ thông báo hay shortcut thì không chạy);
  - setting `startIntro` đang bật;
  - `ANIMATOR_DURATION_SCALE != 0`, cùng cách kiểm tra với `StatusArena.kt:115`.
- Nếu chạy intro: `IntroGate.reset()` và `IntroLogo.preload(...)`.
- Trong `setContent`, đặt `IntroOverlay` sau khối `SlimeLayer` và trước `CamToastHost()`, để nó che cả slime đi lạc.
  - Tham số `expectArena = selectedMainPage == 0 && Cam.isFullFeatured()`. Giá trị lấy từ coroutine `connect()`: lưu kết quả vào một cờ cạnh `camInitDone`.
  - Lớp phủ được vẽ bằng một state `introDone` (`rememberSaveable`) để không chạy lại sau khi đã xong.
- Splash hệ thống (nền đen, `ic_splash_logo`) giữ nguyên. Intro bắt đầu từ nền đen nên chuyển cảnh liền mạch.

## Setting "Intro khi mở app" (mặc định bật)

Đi theo đúng đường của `slimeNightNap`:
- `SettingsRepository` / `SettingsRepositoryImpl`: key `start_intro`, mặc định `true`.
- `SettingsUiState`, `SettingsViewModel` (đọc lên và hàm `setStartIntro`).
- `ColorPaletteUiState` (`onSetStartIntro`), `ColorPaletteScreen` (nối vào viewmodel).
- `ColorPaletteScreenMiuix`: thêm `GlassSwitchPreference` cạnh "Slime ngủ đêm" ([ColorPaletteScreenMiuix.kt:536](manager/app/src/main/java/cam/su/kernel/ui/screen/colorpalette/ColorPaletteScreenMiuix.kt#L536)), icon `Icons.Rounded.AutoAwesome`.
- `CamActivity` đọc thẳng `settings.startIntro` từ `SettingsRepositoryImpl` đã có sẵn trong `onCreate`, không cần sửa `CamActivityViewModel`.
- Chuỗi `settings_start_intro` và `settings_start_intro_summary` trong `values/strings.xml` và `values-vi/strings.xml`.

## Asset

- Chuyển `IMG_7454.PNG` sang `manager/app/src/main/res/drawable-nodpi/intro_logo.webp`, chất lượng khoảng 90, giữ 1254 px.
- Dùng Pillow (`python -I`) hoặc `cwebp`, công cụ nào có sẵn thì dùng.
- Không commit file PNG ở thư mục gốc repo (hiện chưa được git theo dõi).

## Docs

- `docs/CAM_CHANGES.md`: thêm dòng **68**, "Intro khi mở app", ghi các file, luật mở màn và tín hiệu `IntroGate`.
- `CHANGELOG.md`:
  - Nếu chưa có tag `cam-v1.1.9` (`git tag -l cam-v1.1.9`) thì thêm một dòng vào "Tính năng mới" của 1.1.9, ví dụ: "Intro mở app: logo Inner Veterans… hết intro là slime chạy sẵn, tắt trong Giao diện".
  - Nếu đã có tag thì tạo mục mới.
- `AGENTS.md`: cập nhật số class và số test của `testDebugUnitTest`.

## Test

JVM, đặt trong `manager/app/src/test/java/cam/su/kernel/ui/intro/`; xem `HeroMathTest` và `SkeletonTimingTest` để theo đúng kiểu viết:
- **`IntroTimelineTest`**:
  - các nhịp tăng dần và không chồng lên nhau;
  - mọi `hits` đều nằm trong khung hỏa lực;
  - mỗi mặt tam giác có đúng một viên mang về;
  - `revealStart`:
    - Arena ấm sớm thì mở màn ở 1.20;
    - không chờ Arena thì mở màn ở 1.20;
    - Arena ấm ở 1.5 thì mở màn ở 1.5;
    - chờ mà Arena không bao giờ ấm thì mở màn ở 1.90;
    - tổng thời gian ≤ 2.2;
  - `bulletEase` liên tục và đơn điệu, đi từ 0 tới 1.
- **`BoltTest`**:
  - hai đầu tia trùng đúng hai đầu mút;
  - mọi điểm nằm trong biên lệch `2·rough·len`;
  - cùng seed cho cùng kết quả, khác seed cho kết quả khác;
  - đúng 33 điểm.
- **`IntroGeometryTest`**:
  - tổng diện tích 3 mặt bằng diện tích tam giác;
  - `fit` giữ logo nằm trong màn hình cả khi dọc lẫn ngang.

Chạy trên ổ `K:` (subst lại nếu mất sau reboot): `./gradlew :app:testDebugUnitTest`.

## Kiểm tra trên tablet (192.168.50.91)

1. `./gradlew :app:assembleDebug`, rồi cài qua adb. Không đụng camd nên `checkDebugCamdSource` chỉ cảnh báo.
2. Mở lạnh, đồng thời quay màn hình:
   - `adb shell am force-stop cam.su.kernel`
   - `adb shell screenrecord --bit-rate 20000000 --time-limit 6 /sdcard/intro.mp4 &`
   - `adb shell monkey -p cam.su.kernel -c android.intent.category.LAUNCHER 1`
   - Pull video về, tách frame bằng ffmpeg ở các mốc 0.3, 0.7, 1.0, 1.3, 1.6, 2.0s, rồi xem từng frame:
     - các nhịp chạy đúng thứ tự;
     - **lúc mở màn slime đã đứng và chạy, không còn khoảng khởi động**.
3. Đo frame time:
   - Chạy `adb shell dumpsys gfxinfo cam.su.kernel reset` trước khi mở app, rồi `dumpsys gfxinfo cam.su.kernel` sau khi mở.
   - So tỉ lệ frame giật với lúc tắt intro.
   - Nếu giật nhiều thì giảm số tia lửa và độ dày quầng điện.
4. Các trường hợp phụ:
   - tắt setting thì không có intro;
   - tắt animation hệ thống thì không có intro;
   - xoay màn trong lúc intro: không chạy lại, không bị kẹt;
   - mở app từ thông báo cập nhật: không có intro;
   - trang mở đầu không phải Home: mở màn ở 1.2s;
   - chạm vào màn thì nhảy tới mở màn.

## Commit

- Scope `manager:`.
- Stage từng đường dẫn, **không** dùng `git commit -a` (để khỏi dính junction `uapi`).
- Ví dụ commit:
  - `manager: Add the start intro over the slime warm-up`
  - `docs: Record the start intro`

## Đã làm (2026-10-16): khác plan ở đâu và vì sao

Chi tiết và số đo ở dòng 68 của `docs/CAM_CHANGES.md`.

- **Intro không còn là lớp phủ Compose.** Đo trên tablet: sau composition đầu, main thread kẹt 1,2 s (bản debug), các frame đầu 100–330 ms, nên intro giật và bắt đầu muộn. Giờ intro vẽ trên một `SurfaceView` (`setZOrderOnTop`) với thread và Choreographer riêng (`IntroPlayer.kt`), cùng code `DrawScope` qua `CanvasDrawScope` trên `lockHardwareCanvas`. App khởi động bên dưới không làm nó giật nữa.
- **Bỏ splash có logo, vào intro luôn** (Cam yêu cầu). Splash hệ thống đen trơn (`ic_splash_blank`) và chỉ giữ tới khi cắt xong ảnh, không chờ root. Intro chạy trước cả khi root kết nối. Tắt intro thì logo splash quay lại (Android 13+, `IntroSplash`).
- **3 mặt thay bằng 22 mảnh** (Cam thấy 3 mặt "cứng"): 12 mảnh khung ngoài, 6 khung trong, 3 lõi, tâm ngắm. Mảnh bay từ phía trước màn hình, xoay, có vệt mờ, đáp thì chớp. 6 viên đạn găm vào mảnh khung ngoài lúc nó đáp. Bốn chữ Hán đập vào từng chữ.
- **Bỏ scanline toàn màn** (Cam yêu cầu). HUD bật từng mảnh, nhấp nháy.
- **Luật mở màn tính từ lúc app hiện**, vì intro giờ bắt đầu trước khi app có gì: sớm nhất 1,55 s và sau khi app vẽ lần đầu. Có slime 3D thì chờ ấm, tối đa 0,9 s sau khi app hiện. App không hiện thì bỏ cuộc lúc 4,5 s. Bản release trên tablet mở đúng 1,55 s, slime đã ấm từ 0,87 s.
- **Đạn bay 0,10 s thay vì 0,12 s:** với 0,12 s viên đầu rời đi trước khi nhịp hỏa lực bắt đầu.
- **`IntroGate` không có trạng thái `Coming`:** không ai đọc nó. Thay vào đó có `expectArena` (app báo trang đầu) và `shown` (app vẽ lần đầu).
- **"Trang mở đầu không phải Home" không xảy ra:** trang đầu lấy từ `SavedStateHandle`, mở mới luôn là Home. Luật vẫn xử lý trường hợp đó.
- **Xoay máy lúc intro:** intro dừng, activity mới không chạy lại. Lúc thử thấy một crash có sẵn của `Arena3D` khi xoay trong 3 s đầu, có hay không có intro đều vậy (`CAM_CHANGES` mục 7).
