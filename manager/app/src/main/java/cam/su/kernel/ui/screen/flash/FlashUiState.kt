package cam.su.kernel.ui.screen.flash

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class FlashUiState(
    /** the card's title; null: how the flash stands */
    val title: String? = null,
    val text: String,
    val showRebootAction: Boolean,
    val flashingStatus: FlashingStatus,
    val showJailbreakWarning: Boolean,
    @param:StringRes val rebootLabelRes: Int,
)

@Immutable
data class FlashScreenActions(
    val onBack: () -> Unit,
    val onSaveLog: () -> Unit,
    val onReboot: () -> Unit,
    val onConfirmJailbreakWarning: () -> Unit,
    val onDismissJailbreakWarning: () -> Unit,
)
