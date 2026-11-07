package cam.caesar.vn.ui.navigation3

import cam.caesar.vn.ui.component.CamToast
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import kotlinx.coroutines.channels.ReceiveChannel
import cam.caesar.vn.Cam
import cam.caesar.vn.R
import cam.caesar.vn.data.repository.SettingsRepositoryImpl
import cam.caesar.vn.camApp
import cam.caesar.vn.files.IncomingFile
import cam.caesar.vn.hiding.ModuleScanNotifier
import cam.caesar.vn.ui.component.dialog.rememberConfirmDialog
import cam.caesar.vn.ui.component.dialog.rememberLoadingDialog
import cam.caesar.vn.ui.viewmodel.FilesSignal
import cam.caesar.vn.ui.screen.flash.FlashIt
import cam.caesar.vn.ui.util.DownloadService
import cam.caesar.vn.ui.util.getFileName
import cam.caesar.vn.ui.webui.WebUIActivity

// cam:// only: the upstream scheme went with the old package (docs/CAM_CHANGES.md row 84)
private const val SCHEME_CAM = "cam"
private const val HOST_ACTION = "action"
private const val HOST_WEBUI = "webui"
private const val PARAM_ID = "id"
private const val PARAM_TOKEN = "token"

/**
 * Resolved intent action to execute after validation.
 */
private sealed interface PendingAction {
    /** Install module(s) from URI — triggered by DownloadService notification or ZIP file open. */
    data class InstallModule(
        val uri: Uri,
        val displayName: String,
        val requiresConfirmation: Boolean,
    ) : PendingAction {
        companion object {
            val InstallModuleSaver = listSaver<InstallModule?, Any>(
                save = { module ->
                    if (module == null) {
                        emptyList()
                    } else {
                        listOf(
                            module.uri,
                            module.displayName,
                            module.requiresConfirmation
                        )
                    }
                },
                restore = { list ->
                    if (list.isEmpty()) {
                        null
                    } else {
                        InstallModule(
                            uri = list[0] as Uri,
                            displayName = list[1] as String,
                            requiresConfirmation = list[2] as Boolean
                        )
                    }
                }
            )
        }
    }

    /** Execute a module's action script — triggered by shortcut or deep link. */
    data class ExecuteAction(val moduleId: String) : PendingAction

    /** Open a module's WebUI — triggered by shortcut. */
    data class OpenWebUI(val moduleId: String) : PendingAction

    /** Open the root hiding check and run it — triggered by the module scan notification. */
    data object OpenHidingCheck : PendingAction

    /** Open any other file in the Files tab — another app's "Open with" (Telegram, like MT Manager). */
    data class OpenFile(val uri: Uri) : PendingAction
}

private sealed interface CamDeepLink {
    data class Action(val moduleId: String) : CamDeepLink
    data class WebUi(val moduleId: String) : CamDeepLink
}

private fun buildInternalWebUiUri(moduleId: String): Uri {
    return Uri.Builder()
        .scheme(SCHEME_CAM)
        .authority(HOST_WEBUI)
        .appendQueryParameter(PARAM_ID, moduleId)
        .build()
}

fun getDisplayName(uri: Uri): String {
    return uri.getFileName(camApp) ?: uri.lastPathSegment ?: "Unknown"
}

private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")

/**
 * Resolve an intent snapshot into a [PendingAction].
 * Returns null if the intent carries no recognized action.
 */
