package cam.caesar.vn.ui.screen.templateeditor

import androidx.compose.runtime.Immutable
import cam.caesar.vn.CamNative
import cam.caesar.vn.data.model.TemplateInfo

@Immutable
data class TemplateEditorUiState(
    val template: TemplateInfo,
    val initialTemplate: TemplateInfo,
    val readOnly: Boolean,
    val isCreation: Boolean,
    val idErrorHint: String = "",
) {
    val titleSummary: String
        get() = initialTemplate.id + if (initialTemplate.author.isNotEmpty()) "@${initialTemplate.author}" else ""
}

@Immutable
data class TemplateEditorActions(
    val onBack: () -> Unit,
    val onDelete: () -> Unit,
    val onSave: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onIdChange: (String) -> Unit,
    val onAuthorChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onProfileChange: (CamNative.Profile) -> Unit,
)
