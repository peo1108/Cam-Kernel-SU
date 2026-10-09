package cam.su.kernel.ui.component

import androidx.compose.runtime.Composable
import cam.su.kernel.Ksu

@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    val ksuVersion = if (Ksu.isAvailable) Ksu.version else null

    if (ksuVersion != null) {
        content()
    }
}
