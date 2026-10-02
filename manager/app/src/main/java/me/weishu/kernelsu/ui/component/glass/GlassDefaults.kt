package me.weishu.kernelsu.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Every liquid glass tunable lives here so the whole app can be adjusted in one place. */
object GlassDefaults {
    val cardBlur = 8.dp
    val listCardBlur = 8.dp
    val barBlur = 12.dp
    val dialogBlur = 16.dp

    val cardLensHeight = 12.dp
    val cardLensAmount = 16.dp
    val barLensHeight = 8.dp
    val barLensAmount = 12.dp

    const val cardTintLight = 0.35f
    const val cardTintDark = 0.35f
    const val coloredCardTint = 0.8f
    const val barTintMax = 0.6f

    val cardCorner = 20.dp
    val dialogCorner = 32.dp
    val popupCorner = 20.dp

    @Composable
    @ReadOnlyComposable
    fun cardTint(): Color = colorScheme.surface.copy(alpha = if (isInDarkTheme()) cardTintDark else cardTintLight)
}
