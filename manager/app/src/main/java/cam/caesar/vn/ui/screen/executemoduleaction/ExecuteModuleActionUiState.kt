package cam.su.kernel.ui.screen.executemoduleaction

import androidx.compose.runtime.Immutable

@Immutable
data class ExecuteModuleActionUiState(
    val moduleId: String,
    val text: String,
    val isComplete: Boolean = false,
    /** how the action ended; null while it runs */
    val succeeded: Boolean? = null,
)

@Immutable
data class ExecuteModuleActionScreenActions(
    val onBack: () -> Unit,
    val onSaveLog: () -> Unit,
    val onClose: () -> Unit = {},
)
