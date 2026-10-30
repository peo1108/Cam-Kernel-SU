package cam.su.kernel.ui.component.listdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/** What flies from a picked row into its detail: the app icon and the name. */
enum class FlightPart { Icon, Label }

private class FlightCopy(val part: FlightPart, val from: Rect, val content: @Composable () -> Unit)

private class Flight(val key: Any, val copies: List<FlightCopy>)

private class Holder {
    var coordinates: LayoutCoordinates? = null
}

/** A copy's offset from its landing spot and its scale (about its top start corner). */
internal class FlightFrame(val translation: Offset, val scale: Float)

/**
 * Where a copy laid out at [to] is drawn at [progress]: over [from] at 0, on [to] at 1. One scale,
 * from the heights: a name may show clipped narrower in its row than in the header.
 */
internal fun flightFrame(from: Rect, to: Rect, progress: Float): FlightFrame {
    val rest = 1f - progress
    return FlightFrame(
        translation = Offset((from.left - to.left) * rest, (from.top - to.top) * rest),
        scale = lerp(from.height / to.height, 1f, progress),
    )
}

/**
 * A picked row's icon and name fly into the header of the detail opening beside it, on the pick's
 * clock ([PaneMotion]), landing a little before its cards. Rows mark their parts with
 * [flightSource]; the header marks where they land with [flightTarget] and hides them until they
 * have. The copies are drawn by [Overlay] over both panes: the detail's cards clip.
 */
internal class DetailFlight(private val motion: PaneMotion) {
    private val sources = HashMap<Pair<Any, FlightPart>, LayoutCoordinates>()
    private val targets = HashMap<FlightPart, LayoutCoordinates>()
    private var flight by mutableStateOf<Flight?>(null)

    // bumped when a landing spot attaches, so the overlay places its copies again
    private var version by mutableIntStateOf(0)

    private fun progress(): Float = segment(motion.t, 0f, FLIGHT_END)

    /**
     * [key]'s parts take off; those whose row shows less than most of them stay put. Returns the
     * flight, for [end].
     */
    fun launch(key: Any?, parts: Map<FlightPart, @Composable () -> Unit>): Any? {
        targets.clear()
        val copies = if (key == null || motion.animationsOff) emptyList() else parts.mapNotNull { (part, content) ->
            val source = sources[key to part]?.takeIf { it.isAttached } ?: return@mapNotNull null
            val full = Rect(source.positionInWindow(), source.size.toSize())
            val shown = source.boundsInWindow()
            if (shown.width * shown.height < full.width * full.height * 0.6f) return@mapNotNull null
            FlightCopy(part, full, content)
        }
        return (if (key != null && copies.isNotEmpty()) Flight(key, copies) else null).also { flight = it }
    }

    /** [launched]'s pick settled (or no landing spot ever showed): its parts are home. */
    fun end(launched: Any?) {
        if (launched != null && flight === launched) flight = null
    }

    /** Whatever is flying lands at once (the panes joined). */
    fun clear() {
        flight = null
    }

    fun hides(key: Any, part: FlightPart): Boolean {
        val current = flight ?: return false
        return current.key == key && current.copies.any { it.part == part } && progress() < 1f
    }

    fun registerSource(key: Any, part: FlightPart, coordinates: LayoutCoordinates) {
        sources[key to part] = coordinates
    }

    fun unregisterSource(key: Any, part: FlightPart, coordinates: LayoutCoordinates?) {
        if (sources[key to part] === coordinates) sources.remove(key to part)
    }

    fun land(key: Any, part: FlightPart, coordinates: LayoutCoordinates) {
        if (flight?.key != key || targets[part] === coordinates) return
        targets[part] = coordinates
        version++
    }

    fun leave(key: Any, part: FlightPart, coordinates: LayoutCoordinates?) {
        if (flight?.key == key && targets[part] === coordinates) targets.remove(part)
    }

    @Composable
    fun Overlay() {
        val current = flight ?: return
        val anchor = remember { Holder() }
        Box(Modifier.fillMaxSize().onPlaced { anchor.coordinates = it }) {
            current.copies.forEach { copy ->
                key(copy.part) {
                    Box(
                        Modifier
                            .layout { measurable, _ ->
                                version
                                val to = landingRect(copy.part, anchor.coordinates)
                                    ?: return@layout layout(0, 0) {}
                                val placeable = measurable.measure(
                                    Constraints.fixed(to.width.roundToInt().coerceAtLeast(1), to.height.roundToInt().coerceAtLeast(1))
                                )
                                layout(0, 0) { placeable.place(to.left.roundToInt(), to.top.roundToInt()) }
                            }
                            .graphicsLayer {
                                val host = anchor.coordinates?.takeIf { it.isAttached } ?: return@graphicsLayer
                                val to = landingRect(copy.part, host) ?: return@graphicsLayer
                                val p = progress()
                                if (p >= 1f) {
                                    alpha = 0f
                                    return@graphicsLayer
                                }
                                val frame = flightFrame(copy.from.translate(host.windowToLocal(Offset.Zero)), to, p)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = frame.translation.x
                                translationY = frame.translation.y
                                scaleX = frame.scale
                                scaleY = frame.scale
                            }
                    ) { copy.content() }
                }
            }
        }
    }

    private fun landingRect(part: FlightPart, host: LayoutCoordinates?): Rect? {
        val anchor = host?.takeIf { it.isAttached } ?: return null
        val target = targets[part]?.takeIf { it.isAttached } ?: return null
        return anchor.localBoundingBoxOf(target, clipBounds = false).takeIf { it.width > 0f && it.height > 0f }
    }
}

/** Marks a row's icon or name as where [key]'s flight takes off. */
@Composable
fun Modifier.flightSource(motion: PaneMotion?, key: Any, part: FlightPart): Modifier {
    if (motion == null) return this
    val flight = motion.flight
    val holder = remember { Holder() }
    DisposableEffect(flight, key, part) {
        onDispose { flight.unregisterSource(key, part, holder.coordinates) }
    }
    return this.onGloballyPositioned {
        holder.coordinates = it
        flight.registerSource(key, part, it)
    }
}

/** Marks the detail header's icon or name as where [key]'s flight lands; hidden until it has. */
@Composable
fun Modifier.flightTarget(motion: PaneMotion?, key: Any, part: FlightPart): Modifier {
    if (motion == null) return this
    val flight = motion.flight
    val holder = remember { Holder() }
    DisposableEffect(flight, key, part) {
        onDispose { flight.leave(key, part, holder.coordinates) }
    }
    return this
        .onGloballyPositioned {
            holder.coordinates = it
            flight.land(key, part, it)
        }
        .graphicsLayer { alpha = if (flight.hides(key, part)) 0f else 1f }
}
