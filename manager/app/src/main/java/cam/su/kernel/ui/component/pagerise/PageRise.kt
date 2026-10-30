package cam.su.kernel.ui.component.pagerise

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.component.listdetail.segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.exp

// the page left fades and its cards sink this long, then the pager jumps
private const val PAGE_LEAVE_MS = 90

// the new page's rise, from the first card moving to the last one landing
private const val PAGE_RISE_MS = 640

// the new page itself (its bar, what is not a card) shows over this first part of the rise
private const val PAGE_FADE_END = 0.2f

// cards start one after another, down the page and then across, and each takes what is left, so
// a long list takes no longer than a short one
private const val RISE_STAGGER_DOWN = 0.28f
private const val RISE_STAGGER_ACROSS = 0.06f
private const val RISE_SPAN = 1f - RISE_STAGGER_DOWN - RISE_STAGGER_ACROSS

// a card shows over this first part of its own rise
private const val RISE_ALPHA_END = 0.35f

// how far below its place a card starts: cards lower down come from further, so the gaps between
// them close as they land and the list reads as drawn up in one piece
private const val RISE_NEAR_DP = 56f
private const val RISE_FAR_DP = 120f

private const val SINK_DP = 20f

// a critically damped spring's path: no jump at the start, a long soft landing
private const val RISE_OMEGA = 7f
private val RISE_PATH_END = 1f - (1f + RISE_OMEGA) * exp(-RISE_OMEGA)

/** A card's own time at [t]: [top] and [left] are where it sits, as shares of the page. */
internal fun riseTime(t: Float, top: Float, left: Float): Float {
    val start = RISE_STAGGER_DOWN * top.coerceIn(0f, 1f) + RISE_STAGGER_ACROSS * left.coerceIn(0f, 1f)
    return segment(t, start, start + RISE_SPAN)
}

/** How far along its way a card is at its own [time]. */
internal fun riseProgress(time: Float): Float {
    val x = RISE_OMEGA * time.coerceIn(0f, 1f)
    return (1f - (1f + x) * exp(-x)) / RISE_PATH_END
}

/** The card's opacity at its own [time]. */
internal fun riseAlpha(time: Float): Float = segment(time, 0f, RISE_ALPHA_END)

/** How far below its place a card at [top] starts, in dp. */
internal fun riseDistanceDp(top: Float): Float = RISE_NEAR_DP + (RISE_FAR_DP - RISE_NEAR_DP) * top.coerceIn(0f, 1f)

internal class RiseCard {
    var coordinates: LayoutCoordinates? = null
    var play = -1

    // false until it had a place to measure: this frame it is hidden, the next it starts its rise
    var placed = false
    var top = 0f
    var left = 0f
    var moves = false
}

/** One main page's part of a switch: its state is read only in layers, so only its own cards redraw. */
@Stable
class PageRise internal constructor() {
    // 0 when the rise starts, 1 at rest
    internal var t by mutableFloatStateOf(1f)

    // 0 at rest, 1 once the page is gone
    internal var sink by mutableFloatStateOf(0f)

    // a new play makes the cards find their place on the page again
    internal var play = 0
    internal var area: LayoutCoordinates? = null
    internal var job: Job? = null

    internal fun pageAlpha(): Float = (1f - sink) * segment(t, 0f, PAGE_FADE_END)

    internal fun drawCard(layer: GraphicsLayerScope, card: RiseCard) {
        val t = t
        val sink = sink
        if (t >= 1f && sink <= 0f) return
        if (card.play != play || !card.placed) {
            card.play = play
            place(card)
        }
        if (!card.placed) {
            layer.alpha = 0f
            return
        }
        // a card in a dialog or popup is in another window: it does not move
        if (!card.moves) return
        val time = riseTime(t, card.top, card.left)
        val rise = (1f - riseProgress(time)) * riseDistanceDp(card.top)
        with(layer) { translationY = (rise + sink * SINK_DP).dp.toPx() }
        layer.alpha = riseAlpha(time)
    }

