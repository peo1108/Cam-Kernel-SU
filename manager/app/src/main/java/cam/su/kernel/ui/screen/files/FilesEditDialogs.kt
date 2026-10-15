package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.ConflictChoice
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FileKind
import cam.su.kernel.files.FileKinds
import cam.su.kernel.files.FileMode
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.SortBy
import cam.su.kernel.ui.viewmodel.FilesUiState
import cam.su.kernel.ui.viewmodel.FilesViewModel
import cam.su.kernel.ui.viewmodel.OpUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Delete: to the trash (kept 7 days) unless "for good" is ticked. */
@Composable
internal fun DeleteDialog(sources: List<String>, vm: FilesViewModel, close: () -> Unit) {
    val canTrash = remember(sources) { vm.canTrash(sources) }
    var forGood by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_delete_title, sources.size), onDismiss = close) {
        val toTrash = canTrash && !forGood
        Text(
            stringResource(if (toTrash) R.string.files_delete_trash_note else R.string.files_delete_permanent_note),
            color = if (toTrash) colorScheme.onSurface else colorScheme.error,
        )
        if (canTrash) CheckRow(stringResource(R.string.files_delete_forever), forGood) { forGood = it }
        DialogButtons(close, stringResource(R.string.files_delete)) { vm.delete(sources, permanent = forGood) }
    }
}

private val trashDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

/** The trash: what was deleted in the last days, put back or removed for good. */
@Composable
internal fun TrashDialog(vm: FilesViewModel) {
    FilesDialog(show = true, title = stringResource(R.string.files_trash), onDismiss = vm::hideTrash) {
        val live by vm.uiState.collectAsState()
        val items = live.trash ?: return@FilesDialog
        Text(stringResource(R.string.files_trash_summary), fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        if (items.isEmpty()) {
            Text(stringResource(R.string.files_trash_empty), modifier = Modifier.padding(vertical = 20.dp))
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 340.dp).padding(top = 6.dp)) {
            items(items.size) { i ->
                val item = items[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindTile(if (item.isDir) FileKind.FOLDER else FileKinds.of(fileEntryOf(item)), link = false)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(
                            "${FsPath.parent(item.original)} · ${trashDate.format(Date(item.deletedAt))} · ${FileFormat.size(item.size)}",
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    }
                    TextButton(text = stringResource(R.string.files_trash_restore), onClick = { vm.restoreTrash(item) })
                    Spacer(Modifier.width(6.dp))
                    TextButton(text = stringResource(R.string.files_trash_purge), onClick = { vm.purgeTrash(listOf(item)) })
                }
            }
        }
        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.files_close), onClick = vm::hideTrash, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.files_trash_purge_all),
                enabled = items.isNotEmpty(),
                onClick = { vm.purgeTrash(items) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(color = colorScheme.error, textColor = colorScheme.onError),
            )
        }
    }
}

/** Just enough of a FileEntry for a trash row's icon. */
private fun fileEntryOf(item: cam.su.kernel.files.TrashItem) = FileEntry(
    name = item.name, path = item.path, type = FileEntry.TYPE_FILE, size = item.size, mtime = item.deletedAt,
    mode = 0, uid = 0, gid = 0, owner = "", group = "", context = null,
)

