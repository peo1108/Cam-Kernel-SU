package cam.su.kernel.ui.component.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent

internal object IosControlColors {
    val greenLight = Color(0xFF34C759)
    val greenDark = Color(0xFF30D158)
    val offLight = Color(0x29787880)
    val offDark = Color(0x52787880)
}

/**
 * iOS 26 switch: a wide track and a capsule thumb. While pressed the thumb swells into a clear glass
 * lens over the track, then springs to the other side.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dark = isInDarkTheme()
    val on = if (dark) IosControlColors.greenDark else IosControlColors.greenLight
    val off = if (dark) IosControlColors.offDark else IosControlColors.offLight
    val haptic = LocalHapticFeedback.current
    val onChange by rememberUpdatedState(onCheckedChange)
    var pressed by remember { mutableStateOf(false) }
    val position by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.68f, stiffness = 420f), label = "switch")
    val lens by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 520f), label = "lens")
    Canvas(
        modifier = modifier
            .size(62.dp, 28.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(enabled, onCheckedChange != null) {
                if (!enabled || onCheckedChange == null) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) {
                        up.consume()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChange?.invoke(!checked)
                    }
                }
            },
    ) {
        val h = size.height
        val trackColor = lerp(off, on, position)
        drawRoundRect(trackColor, cornerRadius = CornerRadius(h / 2))
        val inset = 2.dp.toPx()
        val tw = 37.dp.toPx()
        val th = h - 2 * inset
        val cx = inset + tw / 2 + (size.width - 2 * inset - tw) * position
        drawCapsuleThumb(Offset(cx, h / 2), tw, th, lens, tint = trackColor, dark = dark) { }
    }
}

/**
 * Capsule thumb shared by the switch and the slider. At [lens] 0 it is a solid white pill; towards
 * 1 it grows and turns into clear glass that shows [under] magnified, with a bright rim.
 */
internal fun DrawScope.drawCapsuleThumb(
    center: Offset,
    width: Float,
    height: Float,
    lens: Float,
    tint: Color,
    dark: Boolean,
    under: DrawScope.() -> Unit,
) {
    val grow = 1f + 0.38f * lens
    val w = width * grow
    val h = height * grow
    val rect = Rect(center.x - w / 2, center.y - h / 2, center.x + w / 2, center.y + h / 2)
    val radius = CornerRadius(h / 2)
    val solid = if (dark) Color(0xFFE5E5EA) else Color.White
    // shadow
    drawRoundRect(
        Color.Black.copy(alpha = 0.12f * (1f - 0.4f * lens)),
        topLeft = rect.topLeft + Offset(0f, 1.5.dp.toPx()),
        size = rect.size,
        cornerRadius = radius,
    )
    // solid pill fading into glass
    drawRoundRect(solid.copy(alpha = 1f - 0.82f * lens), rect.topLeft, rect.size, radius)
    if (lens > 0.01f) {
        // the lens: what lies under it, magnified, then a faint tint and a bright rim
        clipRoundRect(rect, radius) {
            scaleAround(1.5f, center) { under() }
        }
        drawRoundRect(tint.copy(alpha = 0.10f * lens), rect.topLeft, rect.size, radius)
        drawRoundRect(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.95f * lens), Color.White.copy(alpha = 0.15f * lens), Color.White.copy(alpha = 0.7f * lens)),
                start = rect.topLeft,
                end = rect.bottomRight,
            ),
            rect.topLeft,
            rect.size,
            radius,
            style = Stroke(1.6.dp.toPx()),
        )
    }
}

private inline fun DrawScope.clipRoundRect(rect: Rect, radius: CornerRadius, block: DrawScope.() -> Unit) {
    clipPath(Path().apply { addRoundRect(RoundRect(rect, radius)) }) { block() }
}

private inline fun DrawScope.scaleAround(factor: Float, pivot: Offset, block: DrawScope.() -> Unit) {
    withTransform({ scale(factor, factor, pivot) }) { block() }
}

/** Drop-in for miuix SwitchPreference with the iOS switch; the whole row toggles it. */
@Composable
fun GlassSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = { GlassSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
        onClick = { if (enabled) onCheckedChange(!checked) },
        enabled = enabled,
    )
}
