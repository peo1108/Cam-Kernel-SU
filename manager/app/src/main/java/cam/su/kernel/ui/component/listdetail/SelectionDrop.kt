package cam.su.kernel.ui.component.listdetail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.component.glass.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

// the drop fades in (first pick) and out (back to the overview) over this share of a pick
private const val DROP_FADE_END = 0.3f

/**
 * The tinted outline on the picked row of a list beside its detail. On [PaneMotion]'s clock it
 * glides from the row picked before to the new one (riding along if the list scrolls meanwhile);
 * the first pick fades it in on its row, going back to the overview fades it out.
 */
internal class SelectionDrop(private val motion: PaneMotion) {
    var host: LayoutCoordinates? = null

    // the picked row in the overlay's space, kept up to date as the row moves; read while drawing
    var target by mutableStateOf<Rect?>(null)
        private set

    // where the drop starts, relative to the picked row; it closes to nothing as the drop lands
    var fromDelta = Rect.Zero
        private set
    var fromNothing = true
        private set

    // the row left for the overview, where the drop fades out
    var leaving: Rect? = null
        private set

    fun follow(coordinates: LayoutCoordinates?) {
        target = rectOf(coordinates)
    }

    /**
     * A pick is starting: the drop leaves the row it is on for [next]'s row (or for nothing);
     * [fromNothing] fades it in on [next]'s row instead (the panes just split).
     */
    fun retarget(next: LayoutCoordinates?, fromNothing: Boolean = false) {
        // from where it is drawn now: a pick in the middle of a glide carries on from there
        val before = if (fromNothing) null else frame()?.takeIf { it.second > 0f }?.first
        val after = rectOf(next)
        target = after
        leaving = if (after == null) before else null
        this.fromNothing = before == null
        fromDelta = if (before != null && after != null) {
            Rect(before.left - after.left, before.top - after.top, before.right - after.right, before.bottom - after.bottom)
        } else {
            Rect.Zero
        }
    }

    private fun rectOf(coordinates: LayoutCoordinates?): Rect? {
        val host = host?.takeIf { it.isAttached } ?: return null
        if (coordinates == null || !coordinates.isAttached) return null
        return host.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    /** Where the drop is drawn this frame and how opaque; null for nothing. */
    fun frame(): Pair<Rect, Float>? {
        val t = motion.t
        val rect = target
        if (rect != null) {
            if (fromNothing) return rect to segment(t, 0f, DROP_FADE_END)
            return glidingRect(rect, fromDelta, t) to 1f
        }
        val gone = leaving ?: return null
        val alpha = 1f - segment(t, 0f, DROP_FADE_END)
        return if (alpha > 0f) gone to alpha else null
    }
}

/**
 * Where the drop is drawn: at the old row ([target] moved by [fromDelta]) when [progress] is 0, on
 * [target] at 1. The delta is kept relative to the picked row, so a scroll mid-glide moves both.
 */
internal fun glidingRect(target: Rect, fromDelta: Rect, progress: Float): Rect {
    val rest = 1f - progress
    return Rect(
        target.left + fromDelta.left * rest,
        target.top + fromDelta.top * rest,
        target.right + fromDelta.right * rest,
        target.bottom + fromDelta.bottom * rest,
    )
}

/**
 * Draws the drop of [motion] over the list's rows; give it the list's whole area (matchParentSize
 * in the list's Box). It has its own layer, so the drop moving redraws nothing else.
 */
@Composable
fun PaneDropOverlay(motion: PaneMotion?, modifier: Modifier = Modifier) {
    if (motion == null) return
    val drop = motion.drop
    val color = colorScheme.primary
    Spacer(
        modifier
            .onPlaced { drop.host = it }
            .graphicsLayer()
            .drawBehind {
                val (r, a) = drop.frame() ?: return@drawBehind
                if (a <= 0f) return@drawBehind
                val corner = CornerRadius(GlassDefaults.cardCorner.toPx())
                drawRoundRect(color.copy(alpha = 0.14f * a), r.topLeft, r.size, corner)
                drawRoundRect(color.copy(alpha = 0.7f * a), r.topLeft, r.size, corner, style = Stroke(1.5.dp.toPx()))
            }
    )
}
