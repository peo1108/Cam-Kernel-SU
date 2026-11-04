package cam.su.kernel.ui.component.hero

import androidx.compose.ui.geometry.Rect

/** Share of the source icon that must be on screen for a flight to start. */
internal const val HERO_MIN_VISIBLE = 0.9f

// float noise around the settled depths (0 on top, 1 one page down) is not movement
private const val EPSILON = 0.001f

/** What the overlay does this frame. */
internal enum class HeroPhase {
    /** The copy flies between the live source and target; both real elements hidden. */
    Flying,

    /** The page is settled on top, or covered by another page: only the real elements show. */
    Docked,

    /** No source to fly to (the card left composition): the copy fades with the target. */
    FadingOut,

    /** No target composed (not yet, or App Profile scrolled away): nothing drawn. */
    Gone,
}

/**
 * Flight progress for the App Profile entry's `relativeDepth`: miuix-nav reports -1 while the page
 * is off screen and 0 when it is on top (observed on the device, continuous with swipes), so
 * 0 = at the source card, 1 = at the header.
 */
internal fun heroProgress(depth: Float): Float = (1f + depth).coerceIn(0f, 1f)

/** The phase for one frame; [running] is the App Profile entry's `isRunning`. */
internal fun heroPhase(depth: Float, running: Boolean, sourceAttached: Boolean, targetAttached: Boolean): HeroPhase =
    when {
        !targetAttached -> HeroPhase.Gone
        depth > EPSILON -> HeroPhase.Docked
        heroProgress(depth) >= 1f - EPSILON && !running -> HeroPhase.Docked
        !sourceAttached -> HeroPhase.FadingOut
        else -> HeroPhase.Flying
    }

/** Whether the real icon/label in the Superuser card is hidden (the copy stands in for it). */
internal fun HeroPhase.hidesSource(): Boolean = this == HeroPhase.Flying

/** Whether the real icon/label in the App Profile header is hidden. */
internal fun HeroPhase.hidesTarget(): Boolean = this == HeroPhase.Flying || this == HeroPhase.FadingOut

/**
 * Share of [full] (an element's bounds) that [clipped] (the same bounds clipped by its ancestors,
 * `boundsInWindow`) still covers: 1 = fully on screen, 0 = hidden or empty.
 */
internal fun visibleFraction(full: Rect, clipped: Rect): Float {
    val area = full.width * full.height
    if (area <= 0f) return 0f
    val seen = clipped.intersect(full)
    if (seen.isEmpty) return 0f
    return seen.width * seen.height / area
}

/** Whether a tap on "Open full profile" starts a flight at all. */
internal fun heroCanLaunch(animationsOff: Boolean, sourceVisible: Float): Boolean =
    !animationsOff && sourceVisible >= HERO_MIN_VISIBLE
