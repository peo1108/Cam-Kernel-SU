package cam.su.kernel.ui.screen.appprofile

import androidx.compose.runtime.Immutable
import cam.su.kernel.CamNative
import cam.su.kernel.ui.screen.superuser.GroupedApps

@Immutable
data class AppProfileUiState(
    val uid: Int,
    val packageName: String,
    val profile: CamNative.Profile,
    val appGroup: GroupedApps,
    val sharedUserId: String,
) {
    val isUidGroup get() = appGroup.apps.size > 1
}

@Immutable
data class AppProfileActions(
    val onBack: () -> Unit,
    val onLaunchApp: (String, Int) -> Unit,
    val onForceStopApp: (String, Int) -> Unit,
    val onRestartApp: (String, Int) -> Unit,
    val onOpenSystemInfo: (String, Int) -> Unit,
    val onAppChanged: () -> Unit,
    val onAppRemoved: () -> Unit,
    val onViewTemplate: (String) -> Unit,
    val onManageTemplate: () -> Unit,
    val onProfileChange: (CamNative.Profile) -> Unit,
    val onCheckHiding: () -> Unit,
)
