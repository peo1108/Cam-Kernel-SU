# Page expand transition

2026-10-19. Cam found the page transitions too plain and picked, from a browser demo of three
styles, "C: nở sâu nhiều lớp" (deep multi-layer expand), with automatic pieces plus a few marked
ones.

## What the user sees

t is the incoming page's progress, `1 + relativeDepth` from miuix-nav (-1 off screen, 0 on top,
continuous with back swipes).

- **Window.** The page is clipped to a rounded rect that grows from the tapped element to the
  whole page; the corner goes from the element's corner to the screen corner. The page fades in
  over t 0 → 0.3, so the tapped element shows under it at first. Page content is never scaled
  (glass samples the page background at its own size).
- **Covered page.** Scales to 0.92 about its center and dims to 0.4 (miuix scrim 0.5 × 0.8). The
  window starts from where the element is drawn on that sinking page, so open and back follow
  the same path. No blur: a full-page `RenderEffect` blur (8 dp) doubled the janky frames on the
  TB323FU (2.8% → 6.0%, p99 36 → 48 ms; the plain slide is 2.5%).
- **Pieces.** Every `GlassCard` on the page flies from the element's center to its place, moving
  and fading only; a card starts later the lower it sits (start 0.08 + 0.4 × top share, span
  0.52, ease in-out cubic, because the spring already front-loads t). The top bar
  (`BlurredBar`) fades in over t 0.5 → 0.9.
- **Motion.** A critically damped spring of stiffness 60 (miuix's slide: 146), about 0.6 s to 95%,
  for pushes launched from a source and pops of expanded pages (`ExpandTransition.motion`).
  miuix-nav starts the push animation at the push, but a page takes ~100 ms to compose; the open
  replays from where the push was when the page composed (`ExpandFlight.openFrom`), so its start
  is not lost. Back is not stretched.
- **App Profile** keeps the existing icon and label hero flight on top of this.
- **Back** runs the same thing backwards and follows the finger.

Nothing to grow out of:
- page opened from a menu, dialog, shortcut, intent, or restored after process death: miuix's slide;
- element less than 90% on screen at tap time, or animations off: slide;
- at back, the element is not composed: the last known spot; the page size changed (rotation):
  shrink to a centered 40% rect and fade.

## Sources (scope A)

Settings rows (Account, Theme, Templates, Backup, About), Superuser app card (App Profile) and
su log button, Module library button, library cards (detail), Home status arena (Install),
Features hiding check card, App Profile hiding check card, template cards and the new template
button. Each marks the element with `Modifier.expandSource(key)` and wraps its click in
`expandClick(key) { … }` (launch, then the existing push). Keys are unique strings per source.

The About logo is not a piece: it samples the background (`textureBlur`) and tracks its own
window position for the scroll fade; moving it would break both. The library detail page has no
icon. So no marked pieces besides the App Profile hero.

## How it works

`ui/component/expand/`:
- `ExpandMath.kt`: pure functions (window rect, sink, fades, piece stagger, fallbacks); JVM tests
  in `ExpandMathTest`.
- `Expand.kt`:
  - `ExpandState`: sources by key (coordinates and their `GlassPageAnchor`, like the hero);
    `launch(key)` stores a pending expand; the next page's `GlassPage` claims it during its first
    composition when its route is on top and it is coming in (depth < 0), within 1 s. Flights are
    keyed by route (`NavKey`) and live while the route is on the back stack, because miuix-nav
    drops covered pages from composition and recreates them (with a new scope) when they come
    back. It also gives a page a new scope when its pop starts: the new scope attaches before the
    old one detaches, so a flight is dropped only when no scope of its route is left.
  - `ExpandTransition`: the `NavDisplay` transition. A covered page is drawn with the transition
    of the page above it (checked in miuix-nav 0.9.4 bytecode, `NavEntryHost`: own transition at
    depth <= 0, the next entry's above), so one transition draws both. Pages with no flight get
    `NavTransitions.MiuixDefault` unchanged; for pages it draws, MiuixDefault sees depth 0 (a
    delegating scope), so its slide and parallax are off.
  - `expandPiece()` (in `GlassCard`), `expandBar()` (in `BlurredBar`): a graphics layer only on
    pages that have a flight, read while drawing.
- `GlassPage(navKey)`: every `NavDisplay` entry passes its route.
- `cardEntrance` skips its own slide-in on a page that is expanding.

Everything is read in draw-phase lambdas; nothing recomposes per frame.

## Checks

- `ExpandMathTest` (15).
- Done on the TB323FU (release, Bunker background, screen recording): Settings → Theme and back;
  Settings → Templates → a template → back → back (the second back still shrinks into the row);
  Superuser → Open full profile (hero plus cascading cards); `dumpsys gfxinfo` over 8 Theme
  open/close cycles: slide 2.46%, final 2.51% janky frames.
- Not yet: slow back swipe and cancel, double tap, rotation while open, animations off, the other
  sources, image and gradient backgrounds, phones.
