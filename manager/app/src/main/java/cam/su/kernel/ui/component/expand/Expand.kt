package cam.su.kernel.ui.component.expand

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import cam.su.kernel.ui.component.glass.GlassDefaults
import cam.su.kernel.ui.component.glass.GlassPageAnchor
import cam.su.kernel.ui.component.glass.LocalGlassPageAnchor
import cam.su.kernel.ui.component.hero.visibleFraction
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import java.util.IdentityHashMap
import kotlin.math.abs

// a push this long after the tap is not the tap's page
private const val CLAIM_WINDOW_MS = 1000L

// the covered page is one page under the page above it; relative depths differ by exactly 1
private const val DEPTH_MATCH = 0.02f

/** A tapped element a page can expand from, and the page it sits on. */
private class ExpandSource(
    val token: Any,
    val coordinates: LayoutCoordinates,
    val page: GlassPageAnchor?,
    val corner: Float,
) {
    val isAttached get() = coordinates.isAttached && page?.coordinates?.isAttached == true

    // where the element sits on its page once that page is at rest (pages rest at the origin)
    fun restingRect(): Rect? {
        val pageCoordinates = page?.coordinates?.takeIf { it.isAttached } ?: return null
        if (!coordinates.isAttached) return null
        // an element in a dialog or popup is in another window: nothing to measure against
        if (coordinates.findRootCoordinates() !== pageCoordinates.findRootCoordinates()) return null
        return pageCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
    }
}

private class PendingExpand(val sourceKey: Any, val corner: Float, val rect: Rect, val pageSize: Size, val at: Long)

/**
 * One page that grew out of a tapped element. It lives while the page's route is on the back
 * stack, so the page still shrinks back into the element after deeper pages come and go.
 */
internal class ExpandFlight(val sourceKey: Any, val corner: Float, var rect: Rect, val pageSize: Size) {
    /**
     * Where the push animation already was when the page first composed: miuix-nav starts it at
     * the push, and a page takes ~100 ms to compose, so the open would lose its first part. The
     * open plays from here as from 0; once the page settles it is 0 again (back is not stretched).
     */
    var openFrom = 0f

    /** [raw] progress of this page's transition, as the expand plays it. */
    fun progress(raw: Float): Float = openProgress(raw, openFrom)
}

/**
 * The page expand transition (docs/superpowers/specs/2026-10-19-page-expand-design.md): a page
 * opened from a tapped element grows out of it, its glass cards fly out of it one after another,
 * and the page under it sinks. Sources register their coordinates; pages register their
 * [NavTransitionScope] (the one miuix-nav hands to [ExpandTransition]); everything else is read
 * while drawing, so nothing recomposes during a transition.
 */
