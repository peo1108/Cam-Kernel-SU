package cam.su.kernel.ui.component.expand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import kotlin.math.abs

/** Share of the tapped element that must be on screen for a page to expand from it. */
internal const val EXPAND_MIN_VISIBLE = 0.9f

// float noise around the settled depths is not movement
internal const val EXPAND_EPSILON = 0.001f

/** How far the covered page sinks: its scale at full cover is 1 - this. */
internal const val COVER_SINK = 0.08f

/** The covered page's dim at full cover, as a share of miuix-nav's scrim (dimAmount 0.5 → 0.4). */
internal const val COVER_SCRIM = 0.8f

/** Spring stiffness of an expand (miuix's slide: 146), critically damped: ~0.6 s to 95%. */
internal const val EXPAND_STIFFNESS = 60f

// a page that composed this late into its push just finishes the push as it is
private const val OPEN_STRETCH_MAX = 0.9f

/** Share of the page a lost source shrinks to (centered) when there is nothing to fly back to. */
internal const val FALLBACK_SHARE = 0.4f

// the container is see-through glass at first (the tapped row shows under it) and turns into the page
private const val CONTAINER_FADE_END = 0.3f

// pieces start one after another from top to bottom; each takes PIECE_SPAN of the transition.
// The spring already front-loads t, so pieces ease in and out: they visibly travel, not just land.
private const val PIECE_FIRST = 0.08f
private const val PIECE_STAGGER = 0.4f
private const val PIECE_SPAN = 0.52f

// the bar comes in last
private const val BAR_START = 0.5f
private const val BAR_END = 0.9f

/**
 * Expand progress for the incoming page's `relativeDepth`: miuix-nav reports -1 while the page is
 * off screen and 0 when it is on top, so 0 = at the tapped element, 1 = full page.
 */
internal fun expandProgress(depth: Float): Float = (1f + depth).coerceIn(0f, 1f)

/** The progress an open is replayed from: where the push already was when the page composed. */
internal fun openStart(progressAtCompose: Float): Float =
    if (progressAtCompose < OPEN_STRETCH_MAX) progressAtCompose.coerceAtLeast(0f) else 0f

/** [raw] progress replayed from [from]: [from] plays as 0, 1 stays 1. */
internal fun openProgress(raw: Float, from: Float): Float =
    if (from <= 0f) raw.coerceIn(0f, 1f) else ((raw - from) / (1f - from)).coerceIn(0f, 1f)

/** How covered a page is (0 on top, 1 fully under the page above), from its `relativeDepth`. */
internal fun coverProgress(depth: Float): Float = depth.coerceIn(0f, 1f)

/** Whether an expanding page at [depth] needs drawing as moving (settled on top draws plain). */
internal fun expandMoving(depth: Float, running: Boolean): Boolean =
    depth <= EXPAND_EPSILON && (running || expandProgress(depth) < 1f - EXPAND_EPSILON)

/** The covered page's scale at [cover]. */
internal fun coveredScale(cover: Float): Float = 1f - COVER_SINK * cover

/** Whether a tap starts an expand at all. */
internal fun expandCanLaunch(animationsOff: Boolean, sourceVisible: Float): Boolean =
    !animationsOff && sourceVisible >= EXPAND_MIN_VISIBLE

/** Whether [current] is still the page size the source rect was measured on (a rotation is not). */
internal fun samePageSize(measured: Size, current: Size): Boolean =
    abs(measured.width - current.width) < 1f && abs(measured.height - current.height) < 1f

/** The centered rect a page shrinks to when its source is gone. */
internal fun fallbackRect(page: Size): Rect {
    val w = page.width * FALLBACK_SHARE
    val h = page.height * FALLBACK_SHARE
    return Rect(Offset((page.width - w) / 2f, (page.height - h) / 2f), Size(w, h))
}

/** [rect] as it appears on the covered page while that page is scaled by [scale] about its center. */
internal fun scaledAboutCenter(rect: Rect, page: Size, scale: Float): Rect {
    val cx = page.width / 2f
    val cy = page.height / 2f
    return Rect(
        left = cx + (rect.left - cx) * scale,
        top = cy + (rect.top - cy) * scale,
        right = cx + (rect.right - cx) * scale,
        bottom = cy + (rect.bottom - cy) * scale,
    )
}

/**
 * The visible window of the incoming page at [t]: from where [source] appears on the sinking
 * covered page to the whole [page].
 */
internal fun containerRect(source: Rect, page: Size, t: Float): Rect {
    val from = scaledAboutCenter(source, page, coveredScale(t))
    return lerp(from, Rect(Offset.Zero, page), t.coerceIn(0f, 1f))
}

/** Corner radius of the window at [t]. */
internal fun containerCorner(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)

/** Opacity of the incoming page at [t]. */
internal fun containerAlpha(t: Float): Float = segment(t, 0f, CONTAINER_FADE_END)

/** Opacity of the page's top bar at [t]. */
internal fun barAlpha(t: Float): Float = segment(t, BAR_START, BAR_END)

/**
 * Progress of one piece of the incoming page at [t]; [top] is where it sits, as a share of the
 * page height (0 = top), so pieces land one after another from top to bottom.
 */
internal fun pieceProgress(t: Float, top: Float): Float {
    val start = PIECE_FIRST + PIECE_STAGGER * top.coerceIn(0f, 1f)
    return easeInOutCubic(segment(t, start, start + PIECE_SPAN))
}

/** Offset that puts a piece centered at [piece] on [from] at progress 0 and in place at 1. */
internal fun pieceOffset(from: Offset, piece: Offset, progress: Float): Offset =
    (from - piece) * (1f - progress.coerceIn(0f, 1f))

internal fun segment(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

internal fun easeInOutCubic(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    if (c < 0.5f) return 4f * c * c * c
    val inv = -2f * c + 2f
    return 1f - inv * inv * inv / 2f
}
