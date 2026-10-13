package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.Archives
import cam.su.kernel.files.ConflictChoice
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FileKind
import cam.su.kernel.files.FileKinds
import cam.su.kernel.files.FileMode
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.SortBy
import cam.su.kernel.ui.component.glass.GlassDialog
import cam.su.kernel.ui.component.glass.LocalGlassOverlayBackdrop
import cam.su.kernel.ui.viewmodel.FilesUiState
import cam.su.kernel.ui.viewmodel.FilesViewModel
import cam.su.kernel.ui.viewmodel.OpUi
import cam.su.kernel.ui.viewmodel.PropertiesUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilesDialogs(state: FilesUiState, vm: FilesViewModel, dialog: FilesDialogState?, onDialog: (FilesDialogState?) -> Unit) {
    val close = { onDialog(null) }
    when (dialog) {
        is FilesDialogState.Menu -> ActionGrid(state, vm, dialog, onDialog)
        is FilesDialogState.ConfirmCopy -> {
            val what = dialog.sources.singleOrNull()?.let { FsPath.name(it) } ?: stringResource(R.string.files_n_items, dialog.sources.size)
            val dest = dialog.dest.let { if (it == "/") it else "$it/" }
            val extracting = Archives.isInside(dialog.sources.first())
            Confirm(
                title = stringResource(
                    when {
                        extracting -> R.string.files_extract
                        dialog.move -> R.string.files_move
                        else -> R.string.files_copy
                    },
                ),
                text = stringResource(
                    when {
                        extracting -> R.string.files_extract_confirm
                        dialog.move -> R.string.files_move_confirm
                        else -> R.string.files_copy_confirm
                    },
                    what,
                    dest,
                ),
                onDismiss = close,
            ) { vm.copy(dialog.sources, dialog.move, dialog.dest) }
        }

        is FilesDialogState.ConfirmDelete -> Confirm(
            title = stringResource(R.string.files_delete_title, dialog.sources.size),
            text = stringResource(R.string.files_delete_message),
            onDismiss = close,
        ) { vm.delete(dialog.sources) }

        is FilesDialogState.Rename -> NameDialog(
            title = stringResource(R.string.files_rename),
            initial = dialog.entry.name,
            onDismiss = close,
            buttons = listOf(stringResource(android.R.string.ok) to { name: String -> vm.rename(dialog.entry.path, name) }),
        )

        is FilesDialogState.MoveTarget -> NameDialog(
            title = stringResource(R.string.files_move_target),
            initial = FsPath.parent(dialog.entry.path),
            validate = { it.isNotBlank() },
            onDismiss = close,
            buttons = listOf(stringResource(android.R.string.ok) to { target: String -> vm.moveTo(dialog.entry.path, target) }),
        )

        FilesDialogState.Create -> NameDialog(
            title = stringResource(R.string.files_create),
            initial = "",
            onDismiss = close,
            buttons = listOf(
                stringResource(R.string.files_create_file) to { name: String -> vm.create(name, folder = false) },
                stringResource(R.string.files_create_folder) to { name: String -> vm.create(name, folder = true) },
            ),
        )

        is FilesDialogState.Tools -> FilesDialog(show = true, title = stringResource(R.string.files_tools), onDismiss = close) {
            ListAction(stringResource(R.string.files_tool_symlink)) {
                vm.symlinkInOther(dialog.entry.path)
                close()
            }
            ListAction(stringResource(R.string.files_tool_checksum), enabled = false) {}
        }

        is FilesDialogState.ApkOpen -> FilesDialog(show = true, title = stringResource(R.string.files_open_with), onDismiss = close) {
            ListAction(stringResource(R.string.files_install)) {
                close()
                vm.install(dialog.entry)
            }
            ListAction(stringResource(R.string.files_view)) {
                close()
                vm.browseArchive(dialog.panel, dialog.entry)
            }
            ListAction(stringResource(R.string.files_other_app)) {
                close()
                vm.openWith(dialog.entry, "application/vnd.android.package-archive")
            }
        }

        is FilesDialogState.Compress -> NameDialog(
            title = stringResource(R.string.files_compress),
            initial = dialog.name,
            allowInitial = true,
            onDismiss = close,
            buttons = listOf(stringResource(android.R.string.ok) to { name: String -> vm.compress(dialog.sources, name) }),
        )

        FilesDialogState.EditPath -> NameDialog(
            title = stringResource(R.string.files_go_to),
            initial = state.activePanel.nav.path,
            label = stringResource(R.string.files_path),
            validate = { it.trim().startsWith("/") },
            allowInitial = true,
            onDismiss = close,
            buttons = listOf(stringResource(android.R.string.ok) to { path: String -> vm.goToPath(path) }),
        )

        is FilesDialogState.OpenAs -> FilesDialog(show = true, title = stringResource(R.string.files_open_with), onDismiss = close) {
            ListAction(stringResource(R.string.files_text_editor)) {
                close()
                vm.edit(dialog.entry)
            }
            ListAction(stringResource(R.string.files_other_app)) {
                close()
                vm.openWith(dialog.entry, "*/*")
            }
        }

        FilesDialogState.Sort -> SortDialog(state, vm, close)
        is FilesDialogState.Permissions -> PermissionsDialog(dialog.entry, vm, close)
        is FilesDialogState.Owner -> OwnerDialog(dialog.entry, vm, close)
        is FilesDialogState.Context -> ContextDialog(dialog.entry, vm, close)
        null -> Unit
    }

    val sub = dialog is FilesDialogState.Permissions || dialog is FilesDialogState.Owner || dialog is FilesDialogState.Context
    if (state.properties != null && !sub) PropertiesDialog(vm, onDialog)

    // Miuix overlay dialogs keep the content lambda they were shown with, so these read the
    // live state inside it rather than values captured here.
    FilesDialog(show = state.busy, title = null, onDismiss = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfiniteProgressIndicator()
            Text(stringResource(R.string.processing), modifier = Modifier.padding(start = 12.dp))
        }
    }

    state.op?.let { op ->
        OpDialog(show = op.conflict == null, vm = vm)
        if (op.conflict != null) ConflictDialog(vm)
    }
}

