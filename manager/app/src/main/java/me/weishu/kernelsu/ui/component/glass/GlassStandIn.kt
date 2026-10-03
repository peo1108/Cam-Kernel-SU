package me.weishu.kernelsu.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import me.weishu.kernelsu.ui.component.miuix.effect.BgEffectConfig
import me.weishu.kernelsu.ui.component.miuix.effect.DeviceType
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.shouldShowSplitPane
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** A backdrop that only fills a color: it never changes, so surfaces sampling it never re-record. */
@Stable
class SolidBackdrop(val color: Color) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        drawRect(color)
    }
}

/**
 * Static stand-in for the animated gradient background, for bar glass: sampling the live gradient
 * would re-record the bars every frame, and sampling nothing renders black. The gradient has no
 * detail to refract, so its average color (over the surface, like the shader draws it) looks the same.
 */
@Composable
fun rememberGradientStandIn(): Backdrop {
    val seed = colorScheme.primary
    val surface = colorScheme.surface
    val dark = isInDarkTheme()
    val deviceType = if (shouldShowSplitPane()) DeviceType.PAD else DeviceType.PHONE
    return remember(seed, surface, dark, deviceType) {
        val colors = BgEffectConfig.tint(BgEffectConfig.get(deviceType, dark), seed.toArgb()).colors1
        var r = 0f
        var g = 0f
        var b = 0f
        val points = colors.size / 4
        for (i in 0 until points) {
            val a = colors[i * 4 + 3]
            r += colors[i * 4] * a + surface.red * (1f - a)
            g += colors[i * 4 + 1] * a + surface.green * (1f - a)
            b += colors[i * 4 + 2] * a + surface.blue * (1f - a)
        }
        SolidBackdrop(Color(r / points, g / points, b / points))
    }
}