    // once per play: a card's offset would feed back into a place measured every frame
    private fun place(card: RiseCard) {
        val page = area?.takeIf { it.isAttached }
        val coordinates = card.coordinates?.takeIf { it.isAttached }
        card.moves = false
        card.placed = page != null && coordinates != null
        if (page == null || coordinates == null) return
        if (coordinates.findRootCoordinates() !== page.findRootCoordinates()) return
        val size = page.size
        if (size.width <= 0 || size.height <= 0) return
        val rect = page.localBoundingBoxOf(coordinates, clipBounds = false)
        card.top = rect.top / size.height
        card.left = rect.left / size.width
        card.moves = true
    }
}

/**
 * Tapping a tab: the page left fades while its cards sink, the pager jumps, and the new page's
 * cards ([pageRiseCard]) rise into place one after another. A swipe drags the pages along with the
 * finger as before: there the new page is already in sight.
 */
@Stable
class PageSwitch internal constructor(
    pageCount: Int,
    private val scope: CoroutineScope,
    private val animationsOff: Boolean,
) {
    private val pages = List(pageCount) { PageRise() }

    // hidden by [prepare], until its page has been jumped to
    private var hidden: PageRise? = null

    fun page(index: Int): PageRise = pages[index]

    /** Before the frame of the tap: [to] must not show its cards at rest for a frame. */
    fun prepare(from: Int, to: Int) {
        // a tap this one replaces may never have started: its page would stay hidden
        hidden?.t = 1f
        hidden = null
        if (animationsOff || from == to) return
        val arriving = pages[to]
        hidden = arriving
        arriving.job?.cancel()
        arriving.play++
        arriving.t = 0f
    }

    /** [jump] moves the pager from [from] to [to]; [prepare] came first. */
    suspend fun switch(from: Int, to: Int, jump: suspend () -> Unit) {
        val leaving = pages[from]
        if (animationsOff || from == to) {
            leaving.sink = 0f
            jump()
            return
        }
        val arriving = pages[to]
        var jumped = false
        try {
            animate(leaving.sink, 1f, animationSpec = tween(PAGE_LEAVE_MS, easing = LinearEasing)) { value, _ ->
                leaving.sink = value
            }
            jump()
            jumped = true
            hidden = null
            leaving.sink = 0f
            arriving.sink = 0f
            arriving.job = scope.launch {
                // the clock starts once the new page has drawn its first frame
                withFrameNanos { }
                animate(0f, 1f, animationSpec = tween(PAGE_RISE_MS, easing = LinearEasing)) { value, _ ->
                    arriving.t = value
                }
            }
        } finally {
            // cancelled before the jump. If it was by a finger on the pager and not by another tap
            // (that one takes over the leaving page's fade), this page is still the one in sight.
            if (!jumped && hidden === arriving) {
                hidden = null
                arriving.t = 1f
                leaving.sink = 0f
            }
        }
    }
}

@Composable
fun rememberPageSwitch(pageCount: Int): PageSwitch {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember {
        PageSwitch(
            pageCount,
            scope,
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
        )
    }
}

private val LocalPageRise = staticCompositionLocalOf<PageRise?> { null }

/** A main page in the pager: it fades as a whole, and its cards move on [rise]. */
@Composable
fun PageRiseBox(rise: PageRise, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { rise.area = it }
            .graphicsLayer { alpha = rise.pageAlpha() }
    ) {
        CompositionLocalProvider(LocalPageRise provides rise, content = content)
    }
}

/** A card of a main page: it rises with the others when a tab is tapped (moves only: glass must not scale). */
@Composable
fun Modifier.pageRiseCard(): Modifier {
    val rise = LocalPageRise.current ?: return this
    val card = remember { RiseCard() }
    return this
        .onPlaced { card.coordinates = it }
        .graphicsLayer { rise.drawCard(this, card) }
}
