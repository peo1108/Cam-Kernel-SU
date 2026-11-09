package cam.caesar.vn.ui.screen.attest

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.caesar.vn.attest.AttestViewModel
import cam.caesar.vn.files.FileEntry
import cam.caesar.vn.ui.navigation3.LocalNavigator

/** keystore2 attestation: import a keybox, pick targets, configure, enable, and check what apps see. */
@Composable
fun AttestScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel { AttestViewModel() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    // the system picker, for what the in-app browser cannot reach (Drive, a USB stick)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        result.data?.data?.let(viewModel::importKeybox)
    }

    // Back closes the browser before it leaves the page
    BackHandler(enabled = uiState.browser.open) { viewModel.closeBrowser() }

    val actions = AttestActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenBrowser = viewModel::openBrowser,
        onCloseBrowser = viewModel::closeBrowser,
        onSystemPicker = {
            picker.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                },
            )
        },
        onBrowserUp = viewModel::up,
        onBrowserNavigate = viewModel::navigate,
        onPickKeybox = viewModel::importKeyboxFile,
        onConfigure = viewModel::configure,
        onSetEnabled = viewModel::setEnabled,
        onCheckAttestation = viewModel::checkAttestation,
    )

    AttestScreenMiuix(uiState, actions)
}

data class AttestActions(
    val onBack: () -> Unit,
    val onOpenBrowser: () -> Unit,
    val onCloseBrowser: () -> Unit,
    val onSystemPicker: () -> Unit,
    val onBrowserUp: () -> Unit,
    val onBrowserNavigate: (String) -> Unit,
    val onPickKeybox: (FileEntry) -> Unit,
    val onConfigure: () -> Unit,
    val onSetEnabled: (Boolean) -> Unit,
    val onCheckAttestation: () -> Unit,
)
