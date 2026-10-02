# Miuix Liquid Glass + SU Kernel Branding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Manager's Miuix UI mode into iOS-style liquid glass over the device wallpaper, and rebrand the app as "SU Kernel" with the new logo icon.

**Architecture:** A root-level glass background (device wallpaper read via libsu, gradient, custom image, or plain surface) is drawn per navigation page and recorded into a `LayerBackdrop` exposed as `LocalGlassBackdrop`. Cards sample that background; bars sample background + page content through the existing `CombinedBackdrop`. Most screen call sites stay unchanged because the existing `rememberBlurBackdrop` / `BlurredBar` helpers are rewritten to be glass, and `Card` is swapped for `GlassCard`.

**Tech Stack:** Kotlin, Jetpack Compose, miuix 0.9.4 (`miuix-ui`, `miuix-blur`), libsu 6.0.0 (`core`, `io`), Pillow (icon script), JUnit 4 (new).

**Spec:** `docs/superpowers/specs/2026-10-02-miuix-liquid-glass-design.md`

## Global Constraints

- Scope is `manager/` (+ one script in `scripts/`). No kernel, ksud, supercall, or JNI changes.
- Material `UiMode` must look and behave exactly as before.
- Glass is always on in Miuix mode; no on/off toggle.
- `minSdk = 31`; lens and animated gradient need `RuntimeShader` (API 33). On API 31-32: blur + vibrancy + tint only, static gradient.
- Background source default = device wallpaper; fallback when unreadable = plain `MiuixTheme.colorScheme.surface` (no gradient, no wallpaper colors).
- Background types: `0` device wallpaper, `1` animated gradient, `2` custom image. Blur slider 0-40dp, dim slider 0-0.6.
- Wallpaper path: `/data/system/users/<uid / 100000>/wallpaper`, read through libsu `SuFile` / `SuFileInputStream`. No new Android permissions.
- App name `"SU Kernel"` (`"SU Kernel PR"` for PR builds). In-app "KernelSU" strings unchanged.
- Launcher icon = full `IMG_7454.PNG` logo, fit to the 66dp safe zone of a 108dp canvas, black background.
- New strings: English `values/strings.xml` + Vietnamese `values-vi/strings.xml` only.
- Commits: `manager: <Summary>` (`scripts:` for the icon script is folded into the branding commit), ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not stage `IMG_7454.PNG` at repo root or the unrelated `manager/app/src/main/cpp/uapi` deletion.
- Build gate for every task: `cd manager && ./gradlew :app:testDebugUnitTest :app:assembleDebug` succeeds (`libksud.so` is already present in `app/src/main/jniLibs/arm64-v8a/`). adb: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`.

## Review Focus

1. **Navigation push/pop with transparent pages** - during the slide transition both pages are visible; each page must paint its own background so the outgoing page never shows through the incoming one. Task 3 owns this (`GlassPage` per `NavDisplay` entry); verified manually in Task 3 step "transition check".
2. **Very large or odd wallpapers** (e.g. 4000x9000 PNG, 1x1, corrupt file) - must not OOM or crash; decode is downsampled, failures fall back to plain surface. Task 2 tests `downscaleTarget` extremes; Task 3 tests decode failure path.
3. **Root denied / revoked after a wallpaper was cached** - must fall back to plain surface, not keep showing a stale wallpaper. Task 2 test `refresh returns null without root`.
4. **Text contrast on bright wallpapers in light mode and dark wallpapers in dark mode** - card tint + dim overlay must keep body text readable. Manual check in Task 4 (pilot screenshots on a white and on a black wallpaper).
5. **Long-list scroll performance** (Superuser with 300+ apps, Module list) - `GlassListCard` has no lens; janky-frame % measured in Task 9 against the baseline captured in Task 0.

---

### Task 0: Spike - overlay dialog backdrop and baseline (throwaway)

**Files:** throwaway branch `spike/glass-dialog` only; nothing merged. Findings appended to the spec under a new `## 9. Spike results` section (that edit IS committed).

