package me.weishu.kernelsu.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.component.glass.LocalGlassBackdrop
import me.weishu.kernelsu.ui.component.liquid.rememberCombinedBackdrop
import me.weishu.kernelsu.ui.component.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Records page content (without a background fill) so bars can sample it. The glass page
 * background is sampled separately through [LocalGlassBackdrop].
 */
@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    return rememberLayerBackdrop()
}

/**
 * Glass bar: samples the page background and the content scrolling under it. The surface
 * tint grows with [scrollFraction] (0 = resting, 1 = content under the bar).
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    scrollFraction: () -> Float = { 1f },
    content: @Composable () -> Unit,
) {
    if (!blurActive || backdrop == null) {
        Box { content() }
        return
    }
    val glass = LocalGlassBackdrop.current
    val sample: Backdrop = if (glass != null) rememberCombinedBackdrop(glass, backdrop) else backdrop
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier = Modifier.drawBackdrop(
            backdrop = sample,
            shape = { RectangleShape },
            effects = {
                vibrancy()
                blur(12.dp.toPx(), 12.dp.toPx())
            },
            onDrawSurface = {
                drawRect(surface.copy(alpha = 0.6f * scrollFraction().coerceIn(0f, 1f)))
            },
        ),
    ) {
        content()
    }
}
