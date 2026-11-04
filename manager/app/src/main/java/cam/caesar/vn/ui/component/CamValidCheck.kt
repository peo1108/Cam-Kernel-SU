package cam.caesar.vn.ui.component

import androidx.compose.runtime.Composable
import cam.caesar.vn.Cam

@Composable
fun CamIsValid(
    content: @Composable () -> Unit
) {
    val camVersion = if (Cam.isAvailable) Cam.version else null

    if (camVersion != null) {
        content()
    }
}
