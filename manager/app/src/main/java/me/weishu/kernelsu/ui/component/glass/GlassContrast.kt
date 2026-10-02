package me.weishu.kernelsu.ui.component.glass

// Background luma the overlay should reach so on-surface text stays readable:
// dark theme (white text) needs a dark background, light theme (dark text) a light one.
private const val DARK_TARGET_LUMA = 0.35f
private const val LIGHT_TARGET_LUMA = 0.65f
private const val MAX_AUTO_DIM = 0.75f

/**
 * Dim overlay alpha for an image background with average luma [meanLuma] (0..1): at least the
 * user's [userDim], raised when the image fights the theme (bright image in dark mode, dark image in
 * light mode). The overlay is black in dark mode and white in light mode.
 */
fun effectiveDim(userDim: Float, meanLuma: Float, dark: Boolean): Float {
    val luma = meanLuma.coerceIn(0f, 1f)
    val needed = if (dark) {
        if (luma <= DARK_TARGET_LUMA) 0f else 1f - DARK_TARGET_LUMA / luma
    } else {
        if (luma >= LIGHT_TARGET_LUMA) 0f else (LIGHT_TARGET_LUMA - luma) / (1f - luma)
    }
    return maxOf(userDim, needed.coerceAtMost(MAX_AUTO_DIM))
}