@Stable
class ExpandState(
    private val animationsOff: Boolean,
    private val isTop: (NavKey) -> Boolean,
    private val onBackStack: (NavKey) -> Boolean,
) {
    /** The screen's corner radius; the window ends with it. */
    var screenCorner: Dp = 0.dp

    private val sources = HashMap<Any, ExpandSource>()
    private val flights = HashMap<NavKey, ExpandFlight>()
    private val pages = IdentityHashMap<NavTransitionScope, NavKey>()
    private var pending: PendingExpand? = null

    /**
     * Makes the next pushed page grow out of [key]'s source; call it right before the push. False
     * (the page slides in as usual) when the source is mostly off screen or animations are off.
     */
    fun launch(key: Any): Boolean {
        pending = null
        val source = sources[key]?.takeIf { it.isAttached } ?: return false
        val full = Rect(source.coordinates.positionInWindow(), source.coordinates.size.toSize())
        if (!expandCanLaunch(animationsOff, visibleFraction(full, source.coordinates.boundsInWindow()))) return false
        val rect = source.restingRect() ?: return false
        val pageSize = source.page?.coordinates?.size?.toSize() ?: return false
        pending = PendingExpand(key, source.corner, rect, pageSize, SystemClock.uptimeMillis())
        return true
    }

    internal fun register(key: Any, token: Any, coordinates: LayoutCoordinates, page: GlassPageAnchor?, corner: Float) {
        val known = sources[key]
        if (known?.token === token && known.coordinates === coordinates && known.page === page) return
        sources[key] = ExpandSource(token, coordinates, page, corner)
    }

    internal fun unregister(key: Any, token: Any) {
        if (sources[key]?.token === token) sources.remove(key)
    }

    /**
     * Called in the first composition of every page. The page just pushed claims a fresh launch.
     * True when the page has a flight (it is expanding, or will shrink back on its way out).
     */
    internal fun attach(navKey: NavKey, scope: NavTransitionScope): Boolean {
        pages[scope] = navKey
        val launch = pending
        // runs in composition: reading the back stack must not make every page recompose on navigation
        val top = Snapshot.withoutReadObservation { isTop(navKey) }
        val depth = Snapshot.withoutReadObservation { scope.relativeDepth }
        // only a page coming in (a covered page recomposing on a pop is on top too, at depth 1)
        if (launch != null && SystemClock.uptimeMillis() - launch.at < CLAIM_WINDOW_MS && top && depth < 0f) {
            flights[navKey] = ExpandFlight(launch.sourceKey, launch.corner, launch.rect, launch.pageSize).apply {
                openFrom = openStart(expandProgress(depth))
            }
            pending = null
        }
        return flights.containsKey(navKey)
    }

    /**
     * A page left composition: covered pages come back, popped ones are done. miuix-nav also hands
     * a page a new scope when its pop starts; the new one attaches before the old one detaches,
     * and that page is still shrinking back.
     */
    internal fun detach(navKey: NavKey, scope: NavTransitionScope) {
        pages.remove(scope)
        // (IdentityHashMap compares values by reference; routes compare by value)
        if (!onBackStack(navKey) && pages.values.none { it == navKey }) flights.remove(navKey)
    }

    internal fun flight(navKey: NavKey): ExpandFlight? = flights[navKey]

    private fun flightOf(scope: NavTransitionScope): ExpandFlight? = pages[scope]?.let { flights[it] }

    // the flight of the page right above a covered page: miuix-nav draws a covered page with the
    // transition of the page above it, at a depth exactly one more than that page's
    internal fun flightAbove(scope: NavTransitionScope): ExpandFlight? {
        val depth = scope.relativeDepth
        for ((other, navKey) in pages) {
            if (other !== scope && abs(other.relativeDepth - (depth - 1f)) < DEPTH_MATCH) return flights[navKey]
        }
        return null
    }

    /**
     * Whether the transition about to start belongs to an expand: a tap just launched one, or the
     * page on top (about to leave) grew out of something. Read by miuix-nav when it picks the
     * motion, so it must not observe the per-frame depths.
     */
    internal fun drivesMotion(): Boolean = Snapshot.withoutReadObservation {
        val launch = pending
        if (launch != null && SystemClock.uptimeMillis() - launch.at < CLAIM_WINDOW_MS) return@withoutReadObservation true
        pages.any { (scope, navKey) -> flights.containsKey(navKey) && abs(scope.relativeDepth) < DEPTH_MATCH }
    }

    /** Whether [scope]'s page is drawn by the expand instead of miuix's slide this frame. */
    internal fun drives(scope: NavTransitionScope): Boolean =
        if (scope.relativeDepth <= EXPAND_EPSILON) flightOf(scope) != null else flightAbove(scope) != null

    /** Where the source sits on its page now; the remembered spot while it is not composed. */
    internal fun sourceRect(flight: ExpandFlight, page: Size): Rect {
        if (!samePageSize(flight.pageSize, page)) return fallbackRect(page)
        sources[flight.sourceKey]?.restingRect()?.let { flight.rect = it }
        return flight.rect
    }

    internal fun drawEntry(layer: GraphicsLayerScope, scope: NavTransitionScope) {
        val depth = scope.relativeDepth
        if (depth <= EXPAND_EPSILON) {
            val flight = flightOf(scope) ?: return
            if (!expandMoving(depth, scope.isRunning)) {
                flight.openFrom = 0f
                return
            }
            val t = flight.progress(expandProgress(depth))
            // the first frame draws before the layer knows its size
            val page = layer.size.takeIf { it.width > 0f && it.height > 0f } ?: flight.pageSize
            val rect = containerRect(sourceRect(flight, page), page, t)
            val corner = containerCorner(flight.corner, with(layer) { screenCorner.toPx() }, t)
            layer.shape = WindowShape(rect, corner)
            layer.clip = true
            layer.alpha = containerAlpha(t)
        } else {
            // sinks and dims (scrimFraction); no blur: a full-page RenderEffect blur doubled the
            // janky frames on the TB323FU (2.8% → 6.0%, p99 36 → 48 ms)
            val above = flightAbove(scope) ?: return
            val scale = coveredScale(above.progress(coverProgress(depth)))
            layer.scaleX = scale
            layer.scaleY = scale
        }
    }
}

