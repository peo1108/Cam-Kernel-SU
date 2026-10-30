package cam.su.kernel.ui.screen.appprofile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.ui.component.hero.LocalHero
import cam.su.kernel.ui.component.listdetail.PaneMotion
import cam.su.kernel.ui.screen.superuser.GroupedApps
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
    // the header is a LazyColumn item and may leave while the page stays: the page ends the flight
    val hero = LocalHero.current
    DisposableEffect(hero, uid) {
        onDispose { hero?.end(uid) }
    }
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

/**
 * [appGroup]'s profile beside the Superuser list on a wide page: the same editor as the profile
 * page, but [onClose] puts the list's overview back instead of popping a page, and every saved edit
 * calls [onEdited] so the list's row follows at once.
 */
@Composable
fun AppProfilePane(
    appGroup: GroupedApps,
    motion: PaneMotion?,
    bottomPadding: Dp,
    onClose: () -> Unit,
    onEdited: () -> Unit,
    onAppChanged: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val editor = rememberAppProfileEditor(appGroup, onSaved = onEdited)
    val actions = AppProfileActions(
        onBack = onClose,
        onLaunchApp = ::launchApp,
        onForceStopApp = ::forceStopApp,
        onRestartApp = ::restartApp,
        onOpenSystemInfo = ::openSystemAppInfo,
        onAppChanged = onAppChanged,
        onAppRemoved = {
            onAppChanged()
            onClose()
        },
        onViewTemplate = editor.onViewTemplate,
        onManageTemplate = editor.onManageTemplate,
        onProfileChange = editor.onProfileChange,
        onCheckHiding = dropUnlessResumed { navigator.push(Route.HidingCheck(appGroup.uid)) },
    )
    AppProfilePaneMiuix(
        state = editor.state,
        actions = actions,
        motion = motion,
        bottomPadding = bottomPadding,
    )
}
