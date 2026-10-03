package me.weishu.kernelsu.ui.util

import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.component.glass.GlassDefaults
import me.weishu.kernelsu.ui.component.glass.LocalGlassBackdrop
import me.weishu.kernelsu.ui.component.glass.LocalGlassInBar
import me.weishu.kernelsu.ui.component.liquid.lens
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
 * Bar container. Top bars are fully transparent (iOS style: title and glass droplet buttons
 * float over the page); with [scrollBehavior] they get a scroll edge effect, a blur that fades out
 * downwards, only while content is scrolled under them. With [glass] the bar is a glass slab
 * sampling the page background and the content scrolling under it, used by the docked bottom bar.
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    glass: Boolean = false,
    scrollBehavior: ScrollBehavior? = null,
    content: @Composable () -> Unit,
) {
    val pageGlass = LocalGlassBackdrop.current
    // Controls in the bar (droplet buttons, search field) refract the content scrolling under it.
    val sample: Backdrop? = when {
        !blurActive || backdrop == null -> pageGlass
        pageGlass != null -> rememberCombinedBackdrop(pageGlass, backdrop)
        else -> backdrop
    }
    if (!glass || sample == null || backdrop == null || !blurActive) {
        CompositionLocalProvider(LocalGlassBackdrop provides sample, LocalGlassInBar provides true) {
            Box(modifier = if (sample != null && backdrop != null && blurActive) Modifier.scrollEdge(sample, scrollBehavior) else Modifier) {
                content()
            }
        }
        return
    }
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier = Modifier.drawBackdrop(
            backdrop = sample,
            shape = { BarShape },
            effects = {
                vibrancy()
                blur(GlassDefaults.barBlur.toPx(), GlassDefaults.barBlur.toPx())
                lens(GlassDefaults.barLensHeight.toPx(), GlassDefaults.barLensAmount.toPx())
            },
            onDrawSurface = {
                drawRect(surface.copy(alpha = GlassDefaults.barTintMax))
            },
        ),
    ) {
        CompositionLocalProvider(LocalGlassBackdrop provides sample, LocalGlassInBar provides true) {
            content()
        }
    }
}

/** How far content has scrolled under the bar: 0 at rest, 1 once the bar is fully overlapped. */
private fun ScrollBehavior.edgeFraction(): Float =
    maxOf(state.collapsedFraction, state.overlappedFraction).coerceIn(0f, 1f)

@Composable
private fun Modifier.scrollEdge(sample: Backdrop, scrollBehavior: ScrollBehavior?): Modifier {
    if (scrollBehavior == null) return this
    // Only toggles composition when content starts or stops overlapping; the strength itself is
    // read in the layer block, so scrolling does not recompose the bar.
    val active by remember(scrollBehavior) { derivedStateOf { scrollBehavior.edgeFraction() > 0.01f } }
    if (!active) return this
    val surface = MiuixTheme.colorScheme.surface
    return drawBackdrop(
        backdrop = sample,
        shape = { BarShape },
        effects = {
            blur(GlassDefaults.edgeBlur.toPx(), GlassDefaults.edgeBlur.toPx())
        },
        layerBlock = { alpha = scrollBehavior.edgeFraction() },
        onDrawSurface = {
            drawRect(Brush.verticalGradient(listOf(surface.copy(alpha = GlassDefaults.edgeTint), Color.Transparent)))
        },
        progressiveGradient = ProgressiveBlur.Top,
    )
}

// Corner-based so the lens shader can refract along the bar edges.
private val BarShape = RoundedCornerShape(0.dp)
