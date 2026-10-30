package cam.su.kernel.ui.component.listdetail

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Stiffness of a pick's spring, critically damped like the page expand: ~0.47 s to 95%. */
internal const val PANE_STIFFNESS = 100f

// a card of the new detail starts this share of the way from its place toward the picked row
// (further, and the pane's edge visibly cuts the cards coming in from the list's side)
internal const val PIECE_REACH = 0.3f

// a card of the detail leaving drifts this share of the way toward its row while it fades
internal const val PIECE_LEAVE_REACH = 0.08f

// cards start one after another, top to bottom then left to right; each takes PIECE_SPAN, and
// the last (bottom, far column) lands just as the pick settles: the four add up to 1
private const val PIECE_FIRST = 0.04f
private const val PIECE_STAGGER_DOWN = 0.3f
private const val PIECE_STAGGER_ACROSS = 0.1f
private const val PIECE_SPAN = 0.56f

/** The copies of the icon and name land at this point of the pick, a little before the cards. */
internal const val FLIGHT_END = 0.8f

// the header's text shows while the icon and name come down into it
private const val REVEAL_START = 0.3f
private const val REVEAL_END = 0.72f

// a header card coming in from the overview fades in first
private const val HEADER_FADE_END = 0.25f

/**
 * Turning the tablet: how long the panes wait for the system's rotation (it plays over the window
 * for about 0.3 s) before they split or the open row opens in place.
 */
internal const val ROTATION_HOLD_MS = 280L

/** Stiffness of the split after a rotation: a bigger change, the page expand's pace (~0.6 s). */
internal const val SPLIT_STIFFNESS = 60f

// while the panes split: the list narrows over the first part, the divider draws down meanwhile
private const val LIST_NARROW_END = 0.6f
private const val DIVIDER_START = 0.05f
private const val DIVIDER_END = 0.55f

// how long the detail leaving takes to fade (to another detail, to or from the overview); to or
// from the overview the bar's title changes, so the new pane waits until the old one is mostly gone
private const val LEAVE_TO_DETAIL_MS = 120
private const val LEAVE_OVERVIEW_MS = 140
private const val ARRIVE_OVERVIEW_DELAY_MS = 90
private const val ARRIVE_OVERVIEW_MS = 200

internal fun segment(t: Float, start: Float, end: Float): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

internal fun easeInOutCubic(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    if (c < 0.5f) return 4f * c * c * c
    val inv = -2f * c + 2f
    return 1f - inv * inv * inv / 2f
}

/**
 * Progress of one card of the new detail at [t]: [top] and [left] are where it sits, as shares of
 * the pane (0 = top, start), so cards land one after another, down the pane and then across.
 */
internal fun panePieceProgress(t: Float, top: Float, left: Float): Float {
    val start = PIECE_FIRST + PIECE_STAGGER_DOWN * top.coerceIn(0f, 1f) + PIECE_STAGGER_ACROSS * left.coerceIn(0f, 1f)
    return easeInOutCubic(segment(t, start, start + PIECE_SPAN))
}

/** Offset of a card centered at [piece] coming out of the row centered at [row], at [progress]. */
internal fun panePieceOffset(row: Offset, piece: Offset, progress: Float): Offset =
    (row - piece) * (PIECE_REACH * (1f - progress.coerceIn(0f, 1f)))

/** Opacity of the header's text at [t]: it shows as the icon and name land. */
internal fun headerRevealAlpha(t: Float): Float = segment(t, REVEAL_START, REVEAL_END)

/**
 * One clock for everything a pick moves (the drop on the row, the icon and name flying into the
 * detail, the detail's cards coming out of the row), so they read as one motion and settle
 * together; critically damped like the page expand, never bouncing.
 */
