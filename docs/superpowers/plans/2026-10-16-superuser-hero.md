# Superuser → App Profile Icon Flight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app icon and label fly from an expanded Superuser card to the App Profile header on "Open full profile", and back on every kind of back, following the page transition.

**Architecture:** An overlay above `NavDisplay` draws one icon and one label at a rect interpolated between the live source (Superuser card) and target (App Profile header) coordinates, with t = 1 + `relativeDepth` of the App Profile entry. Real elements hide while the copy flies. Decisions are pure functions (`HeroMath.kt`, JVM tests); Compose wiring is in `Hero.kt`.

**Tech Stack:** Kotlin, Jetpack Compose (ui-geometry `Rect` and `lerp`), Miuix (`top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope`), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-16-superuser-hero-design.md`

## Global Constraints

- No change to miuix-nav, navigation routes, kernel, camd or supercalls (spec header).
- At most one flight at a time; only the Superuser "Open full profile" path starts one.
- Source icon 48 dp, target icon 64 dp; label style: Miuix `Text`, `FontWeight(550)`, `colorScheme.onSurface`, one line.
- No flight when less than 90% of the source icon is visible, or `Settings.Global.ANIMATOR_DURATION_SCALE` is 0.
- Coordinates and depth are read in draw (`graphicsLayer`) or layout phase only; no per-frame recomposition.
- Gradle from `K:\manager` (`subst K:` if missing); never `connectedAndroidTest`; `libcamd.so` in jniLibs must be the device's camd (CAMD_SOURCE_ID of HEAD) before installing a build.
- Stage paths explicitly (never `git commit -a`: the `uapi` junction); commit subjects `<scope>: <Summary>` with the session's Co-Authored-By line.

## Review Focus

- Back pressed while the forward flight is still moving: the copy must reverse from where it is (depth reverses; covered by `heroPhase` tests and a device step in Task 3).
- Double tap on "Open full profile": one flight, no second copy (`launch` keeps an existing flight for the same key; device step in Task 3).
- Opening from a filtered list (bottom-bar search active): the searched card is the source (device step in Task 4).
- The app is uninstalled or its group disappears while App Profile is open (`AppProfileScreen` pops itself): the flight ends, nothing is left floating (`end` in the page's `DisposableEffect`; device step in Task 4).
- App Profile scrolled down before going back (header item disposed): no copy appears, the page leaves normally (`heroPhase` → `Gone` without target; JVM test in Task 2, device step in Task 4).

---

### Task 1: Check miuix-nav on the device, take the jank baseline

Throwaway probe; nothing of it is committed except the findings.

**Files:**
- Modify (temporarily, reverted in Step 4): `manager/app/src/main/java/cam/su/kernel/ui/screen/appprofile/AppProfileScreen.kt`, `manager/app/src/main/java/cam/su/kernel/ui/screen/superuser/SuperUserScreen.kt`
- Modify: `docs/superpowers/specs/2026-10-16-superuser-hero-design.md` (section 3, risk 1)

**Interfaces:** none.

- [ ] **Step 1: Add probe logging.** In `AppProfileScreen` and in `SuperUserScreen`: `val nav = LocalNavTransitionScope.current` and `LaunchedEffect(Unit) { snapshotFlow { nav.relativeDepth to nav.isRunning }.collect { Log.i("CamHero", "<screen> depth=${it.first} running=${it.second}") } }`, plus `DisposableEffect(Unit) { Log.i("CamHero", "<screen> composed"); onDispose { Log.i("CamHero", "<screen> disposed") } }`.
- [ ] **Step 2: Build, install, record.** `Push-Location K:\manager; & K:\manager\gradlew.bat :app:assembleDebug`, `adb install -r` the debug APK, `adb logcat -c`. On the tablet: Superuser tab → tap a card → "Mở hồ sơ đầy đủ" → wait 2 s → back button; again with a full edge swipe; again with a half swipe released (cancel). `adb logcat -d -s CamHero`.
  Expected to learn: the App Profile depth range on open/back (spec assumes -1 → 0 → -1), whether depth follows the swipe continuously, and whether "superuser disposed" appears while App Profile is on top.
- [ ] **Step 3: Jank baseline.** Same build, Superuser tab, first card expanded: `adb shell dumpsys gfxinfo cam.su.kernel reset`, then 10 × (tap "Mở hồ sơ đầy đủ" (coordinates from `uiautomator dump`), wait 1.5 s, `input keyevent KEYCODE_BACK`, wait 1.5 s), then `adb shell dumpsys gfxinfo cam.su.kernel | grep -E "Total frames|Janky frames|90th|99th"`. Keep the numbers.
- [ ] **Step 4: Revert the probe.** `git diff --stat` shows only the two probe files; restore them with `git restore manager/app/src/main/java/cam/su/kernel/ui/screen/appprofile/AppProfileScreen.kt manager/app/src/main/java/cam/su/kernel/ui/screen/superuser/SuperUserScreen.kt` (never a bare `git restore .`/`checkout`: the `uapi` junction).
- [ ] **Step 5: Record findings.** Replace "not yet observed" in spec risk 1 with what Step 2 showed and add the Step 3 baseline line. If the depth range or sign differs from -1 → 0, update `heroProgress` in Task 2 (its test values follow the observed range) before starting it. If the Superuser page is disposed while covered, note it: the return flight then relies on re-registration (spec risk 1).
- [ ] **Step 6: Commit.** `git add docs/superpowers/specs/2026-10-16-superuser-hero-design.md`; `git commit -m "docs: Record how miuix-nav moves App Profile for the icon flight"`.

### Task 2: Flight decisions (`HeroMath.kt`)

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/ui/component/hero/HeroMath.kt`
- Test: `manager/app/src/test/java/cam/su/kernel/ui/component/hero/HeroMathTest.kt`