/** The app's [ExpandState]; null outside [cam.su.kernel.ui.CamActivity]'s navigation. */
val LocalExpand: ProvidableCompositionLocal<ExpandState?> = staticCompositionLocalOf { null }

@Composable
fun rememberExpandState(isTop: (NavKey) -> Boolean, onBackStack: (NavKey) -> Boolean): ExpandState {
    val context = LocalContext.current
    return remember {
        ExpandState(
            animationsOff = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
            isTop = isTop,
            onBackStack = onBackStack,
        )
    }
}

/**
 * miuix's slide for every page that has nothing to grow out of; pages that do are clipped to a
 * window growing out of the tapped element, and the page under them sinks and dims.
 */
class ExpandTransition(private val state: ExpandState) : NavTransition {
    private val base = NavTransitions.MiuixDefault

    override val opaqueDepth: Float get() = base.opaqueDepth
    override val dismissDirection: NavSwipeDirection get() = base.dismissDirection

    // slower than miuix's slide (stiffness 146): the cards need time to be seen flying
    override val motion: NavMotion get() = if (state.drivesMotion()) EXPAND_MOTION else base.motion

    override fun scrimFraction(scope: NavTransitionScope): Float {
        val depth = scope.relativeDepth
        val above = if (depth > EXPAND_EPSILON) state.flightAbove(scope) else null
        return if (above != null) COVER_SCRIM * above.progress(coverProgress(depth)) else base.scrimFraction(scope)
    }

    private companion object {
        val EXPAND_SPRING = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = EXPAND_STIFFNESS, clampOvershoot = true)
        val EXPAND_MOTION = NavMotion(commit = EXPAND_SPRING, cancel = EXPAND_SPRING, programmatic = EXPAND_SPRING)
    }

    override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier =
        with(base) { transformEntry(SlideScope(scope, state)) }.graphicsLayer { state.drawEntry(this, scope) }
}

// miuix's slide sees a page at rest while the expand draws it
private class SlideScope(private val scope: NavTransitionScope, private val state: ExpandState) : NavTransitionScope by scope {
    override val relativeDepth: Float
        get() = if (state.drives(scope)) 0f else scope.relativeDepth
}

// the expanding page's visible window, in the page's own coordinates
private class WindowShape(private val rect: Rect, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}

