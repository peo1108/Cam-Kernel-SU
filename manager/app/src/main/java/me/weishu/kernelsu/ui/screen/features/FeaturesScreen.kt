package me.weishu.kernelsu.ui.screen.features

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.viewmodel.FeaturesViewModel

@Composable
fun FeaturesPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<FeaturesViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    // where the next AnyKernel3 flash goes: the active slot, or the inactive one after an OTA
    var inactive by rememberSaveable { mutableStateOf(false) }
    var showBuilds by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }

    val selectAnyKernel = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        if (isZipFile(context, uri)) {
            navigator.push(Route.Flash(FlashIt.FlashAnyKernel(uri = uri, inactive = inactive)))
        } else {
            Toast.makeText(context, R.string.gki_only_zip, Toast.LENGTH_SHORT).show()
        }
    }
    val pickLocal = {
        selectAnyKernel.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/zip" })
    }

    SfsBuildDialog(
        show = showBuilds,
        kmi = uiState.gki.kmi,
        onDismissRequest = { showBuilds = false },
        onSelected = { build ->
            navigator.push(Route.Flash(FlashIt.FlashAnyKernel(url = build.url, inactive = inactive)))
        },
    )
    GkiSourceDialog(
        show = showSource,
        onDismissRequest = { showSource = false },
        onLocal = pickLocal,
        onProject = { showBuilds = true },
    )

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.refresh()
    }

    // back from the background: the boot guard or another app may have changed things
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) viewModel.refresh()
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    val actions = FeaturesActions(
        onSetBootGuardEnabled = viewModel::setBootGuardEnabled,
        onSetBootGuardFailures = viewModel::setBootGuardFailures,
        onSetBootGuardDisableAll = viewModel::setBootGuardDisableAll,
        onReenableModule = viewModel::reenableModule,
        onDismissBootGuard = viewModel::dismissBootGuard,
        onSetConflictDetection = viewModel::setConflictDetection,
        onSetConflictWarnOnFlash = viewModel::setConflictWarnOnFlash,
        onSetConflictIncludeProps = viewModel::setConflictIncludeProps,
        onRescanConflicts = viewModel::rescanConflicts,
        onSetHideBootloader = viewModel::setHideBootloader,
        onCheckAttestation = viewModel::checkAttestation,
        onGkiInstallDirect = {
            inactive = false
            showBuilds = true
        },
        onGkiInstallLocal = {
            inactive = false
            pickLocal()
        },
        onGkiInstallInactive = {
            inactive = true
            showSource = true
        },
    )

    FeaturesPagerMiuix(uiState, actions, bottomInnerPadding)
}

private fun isZipFile(context: Context, uri: Uri): Boolean {
    if (uri.lastPathSegment?.endsWith(".zip", ignoreCase = true) == true) return true
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0)?.endsWith(".zip", ignoreCase = true) == true
        }
    }.getOrNull() ?: false
}