@Stable
class PaneMotion internal constructor(
    private val scope: CoroutineScope,
    internal val animationsOff: Boolean,
) {
    /** 0 when a pick starts, 1 once everything has landed. */
    internal val clock = Animatable(1f)

    // the tablet just turned to landscape: everything waits at the start (the list still whole,
    // the open card still open in it) while the system's rotation plays over the window
    private var held by mutableStateOf(false)

    // the panes are splitting: the list narrows and the divider draws down on the clock
    internal var splitting by mutableStateOf(false)
        private set
    private var joining by mutableStateOf(false)
    private var splitJob: Job? = null
    private var splitToken: Any? = null

    /** The pick's progress this frame: 0 while the panes wait to split. */
    internal val t: Float get() = if (held) 0f else clock.value

    // the layout the split motion last started for; the frame the layout changes in composes
    // before that motion starts (applySplit runs as a side effect)
    private var appliedSplit: Boolean? = null

    /**
     * Whether the open row's card may show open in the list: always with one pane, except right
     * after turning to portrait (it opens once the rotation is over); with two panes only until
     * they split, so it closes as its detail comes out of it.
     */
    fun inlineOpen(split: Boolean): Boolean {
        val changing = appliedSplit != null && appliedSplit != split
        return if (split) held || changing else !(joining || changing)
    }

    /** [split] is the layout now: a change starts the split's or the join's motion. */
    internal fun applySplit(split: Boolean, parts: (Any) -> Map<FlightPart, @Composable () -> Unit>) {
        val before = appliedSplit
        appliedSplit = split
        if (before == null || before == split) return
        if (split) splitOpen(parts) else splitClose()
    }

    /** The row whose detail is shown; null for the overview. */
    var picked by mutableStateOf<Any?>(null)
        private set

    /** The row picked before: its detail is the one leaving. */
    internal var left: Any? = null
        private set

    private val rows = HashMap<Any, LayoutCoordinates>()

    // the picked row and the row left, in the window, as the pick started
    internal var pickedRow: Rect? = null
        private set
    internal var leftRow: Rect? = null
        private set

    internal val drop = SelectionDrop(this)
    internal val flight = DetailFlight(this)

    /**
     * Shows [key]'s detail (null: the overview). [parts] draw the row's icon and name as the
     * header has them; they fly from the row into it.
     */
    fun pick(key: Any?, parts: Map<FlightPart, @Composable () -> Unit> = emptyMap()) {
        if (key == picked) return
        if (splitting) {
            // the split shows whatever is picked when it starts moving
            left = null
            picked = key
            return
        }
        leftRow = picked?.let { windowRect(rows[it]) }
        pickedRow = key?.let { windowRect(rows[it]) }
        drop.retarget(rowCoordinates(key))
        val launched = flight.launch(key, parts)
        left = picked
        picked = key
        scope.launch {
            if (animationsOff) {
                clock.snapTo(1f)
            } else {
                clock.snapTo(0f)
                clock.animateTo(1f, spring(dampingRatio = 1f, stiffness = PANE_STIFFNESS))
            }
            flight.end(launched)
        }
    }

    /**
     * The panes are splitting (turned to landscape): hold everything at the start until the
     * rotation is over, then narrow the list, draw the divider down and bring the picked row's
     * detail out of it, all on one slower clock. [parts] gives a row's flying icon and name.
     */
    internal fun splitOpen(parts: (Any) -> Map<FlightPart, @Composable () -> Unit>) {
        splitJob?.cancel()
        joining = false
        if (animationsOff) return
        held = true
        splitting = true
        val mine = Any().also { splitToken = it }
        splitJob = scope.launch {
            try {
                clock.snapTo(0f)
                delay(ROTATION_HOLD_MS)
                // the rows have their landscape places by now
                withFrameNanos { }
                val key = picked
                left = null
                leftRow = null
                pickedRow = key?.let { windowRect(rows[it]) }
                drop.retarget(rowCoordinates(key), fromNothing = true)
                val launched = flight.launch(key, key?.let(parts).orEmpty())
                held = false
                clock.animateTo(1f, spring(dampingRatio = 1f, stiffness = SPLIT_STIFFNESS))
                flight.end(launched)
            } finally {
                // a newer turn of the tablet owns the flags now
                if (splitToken === mine) {
                    held = false
                    splitting = false
                }
            }
        }
    }

    /** The panes joined (turned to portrait): the open row opens in place once the rotation is over. */
    internal fun splitClose() {
        splitJob?.cancel()
        held = false
        splitting = false
        flight.clear()
        if (animationsOff) return
        joining = true
        val mine = Any().also { splitToken = it }
        splitJob = scope.launch {
            try {
                delay(ROTATION_HOLD_MS)
            } finally {
                if (splitToken === mine) joining = false
            }
        }
    }

    /** Width of the list this frame while the panes split: from the whole page to [target]. */
    internal fun listWidth(full: Int, target: Int): Int {
        if (!splitting) return target
        return lerp(full.toFloat(), target.toFloat(), easeInOutCubic(segment(t, 0f, LIST_NARROW_END))).roundToInt()
    }

    /** The divider's height share this frame: it draws down while the panes split. */
    internal fun dividerScale(): Float = if (splitting) easeInOutCubic(segment(t, DIVIDER_START, DIVIDER_END)) else 1f

    /** The detail pane's opacity: its bar comes in first while the panes split. */
    internal fun detailAlpha(): Float = if (splitting) segment(t, 0f, HEADER_FADE_END) else 1f

    internal fun rowCoordinates(key: Any?): LayoutCoordinates? = key?.let { rows[it] }

    internal fun placeRow(key: Any, coordinates: LayoutCoordinates) {
        rows[key] = coordinates
        if (key == picked) drop.follow(coordinates)
    }

    internal fun removeRow(key: Any, coordinates: LayoutCoordinates?) {
        if (rows[key] !== coordinates) return
        rows.remove(key)
        if (key == picked) drop.follow(null)
    }

    private fun windowRect(coordinates: LayoutCoordinates?): Rect? {
        if (coordinates == null || !coordinates.isAttached) return null
        return Rect(coordinates.positionInWindow(), coordinates.size.toSize())
    }

    /** Places a card of [key]'s detail this frame; [pane] is the detail's whole area. */
    internal fun drawPiece(layer: GraphicsLayerScope, key: Any?, pane: LayoutCoordinates?, piece: LayoutCoordinates?) {
        val t = t
        if (t >= 1f) return
        val area = pane?.takeIf { it.isAttached } ?: return
        val card = piece?.takeIf { it.isAttached } ?: return
        // a card in a dialog or popup is in another window: it does not move
        if (card.findRootCoordinates() !== area.findRootCoordinates()) return
        val size = area.size
        if (size.width <= 0 || size.height <= 0) return
        val rect = area.localBoundingBoxOf(card, clipBounds = false)
        if (key != picked) {
            // the detail leaving: it fades (AnimatedContent) and drifts back toward its row
            val row = leftRow?.takeIf { key == left } ?: return
            val drift = (area.windowToLocal(row.center) - rect.center) * (PIECE_LEAVE_REACH * easeInOutCubic(segment(t, 0f, 0.5f)))
            layer.translationX = drift.x
            layer.translationY = drift.y
            return
        }
        val p = panePieceProgress(t, rect.top / size.height, rect.left / size.width)
        val row = pickedRow
        if (key == null || row == null) {
            // the overview, or a pick whose row is not on screen: the cards rise into place
            layer.translationY = (1f - p) * with(layer) { 24.dp.toPx() }
        } else {
            val offset = panePieceOffset(area.windowToLocal(row.center), rect.center, p)
            layer.translationX = offset.x
            layer.translationY = offset.y
        }
        layer.alpha = p
    }

    /** The detail header's card: still between two details, fading in from the overview. */
    internal fun headerAlpha(key: Any?): Float {
        val t = t
        return when {
            key != picked -> if (picked != null) 0f else 1f
            left == null && t < 1f -> segment(t, 0f, HEADER_FADE_END)
            else -> 1f
        }
    }

    /** The header's text: hidden as its detail leaves for another, showing as the icon lands. */
    internal fun revealAlpha(key: Any?): Float {
        if (key != picked) return if (picked != null) 0f else 1f
        val t = t
        return if (t < 1f) headerRevealAlpha(t) else 1f
    }
}

