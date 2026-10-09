package cam.su.kernel.ui.component.uninstalldialog

import androidx.compose.runtime.Composable

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    UninstallDialogMiuix(show, onDismissRequest)
}
