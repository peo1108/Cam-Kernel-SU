package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.viewmodel.HidingCheckViewModel

/** The root hiding check, for the whole device or, with [uid], for that app. */
@Composable
fun HidingCheckScreen(uid: Int? = null) {
    val navigator = LocalNavigator.current
    val viewModel = viewModel(key = "hiding-check-${uid ?: "device"}") { HidingCheckViewModel(uid) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // only the saved scan and the switches; the audit itself waits for the user
    LaunchedEffect(Unit) { viewModel.load() }

    val actions = HidingCheckActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onScan = viewModel::scan,
        onApplyFixes = viewModel::applyFixes,
        onSetFix = viewModel::setFix,
        onIgnore = viewModel::ignore,
        onRestore = viewModel::restore,
        onClearHistory = viewModel::clearHistory,
        onCheckUpdates = viewModel::checkUpdates,
        onDisableModule = viewModel::disableModule,
        onCheckAttestation = viewModel::checkAttestation,
        onLaunchApp = viewModel::launchCheckedApp,
    )

    HidingCheckScreenMiuix(uiState, actions)
}