private fun resolveIntent(intent: Intent): PendingAction? {
    // DownloadService notification: install module
    if (intent.action == DownloadService.ACTION_INSTALL_MODULE) {
        val token = intent.getStringExtra(DownloadService.EXTRA_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        if (token != SettingsRepositoryImpl().intentToken) return null
        val uriString = intent.getStringExtra(DownloadService.EXTRA_MODULE_URI)
            ?: return null
        val uri = uriString.toUri()
        return PendingAction.InstallModule(
            uri = uri,
            displayName = getDisplayName(uri),
            requiresConfirmation = false,
        )
    }

    // ModuleScanNotifier: a new module leaks root
    if (intent.getBooleanExtra(ModuleScanNotifier.EXTRA_OPEN_HIDING_CHECK, false)) {
        return PendingAction.OpenHidingCheck
    }

    // File manager: open ZIP
    val viewUri = intent.data
    if (viewUri != null && viewUri.scheme == "content" && intent.type in ZIP_MIME_TYPES) {
        return PendingAction.InstallModule(
            uri = viewUri,
            displayName = getDisplayName(viewUri),
            requiresConfirmation = true,
        )
    }

    // File manager: open anything else in the Files tab
    if (intent.action == Intent.ACTION_VIEW && viewUri != null && (viewUri.scheme == "content" || viewUri.scheme == "file")) {
        return PendingAction.OpenFile(viewUri)
    }

    // Check deep links
    return when (val deepLink = parseValidatedDeepLink(intent.data)) {
        is CamDeepLink.Action -> PendingAction.ExecuteAction(deepLink.moduleId)
        is CamDeepLink.WebUi -> PendingAction.OpenWebUI(deepLink.moduleId)
        null -> null
    }
}

private fun parseValidatedDeepLink(uri: Uri?): CamDeepLink? {
    if (uri?.scheme != SCHEME_CAM) return null

    val moduleId = uri.getQueryParameter(PARAM_ID)?.takeIf { it.isNotBlank() } ?: return null
    val token = uri.getQueryParameter(PARAM_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
    if (token != SettingsRepositoryImpl().intentToken) return null

    return when (uri.host) {
        HOST_ACTION -> CamDeepLink.Action(moduleId)
        HOST_WEBUI -> CamDeepLink.WebUi(moduleId)
        else -> null
    }
}

@SuppressLint("StringFormatInvalid")
@Composable
fun IntentDispatcher(intentChannel: ReceiveChannel<Intent>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = LocalNavigator.current
    val isSafeMode = Cam.isSafeMode
    val isManager = Cam.isAvailable
    var pendingZipInstall by rememberSaveable(stateSaver = PendingAction.InstallModule.InstallModuleSaver) { mutableStateOf(null) }

    val installDialog = rememberConfirmDialog(
        onConfirm = {
            pendingZipInstall?.let { action ->
                navigator.push(Route.Flash(FlashIt.FlashModules(listOf(action.uri))))
            }
            pendingZipInstall = null
        },
        onDismiss = { pendingZipInstall = null }
    )
    val loadingDialog = rememberLoadingDialog()

    CollectIntentChannel(intentChannel) { intent ->
        if (!isManager) return@CollectIntentChannel
        val action = resolveIntent(intent) ?: return@CollectIntentChannel

        when (action) {
            is PendingAction.InstallModule -> {
                if (isSafeMode) {
                    CamToast.show(context, resources.getString(R.string.safe_mode_module_disabled))
                    return@CollectIntentChannel
                }
                if (action.requiresConfirmation) {
                    pendingZipInstall = action
                    installDialog.showConfirm(
                        title = resources.getString(R.string.module),
                        content = resources.getString(
                            R.string.module_install_prompt_with_name,
                            "\n${action.displayName}"
                        )
                    )
                } else {
                    navigator.push(Route.Flash(FlashIt.FlashModules(listOf(action.uri))))
                }
            }

            is PendingAction.ExecuteAction -> {
                navigator.push(Route.ExecuteModuleAction(action.moduleId, fromShortcut = true))
            }

            is PendingAction.OpenWebUI -> {
                val webIntent = Intent(context, WebUIActivity::class.java)
                    .setData(buildInternalWebUiUri(action.moduleId))
                context.startActivity(webIntent)
            }

            PendingAction.OpenHidingCheck -> {
                navigator.push(Route.HidingCheck(scanNow = true))
            }

            is PendingAction.OpenFile -> {
                // a copy can take a moment (a large file in another app's private folder)
                val path = loadingDialog.withLoading { IncomingFile.resolve(context, action.uri) }
                if (path == null) {
                    CamToast.show(context, resources.getString(R.string.files_open_incoming_failed))
                } else {
                    FilesSignal.open(path)
                }
            }
        }
    }
}

/**
 * Receive intents inside a [LaunchedEffect] tied to the channel identity.
 * Each emitted intent is processed exactly once; no activity.intent mutation needed.
 */
@Composable
private fun CollectIntentChannel(intentChannel: ReceiveChannel<Intent>, onIntent: suspend (Intent) -> Unit) {
    LaunchedEffect(intentChannel) {
        for (intent in intentChannel) {
            onIntent(intent)
        }
    }
}
