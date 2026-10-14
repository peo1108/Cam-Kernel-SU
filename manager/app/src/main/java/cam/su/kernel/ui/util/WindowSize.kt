package cam.su.kernel.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val deviceDensity = LocalResources.current.displayMetrics.density
    val widthDp = windowInfo.containerSize.width / deviceDensity
    val heightDp = windowInfo.containerSize.height / deviceDensity
    return widthDp >= 840f || (widthDp >= 600f && heightDp / widthDp < 1.2f)
}

/**
 * The Files tab shows both panels side by side from 600dp up, also on a tablet held upright: a
 * file row needs far less width than the pages [shouldShowSplitPane] is for. Phones keep one panel.
 */
@Composable
fun shouldShowTwoFilePanels(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val deviceDensity = LocalResources.current.displayMetrics.density
    return windowInfo.containerSize.width / deviceDensity >= 600f
}