/** rwx for owner, group, others, then setuid / setgid / sticky; written as octal. */
@Composable
internal fun PermissionsDialog(entry: FileEntry, vm: FilesViewModel, close: () -> Unit) {
    var bits by remember(entry.path) { mutableIntStateOf(entry.mode and FileMode.PERM_MASK) }
    var recursive by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_perm_title), onDismiss = close) {
        Row {
            Spacer(Modifier.width(72.dp))
            listOf(R.string.files_perm_read, R.string.files_perm_write, R.string.files_perm_exec).forEach {
                Text(stringResource(it), fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            }
        }
        listOf(R.string.files_perm_owner to 6, R.string.files_perm_group to 3, R.string.files_perm_others to 0).forEach { (label, shift) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(stringResource(label), fontSize = 14.sp, modifier = Modifier.width(72.dp))
                for (bit in intArrayOf(4, 2, 1)) {
                    val mask = bit shl shift
                    Box(Modifier.weight(1f)) { BitBox(bits and mask != 0) { bits = bits xor mask } }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(stringResource(R.string.files_perm_special), fontSize = 14.sp, modifier = Modifier.width(72.dp))
            listOf("SUID" to 0x800, "SGID" to 0x400, "Sticky" to 0x200).forEach { (label, mask) ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BitBox(bits and mask != 0) { bits = bits xor mask }
                    Text(label, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Text(FileFormat.octal(bits), fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (entry.isDir) CheckRow(stringResource(R.string.files_recursive), recursive) { recursive = it }
        DialogButtons(close, stringResource(R.string.files_apply)) { vm.chmod(entry.path, bits, recursive) }
    }
}

@Composable
internal fun OwnerDialog(entry: FileEntry, vm: FilesViewModel, close: () -> Unit) {
    var uid by remember(entry.path) { mutableStateOf(entry.uid.toString()) }
    var gid by remember(entry.path) { mutableStateOf(entry.gid.toString()) }
    var recursive by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_owner_title), onDismiss = close) {
        TextField(value = uid, onValueChange = { uid = it.filter(Char::isDigit) }, label = "UID (${entry.owner})", singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TextField(value = gid, onValueChange = { gid = it.filter(Char::isDigit) }, label = "GID (${entry.group})", singleLine = true, modifier = Modifier.fillMaxWidth())
        if (entry.isDir) CheckRow(stringResource(R.string.files_recursive), recursive) { recursive = it }
        val u = uid.toIntOrNull()
        val g = gid.toIntOrNull()
        DialogButtons(close, stringResource(R.string.files_apply), enabled = u != null && g != null) { vm.chown(entry.path, u!!, g!!, recursive) }
    }
}

@Composable
internal fun ContextDialog(entry: FileEntry, vm: FilesViewModel, close: () -> Unit) {
    var context by remember(entry.path) { mutableStateOf(entry.context.orEmpty()) }
    var recursive by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_context_title), onDismiss = close) {
        TextField(value = context, onValueChange = { context = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (entry.isDir) CheckRow(stringResource(R.string.files_recursive), recursive) { recursive = it }
        DialogButtons(close, stringResource(R.string.files_apply), enabled = context.isNotBlank()) { vm.setContext(entry.path, context, recursive) }
    }
}

@Composable
internal fun SortDialog(state: FilesUiState, vm: FilesViewModel, close: () -> Unit) {
    var by by remember { mutableStateOf(state.sort.by) }
    var descending by remember { mutableStateOf(state.sort.descending) }
    FilesDialog(show = true, title = stringResource(R.string.files_menu_sort), onDismiss = close) {
        listOf(
            SortBy.NAME to R.string.files_sort_name,
            SortBy.SIZE to R.string.files_sort_size,
            SortBy.TIME to R.string.files_sort_time,
            SortBy.TYPE to R.string.files_sort_type,
        ).forEach { (option, label) ->
            CheckRow(stringResource(label), by == option, round = true) { by = option }
        }
        Spacer(Modifier.height(6.dp))
        CheckRow(stringResource(R.string.files_sort_desc), descending) { descending = it }
        DialogButtons(close, stringResource(android.R.string.ok)) { vm.setSort(by, descending) }
    }
}

@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    label: String? = null,
    allowInitial: Boolean = false,
    validate: (String) -> Boolean = FsPath::isValidName,
    buttons: List<Pair<String, (String) -> Unit>>,
) {
    var text by remember { mutableStateOf(initial) }
    val valid = validate(text) && (allowInitial || text != initial)
    FilesDialog(show = true, title = title, onDismiss = onDismiss) {
        TextField(
            value = text,
            onValueChange = { text = it },
            label = label ?: stringResource(R.string.files_create_name),
            singleLine = true,
            // File names and paths: Vietnamese IMEs (Laban, Gboard Telex) turn "data" into "dât"
            // even on URI fields; a password-type field switches them to plain input. No visual
            // transformation is set, so the text stays readable.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(android.R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
            buttons.forEach { (label, action) ->
                TextButton(
                    text = label,
                    enabled = valid,
                    onClick = {
                        action(text.trim())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
internal fun Confirm(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FilesDialog(show = true, title = title, onDismiss = onDismiss) {
        Text(text)
        DialogButtons(onDismiss, stringResource(android.R.string.ok), onConfirm = onConfirm)
    }
}

@Composable
internal fun OpDialog(show: Boolean, vm: FilesViewModel) {
    val context = LocalContext.current
    val kind = vm.uiState.collectAsState().value.op?.kind ?: return
    FilesDialog(show = show, title = opTitle(context, kind), onDismiss = null) {
        val live by vm.uiState.collectAsState()
        val op: OpUi = live.op ?: return@FilesDialog
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = if (op.total > 0) (op.done.toFloat() / op.total).coerceIn(0f, 1f) else null,
        )
        Spacer(Modifier.height(10.dp))
        val amount = if (op.kind == OpKind.DELETE) "${op.done} / ${op.total}" else "${FileFormat.size(op.done)} / ${FileFormat.size(op.total)}"
        Text(amount, fontSize = 13.sp)
        Text(op.current, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
        TextButton(text = stringResource(android.R.string.cancel), onClick = vm::cancelOp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

@Composable
internal fun ConflictDialog(vm: FilesViewModel) {
    FilesDialog(show = true, title = stringResource(R.string.files_conflict_title), onDismiss = null) {
        val live by vm.uiState.collectAsState()
        val conflict = live.op?.conflict ?: return@FilesDialog
        var applyAll by remember(conflict) { mutableStateOf(false) }
        val merge = conflict.srcIsDir && conflict.dstIsDir
        Text(stringResource(R.string.files_conflict_message, FsPath.name(conflict.dst), FsPath.parent(conflict.dst)))
        CheckRow(stringResource(R.string.files_conflict_apply_all), applyAll) { applyAll = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(R.string.files_conflict_skip), onClick = { vm.resolveConflict(ConflictChoice.SKIP, applyAll) }, modifier = Modifier.weight(1f))
            TextButton(text = stringResource(R.string.files_conflict_rename), onClick = { vm.resolveConflict(ConflictChoice.RENAME, applyAll) }, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(if (merge) R.string.files_conflict_merge else R.string.files_conflict_overwrite),
                onClick = { vm.resolveConflict(ConflictChoice.OVERWRITE, applyAll) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        TextButton(text = stringResource(android.R.string.cancel), onClick = vm::cancelOp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
