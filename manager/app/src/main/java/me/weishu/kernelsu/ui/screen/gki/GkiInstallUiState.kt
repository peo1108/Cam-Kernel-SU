package me.weishu.kernelsu.ui.screen.gki

import android.net.Uri
import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.Ak3Backup
import me.weishu.kernelsu.ui.util.GkiStatus
import me.weishu.kernelsu.ui.util.SfsBuild

/** How the AnyKernel3 zip gets onto the device. */
@Parcelize
enum class GkiMethod(@StringRes val label: Int) : Parcelable {
    /** a project build for this device's KMI, to the active slot */
    Direct(R.string.direct_install),
    /** a zip picked on the device, to the active slot */
    Local(R.string.gki_install_local),
    /** either source, to the inactive slot after an OTA */
    Inactive(R.string.install_inactive_slot),
}

/** Where the zip for [GkiMethod.Inactive] comes from. */
@Parcelize
enum class GkiSource(@StringRes val label: Int) : Parcelable {
    Project(R.string.gki_source_project),
    Local(R.string.gki_source_local),
}

@Immutable
sealed interface BuildsState {
    data object Loading : BuildsState
    data class Loaded(val builds: List<SfsBuild>) : BuildsState
    data class Failed(val message: String) : BuildsState
}

@Immutable
data class GkiInstallUiState(
    val status: GkiStatus = GkiStatus(),
    val builds: BuildsState = BuildsState.Loading,
    val backups: List<Ak3Backup> = emptyList(),
    val method: GkiMethod? = null,
    val source: GkiSource = GkiSource.Project,
    val build: SfsBuild? = null,
    /** the build matching this device's kernel best; preselected, labelled (Recommended) */
    val recommended: SfsBuild? = null,
    val localZip: Uri? = null,
    val localZipName: String? = null,
    val backupBoot: Boolean = true,
    val advancedShown: Boolean = false,
    val restoreShown: Boolean = false,
) {
    val usesProjectBuild: Boolean
        get() = method == GkiMethod.Direct || (method == GkiMethod.Inactive && source == GkiSource.Project)

    val usesLocalZip: Boolean
        get() = method == GkiMethod.Local || (method == GkiMethod.Inactive && source == GkiSource.Local)

    val canInstall: Boolean
        get() = status.supported && when {
            usesProjectBuild -> build != null
            usesLocalZip -> localZip != null
            else -> false
        }
}

@Immutable
data class GkiInstallActions(
    val onBack: () -> Unit,
    val onSelectMethod: (GkiMethod) -> Unit,
    val onSelectSource: (GkiSource) -> Unit,
    val onPickBuild: () -> Unit,
    val onPickLocalZip: () -> Unit,
    val onToggleAdvanced: () -> Unit,
    val onSetBackupBoot: (Boolean) -> Unit,
    val onToggleRestore: () -> Unit,
    val onRestore: (Ak3Backup) -> Unit,
    val onNext: () -> Unit,
)
