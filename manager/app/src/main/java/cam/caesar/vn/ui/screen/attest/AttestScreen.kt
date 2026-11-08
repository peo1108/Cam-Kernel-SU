package cam.caesar.vn.ui.screen.attest

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.caesar.vn.attest.AttestViewModel
import cam.caesar.vn.ui.navigation3.LocalNavigator

/** keystore2 attestation: import a keybox, pick targets, configure, enable, and check what apps see. */
@Composable
fun AttestScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel { AttestViewModel() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importKeybox)
    }

    val actions = AttestActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onImportKeybox = { picker.launch("*/*") },
        onConfigure = viewModel::configure,
        onSetEnabled = viewModel::setEnabled,
        onCheckAttestation = viewModel::checkAttestation,
    )

    AttestScreenMiuix(uiState, actions)
}

data class AttestActions(
    val onBack: () -> Unit,
    val onImportKeybox: () -> Unit,
    val onConfigure: () -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onCheckAttestation: () -> Unit,
)
