package cam.su.kernel.ui.component.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.SwitchColors
import top.yukonga.miuix.kmp.basic.SwitchDefaults

/** iOS switch colors: system green when on, a grey fill when off, white thumb either way. */
@Composable
fun iosSwitchColors(): SwitchColors {
    val dark = isInDarkTheme()
    val green = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    val off = if (dark) Color(0x52787880) else Color(0x29787880)
    return SwitchDefaults.switchColors(
        checkedThumbColor = Color.White,
        uncheckedThumbColor = Color.White,
        disabledCheckedThumbColor = Color.White.copy(alpha = 0.8f),
        disabledUncheckedThumbColor = Color.White.copy(alpha = 0.8f),
        checkedTrackColor = green,
        uncheckedTrackColor = off,
        disabledCheckedTrackColor = green.copy(alpha = 0.4f),
        disabledUncheckedTrackColor = off.copy(alpha = 0.5f),
    )
}