**Interfaces:**
- Produces (all `internal`, package `cam.su.kernel.ui.component.hero`):
  - `const val HERO_MIN_VISIBLE = 0.9f`
  - `enum class HeroPhase { Flying, Docked, FadingOut, Gone }`
  - `fun heroProgress(depth: Float): Float`
  - `fun heroPhase(depth: Float, running: Boolean, sourceAttached: Boolean, targetAttached: Boolean): HeroPhase`
  - `fun HeroPhase.hidesSource(): Boolean`, `fun HeroPhase.hidesTarget(): Boolean`
  - `fun visibleFraction(full: Rect, clipped: Rect): Float` (`androidx.compose.ui.geometry.Rect`)
  - `fun heroCanLaunch(animationsOff: Boolean, sourceVisible: Float): Boolean`

- [ ] **Step 1: Write the failing tests** in `HeroMathTest`:

```kotlin
@Test fun progressMapsDepthToZeroOne() {
    assertEquals(0f, heroProgress(-1f), 0.001f); assertEquals(0.5f, heroProgress(-0.5f), 0.001f)
    assertEquals(1f, heroProgress(0f), 0.001f); assertEquals(0f, heroProgress(-1.5f), 0.001f)
    assertEquals(1f, heroProgress(0.4f), 0.001f)
}
@Test fun noTargetIsGone() = assertEquals(HeroPhase.Gone, heroPhase(-0.5f, running = true, sourceAttached = true, targetAttached = false))
@Test fun coveredByAnotherPageIsDocked() = assertEquals(HeroPhase.Docked, heroPhase(0.5f, running = true, sourceAttached = true, targetAttached = true))
@Test fun landedIsDocked() = assertEquals(HeroPhase.Docked, heroPhase(0f, running = false, sourceAttached = true, targetAttached = true))
@Test fun movingIsFlying() {
    assertEquals(HeroPhase.Flying, heroPhase(-0.3f, running = true, sourceAttached = true, targetAttached = true))
    // a back swipe that has just started: still at depth 0 but running
    assertEquals(HeroPhase.Flying, heroPhase(0f, running = true, sourceAttached = true, targetAttached = true))
}
@Test fun missingSourceFades() = assertEquals(HeroPhase.FadingOut, heroPhase(-0.3f, running = true, sourceAttached = false, targetAttached = true))
@Test fun hiddenElementsPerPhase() {
    assertTrue(HeroPhase.Flying.hidesSource() && HeroPhase.Flying.hidesTarget())
    assertTrue(!HeroPhase.FadingOut.hidesSource() && HeroPhase.FadingOut.hidesTarget())
    for (p in listOf(HeroPhase.Docked, HeroPhase.Gone)) assertTrue(!p.hidesSource() && !p.hidesTarget())
}
@Test fun visibleFractionOfIcon() {
    val full = Rect(0f, 0f, 100f, 100f)
    assertEquals(1f, visibleFraction(full, full), 0.001f)
    assertEquals(0.5f, visibleFraction(full, Rect(0f, 50f, 100f, 100f)), 0.001f)
    assertEquals(0f, visibleFraction(full, Rect.Zero), 0.001f)
    assertEquals(0f, visibleFraction(Rect.Zero, Rect.Zero), 0.001f)
}
@Test fun launchNeedsAnimationsAndAVisibleIcon() {
    assertTrue(heroCanLaunch(animationsOff = false, sourceVisible = 0.95f))
    assertFalse(heroCanLaunch(animationsOff = false, sourceVisible = 0.89f))
    assertFalse(heroCanLaunch(animationsOff = true, sourceVisible = 1f))
}
```

