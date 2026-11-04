package cam.caesar.vn.ui.screen.appprofile

import androidx.compose.runtime.Immutable
import cam.caesar.vn.CamNative
import cam.caesar.vn.ui.screen.superuser.GroupedApps

@Immutable
data class AppProfileUiState(
    val uid: Int,
    val packageName: String,
    val profile: CamNative.Profile,
    val appGroup: GroupedApps,
    val sharedUserId: String,
    /** The custom SELinux rules are still being read; their editor waits for them. */
    val rulesLoading: Boolean = false,
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
