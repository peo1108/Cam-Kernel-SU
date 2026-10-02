package me.weishu.kernelsu.ui.component

import androidx.compose.runtime.Composable
import me.weishu.kernelsu.Ksu

@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    val ksuVersion = if (Ksu.isAvailable) Ksu.version else null

    if (ksuVersion != null) {
        content()
    }
}