/** A page whose route grew out of a tapped element; what its pieces need to fly. */
class ExpandPage internal constructor(
    private val state: ExpandState,
    private val navKey: NavKey,
    private val scope: NavTransitionScope,
    private val anchor: GlassPageAnchor,
) {
    /** Whether the page is growing or shrinking right now (read without observing). */
    fun isMovingNow(): Boolean = Snapshot.withoutReadObservation { progress() != null }

    // progress of the page's expand this frame; null when it is not moving
    private fun progress(): Float? {
        val flight = state.flight(navKey) ?: return null
        val depth = scope.relativeDepth
        if (!expandMoving(depth, scope.isRunning)) return null
        return flight.progress(expandProgress(depth))
    }

    internal fun drawPiece(layer: GraphicsLayerScope, coordinates: LayoutCoordinates?) {
        val flight = state.flight(navKey) ?: return
        val t = progress() ?: return
        val page = anchor.coordinates?.takeIf { it.isAttached } ?: return
        val piece = coordinates?.takeIf { it.isAttached } ?: return
        // a card in a dialog or popup is in another window: it does not fly
        if (piece.findRootCoordinates() !== page.findRootCoordinates()) return
        val pageSize = page.size.toSize()
        if (pageSize.height <= 0f) return
        val rect = page.localBoundingBoxOf(piece, clipBounds = false)
        val p = pieceProgress(t, rect.top / pageSize.height)
        val from = scaledAboutCenter(state.sourceRect(flight, pageSize), pageSize, coveredScale(t)).center
        val offset = pieceOffset(from, rect.center, p)
        layer.translationX = offset.x
        layer.translationY = offset.y
        layer.alpha = p
    }

    internal fun drawBar(layer: GraphicsLayerScope) {
        val t = progress() ?: return
        layer.alpha = barAlpha(t)
    }
}

/** The current page's [ExpandPage]; null on pages that did not grow out of anything. */
val LocalExpandPage: ProvidableCompositionLocal<ExpandPage?> = staticCompositionLocalOf { null }

/** For [cam.su.kernel.ui.component.glass.GlassPage]: registers the page, null unless it expands. */
@Composable
fun rememberExpandPage(navKey: NavKey?, scope: NavTransitionScope?, anchor: GlassPageAnchor): ExpandPage? {
    val state = LocalExpand.current ?: return null
    if (navKey == null || scope == null) return null
    val expanding = remember(state, navKey, scope) { state.attach(navKey, scope) }
    DisposableEffect(state, navKey, scope) {
        onDispose { state.detach(navKey, scope) }
    }
    return remember(state, navKey, scope, anchor, expanding) {
        if (expanding) ExpandPage(state, navKey, scope, anchor) else null
    }
}

/** Marks a tapped element pages can grow out of; [key] is unique across the app. */
@Composable
fun Modifier.expandSource(key: Any, corner: Dp = GlassDefaults.cardCorner): Modifier {
    val state = LocalExpand.current ?: return this
    val page = LocalGlassPageAnchor.current
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val token = remember(key) { Any() }
    DisposableEffect(state, key, token) {
        onDispose { state.unregister(key, token) }
    }
    return this.onGloballyPositioned { state.register(key, token, it, page, cornerPx) }
}

/** [onClick] that first makes the page it opens grow out of [key]'s [expandSource]. */
@Composable
fun expandClick(key: Any, onClick: () -> Unit): () -> Unit {
    val state = LocalExpand.current
    return remember(state, key, onClick) {
        {
            state?.launch(key)
            onClick()
        }
    }
}

private class PieceCoordinates {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Flies this element out of the tapped element while its page grows, one after another from top
 * to bottom. It only moves and fades: glass sampling the page background must keep its own size.
 */
@Composable
fun Modifier.expandPiece(): Modifier {
    val page = LocalExpandPage.current ?: return this
    val holder = remember { PieceCoordinates() }
    return this
        .onPlaced { holder.coordinates = it }
        .graphicsLayer { page.drawPiece(this, holder.coordinates) }
}

/** Fades a page's top bar in last while the page grows. */
@Composable
fun Modifier.expandBar(): Modifier {
    val page = LocalExpandPage.current ?: return this
    return this.graphicsLayer { page.drawBar(this) }
}
