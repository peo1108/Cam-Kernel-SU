package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.viewmodel.HidingCheckViewModel

@Composable
fun HidingCheckScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<HidingCheckViewModel>()
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
    )

    HidingCheckScreenMiuix(uiState, actions)
}
