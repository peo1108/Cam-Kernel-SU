package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Module(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val versionCode: Int,
    val description: String,
    val enabled: Boolean,
    val update: Boolean,
    val remove: Boolean,
    val updateJson: String,
    val hasWebUi: Boolean,
    val hasActionScript: Boolean,
    val metamodule: Boolean,
    val actionIconPath: String?,
    val webUiIconPath: String?,
    /** disabled by ksud's boot guard and not enabled again since */
    val autoDisabled: Boolean = false,
    val conflicts: List<ModuleConflict> = emptyList(),
    /** version kept by ksud when an update replaced it; null when there is none */
    val backupVersion: String? = null,
)
