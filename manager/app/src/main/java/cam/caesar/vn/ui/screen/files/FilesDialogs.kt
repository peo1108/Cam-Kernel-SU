package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.Archives
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FileKind
import cam.su.kernel.files.FileKinds
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.Systemless
import cam.su.kernel.ui.component.ProgressDetails
import cam.su.kernel.ui.viewmodel.FilesUiState
import cam.su.kernel.ui.viewmodel.FilesViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

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

        is FilesDialogState.ConfirmDelete -> DeleteDialog(dialog.sources, vm, close)

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
            val readable = dialog.entry.isReadableFile()
            ListAction(stringResource(R.string.files_tool_symlink)) {
                vm.symlinkInOther(dialog.entry.path)
                close()
            }
            ListAction(stringResource(R.string.files_tool_checksum), enabled = readable) {
                close()
                vm.inspector.showChecksum(dialog.entry)
            }
            ListAction(stringResource(R.string.files_hex), enabled = readable) {
                close()
                vm.hex(dialog.entry)
            }
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
            ListAction(stringResource(R.string.files_apk_info)) {
                close()
                vm.inspector.showApkInfo(dialog.entry)
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
            if (FileKinds.isScript(dialog.entry)) {
                ListAction(stringResource(R.string.files_run)) {
                    close()
                    vm.runScript(dialog.entry)
                }
            }
            ListAction(stringResource(R.string.files_text_editor)) {
                close()
                vm.edit(dialog.entry)
            }
            // the hex viewer reads ranges of a file on disk; an entry inside an archive has none
            if (!Archives.isInside(dialog.entry.path)) {
                ListAction(stringResource(R.string.files_hex)) {
                    close()
                    vm.hex(dialog.entry)
                }
            }
            ListAction(stringResource(R.string.files_other_app)) {
                close()
                vm.openWith(dialog.entry, "*/*")
            }
        }

        FilesDialogState.Search -> SearchDialog(vm, close)

        is FilesDialogState.ConfirmBackup -> Confirm(
            title = stringResource(R.string.files_backup_title),
            text = stringResource(
                R.string.files_backup_confirm,
                dialog.partition.name,
                FileFormat.size(dialog.partition.size),
                cam.su.kernel.files.Partitions.BACKUP_DIR,
            ),
            onDismiss = close,
        ) { vm.system.backupPartition(dialog.partition.name) }

        is FilesDialogState.ConfirmFlash -> FlashConfirmDialog(dialog, vm, onDialog)

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
    if (state.checksum != null) ChecksumDialog(vm)
    if (state.override != null) OverrideDialog(vm)
    if (state.processes != null) ProcessDialog(vm)
    if (state.apkInfo != null) ApkInfoDialog(vm)
    if (state.partitions != null) PartitionsDialog(vm, onDialog)
    if (state.trash != null) TrashDialog(vm)

    FilesDialog(show = state.busy, title = null, onDismiss = null) {
        val live by vm.uiState.collectAsState()
        val progress = live.busyProgress
        if (progress != null) {
            Text(stringResource(R.string.files_preparing), modifier = Modifier.padding(bottom = 14.dp))
            ProgressDetails(done = progress.done, total = progress.total, label = progress.name)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfiniteProgressIndicator()
                Text(stringResource(R.string.processing), modifier = Modifier.padding(start = 12.dp))
            }
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
            // inside a zip: delete and rename rewrite the archive (tar stays read-only)
            if (inArchive && Archives.isZip(Archives.split(entry.path).first)) {
                add(GridCell(Icons.Rounded.Delete, stringResource(R.string.files_delete), onClick = { onDialog(FilesDialogState.ConfirmDelete(targets)) }))
                add(GridCell(Icons.Rounded.Edit, stringResource(R.string.files_rename), enabled = single, onClick = { onDialog(FilesDialogState.Rename(entry)) }))
            }
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
                        vm.inspector.showProperties(entry)
                    }),
                )
            }
            add(
                GridCell(Icons.Rounded.Share, stringResource(R.string.files_share), enabled = entries.any { it.isReadableFile() }, onClick = {
                    close()
                    vm.share(entries)
                }),
            )
            add(
                GridCell(Icons.Rounded.Check, stringResource(R.string.files_open_with), enabled = single && entry.isReadableFile(), onClick = {
                    onDialog(FilesDialogState.OpenAs(entry))
                }),
            )
            compareTarget(state, menu.panel, entry, entries)?.let { (left, right) ->
                add(
                    GridCell(Icons.Rounded.Difference, stringResource(R.string.files_compare), onClick = {
                        close()
                        vm.compare(left, right)
                    }),
                )
            }
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
            if (single && FileKinds.isScript(entry)) {
                add(
                    GridCell(Icons.Rounded.PlayArrow, stringResource(R.string.files_run), onClick = {
                        close()
                        vm.runScript(entry)
                    }),
                )
            }
            if (single && !inArchive && entry.isReadableFile() && FileKinds.extension(entry.name) == "img") {
                add(
                    GridCell(Icons.Rounded.SystemUpdateAlt, stringResource(R.string.files_flash), onClick = {
                        close()
                        vm.system.showPartitions(entry)
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
            if (single && !inArchive && !entry.isDir && Systemless.canOverride(entry.path)) {
                add(
                    GridCell(Icons.Rounded.Layers, stringResource(R.string.files_override), onClick = {
                        close()
                        vm.system.showOverride(entry)
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

/**
 * The two files "Compare" would open, left panel's first: two files selected in one panel, or
 * this file and the one selected in the other panel, or else the file of the same name there
 * (a module's copy against the system file it replaces).
 */
private fun compareTarget(state: FilesUiState, panel: Int, entry: FileEntry, entries: List<FileEntry>): Pair<String, String>? {
    if (entries.size == 2) return entries.takeIf { list -> list.all { it.isReadableFile() } }?.let { it[0].path to it[1].path }
    if (entries.size > 1 || !entry.isReadableFile()) return null
    val other = state.panels[1 - panel]
    val chosen = other.selection.singleOrNull()?.let { path -> other.entries.find { it.path == path } }
    val match = (chosen ?: other.entries.find { it.name == entry.name })?.takeIf { it.isReadableFile() && it.path != entry.path } ?: return null
    return if (panel == 0) entry.path to match.path else match.path to entry.path
}

/** A file whose bytes can be read: not a folder, device, fifo, socket or broken link. */
private fun FileEntry.isReadableFile() = !isDir && type != FileEntry.TYPE_OTHER && !(isLink && linkBroken)

private class GridCell(
    val icon: ImageVector,
    val label: String,
    val dot: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)
