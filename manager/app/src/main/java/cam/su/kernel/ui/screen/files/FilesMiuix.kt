package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.ConflictChoice
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.SortBy
import cam.su.kernel.ui.component.ListPopupDefaults
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassDialog
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.component.glass.GlassListPopup
import cam.su.kernel.ui.component.glass.LocalGlassOverlayBackdrop
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.util.shouldShowSplitPane
import cam.su.kernel.ui.viewmodel.FilesUiState
import cam.su.kernel.ui.viewmodel.FilesViewModel
import cam.su.kernel.ui.viewmodel.OpUi
import cam.su.kernel.ui.viewmodel.PanelUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun FilesPagerMiuix(
    state: FilesUiState,
    vm: FilesViewModel,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val wide = shouldShowSplitPane()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                SmallTopAppBar(
                    title = stringResource(R.string.files),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = { TopActions(state, vm) },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).fillMaxSize()) {
            Column(modifier = Modifier.padding(top = innerPadding.calculateTopPadding())) {
                if (!wide) PanelSwitcher(state, vm)
                val listBottom = bottomInnerPadding + if (state.activePanel.selection.isNotEmpty()) 96.dp else 12.dp
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        for (i in 0..1) {
                            Box(modifier = Modifier.weight(1f)) {
                                Panel(i, state, vm, listBottom, scrollBehavior.nestedScrollConnection, swipeToSwitch = false)
                            }
                        }
                    }
                } else {
                    // Own list state per panel: switching panels must not carry the scroll over.
                    key(state.active) {
                        Panel(state.active, state, vm, listBottom, scrollBehavior.nestedScrollConnection, swipeToSwitch = true)
                    }
                }
            }
            if (state.activePanel.selection.isNotEmpty() && state.op == null) {
                SelectionBar(
                    state = state,
                    vm = vm,
                    onDelete = { confirmDelete = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomInnerPadding + 8.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }

    FilesDialog(
        show = confirmDelete,
        title = stringResource(R.string.files_delete_title, state.activePanel.selection.size),
        onDismiss = { confirmDelete = false },
    ) {
        Text(stringResource(R.string.files_delete_message))
        DialogButtons(
            dismiss = stringResource(android.R.string.cancel) to { confirmDelete = false },
            confirm = stringResource(R.string.files_delete) to {
                confirmDelete = false
                vm.deleteSelection()
            },
        )
    }

    state.op?.let { op ->
        OpDialog(op, vm)
        op.conflict?.let { ConflictDialog(it, vm) }
    }
}

@Composable
private fun TopActions(state: FilesUiState, vm: FilesViewModel) {
    GlassButtonGroup {
        Box {
            var showSort by remember { mutableStateOf(false) }
            GlassIconButton(onClick = { showSort = true }, holdDownState = showSort) {
                Icon(imageVector = MiuixIcons.Sort, tint = colorScheme.onSurface, contentDescription = null)
            }
            GlassListPopup(
                show = showSort,
                popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                onDismissRequest = { showSort = false },
            ) {
                ListPopupColumn {
                    val labels = listOf(
                        SortBy.NAME to R.string.files_sort_name,
                        SortBy.SIZE to R.string.files_sort_size,
                        SortBy.TIME to R.string.files_sort_time,
                        SortBy.TYPE to R.string.files_sort_type,
                    )
                    labels.forEachIndexed { index, (by, label) ->
                        val arrow = if (state.sort.by == by) (if (state.sort.descending) "  ↓" else "  ↑") else ""
                        DropdownImpl(
                            text = stringResource(label) + arrow,
                            optionSize = labels.size,
                            isSelected = state.sort.by == by,
                            onSelectedIndexChange = {
                                vm.setSort(by)
                                showSort = false
                            },
                            index = index,
                        )
                    }
                }
            }
        }
        GlassIconButton(onClick = vm::toggleHidden) {
            Icon(
                imageVector = if (state.sort.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                tint = colorScheme.onSurface,
                contentDescription = stringResource(R.string.files_show_hidden),
            )
        }
        GlassIconButton(onClick = vm::refresh) {
            Icon(imageVector = Icons.Rounded.Refresh, tint = colorScheme.onSurface, contentDescription = stringResource(R.string.files_refresh))
        }
    }
}

@Composable
private fun PanelSwitcher(state: FilesUiState, vm: FilesViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.panels.forEachIndexed { index, panel ->
            val active = index == state.active
            GlassCard(
                modifier = Modifier.weight(1f),
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = if (active) CardDefaults.defaultColors(color = colorScheme.primaryContainer, contentColor = colorScheme.onPrimaryContainer) else null,
                onClick = { vm.setActive(index) },
            ) {
                Text(
                    text = "${index + 1} · ${FsPath.name(panel.nav.path).ifEmpty { "/" }}",
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = panel.nav.path,
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
    }
}

@Composable
private fun Panel(
    index: Int,
    state: FilesUiState,
    vm: FilesViewModel,
    bottomPadding: Dp,
    nestedScroll: androidx.compose.ui.input.nestedscroll.NestedScrollConnection,
    swipeToSwitch: Boolean,
) {
    val panel = state.panels[index]
    val active = index == state.active
    val listState = rememberPanelListState(index, panel.nav.path)
    val swipeThreshold = with(LocalDensity.current) { 96.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // side by side: tint the panel that copy/move/delete act on
            .background(if (!swipeToSwitch && active) colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent)
            .then(
                if (swipeToSwitch) {
                    Modifier.pointerInput(index) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = { if (abs(dx) > swipeThreshold) vm.setActive(1 - index) },
                        ) { _, amount -> dx += amount }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Breadcrumb(
            path = panel.nav.path,
            highlighted = active,
            onClick = {
                vm.setActive(index)
                vm.navigate(index, it)
            },
        )
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(nestedScroll),
                contentPadding = PaddingValues(bottom = bottomPadding),
                overscrollEffect = null,
            ) {
                panel.nav.parent?.let { parent ->
                    item(key = "..") {
                        UpRow(onClick = {
                            vm.setActive(index)
                            vm.navigate(index, parent)
                        })
                    }
                }
                items(panel.entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.path in panel.selection,
                        onOpen = {
                            vm.setActive(index)
                            if (panel.selection.isEmpty()) vm.open(index, entry) else vm.toggleSelection(index, entry.path)
                        },
                        onSelect = {
                            vm.setActive(index)
                            vm.toggleSelection(index, entry.path)
                        },
                    )
                }
                if (!panel.loading && panel.entries.isEmpty()) {
                    item(key = "empty") {
                        val context = LocalContext.current
                        Text(
                            text = panel.errorErrno?.let { errnoText(context, it) } ?: stringResource(R.string.files_empty),
                            color = if (panel.errorErrno != null) colorScheme.error else colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                        )
                    }
                }
            }
            if (panel.loading && panel.entries.isEmpty()) {
                InfiniteProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            }
        }
    }
}

/** One list state per panel, scrolled back to where it was when a folder is reopened. */
@Composable
private fun rememberPanelListState(index: Int, path: String): LazyListState {
    val positions = remember(index) { HashMap<String, Pair<Int, Int>>() }
    val listState = rememberLazyListState()
    val current = remember(index) { mutableStateOf(path) }
    LaunchedEffect(path) {
        val previous = current.value
        if (previous != path) {
            positions[previous] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            current.value = path
            val (item, offset) = positions[path] ?: (0 to 0)
            listState.scrollToItem(item, offset)
        }
    }
    return listState
}

@Composable
private fun Breadcrumb(path: String, highlighted: Boolean, onClick: (String) -> Unit) {
    val crumbs = FileFormat.breadcrumb(path)
    val rowState = rememberLazyListState()
    LaunchedEffect(path) { rowState.scrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs) { i, (label, target) ->
            val last = i == crumbs.lastIndex
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last && highlighted) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClick(target) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            if (i > 0 && !last) Text("›", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
            if (i == 0 && crumbs.size > 1) Spacer(Modifier.width(2.dp))
        }
    }
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(36.dp).padding(4.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("..", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.files_up), fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

private val dateFormat = SimpleDateFormat("yy-MM-dd HH:mm", Locale.ROOT)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntry, selected: Boolean, onOpen: () -> Unit, onSelect: () -> Unit) {
    val icon: ImageVector = when {
        entry.isLink && entry.linkBroken -> Icons.Rounded.LinkOff
        entry.isDir -> Icons.Rounded.Folder
        entry.isLink -> Icons.Rounded.Link
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
    val iconTint = when {
        entry.isLink && entry.linkBroken -> colorScheme.error
        entry.isDir -> colorScheme.primary
        else -> colorScheme.onSurfaceVariantSummary
    }
    val details = buildString {
        if (!entry.isDir) append(FileFormat.size(entry.size)).append("  ")
        append(dateFormat.format(Date(entry.mtime))).append("  ")
        append(FileFormat.permissions(entry.mode))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) colorScheme.primary else Color.Transparent)
                .clickable(onClick = onSelect),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = if (selected) colorScheme.onPrimary else iconTint,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                color = if (entry.name.startsWith(".")) colorScheme.onSurfaceVariantSummary else colorScheme.onSurface,
            )
            if (entry.isLink) {
                Text(
                    text = "→ ${entry.linkTarget ?: "?"}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    color = if (entry.linkBroken) colorScheme.error else colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = details,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SelectionBar(state: FilesUiState, vm: FilesViewModel, onDelete: () -> Unit, modifier: Modifier) {
    val count = state.activePanel.selection.size
    val other = FsPath.name(state.otherPanel.nav.path).ifEmpty { "/" }
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.files_selected, count) + "  ·  " + stringResource(R.string.files_to_other, other),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons.Rounded.ContentCopy, stringResource(R.string.files_copy)) { vm.copySelection(move = false) }
            BarAction(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.files_move)) { vm.copySelection(move = true) }
            BarAction(Icons.Rounded.Delete, stringResource(R.string.files_delete), onDelete)
            BarAction(Icons.Rounded.DoneAll, stringResource(R.string.files_select_all)) { vm.selectAll(state.active) }
            BarAction(Icons.Rounded.Close, stringResource(android.R.string.cancel)) { vm.clearSelection(state.active) }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun OpDialog(op: OpUi, vm: FilesViewModel) {
    val context = LocalContext.current
    FilesDialog(show = op.conflict == null, title = opTitle(context, op.kind), onDismiss = null) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = if (op.total > 0) (op.done.toFloat() / op.total).coerceIn(0f, 1f) else null,
        )
        Spacer(Modifier.height(10.dp))
        val amount = if (op.kind == OpKind.DELETE) "${op.done} / ${op.total}" else "${FileFormat.size(op.done)} / ${FileFormat.size(op.total)}"
        Text(amount, fontSize = 13.sp)
        Text(
            text = op.current,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
        TextButton(
            text = stringResource(android.R.string.cancel),
            onClick = vm::cancelOp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
    }
}

@Composable
private fun ConflictDialog(conflict: cam.su.kernel.files.CamFileClient.OpEvent.Conflict, vm: FilesViewModel) {
    var applyAll by remember(conflict) { mutableStateOf(false) }
    val merge = conflict.srcIsDir && conflict.dstIsDir
    FilesDialog(show = true, title = stringResource(R.string.files_conflict_title), onDismiss = null) {
        Text(
            stringResource(
                R.string.files_conflict_message,
                FsPath.name(conflict.dst),
                FsPath.parent(conflict.dst),
            ),
        )
        CheckboxPreference(
            title = stringResource(R.string.files_conflict_apply_all),
            checked = applyAll,
            checkboxLocation = CheckboxLocation.Start,
            insideMargin = PaddingValues(vertical = 12.dp),
            onCheckedChange = { applyAll = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = stringResource(R.string.files_conflict_skip),
                onClick = { vm.resolveConflict(ConflictChoice.SKIP, applyAll) },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.files_conflict_rename),
                onClick = { vm.resolveConflict(ConflictChoice.RENAME, applyAll) },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(if (merge) R.string.files_conflict_merge else R.string.files_conflict_overwrite),
                onClick = { vm.resolveConflict(ConflictChoice.OVERWRITE, applyAll) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        TextButton(
            text = stringResource(android.R.string.cancel),
            onClick = vm::cancelOp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun DialogButtons(dismiss: Pair<String, () -> Unit>, confirm: Pair<String, () -> Unit>) {
    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(text = dismiss.first, onClick = dismiss.second, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm.first,
            onClick = confirm.second,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** Glass overlay inside glass pages, a plain window dialog otherwise (as DialogMiuix does). */
@Composable
private fun FilesDialog(
    show: Boolean,
    title: String,
    onDismiss: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = { Column(modifier = Modifier.fillMaxWidth()) { content() } }
    if (LocalGlassOverlayBackdrop.current != null) {
        GlassDialog(show = show, title = title, onDismissRequest = onDismiss, content = body)
    } else {
        WindowDialog(show = show, title = title, onDismissRequest = onDismiss, content = body)
    }
}
