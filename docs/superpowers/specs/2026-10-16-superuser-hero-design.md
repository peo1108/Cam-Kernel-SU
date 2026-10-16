# Superuser → App Profile icon flight

Date: 2026-10-16
Status: implemented 2026-10-16 (plan `docs/superpowers/plans/2026-10-16-superuser-hero.md`; device results in row 67 of `docs/CAM_CHANGES.md`). One change from section 2: the flight runs between resting places (rect relative to the element's `GlassPage`), not live endpoints, which bent the path toward the incoming page.
Scope: `manager/` UI only (`ui/component/hero/`, Superuser list, App Profile header, `CamActivity`).
No change to miuix-nav, navigation routes, kernel, camd or supercalls.
Decided with Cam: keep today's tap behaviour; overlay approach (option 1 of 3).

## 1. Behaviour

**Open.** In an expanded Superuser card (inline profile), tapping "Open full profile"
(`R.string.app_profile_open_full`) makes the card's 48 dp icon grow to 64 dp and fly to the App
Profile header icon; the app label flies along to the header label. The flight follows the page
slide exactly (same timing, same easing) and lands when the page settles. While flying, the real
icon and label in both the card and the header are hidden, so the app never shows twice.

**Back** (back button, edge swipe, system back gesture): icon and label fly back to the card and
follow the finger. Releasing a half swipe (cancel) slides them back into the header.

**No flight when:**

| Situation | Instead |
|---|---|
| At tap time less than 90% of the card icon is visible (scrolled away) | plain page transition |
| On the way back the card is gone (list changed, scrolled out of composition) | icon and label fade out in place |
| Animator duration scale is 0 | plain page transition |
| App Profile opened from anywhere else (hiding check, shortcuts, intents) | plain page transition |
| Another page is pushed on top of App Profile (template editor, ...) | nothing flies; header stays in place |

## 2. Architecture

New package `manager/app/src/main/java/cam/su/kernel/ui/component/hero/`:

**`HeroMath.kt`** (plain Kotlin, JVM-tested):
- `heroProgress(depth: Float): Float` — App Profile entry's `relativeDepth` to t = (1 + depth)
  clamped to 0..1. miuix-nav reports -1 while the incoming page is off screen and 0 when it is
  on top; it moves continuously with gestures.
- `heroPhase(depth, running, sourceAttached, targetAttached)` → `Flying`, `Docked` (t = 1 and not
  running, or depth > 0: page covered by another), `FadingOut` (no source on the way back), `Gone`
  (no target).
- `lerpRect(a, b, t)` for position and size; alpha for `FadingOut` is t.
- `visibleFraction(full: Rect, clipped: Rect): Float` for the 90% rule.
- Which real elements are hidden for a phase (source and target hidden while `Flying` or
  `FadingOut`; nothing hidden when `Docked`).

**`Hero.kt`** (Compose):
- `HeroState`, provided by `LocalHero` from `CamActivity`. At most one flight: key (uid), the
  `PackageInfo` and label to draw, and the `LayoutCoordinates` of source icon, source label,
  target icon, target label, plus the target entry's `NavTransitionScope`.
- `Modifier.heroSource(key, part)` on the `GroupItem` icon and label: records coordinates
  (`onGloballyPositioned`), forgets them on dispose, sets its own alpha to 0 when the state says
  so (read in `graphicsLayer`, draw phase).
- `Modifier.heroTarget(key, part)` on the App Profile header icon and label: same, also records
  `LocalNavTransitionScope.current`; when the App Profile entry leaves composition the flight ends.
- `HeroState.launch(key, packageInfo, label)`: called by `onOpenProfile` in `SuperUserScreen.kt`
  just before `navigator.push(Route.AppProfile(uid))`. Refuses (no flight) when the source icon is
  under the 90% rule or animations are off.
- `HeroOverlay()`: placed over `navDisplay()` in `CamActivity` (inside the same `Scaffold`). Draws
  one `AppIconImage` (64 dp base, bitmap from `AppIconCache`, scaled down to the source size) and
  one `Text` in the card/header label style (550 weight, one line, ellipsis). Takes no input.

**Data flow per frame.** The overlay reads depth (`relativeDepth` is backed by miuix-nav's
`Animatable`, so reads invalidate) and both coordinate sets in the draw phase
(`graphicsLayer { translationX/Y, scaleX/Y, alpha }`), converting with
`overlayCoordinates.localPositionOf(...)` so page slides and parallax (graphics layers) are
included. Endpoints are read live every frame, so the flight lands exactly on a moving target and
follows a card that moved. No recomposition during a flight; only the label width is a
layout-phase read.

**Edits to existing files:**
- `ui/screen/superuser/SuperUserMiuix.kt`: `heroSource` on `GroupItem`'s icon and label.
- `ui/screen/appprofile/AppProfileMiuix.kt`: `heroTarget` on `AppProfileInner`'s icon and label.
- `ui/screen/superuser/SuperUserScreen.kt`: `hero.launch(...)` in `onOpenProfile`.
- `ui/CamActivity.kt`: provide `LocalHero`, add `HeroOverlay()` over `navDisplay()`.

## 3. Risks

1. miuix-nav may drop the covered Main page from composition; the source coordinates would then
   be missing on the way back (fade instead of flight). First implementation step: check on the
   device, together with the depth range (read from the miuix-nav bytecode, `relativeDepth` =
   animated top index minus entry index). **Observed on the TB323FU (2026-10-16, probe logging):**
   App Profile depth runs -1 → 0 on open and 0 → -1 on back, continuously with an edge swipe;
   the Superuser page stays composed while covered (its depth 0 → 1), so the source stays
   registered. A debug build composes App Profile about 350 ms late, so the slide is nearly over
   before the page's first frame: check the flight on a release build. Jank baseline (release,
   compiled, 10 × open full profile + back): 4.22% janky frames, p90 20 ms, p99 34 ms.
2. Editing the profile can re-sort the list on return (an app granted root moves to the top). Live
   coordinates make the flight follow the card; a card that left composition fades out.
3. Detached coordinates (`isAttached == false`) count as missing.
4. Rotation or resize mid-flight loses the flight state; pages show normally, nothing is left
   floating.
5. Cost: one icon and one text per frame during transitions. Check `dumpsys gfxinfo` over ten
   open/close cycles before and after; jank must not rise.

## 4. Testing

- JVM, test first: `HeroMathTest` — depth to t (including depth > 0 and < -1), rect
  interpolation, phase decisions, the 90% rule, hidden elements per phase.
- On the TB323FU, screen recording split into frames (as for the skeleton work): open; back
  button; full swipe back; half swipe then cancel; icon scrolled away (no flight); grant root
  then back (card moved); App Profile from the hiding check (no flight); animations off; a
  multi-app group (owner name label).
- `./gradlew :app:testDebugUnitTest`, all pass.
