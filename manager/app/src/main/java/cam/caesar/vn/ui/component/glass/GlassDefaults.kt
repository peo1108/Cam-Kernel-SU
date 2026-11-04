package cam.su.kernel.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Every liquid glass tunable lives here so the whole app can be adjusted in one place. */
object GlassDefaults {
    /** Backdrop sampling downscale for controls (droplets, search, FAB); cards stay at 1. */
    const val controlDownscale = 2

    val cardBlur = 8.dp
    /** The animated gradient background is rendered at 1/this resolution and stretched. */
    const val gradientDownscale = 4
    /** Saturation boost baked into the card material (vibrancy). */
    const val materialSaturation = 1.5f
    val listCardBlur = 8.dp
    val barBlur = 12.dp
    /** Scroll edge effect under transparent top bars: progressive blur strength and top tint. */
    val edgeBlur = 16.dp
    const val edgeTint = 0.55f
    val dialogBlur = 16.dp

    val cardLensHeight = 12.dp
    val cardLensAmount = 16.dp
    val barLensHeight = 8.dp
    val barLensAmount = 12.dp

    const val cardTintLight = 0.35f
    const val cardTintDark = 0.35f
    const val coloredCardTint = 0.8f
    const val barTintMax = 0.6f

    val dropletSize = 44.dp
    const val dropletPressScale = 1.12f
    const val dropletTintLight = 0.15f
    const val dropletTintDark = 0.1f
    val dropletLensHeight = 12.dp
    val dropletLensAmount = 20.dp
    const val dropletChromaticAberration = 0.5f

    const val overlayTintLight = 0.72f
    const val overlayTintDark = 0.6f
    val dialogLensHeight = 16.dp
    val dialogLensAmount = 24.dp
    val fabSize = 56.dp

    val cardCorner = 20.dp
    val dialogCorner = 32.dp
    val popupCorner = 20.dp

    @Composable
    @ReadOnlyComposable
    fun cardTint(): Color = colorScheme.surface.copy(alpha = if (isInDarkTheme()) cardTintDark else cardTintLight)

    @Composable
    @ReadOnlyComposable
    fun dropletTint(): Color = colorScheme.surface.copy(alpha = if (isInDarkTheme()) dropletTintDark else dropletTintLight)
}
