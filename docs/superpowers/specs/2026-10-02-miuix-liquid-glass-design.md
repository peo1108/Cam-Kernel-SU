# Miuix Liquid Glass Redesign + SU Kernel Branding

Date: 2026-10-02
Branch: feat/managerless-seed
Scope: `manager/` only (Kotlin / Compose). No kernel, ksud, or supercall changes.

## 1. Goal

Make the Manager's Miuix UI mode look like iOS "Liquid Glass": translucent,
refracting glass surfaces (bars, cards, dialogs, popups, buttons) floating over
a colorful background. Also rebrand the app as "SU Kernel" with a new launcher
icon.

Decisions already made with the user:

- Upgrade the existing Miuix mode in place. No new `UiMode`, no third screen set.
- Liquid glass **replaces** the current Miuix look. No on/off toggle.
- Background: animated theme-tinted gradient by default, with an option to use
  a user-picked image instead.
- Shared `Glass*` component layer (approach A), not a theme-level hack and not
  a fork of the miuix library.
- Launcher icon: the full `IMG_7454.PNG` logo (triangle + text), not cropped.
- App name: "SU Kernel".

Out of scope: Material UI mode, module WebUI, kernel/ksud, in-app strings that
refer to "KernelSU" as the root solution (kept as is).

## 2. Platform facts

- `minSdk = 31`, so `RenderEffect` blur is always available.
- `RuntimeShader` (lens refraction, `BgEffectBackground` animated gradient)
  requires API 33.
- Fallback tier is therefore API 31-32 (Android 12/12L): blur + vibrancy only,
  no lens, static gradient background.
- Existing building blocks to reuse:
  - `ui/component/liquid/` - `Lens.kt`, `InnerShadow.kt`, `Vibrancy.kt`,
    `CombinedBackdrop.kt` (ported from Kyant0/AndroidLiquidGlass).
  - `ui/component/FloatingBottomBar.kt` - working liquid glass pill nav bar
    using `top.yukonga.miuix.kmp.blur.drawBackdrop`.
  - `ui/component/miuix/animation/` - `InteractiveHighlight`, `DampedDragAnimation`.
  - `ui/component/miuix/effect/BgEffectBackground.kt` - animated OS3 gradient.
  - `ui/util/` - `rememberBlurBackdrop`, `BlurredBar`.

## 3. Architecture: three layers

```text
Layer 3  Top bar + bottom bar   glass sampling layer 1 + layer 2 (CombinedBackdrop)
Layer 2  Scrolling content      GlassCard samples layer 1
Layer 1  Background             animated gradient OR user image
```

- **Layer 1** is placed once in `MainActivity`, wrapping the nav host, so it
  does not redraw on navigation and stays continuous while swiping the pager.
  It records into a `LayerBackdrop` exposed app-wide via a new
  `LocalGlassBackdrop` CompositionLocal.
- **Layer 2**: every Miuix `Scaffold` gets a transparent container color so
  layer 1 shows through. Cards sample `LocalGlassBackdrop` (background only),
  which avoids recursive sampling of the layer they live in.
- **Layer 3**: bars sample a `CombinedBackdrop(background, pageContent)`,
  same pattern `FloatingBottomBar` already uses. `BlurredBar` (today a
  `textureBlur` + 87% surface) is replaced by glass with a light lens.

Settings impact:

- `enableBlur` and `enableFloatingBottomBarBlur` switches are hidden in the
  Miuix settings UI (glass is always on). The keys stay in
  `SettingsRepository` so Material mode is untouched and no migration is needed.
- `enableFloatingBottomBar` stays: floating pill vs docked bar; both are glass.

## 4. Glass components

New package `me.weishu.kernelsu.ui.component.glass`. Each component keeps the
signature of the Miuix component it replaces so call-site edits are mostly
renames.

| Component | Replaces | Effect |
| --- | --- | --- |
| `GlassCard` | `Card` | blur 8dp + vibrancy + edge lens, specular highlight, ~35% surface tint for text contrast |
| `GlassListCard` | `Card` in long lists (Module, Superuser, Sulog, ModuleRepo) | blur + vibrancy only, no lens, for scroll performance |
| `GlassTopBar` | `TopAppBar` / `SmallTopAppBar` via `BlurredBar` | blur + lens along bottom edge; tint ramps 0% -> 60% with scroll |
| `GlassButton`, `GlassIconButton` | `TextButton`, `IconButton`, FAB | pill shape; press scales and brightens via `InteractiveHighlight` + `DampedDragAnimation` |
| `GlassDialog` | `OverlayDialog` | `backgroundColor = Transparent` + `drawBackdrop` modifier, blur 16dp |
| `GlassPopup` | `OverlayListPopup`, `OverlayDropdownPreference` | as dialog, smaller corner radius |

All tunables (blur radii, lens heights, tint alphas for light/dark, corner
radii) live in a single `GlassDefaults` object.

Not wrapped (kept as Miuix, transparent background, they sit inside glass
cards): `SwitchPreference`, `ArrowPreference`, `TextField`, `Slider`, `TabRow`.

Fallback inside every component: when `RuntimeShader` is unsupported, skip
`lens(...)` and keep blur + vibrancy + tint.