- [ ] **Step 1: Capture baseline performance** - install current `assembleDebug` APK, open Superuser tab, scroll top-to-bottom 5 times, run `adb shell dumpsys gfxinfo cam.su.kernel` and record "Janky frames" % for Superuser and Module lists.
- [ ] **Step 2: Recursion test** - in `ui/component/dialog/DialogMiuix.kt` pass `backgroundColor = Color.Transparent` and `modifier = Modifier.drawBackdrop(backdrop = <page LayerBackdrop>, shape = { RoundedCornerShape(32.dp) }, effects = { blur(16.dp.toPx(), 16.dp.toPx()) })` to one `OverlayDialog`. Open it on device. Record: renders / blank / crash / visible feedback loop.
- [ ] **Step 3: If Step 2 loops**, retry sampling only a background-only `LayerBackdrop` (a `Box` with `layerBackdrop` placed behind the `Scaffold`). Record result.
- [ ] **Step 4: WindowDialog audit** - for each `WindowDialog(` call site (`grep -rn "WindowDialog(" manager/app/src/main/java --include=*Miuix.kt` plus `SeedPicker.kt`, `DownloadDialog.kt`, `ScaleDialog.kt`, `SendLogDialog.kt`, `SuperEditArrow.kt`; skip `webui/`), note whether it is shown from outside a Miuix `Scaffold` popup host (needs a window) or can become `OverlayDialog`.
- [ ] **Step 5: Write `## 9. Spike results`** in the spec: baseline jank numbers, which backdrop dialogs sample, list of WindowDialog sites to convert vs keep opaque. Delete the spike branch.
- [ ] **Step 6: Commit** - `git commit -m "docs: record liquid glass spike results"` (spec only).

---

### Task 1: Unit-test setup + theme-tinted gradient config

**Files:**
- Modify: `manager/gradle/libs.versions.toml` (add `junit = "4.13.2"` and `junit = { group = "junit", name = "junit", version.ref = "junit" }`)
- Modify: `manager/app/build.gradle.kts` (`testImplementation(libs.junit)`)
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/miuix/effect/BgEffectConfig.kt`
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/miuix/effect/BgEffectBackground.kt`
- Test: `manager/app/src/test/java/me/weishu/kernelsu/ui/component/miuix/effect/BgEffectTintTest.kt`

**Interfaces:**
- Produces: `internal fun BgEffectConfig.tint(base: BgEffectConfig.Config, seedArgb: Int): BgEffectConfig.Config`; `BgEffectBackground(..., seedColor: Color? = null, ...)` - when non-null, the preset is passed through `tint`.

- [ ] **Step 1: Write failing tests** in `BgEffectTintTest`:

```kotlin
@Test fun greySeedReturnsBaseUnchanged() {
    val base = BgEffectConfig.get(DeviceType.PHONE, isDark = false)
    assertSame(base, BgEffectConfig.tint(base, 0xFF808080.toInt()))
}
@Test fun redSeedShiftsHuesByOffsets() {          // seed hue 0deg
    val out = BgEffectConfig.tint(BgEffectConfig.get(DeviceType.PHONE, false), 0xFFFF0000.toInt())
    listOf(out.colors1, out.colors2, out.colors3).forEach { arr ->
        assertEquals(16, arr.size)
        floatArrayOf(330f, 350f, 15f, 35f).forEachIndexed { i, h -> assertHueNear(h, hueOf(arr, i), 1f) }
    }
}
@Test fun valueAndAlphaPreserved() { /* for light and dark presets: V and A of every point within 0.01 of base */ }
@Test fun pointsAndTimingCopiedFromBase() { /* points, colorInterpPeriod, lightOffset, saturateOffset, pointOffset equal */ }
```

