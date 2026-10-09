package cam.su.kernel.ui.screen.appprofile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.util.forceStopApp
import cam.su.kernel.ui.util.launchApp
import cam.su.kernel.ui.util.openSystemAppInfo
import cam.su.kernel.ui.util.restartApp
import cam.su.kernel.ui.viewmodel.SuperUserViewModel

@Composable
fun AppProfileScreen(uid: Int) {
    val navigator = LocalNavigator.current
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    if (appGroup == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val editor = rememberAppProfileEditor(appGroup)

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onLaunchApp = ::launchApp,
        onForceStopApp = ::forceStopApp,
        onRestartApp = ::restartApp,
        onOpenSystemInfo = ::openSystemAppInfo,
        onAppChanged = { viewModel.loadAppList(force = true) },
        onAppRemoved = {
            viewModel.loadAppList(force = true)
            navigator.pop()
        },
        onViewTemplate = editor.onViewTemplate,
        onManageTemplate = editor.onManageTemplate,
        onProfileChange = editor.onProfileChange,
        onCheckHiding = dropUnlessResumed { navigator.push(Route.HidingCheck(uid)) },
    )

    AppProfileScreenMiuix(
        state = editor.state,
        actions = actions,
    )
}
