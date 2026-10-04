package me.weishu.kernelsu.ui.screen.gki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.util.Ak3Backup
import me.weishu.kernelsu.ui.viewmodel.GkiInstallViewModel
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import java.util.Date

@Composable
fun GkiInstallScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val viewModel = viewModel<GkiInstallViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val confirmDialog = rememberConfirmDialog()
    var showBuilds by rememberSaveable { mutableStateOf(false) }

    // back from a flash or restore: the kernel and the backups may have changed
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val pickZip = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri)
        if (name.endsWith(".zip", ignoreCase = true)) {
            viewModel.selectLocalZip(uri, name)
        } else {
            scope.launch { snackbarHost.showSnackbar(resources.getString(R.string.gki_only_zip)) }
        }
    }

    SfsBuildDialog(
        show = showBuilds,
        kmi = uiState.status.kmi,
        state = uiState.builds,
        selected = uiState.build,
        onDismissRequest = { showBuilds = false },
        onRetry = viewModel::loadBuilds,
        onSelected = viewModel::selectBuild,
    )

    val actions = GkiInstallActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSelectMethod = { method ->
            if (method == GkiMethod.Inactive) {
                // booting the other slot is only right after an OTA, so ask first
                scope.launch {
                    val result = confirmDialog.awaitConfirm(
                        title = resources.getString(R.string.install_inactive_slot),
                        content = resources.getString(R.string.gki_inactive_warning),
                    )
                    if (result == ConfirmResult.Confirmed) viewModel.selectMethod(method)
                }
            } else {
                viewModel.selectMethod(method)
            }
        },
        onSelectSource = viewModel::selectSource,
        onPickBuild = {
            if (uiState.builds is BuildsState.Failed) viewModel.loadBuilds()
            showBuilds = true
        },
        onPickLocalZip = {
            pickZip.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/zip" })
        },
        onToggleAdvanced = viewModel::toggleAdvanced,
        onSetBackupBoot = viewModel::setBackupBoot,
        onToggleRestore = viewModel::toggleRestore,
        onRestore = { backup ->
            scope.launch {
                val result = confirmDialog.awaitConfirm(
                    title = resources.getString(R.string.gki_restore),
                    content = resources.getString(
                        R.string.gki_restore_confirm,
                        backupLabel(context, backup),
                        backup.slot,
                    ),
                )
                if (result == ConfirmResult.Confirmed) {
                    navigator.push(Route.Flash(FlashIt.RestoreAk3Backup(backup.path)))
                }
            }
        },
        onNext = {
            val state = uiState
            val inactive = state.method == GkiMethod.Inactive
            val flash = when {
                state.usesProjectBuild -> state.build?.let {
                    FlashIt.FlashAnyKernel(url = it.url, inactive = inactive, backup = state.backupBoot)
                }

                state.usesLocalZip -> state.localZip?.let {
                    FlashIt.FlashAnyKernel(uri = it, inactive = inactive, backup = state.backupBoot)
                }

                else -> null
            }
            flash?.let { navigator.push(Route.Flash(it)) }
        },
    )

    GkiInstallScreenMiuix(uiState, actions, snackbarHost)
}

internal fun backupLabel(context: Context, backup: Ak3Backup): String = context.getString(
    if (backup.original) R.string.gki_backup_original else R.string.gki_backup_previous,
    backup.slot.ifBlank { "-" },
)

internal fun backupDate(context: Context, backup: Ak3Backup): String {
    val date = Date(backup.modified * 1000)
    return "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
}

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return fromProvider ?: uri.lastPathSegment.orEmpty()
}