/** MT's long-press menu: a two column grid; • items also do something on long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionGrid(state: FilesUiState, vm: FilesViewModel, menu: FilesDialogState.Menu, onDialog: (FilesDialogState?) -> Unit) {
    val entry = menu.entry
    val targets = vm.targetsFor(menu.panel, entry)
    val single = targets.size == 1
    val other = state.panels[1 - menu.panel].nav.path
    val entries = state.panels[menu.panel].entries.filter { it.path in targets }
    val close = { onDialog(null) }

    FilesDialog(show = true, title = null, onDismiss = close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.files_menu_hint),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = close),
            )
        }
        Text(
            text = if (single) entry.name else stringResource(R.string.files_n_items, targets.size),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        )
        val inArchive = Archives.isInside(entry.path)
        val kind = FileKinds.of(entry)
        val archiveFile = !inArchive && !entry.isDir && Archives.canBrowse(entry.name)
        val cells = buildList {
            add(
                GridCell(
                    Icons.Rounded.ContentCopy,
                    stringResource(if (inArchive) R.string.files_extract_to else R.string.files_copy_to),
                    dot = !inArchive,
                    onClick = { onDialog(FilesDialogState.ConfirmCopy(targets, move = false, dest = other)) },
                    onLongClick = if (inArchive) null else ({
                        vm.copyHere(targets)
                        close()
                    }),
                ),
            )
            if (!inArchive) {
                add(
                    GridCell(
                        Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.files_move_to), dot = true,
                        onClick = { onDialog(FilesDialogState.ConfirmCopy(targets, move = true, dest = other)) },
                        onLongClick = if (single) ({ onDialog(FilesDialogState.MoveTarget(entry)) }) else null,
                    ),
                )
                add(GridCell(Icons.Rounded.Delete, stringResource(R.string.files_delete), onClick = { onDialog(FilesDialogState.ConfirmDelete(targets)) }))
                add(GridCell(Icons.Rounded.Edit, stringResource(R.string.files_rename), enabled = single, onClick = { onDialog(FilesDialogState.Rename(entry)) }))
                add(GridCell(Icons.Rounded.Build, stringResource(R.string.files_tools), enabled = single, onClick = { onDialog(FilesDialogState.Tools(entry)) }))
                add(
                    GridCell(Icons.Rounded.Archive, stringResource(R.string.files_compress), onClick = {
                        val base = if (single) entry.name else FsPath.name(FsPath.parent(entry.path)).ifEmpty { "archive" }
                        onDialog(FilesDialogState.Compress(targets, "$base.zip"))
                    }),
                )
                add(
                    GridCell(Icons.Rounded.Info, stringResource(R.string.files_properties), enabled = single, onClick = {
                        close()
                        vm.showProperties(entry)
                    }),
                )
            }
            add(
                GridCell(Icons.Rounded.Share, stringResource(R.string.files_share), enabled = entries.any { !it.isDir }, onClick = {
                    close()
                    vm.share(entries)
                }),
            )
            add(
                GridCell(Icons.Rounded.Check, stringResource(R.string.files_open_with), enabled = single && !entry.isDir, onClick = {
                    onDialog(FilesDialogState.OpenAs(entry))
                }),
            )
            if (!inArchive) {
                add(
                    GridCell(Icons.Rounded.Bookmark, stringResource(R.string.files_menu_add_bookmark), onClick = {
                        close()
                        vm.addBookmark(if (entry.isDir) entry.path else FsPath.parent(entry.path))
                    }),
                )
            }
            if (archiveFile) {
                add(
                    GridCell(Icons.Rounded.Unarchive, stringResource(R.string.files_extract_to), onClick = {
                        close()
                        vm.extract(targets, other)
                    }),
                )
            }
            if (single && !inArchive && kind == FileKind.APK) {
                add(
                    GridCell(Icons.Rounded.InstallMobile, stringResource(R.string.files_install), onClick = {
                        close()
                        vm.install(entry)
                    }),
                )
            }
            if (single && !inArchive && entry.name.endsWith(".zip", ignoreCase = true)) {
                add(
                    GridCell(Icons.Rounded.Extension, stringResource(R.string.files_install_module), onClick = {
                        close()
                        vm.flashModule(entry)
                    }),
                )
            }
        }
        cells.chunked(2).forEach { row ->
            Row {
                row.forEach { cell ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .combinedClickable(enabled = cell.enabled, onClick = cell.onClick, onLongClick = cell.onLongClick)
                            .alpha(if (cell.enabled) 1f else 0.35f)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(cell.icon, contentDescription = null, tint = colorScheme.onSurface, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(cell.label, fontSize = 15.sp)
                        if (cell.dot) {
                            Box(
                                Modifier
                                    .padding(start = 4.dp)
                                    .size(5.dp)
                                    .background(colorScheme.primary, CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

private class GridCell(
    val icon: ImageVector,
    val label: String,
    val dot: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

private val fullDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

@Composable
private fun PropertiesDialog(vm: FilesViewModel, onDialog: (FilesDialogState?) -> Unit) {
    var more by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_properties), onDismiss = vm::hideProperties) {
        val live by vm.uiState.collectAsState()
        val props: PropertiesUi = live.properties ?: return@FilesDialog
        val e = props.entry
        PropRow(R.string.files_prop_name, e.name)
        PropRow(R.string.files_prop_parent, FsPath.parent(e.path).let { if (it == "/") it else "$it/" })
        PropRow(
            R.string.files_prop_type,
            stringResource(
                when (e.type) {
                    FileEntry.TYPE_DIR -> R.string.files_type_folder
                    FileEntry.TYPE_FILE -> R.string.files_type_file
                    FileEntry.TYPE_LINK -> R.string.files_type_link
                    else -> R.string.files_type_other
                },
            ),
        )
        e.linkTarget?.let { PropRow(R.string.files_prop_link, it) }
        val measure = props.measure
        PropRow(
            R.string.files_prop_size,
            when {
                measure != null -> "${FileFormat.size(measure[0])} (${measure[0]})"
                e.isDir -> stringResource(R.string.files_computing)
                else -> "${FileFormat.size(e.size)} (${e.size})"
            },
        )
        PropRow(R.string.files_prop_modified, fullDate.format(Date(e.mtime)))
        Spacer(Modifier.height(6.dp))
        PropRow(R.string.files_prop_perms, "${FileFormat.permissions(e.mode)}(${FileFormat.octal(e.mode).trimStart('0').padStart(3, '0')})")
        PropRow(R.string.files_prop_owner, "${e.owner} (${e.uid})")
        PropRow(R.string.files_prop_group, "${e.group} (${e.gid})")
        e.context?.let { PropRow(R.string.files_prop_context, it) }
        if (e.isDir && measure != null) {
            Spacer(Modifier.height(6.dp))
            PropRow(R.string.files_prop_files, measure[1].toString())
            PropRow(R.string.files_prop_folders, measure[2].toString())
        }
        Row(modifier = Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.files_prop_more), onClick = { more = !more }, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.files_close),
                onClick = vm::hideProperties,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        if (more) {
            if (!e.isLink) ListAction(stringResource(R.string.files_perm_title)) { onDialog(FilesDialogState.Permissions(e)) }
            ListAction(stringResource(R.string.files_owner_title)) { onDialog(FilesDialogState.Owner(e)) }
            if (!e.isLink) ListAction(stringResource(R.string.files_context_title)) { onDialog(FilesDialogState.Context(e)) }
        }
    }
}

@Composable
private fun PropRow(label: Int, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(stringResource(label), fontSize = 14.sp, modifier = Modifier.widthIn(min = 96.dp).padding(end = 8.dp), color = colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 14.sp)
    }
}

/** rwx for owner, group, others, then setuid / setgid / sticky; written as octal. */
@Composable
private fun PermissionsDialog(entry: FileEntry, vm: FilesViewModel, close: () -> Unit) {
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
private fun OwnerDialog(entry: FileEntry, vm: FilesViewModel, close: () -> Unit) {
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
private fun ContextDialog(entry: FileEntry, vm: FilesViewModel, close: () -> Unit) {
    var context by remember(entry.path) { mutableStateOf(entry.context.orEmpty()) }
    var recursive by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_context_title), onDismiss = close) {
        TextField(value = context, onValueChange = { context = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (entry.isDir) CheckRow(stringResource(R.string.files_recursive), recursive) { recursive = it }
        DialogButtons(close, stringResource(R.string.files_apply), enabled = context.isNotBlank()) { vm.setContext(entry.path, context, recursive) }
    }
}

@Composable
private fun SortDialog(state: FilesUiState, vm: FilesViewModel, close: () -> Unit) {
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
private fun NameDialog(
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
private fun Confirm(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FilesDialog(show = true, title = title, onDismiss = onDismiss) {
        Text(text)
        DialogButtons(onDismiss, stringResource(android.R.string.ok), onConfirm = onConfirm)
    }
}

@Composable
private fun OpDialog(show: Boolean, vm: FilesViewModel) {
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
private fun ConflictDialog(vm: FilesViewModel) {
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

@Composable
private fun DialogButtons(onDismiss: () -> Unit, confirm: String, enabled: Boolean = true, onConfirm: () -> Unit) {
    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(text = stringResource(android.R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm,
            enabled = enabled,
            onClick = {
                onConfirm()
                onDismiss()
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun ListAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, round: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BitBox(checked, round = round) { onChange(!checked) }
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun BitBox(checked: Boolean, round: Boolean = false, onToggle: () -> Unit) {
    val shape = if (round) CircleShape else RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (checked) colorScheme.primary else Color.Transparent)
            .border(1.5.dp, if (checked) colorScheme.primary else colorScheme.onSurfaceVariantSummary, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** Glass overlay inside glass pages, a plain window dialog otherwise (as DialogMiuix does). */
@Composable
fun FilesDialog(show: Boolean, title: String?, onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    val body: @Composable () -> Unit = { Column(modifier = Modifier.fillMaxWidth()) { content() } }
    if (LocalGlassOverlayBackdrop.current != null) {
        GlassDialog(show = show, title = title, onDismissRequest = onDismiss, content = body)
    } else {
        WindowDialog(show = show, title = title, onDismissRequest = onDismiss, content = body)
    }
}