`hueOf` / `assertHueNear` are private test helpers (circular distance). Check the real `BgEffectConfig.get` parameter names before writing and match them.

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*BgEffectTintTest"` - expect FAIL (unresolved `tint`).
- [ ] **Step 3: Implement `tint`** - pure Kotlin (no `android.graphics.Color`; it is stubbed in JVM tests). Seed HSV via private `rgbToHsv`; if seed saturation `< 0.15f` return `base`. Otherwise for each of `colors1..3`, for point index `i in 0..3` (RGBA at `i*4`): convert to HSV, hue = `(seedHue + HUE_OFFSETS[i]).mod(360f)` with `HUE_OFFSETS = floatArrayOf(-30f, -10f, 15f, 35f)`, saturation = `max(s, 0.25f)`, keep V and A, convert back. Return a new `Config` copying all other fields.
- [ ] **Step 4: Add `seedColor: Color? = null`** to `BgEffectBackground`; `preset` = `remember(deviceType, isDarkTheme, seedColor) { BgEffectConfig.get(...).let { if (seedColor != null) BgEffectConfig.tint(it, seedColor.toArgb()) else it } }`. Existing caller (`AboutMiuix.kt`) unchanged.
- [ ] **Step 5: Run tests + build gate** - all PASS, `assembleDebug` OK.
- [ ] **Step 6: Commit** - `manager: Tint background effect from a seed color`.

---

### Task 2: Wallpaper repository

**Files:**
- Create: `manager/app/src/main/java/me/weishu/kernelsu/data/repository/WallpaperRepository.kt`
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/glass/GlassImage.kt`
- Test: `manager/app/src/test/java/me/weishu/kernelsu/data/repository/WallpaperRepositoryTest.kt`
- Test: `manager/app/src/test/java/me/weishu/kernelsu/ui/component/glass/GlassImageTest.kt`

**Interfaces:**
- Produces (WallpaperRepository.kt):
  - `data class FileStamp(val lastModified: Long, val length: Long)`
  - `fun systemWallpaperPath(uid: Int): String` -> `"/data/system/users/${uid / 100000}/wallpaper"`
  - `fun shouldRecopy(source: FileStamp, cached: FileStamp?): Boolean` -> `cached != source`
  - `interface RootFiles { fun isRoot(): Boolean; fun stamp(path: String): FileStamp?; fun open(path: String): InputStream }` with `LibsuRootFiles` impl (`Shell.getShell().isRoot`, `SuFile(path)` exists/`lastModified()`/`length()`, `SuFileInputStream.open(SuFile(path))`).
  - `class WallpaperRepository(filesDir: File, prefs: SharedPreferences, root: RootFiles = LibsuRootFiles, uid: Int = Process.myUid())` with `suspend fun refresh(): File?` on `Dispatchers.IO`. Returns `filesDir/glass_wallpaper` when usable, else `null`. Stores the source stamp in prefs keys `glass_wallpaper_mtime` / `glass_wallpaper_len`. Copies to `glass_wallpaper.tmp` then renames.
- Produces (GlassImage.kt):
  - `fun downscaleTarget(width: Int, height: Int, maxEdge: Int): Pair<Int, Int>` - keeps aspect, long edge `<= maxEdge`, never upscales, each side `>= 1`.
  - `suspend fun decodeGlassBitmap(file: File, maxEdge: Int): ImageBitmap?` - `BitmapFactory` bounds pass, `inSampleSize` = largest power of 2 keeping long edge `>= maxEdge`, then `Bitmap.createScaledBitmap` to `downscaleTarget`; any exception or null decode -> `null`.

- [ ] **Step 1: Write failing tests**

```kotlin
// WallpaperRepositoryTest (fake RootFiles + temp dir + in-memory SharedPreferences fake)
@Test fun pathForPrimaryUser() = assertEquals("/data/system/users/0/wallpaper", systemWallpaperPath(10234))
@Test fun pathForSecondaryUser() = assertEquals("/data/system/users/10/wallpaper", systemWallpaperPath(1010234))
@Test fun recopyWhenNoCache() = assertTrue(shouldRecopy(FileStamp(1, 2), null))
@Test fun noRecopyWhenSame() = assertFalse(shouldRecopy(FileStamp(1, 2), FileStamp(1, 2)))
@Test fun recopyWhenChanged() = assertTrue(shouldRecopy(FileStamp(1, 3), FileStamp(1, 2)))
@Test fun refreshReturnsNullWithoutRoot()            // even if glass_wallpaper already exists
@Test fun refreshReturnsNullWhenSourceMissing()      // live wallpaper: stamp() == null
@Test fun refreshCopiesBytesOnFirstRun()             // file content == fake source bytes, open() called once
@Test fun refreshSkipsCopyWhenStampUnchanged()       // second refresh: open() not called again
```

```kotlin
// GlassImageTest
@Test fun portraitDownscaled() = assertEquals(1080 to 2400, downscaleTarget(2160, 4800, 2400))
@Test fun neverUpscales() = assertEquals(500 to 800, downscaleTarget(500, 800, 2400))
@Test fun hugeImage() = assertEquals(1067 to 2400, downscaleTarget(4000, 9000, 2400))
@Test fun degenerateImage() = assertEquals(1 to 2400, downscaleTarget(1, 9000, 2400))
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "*WallpaperRepositoryTest" --tests "*GlassImageTest"` - FAIL.
- [ ] **Step 3: Implement** the interfaces above. `Process.myUid()` only as the default argument (keeps tests JVM-safe).
- [ ] **Step 4: Run tests + build gate** - PASS.
- [ ] **Step 5: Commit** - `manager: Add root wallpaper repository for glass background`.

---

### Task 3: Glass background foundation

**Files:**
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/data/repository/SettingsRepository.kt`, `SettingsRepositoryImpl.kt` - add `glassBackgroundType: Int` (key `glass_background_type`, default `0`, coerce `0..2`), `glassBackgroundBlur: Float` (`glass_background_blur`, default `0f`, coerce `0f..40f`), `glassBackgroundDim: Float` (`glass_background_dim`, default `0.2f`, coerce `0f..0.6f`).
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/viewmodel/MainActivityViewModel.kt` - expose the three values in its ui state and add the three keys to its watched-keys list (next to `"enable_floating_bottom_bar_blur"`).
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/glass/GlassBackground.kt`
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt`
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/util/BlurExt.kt`
- Modify: every Miuix `Scaffold(` call in `ui/screen/**/*Miuix.kt` - add `containerColor = Color.Transparent`.