- [ ] **Step 2: Run, expect FAIL** (unresolved references): `Push-Location K:\manager; & K:\manager\gradlew.bat :app:testDebugUnitTest --tests "cam.su.kernel.ui.component.hero.*"`.
- [ ] **Step 3: Implement `HeroMath.kt`.** Rules, in this order: no target → `Gone`; `depth > 0.001f` → `Docked`; `heroProgress(depth) >= 0.999f && !running` → `Docked`; no source → `FadingOut`; otherwise `Flying`. `hidesSource` = `Flying`; `hidesTarget` = `Flying` or `FadingOut`. `visibleFraction` = area of `clipped.intersect(full)` / area of `full`, 0 when `full` has no area. KDoc on each saying what the value means for the overlay.
- [ ] **Step 4: Run, expect PASS** (same command).
- [ ] **Step 5: Commit.** `git add` the two files; `git commit -m "manager: Add the icon flight decisions"`.

### Task 3: Overlay and wiring

**Files:**
- Create: `manager/app/src/main/java/cam/su/kernel/ui/component/hero/Hero.kt`
- Modify: `manager/app/src/main/java/cam/su/kernel/ui/CamActivity.kt` (provide `LocalHero` around `navDisplay()`; `HeroOverlay` drawn over it in the `Scaffold` at line ~364)
- Modify: `manager/app/src/main/java/cam/su/kernel/ui/screen/superuser/SuperUserMiuix.kt` (`GroupItem`: icon `AppIconImage` modifier `.padding(end = 10.dp).size(48.dp)` and the label `Text`'s `Modifier.basicMarquee()`)
- Modify: `manager/app/src/main/java/cam/su/kernel/ui/screen/superuser/SuperUserScreen.kt` (`onOpenProfile`, line ~69)
- Modify: `manager/app/src/main/java/cam/su/kernel/ui/screen/appprofile/AppProfileMiuix.kt` (`appIcon` lambda line ~157; label `Text` in `AppProfileInner` line ~236)
- Modify: `manager/app/src/main/java/cam/su/kernel/ui/screen/appprofile/AppProfileScreen.kt` (end the flight when the page leaves; the header is item 0 of a `LazyColumn`, so the header itself may dispose while the page lives — this file is a fifth edit beyond the spec's list for that reason)

**Interfaces:**
- Consumes: everything Task 2 produces.
- Produces (package `cam.su.kernel.ui.component.hero`):
  - `enum class HeroPart { Icon, Label }`
  - `@Stable class HeroState(animationsOff: Boolean)` with `fun launch(key: Any, packageInfo: PackageInfo, label: String): Boolean` (false = no flight; a flight already running for the same key is kept) and `fun end(key: Any)`.
  - `val LocalHero: ProvidableCompositionLocal<HeroState?>` (`staticCompositionLocalOf { null }`)
  - `@Composable fun rememberHeroState(): HeroState` (reads `ANIMATOR_DURATION_SCALE` once, as `SlimeLayer.kt:93` does)
  - `@Composable fun Modifier.heroSource(key: Any, part: HeroPart): Modifier` — `onGloballyPositioned` stores the `LayoutCoordinates`, `DisposableEffect` forgets them, `graphicsLayer { alpha = 0f when the phase hides the source }`; no-op when `LocalHero.current` is null.
  - `@Composable fun Modifier.heroTarget(key: Any, part: HeroPart): Modifier` — same, plus stores `LocalNavTransitionScope.current` for depth and `isRunning`.
  - `@Composable fun HeroOverlay(state: HeroState, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write `Hero.kt`.**
  - `HeroState` keeps the flight (`mutableStateOf`: key, `PackageInfo`, label) and plain maps of source/target coordinates per (key, part); coordinates with `!isAttached` count as missing.
  - `launch`: source icon must be stored; full rect = `Rect(positionInWindow(), size.toSize())`, clipped = `boundsInWindow()`; start only if `heroCanLaunch(animationsOff, visibleFraction(full, clipped))`.
  - `HeroOverlay`: nothing when there is no flight. Otherwise a `fillMaxSize` box recording its own coordinates (`onPlaced`), containing `AppIconImage(packageInfo, label, Modifier.size(64.dp))` and a Miuix `Text(label, fontWeight = FontWeight(550), color = colorScheme.onSurface, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)`.
  - Each copy places itself in `graphicsLayer` with `transformOrigin = TransformOrigin(0f, 0f)`: phase from `heroPhase(depth, isRunning, sourceAttached, targetAttached)`; rects via `own.localBoundingBoxOf(coordinates, clipBounds = false)`; `Flying` → `lerp(sourceRect, targetRect, heroProgress(depth))` (ui-geometry), translation = rect top-left, icon scale = rect width / layer width; `FadingOut` → target rect, `alpha = heroProgress(depth)`; `Docked`/`Gone` → `alpha = 0f`.
  - Label width = lerped rect width, read in a `Modifier.layout` block. The overlay takes no pointer input.
- [ ] **Step 2: Wire it.**
  - `CamActivity`: `val hero = rememberHeroState()`; wrap `navDisplay()` in `CompositionLocalProvider(LocalHero provides hero)` inside a `Box`, with `HeroOverlay(hero)` after it.
  - `GroupItem`: `heroSource(group.uid, HeroPart.Icon)` after `.size(48.dp)`; `heroSource(group.uid, HeroPart.Label)` before `basicMarquee()`.
  - `AppProfileMiuix`: `heroTarget(state.uid, HeroPart.Icon)` after `.size(64.dp)`; `heroTarget(appUid, HeroPart.Label)` before `basicMarquee()`.
  - `SuperUserScreen.onOpenProfile`: before `navigator.push`, `hero?.launch(group.uid, group.primary.packageInfo, if (group.apps.size > 1) ownerNameForUid(group.uid) else group.primary.label)`; `hero` is `LocalHero.current` read in the composable.
  - `AppProfileScreen`: `DisposableEffect(uid) { onDispose { hero?.end(uid) } }`.
- [ ] **Step 3: Build and unit tests.** `Push-Location K:\manager; & K:\manager\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 4: Device check, core paths.** Install the debug APK (`adb install -r`). Record with the scratchpad `record_tab.sh` pattern (`screenrecord`, frames every 50 ms via OpenCV) for each:
  - (a) open;
  - (b) back button;
  - (c) full edge swipe back;
  - (d) half swipe, release;
  - (e) back pressed about 150 ms after tapping open (mid-flight reversal);
  - (f) double tap on "Mở hồ sơ đầy đủ".

  Expected in frames:
  - one icon and one label between card and header, never two;
  - landing exactly on the header (a) and on the card (b, c);
  - (d) returns into the header;
  - (e) reverses without a jump;
  - (f) one flight.
- [ ] **Step 5: Commit.** `git add` `Hero.kt` and the five modified files; `git commit -m "manager: Fly the app icon from Superuser to App Profile"`.

### Task 4: Edge cases, jank, docs

**Files:**
- Modify: `docs/CAM_CHANGES.md` (new row 67 after row 66, same columns: what changed | why / device result)
- Modify: `docs/superpowers/specs/2026-10-16-superuser-hero-design.md` (status line under the date: implemented, commit ids)
- Possibly modify: Task 3 files, for fixes this task finds.

**Interfaces:** consumes Task 3's build; produces nothing new.

- [ ] **Step 1: Device edge cases** (record as in Task 3 Step 4), expected result in brackets:
  1. icon scrolled mostly off screen before tapping open [plain transition, no copy];
  2. grant root in App Profile, then back [copy lands on the card's new place, or fades if it left composition];
  3. App Profile opened from Kiểm tra ẩn root [plain transition];
  4. `settings put global animator_duration_scale 0`, open/back, then restore the previous value [no copy];
  5. a multi-app shared-uid group [owner name flies];
  6. bottom-bar search active, open a result [the searched card is the source];
  7. scroll App Profile down, then back [no copy];
  8. uninstall the profiled app from another shell while App Profile is open (a test APK, never the Manager) [page pops, nothing left floating];
  9. from App Profile open the template editor, then back to App Profile [header stays in place, no copy over either page].
- [ ] **Step 2: Fix what Step 1 shows**, each fix with a `HeroMathTest` case when the cause is a decision. Rerun `:app:testDebugUnitTest`.
- [ ] **Step 3: Jank after.** Repeat Task 1 Step 3 with this build. Janky frames % and p90 must not exceed the baseline by more than noise (about 1 point); report both runs.
- [ ] **Step 4: Docs.**
  - Row 67 in `docs/CAM_CHANGES.md`: files, behaviour, Step 1 results, jank before/after, and what was not tested (RTL layout).
  - Status line in the spec.
- [ ] **Step 5: Commit.** `git add docs/CAM_CHANGES.md docs/superpowers/specs/2026-10-16-superuser-hero-design.md` plus any fixed files; `git commit -m "docs: Record the icon flight device test"`. If Step 2 changed code, make that its own `manager:` commit first.