@Composable
fun rememberPaneMotion(): PaneMotion {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember {
        PaneMotion(scope, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f)
    }
}

/**
 * Keeps [motion] on [selected] when the selection changes some other way than a tapped row (the
 * close button, back, an su log event opening a profile, the tablet turned).
 */
@Composable
fun PaneMotionFollows(motion: PaneMotion?, selected: Any?) {
    if (motion == null) return
    LaunchedEffect(motion, selected) { motion.pick(selected) }
}

private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null
}

/** Marks this list row as [key]'s: the drop finds it, the detail's cards come out of it. */
@Composable
fun Modifier.paneRow(motion: PaneMotion?, key: Any): Modifier {
    if (motion == null) return this
    val holder = remember { CoordinatesHolder() }
    DisposableEffect(motion, key) {
        onDispose { motion.removeRow(key, holder.coordinates) }
    }
    return this.onGloballyPositioned {
        holder.coordinates = it
        motion.placeRow(key, it)
    }
}

internal class PanePiece(val motion: PaneMotion, val key: Any?, val pane: () -> LayoutCoordinates?)

private val LocalPanePiece = staticCompositionLocalOf<PanePiece?> { null }
private val LocalPaneHeader = staticCompositionLocalOf<PanePiece?> { null }

/**
 * The detail beside a list, for [key] (null: the overview). A new pick fades the detail leaving
 * and builds the new one: every glass card in it ([panePiece]) comes out of the picked row one
 * after another, on [motion]'s clock.
 */