**Interfaces:**
- Consumes: Task 1 `BgEffectBackground(seedColor = ...)`; Task 2 `WallpaperRepository.refresh()`, `decodeGlassBitmap(file, maxEdge)`.
- Produces (GlassBackground.kt):
  - `object GlassBackgroundType { const val WALLPAPER = 0; const val GRADIENT = 1; const val IMAGE = 2 }`
  - `sealed interface GlassSource { data object Plain; data object Gradient; data class Bitmap(val image: ImageBitmap) }`
  - `@Immutable data class GlassBackgroundState(val source: GlassSource, val blur: Dp, val dim: Float, val wallpaperFallback: Boolean)` - `wallpaperFallback = type == WALLPAPER && source == Plain`.
  - `val LocalGlassBackgroundState = staticCompositionLocalOf { GlassBackgroundState(GlassSource.Plain, 0.dp, 0f, false) }`
  - `val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }`
  - `@Composable fun rememberGlassBackgroundState(type: Int, blur: Float, dim: Float): GlassBackgroundState` - for `WALLPAPER` runs `WallpaperRepository.refresh()` in a `LifecycleResumeEffect` (every `ON_RESUME`) then decodes; for `IMAGE` decodes `filesDir/glass_bg.jpg` (Task 8 writes it), falling back to `Gradient` on failure; `maxEdge` = long edge of the window in px. Decoding on `Dispatchers.IO`; previous bitmap stays until the new one is ready.
  - `@Composable fun GlassPage(modifier: Modifier = Modifier, content: @Composable () -> Unit)` - fills max size; draws a background `Box` (`Modifier.layerBackdrop(pageBackdrop)`) behind `content`, and provides `LocalGlassBackdrop provides pageBackdrop` to `content`. Background drawing per `source`: `Plain` -> `colorScheme.surface`; `Gradient` -> `BgEffectBackground(dynamicBackground = true, seedColor = colorScheme.primary)` on API 33+, else `Brush.linearGradient` of the 4 tinted colors; `Bitmap` -> `Image(ContentScale.Crop, Modifier.blur(blur))` + overlay `drawRect(if (dark) Color.Black else Color.White, alpha = dim)`.