### Known risks (resolved by the spike, step 0)

1. `OverlayDialog` / `OverlayListPopup` render inside the same Compose tree.
   Sampling a backdrop that contains the dialog itself may recurse. Expected
   resolution: dialogs sample layer 1 plus a snapshot of page content.
2. `WindowDialog` (6 call sites) is a separate window and cannot sample the
   app's backdrop. Convert to `OverlayDialog` where behavior allows; otherwise
   use an opaque frosted surface.

## 5. Background and settings

`GlassBackground` composable, two sources:

1. **Animated gradient (default)**
   - Reuse `BgEffectBackground` / `BgEffectPainter`. Add a function that builds
     a `BgEffectConfig.Config` from the theme key color (`keyColor` or Monet):
     4 neighboring hues, lightness taken from the existing light/dark presets.
     Changing theme color changes the background.
   - Animation pauses when the app is not resumed (lifecycle).
   - API 31-32: static `Brush.linearGradient` of the same colors.
2. **User image**
   - Picked with Photo Picker (`PickVisualMedia`, no permission). Copied to
     `filesDir/glass_bg.jpg`, downscaled so the long edge is at most the screen's
     long edge. No dependency on content URIs.
   - Blur slider 0-40dp, dim/lighten overlay slider 0-60% (direction follows
     light/dark theme) to keep text readable.
   - If the file is missing or fails to decode, fall back to the gradient.

New `SettingsRepository` keys (SharedPreferences, like existing keys):

```kotlin
glassBackgroundType: Int     // 0 = animated gradient, 1 = image
glassBackgroundBlur: Float   // dp
glassBackgroundDim: Float    // 0..0.6
```

Settings UI (Miuix): "Appearance" group gets a `Glass background` entry opening
a sub-page with:

- Live preview (a sample `GlassCard` over the real background)
- Gradient / Image selector
- "Choose image" button + the two sliders (only when Image is selected)

Strings added to `values/strings.xml` (English) and `values-vi/strings.xml`.
Other locales fall back to English.

## 6. Branding

- **Name**: `defaultManagerName` in `manager/app/build.gradle.kts` becomes
  `"SU Kernel"` (`"SU Kernel PR"` for PR builds). The launcher label and APK
  file name (`SU_Kernel_<version>_<code>.apk`) follow. `KSU_NAME` override
  still works. In-app strings mentioning "KernelSU" are unchanged.
- **Icon**: full `IMG_7454.PNG` logo (1254x1254, black background).
  - Adaptive icon: `@color/ic_launcher_background` = black; foreground =
    full logo scaled to fit the 66dp safe zone of the 108dp canvas, exported as
    PNG mipmaps (mdpi..xxxhdpi) replacing the vector `ic_launcher_foreground`.
  - Monochrome layer (themed icons, Android 13+): silhouette generated from the
    logo by luminance threshold, kept to the triangle outline so it stays
    legible as a single color.
  - Source image committed at `manager/icon/launcher-src.png`; mipmaps are
    generated by `scripts/gen_launcher_icon.py` (Pillow) so the icon can be
    regenerated.

## 7. Implementation order

Each step is one `manager: ...` commit.

0. **Spike (throwaway)**: test `OverlayDialog` + `drawBackdrop` for recursion;
   check each `WindowDialog` for conversion. Outcome fixes `GlassDialog` design.
1. **Foundation**: `GlassDefaults`, `LocalGlassBackdrop`, `GlassBackground`
   (theme gradient) in `MainActivity`; transparent Miuix scaffolds.
2. **Branding**: app name + adaptive/monochrome icon.
3. **Home pilot**: `GlassCard`, `GlassTopBar` applied to `HomeMiuix.kt`.
   **Stop and send the user screenshots/APK** to approve the glass look before
   rolling out.
4. **Roll out to remaining screens**: Superuser, Module, ModuleRepo, Settings,
   AppProfile, Template, TemplateEditor, Sulog, About, Install, Flash,
   ExecuteModuleAction, ColorPalette. `GlassListCard` in long lists.
5. **Buttons, dialogs, popups**: `GlassButton`, `GlassIconButton`,
   `GlassDialog`, `GlassPopup`; docked bottom bar becomes glass.
6. **Image background + Glass background settings page**, strings, hide old
   blur switches in Miuix settings.
7. **Performance and fallback pass**.

## 8. Verification

- **Unit tests (JVM)**, new `manager/app/src/test`: gradient `Config`
  generation from key color (light, dark, grey/unsaturated input) and image
  downscale size calculation.
- **Build**: build ksud, copy `libksud.so` into `jniLibs` per AGENTS.md, then
  `./gradlew assembleRelease` after every step.
- **Visual**: install on device/emulator; screenshot each screen in light and
  dark, with gradient and with image background.
- **Performance**: `adb shell dumpsys gfxinfo cam.su.kernel` while scrolling
  Module and Superuser lists; compare janky frame % to a pre-change baseline.
  If clearly worse, reduce values in `GlassDefaults`.
- **Fallback**: API 31 emulator shows static gradient + blur, no
  `RuntimeShader` crash.
- **Branding**: launcher shows "SU Kernel" and the new icon (round, squircle,
  themed icon on Android 13+).
