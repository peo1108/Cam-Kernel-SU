package cam.su.kernel.ui.screen.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.R
import cam.su.kernel.files.Errno
import cam.su.kernel.files.OpKind
import cam.su.kernel.ui.viewmodel.FilesMessage
import cam.su.kernel.ui.viewmodel.FilesViewModel
import android.content.Context

@Composable
fun FilesPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<FilesViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.start()
    }

    val canGoBack = uiState.activePanel.selection.isNotEmpty() || uiState.activePanel.nav.back.isNotEmpty()
    BackHandler(enabled = isCurrentPage && canGoBack) { viewModel.back() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        Toast.makeText(context, messageText(context, message), Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    FilesPagerMiuix(uiState, viewModel, bottomInnerPadding)
}

private fun messageText(context: Context, message: FilesMessage): String = when (message) {
    is FilesMessage.Finished -> if (message.skipped > 0) {
        context.getString(R.string.files_done_skipped, message.items, message.skipped)
    } else {
        context.getString(R.string.files_done, message.items)
    }

    FilesMessage.Cancelled -> context.getString(R.string.files_cancelled)
    FilesMessage.NotYet -> context.getString(R.string.files_not_yet)
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
    else -> context.getString(R.string.files_error_other, errno)
}

fun opTitle(context: Context, kind: OpKind): String = context.getString(
    when (kind) {
        OpKind.COPY -> R.string.files_op_copy
        OpKind.MOVE -> R.string.files_op_move
        OpKind.DELETE -> R.string.files_op_delete
    },
)