- Produces (BlurExt.kt, behavior change, same signatures):
  - `rememberBlurBackdrop(enableBlur)` records content only (`rememberLayerBackdrop { drawContent() }`, no `surface` fill).
  - `BlurredBar(backdrop, blurActive, content)` samples `rememberCombinedBackdrop(LocalGlassBackdrop.current ?: backdrop, backdrop)` with `drawBackdrop` effects `vibrancy(); blur(GlassDefaults.barBlur); lens(...)` and a scroll-independent tint `colorScheme.surface.copy(alpha = GlassDefaults.barTint)`. `GlassDefaults` is created in Task 4; in this task use literals `blur 12.dp`, tint `0.55f`, no lens, and move them into `GlassDefaults` in Task 4.

- [ ] **Step 1: Settings keys** - add the three properties; build gate.
- [ ] **Step 2: GlassBackground.kt** - implement the interfaces above.
- [ ] **Step 3: MainActivity wiring** - in the `UiMode.Miuix` branch: compute `rememberGlassBackgroundState(uiState.glassBackgroundType, uiState.glassBackgroundBlur, uiState.glassBackgroundDim)` and provide it via `LocalGlassBackgroundState`; `Scaffold(containerColor = Color.Transparent) { navDisplay() }`. Wrap every `entry<...> { ... }` body in `GlassPage { ... }` when `uiMode == UiMode.Miuix` (a small local `@Composable fun Page(content)` helper avoids repeating the `if`). Change `LocalEnableBlur provides (uiMode == UiMode.Miuix || uiState.enableBlur)` and same for `LocalEnableFloatingBottomBarBlur`. In `MainScreen`, the floating-bar `backdrop` stops filling `surfaceColor` in Miuix mode.
- [ ] **Step 4: BlurExt.kt** behavior change as specified.
- [ ] **Step 5: Transparent scaffolds** - `containerColor = Color.Transparent` on every Miuix `Scaffold(`; verify with `rg -n "Scaffold\(" manager/app/src/main/java --glob "*Miuix.kt"` that none is left without it.
- [ ] **Step 6: Build gate + install** - `./gradlew :app:assembleDebug`, `adb install -r app/build/outputs/apk/debug/*.apk`.
- [ ] **Step 7: Manual checks** - (a) wallpaper visible behind Home with root granted; (b) change home wallpaper, return to app -> updates; (c) set a live wallpaper -> plain surface; (d) **transition check**: open About from Settings and swipe back slowly - outgoing page never visible through incoming page; (e) Material mode unchanged.
- [ ] **Step 8: Commit** - `manager: Draw device wallpaper behind Miuix pages`.

---

### Task 4: GlassDefaults, GlassCard and Home pilot (user checkpoint)

