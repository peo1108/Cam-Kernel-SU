package cam.su.kernel.ui.screen.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.R
import cam.su.kernel.files.Errno
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.screen.flash.FlashIt
import cam.su.kernel.ui.viewmodel.FilesEvent
import cam.su.kernel.ui.viewmodel.FilesMessage
import cam.su.kernel.ui.viewmodel.FilesViewModel

@Composable
fun FilesPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<FilesViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalNavigator.current

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.start()
    }

    // back from the editor (or another app): the folder may have changed
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(isCurrentPage) {
        if (resumedOnce && isCurrentPage) viewModel.refresh()
        resumedOnce = true
        onPauseOrDispose { }
    }

    // MT: back drops the selection, then goes up; at "/" the tab lets go.
    val canGoBack = uiState.activePanel.selection.isNotEmpty() || uiState.activePanel.nav.parent != null
    BackHandler(enabled = isCurrentPage && canGoBack) { viewModel.back() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        Toast.makeText(context, messageText(context, message), Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is FilesEvent.Open -> startChooser(context, Intent(Intent.ACTION_VIEW).setDataAndType(event.uri, event.mime))

                is FilesEvent.Edit -> navigator.push(Route.TextEditor(event.path))

                is FilesEvent.Hex -> navigator.push(Route.HexViewer(event.path))

                // a real terminal: colors, prompts that wait for an answer, Ctrl+C
                is FilesEvent.Run -> navigator.push(Route.Terminal(FsPath.parent(event.path), event.path))

                is FilesEvent.Terminal -> navigator.push(Route.Terminal(event.cwd))

                is FilesEvent.Flash -> navigator.push(Route.Flash(FlashIt.FlashModules(listOf(Uri.fromFile(event.file)))))

                is FilesEvent.Share -> {
                    val uris = event.uris
                    val intent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    intent.type = "*/*"
                    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    startChooser(context, intent)
                }
            }
        }
    }

    FilesPagerMiuix(uiState, viewModel, bottomInnerPadding)
}

private fun startChooser(context: Context, intent: Intent) {
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.files_no_app, Toast.LENGTH_SHORT).show()
    }
}

private fun messageText(context: Context, message: FilesMessage): String = when (message) {
    is FilesMessage.Finished -> if (message.skipped > 0) {
        context.getString(R.string.files_done_skipped, message.items, message.skipped)
    } else {
        context.getString(R.string.files_done, message.items)
    }

    FilesMessage.Cancelled -> context.getString(R.string.files_cancelled)
    FilesMessage.Done -> context.getString(R.string.files_done_short)
    FilesMessage.Busy -> context.getString(R.string.files_op_busy)
    is FilesMessage.Text -> message.text
    is FilesMessage.Failed -> buildString {
        append(errnoText(context, message.errno))
        message.detail?.let { append("\n").append(it) }
    }
}

fun errnoText(context: Context, errno: Int): String = when (errno) {
    Errno.ENOENT -> context.getString(R.string.files_error_noent)
    Errno.EACCES -> context.getString(R.string.files_error_access)
    Errno.EPERM -> context.getString(R.string.files_error_perm)
    Errno.EEXIST -> context.getString(R.string.files_error_exist)
    Errno.ENOTDIR -> context.getString(R.string.files_error_notdir)
    Errno.EISDIR -> context.getString(R.string.files_error_isdir)
    Errno.EINVAL -> context.getString(R.string.files_error_inval)
    Errno.EROFS -> context.getString(R.string.files_error_rofs)
    Errno.ENOSPC -> context.getString(R.string.files_error_nospc)
    Errno.ENOTEMPTY -> context.getString(R.string.files_error_notempty)
    Errno.EIO -> context.getString(R.string.files_error_io)
    Errno.EFBIG -> context.getString(R.string.files_error_fbig)
    Errno.EXDEV -> context.getString(R.string.files_error_xdev)
    else -> context.getString(R.string.files_error_other, errno)
}

fun opTitle(context: Context, kind: OpKind): String = context.getString(
    when (kind) {
        OpKind.COPY -> R.string.files_op_copy
        OpKind.MOVE -> R.string.files_op_move
        OpKind.DELETE -> R.string.files_op_delete
        OpKind.EXTRACT -> R.string.files_op_extract
        OpKind.COMPRESS -> R.string.files_op_compress
        OpKind.BACKUP -> R.string.files_op_backup
        OpKind.FLASH -> R.string.files_op_flash
        OpKind.TRASH -> R.string.files_op_trash
        OpKind.RESTORE -> R.string.files_op_restore
        OpKind.PURGE -> R.string.files_op_delete
    },
)