@Composable
fun <K> PaneDetail(motion: PaneMotion, key: K?, content: @Composable (K?) -> Unit) {
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            // a detail replacing a detail keeps its bar and header card in place; to or from the
            // overview the bar changes, so the new one fades in as the old one is nearly gone
            val structural = targetState == null || initialState == null
            val enter = if (structural) fadeIn(tween(ARRIVE_OVERVIEW_MS, delayMillis = ARRIVE_OVERVIEW_DELAY_MS)) else EnterTransition.None
            enter togetherWith fadeOut(tween(if (structural) LEAVE_OVERVIEW_MS else LEAVE_TO_DETAIL_MS, easing = LinearEasing))
        },
        label = "paneDetail",
    ) { shown ->
        val pane = remember { CoordinatesHolder() }
        val piece = remember(motion, shown) { PanePiece(motion, shown) { pane.coordinates } }
        Box(Modifier.fillMaxSize().onPlaced { pane.coordinates = it }) {
            CompositionLocalProvider(LocalPanePiece provides piece) { content(shown) }
        }
    }
}

/** A glass card (or any piece) of a detail: it comes out of the picked row with the others. */
@Composable
fun Modifier.panePiece(): Modifier {
    val piece = LocalPanePiece.current ?: return this
    val holder = remember { CoordinatesHolder() }
    return this
        .onPlaced { holder.coordinates = it }
        .graphicsLayer { piece.motion.drawPiece(this, piece.key, piece.pane(), holder.coordinates) }
}

/**
 * The detail's header slot: its card stays put while the icon and name fly into it, so it is not a
 * piece; [content] marks its text with [paneReveal].
 */
@Composable
internal fun PaneHeader(content: @Composable () -> Unit) {
    val piece = LocalPanePiece.current
    Box(
        Modifier.graphicsLayer {
            if (piece != null) alpha = piece.motion.headerAlpha(piece.key)
        }
    ) {
        CompositionLocalProvider(LocalPanePiece provides null, LocalPaneHeader provides piece, content = content)
    }
}

/** The header's text: shows as the icon and name land in the header. */
@Composable
fun Modifier.paneReveal(): Modifier {
    val piece = LocalPaneHeader.current ?: return this
    return this.graphicsLayer { alpha = piece.motion.revealAlpha(piece.key) }
}
