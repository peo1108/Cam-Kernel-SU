package me.weishu.kernelsu.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import kotlin.math.max
import kotlin.math.roundToInt

/** Root coordinates of the current [GlassPage]; a plain field so page movement never recomposes. */
class GlassPageAnchor {
    var coordinates: LayoutCoordinates? = null
}

val LocalGlassPageAnchor = staticCompositionLocalOf<GlassPageAnchor?> { null }

/**
 * True inside bars: controls there sit over scrolling content and need a live backdrop sample.
 * Elsewhere glass only shows the static page background and uses the cheap [glassMaterial].
 */
val LocalGlassInBar = staticCompositionLocalOf { false }

/**
 * True while the current page is sliding in or out (navigation transition). Live backdrop surfaces
 * re-record every frame while their page moves, so bar controls fall back to [glassMaterial] then.
 */
val LocalGlassPageMoving = staticCompositionLocalOf { false }

/**
 * Glass for surfaces that sit on the page background (cards, in-card buttons, rail): draws the
 * matching region of the pre-blurred background ([GlassSource.Bitmap.material]) clipped to [shape],
 * then the dim, the [tint] and a specular rim. One image draw per frame and no offscreen layers, so
 * page transitions and list scrolling stay smooth. Plain and gradient backgrounds are smooth already,
 * so only the tint is drawn and the real background shows through.
 */
@Composable
fun Modifier.glassMaterial(shape: Shape, tint: Color, rim: Boolean = true): Modifier {
    val state = LocalGlassBackgroundState.current
    val anchor = LocalGlassPageAnchor.current
    val dark = isInDarkTheme()
    val bitmap = state.source as? GlassSource.Bitmap
    val dim = if (bitmap != null) effectiveDim(state.dim, bitmap.meanLuma, dark) else 0f
    val dimColor = (if (dark) Color.Black else Color.White).copy(alpha = dim)
    // Offset inside the page: unchanged while the whole page slides, so transitions do not redraw it.
    var offset by remember { mutableStateOf(Offset.Zero) }
    val track = if (bitmap != null && anchor != null) {
        Modifier.onGloballyPositioned { coordinates ->
            val page = anchor.coordinates?.takeIf { it.isAttached } ?: return@onGloballyPositioned
            val next = page.localPositionOf(coordinates, Offset.Zero)
            if (next != offset) offset = next
        }
    } else {
        Modifier
    }
    return this
        .then(track)
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val rimBrush = Brush.linearGradient(
                colors = if (dark) {
                    listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.16f))
                } else {
                    listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.55f))
                },
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val rimWidth = 1.dp.toPx()
            onDrawBehind {
                clipPath(path) {
                    if (bitmap != null) {
                        val material = bitmap.material
                        val page = anchor?.coordinates?.size ?: IntSize(size.width.roundToInt(), size.height.roundToInt())
                        // Same ContentScale.Crop + center alignment the page background uses.
                        val scale = max(page.width.toFloat() / material.width, page.height.toFloat() / material.height)
                        val drawnW = material.width * scale
                        val drawnH = material.height * scale
                        drawImage(
                            image = material,
                            dstOffset = IntOffset(
                                ((page.width - drawnW) / 2f - offset.x).roundToInt(),
                                ((page.height - drawnH) / 2f - offset.y).roundToInt(),
                            ),
                            dstSize = IntSize(drawnW.roundToInt(), drawnH.roundToInt()),
                            filterQuality = FilterQuality.Low,
                        )
                        if (dim > 0f) drawRect(dimColor)
                    }
                    drawRect(tint)
                }
                if (rim) drawPath(path, rimBrush, style = Stroke(rimWidth))
            }
        }
}
