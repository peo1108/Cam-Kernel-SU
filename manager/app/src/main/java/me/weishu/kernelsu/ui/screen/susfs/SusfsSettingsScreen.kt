package me.weishu.kernelsu.ui.screen.susfs

import android.net.Uri
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.util.SusfsConfig
import me.weishu.kernelsu.ui.util.getAutoBootconfig
import me.weishu.kernelsu.ui.util.getSusfsLog
import me.weishu.kernelsu.ui.util.reboot
import me.weishu.kernelsu.ui.viewmodel.SusfsSettingsViewModel
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A read-only text shown in a dialog: the log, a bootconfig preview, or what failed. */
internal data class TextSheet(val title: String, val text: String)

@Composable
fun SusfsSettingsScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val viewModel = viewModel<SusfsSettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val confirmDialog = rememberConfirmDialog()
    var sheet by remember { mutableStateOf<TextSheet?>(null) }
    var sheetShown by rememberSaveable { mutableStateOf(false) }

    fun showSheet(title: String, text: String) {
        sheet = TextSheet(title, text)
        sheetShown = true
    }

    fun snack(res: Int) {
        scope.launch { snackbarHost.showSnackbar(resources.getString(res)) }
    }

    val leave = dropUnlessResumed { navigator.pop() }
    val onBack: () -> Unit = {
        if (!uiState.dirty) {
            leave()
        } else {
            scope.launch {
                val result = confirmDialog.awaitConfirm(
                    title = resources.getString(R.string.susfs_unsaved_title),
                    content = resources.getString(R.string.susfs_unsaved_message),
                    confirm = resources.getString(R.string.susfs_discard),
                )
                if (result == ConfirmResult.Confirmed) leave()
            }
        }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = uiState.dirty,
        onBackCompleted = onBack,
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = uiState.edited.toJson().toString(2)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            snack(if (ok) R.string.susfs_exported else R.string.susfs_export_failed)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val config = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    SusfsConfig.fromJson(JSONObject(text.orEmpty()))
                }.getOrNull()
            }
            if (config == null) {
                snack(R.string.susfs_import_failed)
            } else {
                viewModel.replace(config)
                snack(R.string.susfs_imported)
            }
        }
    }

    val actions = SusfsSettingsActions(
        onBack = onBack,
        onUpdate = viewModel::update,
        onSave = {
            viewModel.save(context) { result ->
                when {
                    !result.saved -> showSheet(
                        resources.getString(R.string.susfs_not_saved),
                        result.errors.joinToString("\n") { "• $it" },
                    )

                    result.failed.isNotEmpty() -> showSheet(
                        resources.getString(R.string.susfs_saved_with_failures),
                        result.failed.joinToString("\n") { "• $it" },
                    )

                    result.rebootNeeded -> scope.launch {
                        val confirm = confirmDialog.awaitConfirm(
                            title = resources.getString(R.string.susfs_saved),
                            content = resources.getString(R.string.susfs_reboot_needed),
                            confirm = resources.getString(R.string.reboot),
                            dismiss = resources.getString(R.string.susfs_later),
                        )
                        if (confirm == ConfirmResult.Confirmed) {
                            withContext(Dispatchers.IO) { reboot() }
                        }
                    }

                    uiState.state.moduleActive -> snack(R.string.susfs_saved_module)
                    else -> snack(R.string.susfs_saved_applied)
                }
            }
        },
        onUseStockBuild = viewModel::useStockBuild,
        onShowLog = {
            scope.launch {
                val log = getSusfsLog().ifBlank { resources.getString(R.string.susfs_log_empty) }
                showSheet(resources.getString(R.string.susfs_log), log)
            }
        },
        onPreviewBootconfig = {
            scope.launch {
                showSheet(resources.getString(R.string.susfs_bootconfig_auto), getAutoBootconfig())
            }
        },
        onExport = {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            exportLauncher.launch("susfs_settings_$stamp.json")
        },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        onReset = {
            scope.launch {
                val result = confirmDialog.awaitConfirm(
                    title = resources.getString(R.string.susfs_reset),
                    content = resources.getString(R.string.susfs_reset_message),
                )
                if (result == ConfirmResult.Confirmed) viewModel.replace(SusfsConfig())
            }
        },
    )

    SusfsSettingsMiuix(uiState, actions, snackbarHost)

    TextSheetDialog(
        sheet = sheet.takeIf { sheetShown },
        onDismissRequest = { sheetShown = false },
    )
}