**Files:**
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/glass/GlassDefaults.kt`
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/glass/GlassCard.kt`
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/util/BlurExt.kt` (literals -> `GlassDefaults`, add lens)
- Modify: `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMiuix.kt`, `manager/app/src/main/java/me/weishu/kernelsu/ui/component/miuix/WarningCard.kt`

**Interfaces:**
- Consumes: `LocalGlassBackdrop` (Task 3), `lens`, `vibrancy` (`ui/component/liquid`), `Highlight` presets from `top.yukonga.miuix.kmp.blur.highlight`.
- Produces:
  - `object GlassDefaults` - `cardBlur = 8.dp`, `listCardBlur = 8.dp`, `barBlur = 12.dp`, `dialogBlur = 16.dp`, `cardLensHeight = 12.dp`, `cardLensAmount = 16.dp`, `barLensHeight = 8.dp`, `barLensAmount = 12.dp`, `cardTintLight = 0.35f`, `cardTintDark = 0.35f`, `barTint = 0.55f`, `cardCorner = 20.dp`, `dialogCorner = 32.dp`, `popupCorner = 20.dp`, and `@Composable fun cardTint(): Color` (surface with the light/dark alpha).
  - `@Composable fun GlassCard(modifier: Modifier = Modifier, cornerRadius: Dp = GlassDefaults.cardCorner, insideMargin: PaddingValues = CardDefaults.InsideMargin, lens: Boolean = true, onClick: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)` - mirrors miuix `Card` parameters used in this codebase. Uses `drawBackdrop(LocalGlassBackdrop.current, shape, effects = { vibrancy(); blur(cardBlur); if (lens) lens(cardLensHeight, cardLensAmount) }, highlight = { if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight }, onDrawSurface = { drawRect(cardTint) })`. When `LocalGlassBackdrop.current == null` it renders `Modifier.background(cardTint, shape)`. Click/long-press via `combinedClickable` with `PressFeedbackType.Sink`-like scale (reuse `pressable`/`InteractiveHighlight` if trivial, else plain scale 0.97f).
  - `@Composable fun GlassListCard(...)` - same signature, calls `GlassCard(lens = false, ...)`.

- [ ] **Step 1: GlassDefaults + GlassCard + GlassListCard** - implement.
- [ ] **Step 2: BlurExt.kt** - use `GlassDefaults.barBlur`, `barTint`, add `lens(barLensHeight, barLensAmount)`.
- [ ] **Step 3: Home pilot** - replace `Card(` with `GlassCard(` in `HomeMiuix.kt` and `WarningCard.kt`, keeping arguments.
- [ ] **Step 4: Build gate + install + screenshots** - Home in light and dark, on a bright and on a dark wallpaper, with `adb exec-out screencap -p > home-<variant>.png` (4 files in the scratchpad, not the repo).
- [ ] **Step 5: Commit** - `manager: Add glass cards and apply them to Home`.
- [ ] **Step 6: STOP - user checkpoint.** Send the 4 screenshots and the APK path to the user. Adjust only `GlassDefaults` values per feedback (amend with a follow-up commit `manager: Tune glass defaults`). Do not start Task 6 before the user approves.

---

### Task 5: SU Kernel branding

**Files:**
- Create: `manager/icon/launcher-src.png` (copy of repo-root `IMG_7454.PNG`; root file stays untracked)
- Create: `scripts/gen_launcher_icon.py`
- Create: `manager/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_logo.png`, `.../ic_launcher_logo_mono.png`
- Modify: `manager/app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `manager/app/src/main/res/values/colors.xml`, `manager/app/build.gradle.kts:27`

**Interfaces:**
- Produces: `python scripts/gen_launcher_icon.py [src]` (default `manager/icon/launcher-src.png`) writes the 10 PNGs above. Foreground canvas sizes 108dp -> mdpi 108, hdpi 162, xhdpi 216, xxhdpi 324, xxxhdpi 432 px; the logo (whole image, cropped to `(150, 30, 1104, 1184)` like the approved preview) is fit into the centered 66% square on a transparent canvas. Mono: same geometry, alpha = luminance threshold (`L > 90` -> opaque white) of the cropped logo, then dilate 1px so thin strokes survive at mdpi.

- [ ] **Step 1: Script** - write and run `python scripts/gen_launcher_icon.py`; check `xxxhdpi/ic_launcher_logo.png` visually (Read the image).
- [ ] **Step 2: Resources** - `ic_launcher.xml`: foreground `@mipmap/ic_launcher_logo`, monochrome `@mipmap/ic_launcher_logo_mono`; `colors.xml`: `ic_launcher_background` = `#FF000000`. Leave `@drawable/ic_launcher_foreground` (used in About/Module screens) and the splash drawable untouched.
- [ ] **Step 3: Name** - `defaultManagerName = if (isPrBuild) "SU Kernel PR" else "SU Kernel"`.
- [ ] **Step 4: Build gate + install** - launcher label "SU Kernel"; icon correct in round and squircle launchers; themed icon on Android 13+; APK named `SU_Kernel_*.apk`.
- [ ] **Step 5: Commit** - `manager: Rebrand app as SU Kernel with new launcher icon`.

---

### Task 6: Roll glass cards out to all Miuix screens

**Files (modify):** `ui/screen/{superuser/SuperUserMiuix,module/ModuleMiuix,modulerepo/ModuleRepoMiuix,settings/SettingsMiuix,appprofile/AppProfileMiuix,template/TemplateMiuix,templateeditor/TemplateEditorMiuix,sulog/SulogMiuix,about/AboutMiuix,install/InstallMiuix,install/SeedPicker,flash/FlashMiuix,executemoduleaction/ExecuteModuleActionMiuix,colorpalette/ColorPaletteScreenMiuix}.kt`, `ui/component/profile/RootProfileConfigMiuix.kt`, and any other `ui/component/**/*Miuix.kt` using `Card(`.

**Interfaces:** Consumes `GlassCard`, `GlassListCard` (Task 4).

- [ ] **Step 1: Long lists use `GlassListCard`** - per-item cards inside `LazyColumn` `items(...)` in SuperUser, Module, ModuleRepo, Sulog.
- [ ] **Step 2: All other `Card(` -> `GlassCard(`**. AboutMiuix keeps its own `BgEffectBackground` header only if it still reads well over the page background; otherwise remove that `BgEffectBackground` call (the page background replaces it).
- [ ] **Step 3: Verify none left** - `rg -n "\bCard\(" manager/app/src/main/java --glob "*Miuix.kt"` returns nothing except `webui/`.
- [ ] **Step 4: Build gate + install** - visit every screen in light and dark; screenshot Superuser and Module.
- [ ] **Step 5: Commit** - `manager: Apply glass cards to all Miuix screens`.

---

### Task 7: Glass buttons, dialogs, popups, docked bottom bar

**Files:**
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/component/glass/GlassButton.kt`, `GlassDialog.kt`, `GlassPopup.kt`
- Modify: Miuix call sites of `TextButton(`, `IconButton(`, `FloatingActionButton(`, `OverlayDialog(`, `OverlayListPopup(`, `OverlayDropdownPreference(` and the `WindowDialog(` sites the spike marked convertible; `ui/component/bottombar/BottomBarMiuix.kt` (docked branch, `!enableFloatingBottomBar`).

**Interfaces:**
- Consumes: `GlassDefaults`, `LocalGlassBackdrop`, spike results (spec section 9) deciding which backdrop dialogs sample.
- Produces:
  - `GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false)` - pill (`CircleShape`), glass via `drawBackdrop`, primary tints with `colorScheme.primary.copy(alpha = 0.85f)`; press = `InteractiveHighlight` + scale from `DampedDragAnimation` as in `FloatingBottomBar.kt`.
  - `GlassIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit)` - 40dp circle, same effects.
  - `GlassFab(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit)` - 56dp circle, primary tint.
  - `GlassDialog(show, title, summary, onDismissRequest, ..., content)` - same parameters as the miuix `OverlayDialog` overload used here; passes `backgroundColor = Color.Transparent` and the backdrop modifier with `GlassDefaults.dialogBlur` / `dialogCorner`. Opaque-frosted fallback (`surface.copy(alpha = 0.92f)`) for unconverted `WindowDialog` sites.
  - `GlassPopup` / `GlassDropdownPreference` - same pattern with `popupCorner`.

- [ ] **Step 1: GlassButton.kt** + replace `TextButton(`, `IconButton(`, `FloatingActionButton(` in Miuix files (keep icon-only top bar actions as `GlassIconButton`).
- [ ] **Step 2: GlassDialog.kt** + replace `OverlayDialog(` and convertible `WindowDialog(` sites.
- [ ] **Step 3: GlassPopup.kt** + replace `OverlayListPopup(` / `OverlayDropdownPreference(`.
- [ ] **Step 4: Docked bottom bar** - in `BottomBarMiuix.kt` non-floating branch, wrap the bar in `BlurredBar(backdrop)` (glass via Task 3/4).
- [ ] **Step 5: Build gate + install** - open every dialog type (uninstall module, reboot popup, KMI chooser, scale dialog, send log, root profile dialogs), dropdowns in Settings, FAB in Module; light and dark.
- [ ] **Step 6: Commit** - `manager: Add glass buttons, dialogs and popups`.

---

### Task 8: Glass background settings page

**Files:**
- Create: `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/colorpalette/GlassBackgroundSection.kt`
- Modify: `ui/screen/colorpalette/ColorPaletteScreenMiuix.kt`, `ColorPaletteUiState.kt`, `ColorPaletteScreen.kt`, `ui/screen/settings/SettingsUiState.kt`, `ui/viewmodel/SettingsViewModel.kt`
- Modify: `manager/app/src/main/res/values/strings.xml`, `manager/app/src/main/res/values-vi/strings.xml`

**Interfaces:**
- Consumes: settings keys (Task 3), `LocalGlassBackgroundState.wallpaperFallback`, `GlassCard`, `downscaleTarget` / `decodeGlassBitmap` (Task 2).
- Produces:
  - `SettingsUiState`: `glassBackgroundType: Int = 0`, `glassBackgroundBlur: Float = 0f`, `glassBackgroundDim: Float = 0.2f`.
  - `SettingsViewModel.setGlassBackgroundType(Int)`, `setGlassBackgroundBlur(Float)`, `setGlassBackgroundDim(Float)`, `suspend fun importGlassImage(uri: Uri): Boolean` (reads via `contentResolver`, downscales to the screen long edge, writes `filesDir/glass_bg.jpg` JPEG q90 via temp file + rename, then sets type `IMAGE`).
  - `ColorPaletteScreenActions`: `onSetGlassBackgroundType`, `onSetGlassBackgroundBlur`, `onSetGlassBackgroundDim`, `onPickGlassImage: (Uri) -> Unit`.
  - `@Composable fun GlassBackgroundSection(state: SettingsUiState, actions: ColorPaletteScreenActions)` placed in `ColorPaletteScreenMiuix` where the blur switches were.
- Strings (EN / VI):
  - `glass_background` "Glass background" / "Nen kinh" -> write with proper Vietnamese diacritics: "Nền kính"
  - `glass_background_wallpaper` "Device wallpaper" / "Hình nền máy"
  - `glass_background_gradient` "Animated gradient" / "Gradient động"
  - `glass_background_image` "Custom image" / "Ảnh tự chọn"
  - `glass_background_pick_image` "Choose image" / "Chọn ảnh"
  - `glass_background_blur` "Background blur" / "Độ mờ nền"
  - `glass_background_dim` "Background dim" / "Độ tối nền"
  - `glass_background_fallback` "Live wallpaper or no root access - using the default background" / "Live wallpaper hoặc không có quyền root - đang dùng nền mặc định"

- [ ] **Step 1: State/actions/viewmodel plumbing** - fields, setters (write repo + update state), `importGlassImage`.
- [ ] **Step 2: GlassBackgroundSection** - preview (`GlassCard` 96dp tall with sample text), `OverlayDropdownPreference`-style selector (or `GlassDropdownPreference` from Task 7) with the three types, "Choose image" `ArrowPreference` launching `rememberLauncherForActivityResult(PickVisualMedia())` (Custom image only), blur `Slider` 0-40 and dim `Slider` 0-0.6 (Wallpaper and Custom image only), fallback note text when `wallpaperFallback`.
- [ ] **Step 3: Hide old switches in Miuix** - remove the `settings_enable_blur` and `settings_enable_glass` `SwitchPreference`s from `ColorPaletteScreenMiuix.kt` only (Material screen untouched; keep the floating bottom bar switch).
- [ ] **Step 4: Strings** in both files.
- [ ] **Step 5: Build gate + install** - switch through all three types; pick a 4000px photo; sliders update live; deny root in KernelSU allowlist for the manager -> fallback note shows.
- [ ] **Step 6: Commit** - `manager: Add glass background settings page`.

---

### Task 9: Performance and fallback pass

**Files:** `ui/component/glass/GlassDefaults.kt` only if tuning is needed.

- [ ] **Step 1: Jank** - same procedure as Task 0 Step 1; compare to baseline. If Superuser or Module janky % is more than 5 points above baseline, lower `listCardBlur` to `4.dp` and re-measure; record numbers in the spec section 9.
- [ ] **Step 2: API 31 fallback** - create/boot an API 31 emulator (`sdkmanager "system-images;android-31;google_apis;x86_64"`, `avdmanager create avd`), install, walk Home/Settings/Module: no crash, blur visible, no lens, gradient type shows static gradient.
- [ ] **Step 3: Memory** - with a 4000x9000 wallpaper, `adb shell dumpsys meminfo cam.su.kernel` Graphics row stays under 150 MB.
- [ ] **Step 4: Final full verification** - `./gradlew :app:testDebugUnitTest :app:assembleRelease`; walk every screen once in light and dark.
- [ ] **Step 5: Commit** (if anything changed) - `manager: Tune glass for list performance`; update `docs/CAM_CHANGES.md` with a short "Liquid glass UI + SU Kernel branding" entry in the same commit or as `docs: record liquid glass changes`.
