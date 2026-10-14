package cam.su.kernel.ui.component.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import kotlin.math.abs

/**
 * iOS 26 slider: a thin track filled with the accent up to a capsule thumb that becomes a glass lens while dragged. [keyPoints] are drawn
 * as dots; within [magnetThreshold] of one the value snaps to it with a haptic tick. Tap the track
 * to jump; vertical drags still scroll the page.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    keyPoints: List<Float> = emptyList(),
    magnetThreshold: Float = 0f,
    enabled: Boolean = true,
) {
    val dark = isInDarkTheme()
    val track = if (dark) Color(0x52787880) else Color(0x29787880)
    val fill = colorScheme.primary
    val haptic = LocalHapticFeedback.current
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    val lens by animateFloatAsState(if (dragging) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "lens")
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val lastSnap = remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .pointerInput(enabled, valueRange, keyPoints, magnetThreshold) {
                if (!enabled) return@pointerInput
                val radius = 19.dp.toPx()
                fun set(x: Float) {
                    val f = ((x - radius) / (size.width - 2 * radius)).coerceIn(0f, 1f)
                    var v = valueRange.start + f * span
                    val near = keyPoints.minByOrNull { abs(it - v) }
                    if (near != null && abs(near - v) <= magnetThreshold) v = near
                    val snapped = near?.takeIf { it == v }
                    if (snapped != null && snapped != lastSnap.value) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastSnap.value = snapped
                    onChange(v)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag != null) {
                        dragging = true
                        set(drag.position.x)
                        horizontalDrag(drag.id) {
                            set(it.position.x)
                            it.consume()
                        }
                        dragging = false
                        onFinished?.invoke()
                    } else {
                        // lifted without sliding: a tap on the track jumps there (a vertical scroll consumed it)
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) {
                            set(down.position.x)
                            onFinished?.invoke()
                        }
                    }
                }
            },
    ) {
        val radius = 19.dp.toPx()
        val trackH = 6.dp.toPx()
        val cy = size.height / 2
        val left = radius
        val right = size.width - radius
        val x = left + (right - left) * fraction
        val drawRail: DrawScope.() -> Unit = {
            drawTrack(track, left, right, cy, trackH)
            drawTrack(fill, left, x, cy, trackH)
            keyPoints.forEach { k ->
                val kx = left + (right - left) * ((k - valueRange.start) / span).coerceIn(0f, 1f)
                drawCircle(if (kx <= x) Color.White.copy(alpha = 0.85f) else fill.copy(alpha = 0.5f), 2.dp.toPx(), Offset(kx, cy))
            }
        }
        drawRail()
        // iOS 26: a capsule thumb that turns into a glass lens over the rail while dragged
        drawCapsuleThumb(Offset(x, cy), 38.dp.toPx(), 24.dp.toPx(), lens, tint = fill, dark = dark, under = drawRail)
    }
}

private fun DrawScope.drawTrack(color: Color, from: Float, to: Float, cy: Float, h: Float) {
    if (to <= from) return
    drawRoundRect(color, Offset(from, cy - h / 2), Size(to - from, h), CornerRadius(h / 2))
}
