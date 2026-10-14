package cam.su.kernel.ui.screen.files

import cam.su.kernel.ui.component.bottombar.LocalNavSideInsets
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.AppView
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FileKind
import cam.su.kernel.files.FileKinds
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.Thumbnails
import cam.su.kernel.ui.component.ListPopupDefaults
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.component.glass.MorphIcon
import cam.su.kernel.ui.component.glass.morphAnchor
import cam.su.kernel.ui.component.glass.rememberMorphPopupState
import cam.su.kernel.ui.component.glass.GlassListPopup
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.util.shouldShowSplitPane
import cam.su.kernel.ui.viewmodel.FilesUiState
import cam.su.kernel.ui.viewmodel.FilesViewModel
import cam.su.kernel.ui.viewmodel.PanelUi
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Which dialog is up; at most one besides the operation dialogs. */
sealed interface FilesDialogState {
    data class Menu(val panel: Int, val entry: FileEntry) : FilesDialogState
    data class ConfirmCopy(val sources: List<String>, val move: Boolean, val dest: String) : FilesDialogState
    data class ConfirmDelete(val sources: List<String>) : FilesDialogState
    data class Rename(val entry: FileEntry) : FilesDialogState
    data class MoveTarget(val entry: FileEntry) : FilesDialogState
    data class Tools(val entry: FileEntry) : FilesDialogState
    data object Create : FilesDialogState
    data object Sort : FilesDialogState
    data class Permissions(val entry: FileEntry) : FilesDialogState
    data class Owner(val entry: FileEntry) : FilesDialogState
    data class Context(val entry: FileEntry) : FilesDialogState
    data class ApkOpen(val panel: Int, val entry: FileEntry) : FilesDialogState
    data class Compress(val sources: List<String>, val name: String) : FilesDialogState
    data object EditPath : FilesDialogState
    data class OpenAs(val entry: FileEntry) : FilesDialogState
    data object Search : FilesDialogState
    data class ConfirmBackup(val partition: cam.su.kernel.files.Partition) : FilesDialogState

    /** Flashing asks twice: [last] is the second time. */
    data class ConfirmFlash(val image: FileEntry, val partition: cam.su.kernel.files.Partition, val last: Boolean = false) : FilesDialogState
}

@Composable
fun FilesPagerMiuix(
    state: FilesUiState,
    vm: FilesViewModel,
    bottomInnerPadding: Dp,
) {
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val wide = shouldShowSplitPane()
    var dialog by remember { mutableStateOf<FilesDialogState?>(null) }
    var drawer by remember { mutableStateOf(false) }
    // composed after FilesPager's handler, so it wins while the drawer is open
    BackHandler(enabled = drawer) { drawer = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                BlurredBar(backdrop) {
                    Box {
                    SmallTopAppBar(
                        title = state.activePanel.nav.path.let { if (it.endsWith("/")) it else "$it/" },
                        color = barColor,
                        navigationIcon = {
                            // the drawer takes over from its button, like the menus grow out of theirs
                            val menuAlpha by animateFloatAsState(if (drawer) 0f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "drawerButton")
                            GlassIconButton(onClick = { drawer = true }, modifier = Modifier.graphicsLayer { alpha = menuAlpha }) {
                                Icon(Icons.Rounded.Menu, contentDescription = null, tint = colorScheme.onSurface)
                            }
                        },
                        actions = {
                            OverflowMenu(state, vm, onSort = { dialog = FilesDialogState.Sort }, onSearch = { dialog = FilesDialogState.Search })
                        },
                    )
                    // MT: tapping the path lets you type another one
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 96.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                dialog = FilesDialogState.EditPath
                            },
                    )
                    }
                }
            },
            popupHost = { },
            contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal).add(LocalNavSideInsets.current),
        ) { innerPadding ->
            Box(modifier = (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding(), bottom = bottomInnerPadding)
                        .windowInsetsPadding(LocalNavSideInsets.current),
                ) {
                    HeaderStats(state.activePanel, state.appNames)
                    if (!wide) PanelSwitcher(state, vm)
                    Row(modifier = Modifier.weight(1f)) {
                        val shown = if (wide) listOf(0, 1) else listOf(state.active)
                        shown.forEachIndexed { i, index ->
                            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colorScheme.dividerLine))
                            Box(modifier = Modifier.weight(1f)) {
                                key(index) {
                                    Panel(
                                        index, state, vm, wide,
                                        onMenu = { entry -> dialog = FilesDialogState.Menu(index, entry) },
                                        onApk = { entry -> dialog = FilesDialogState.ApkOpen(index, entry) },
                                        onOpenAs = { entry -> dialog = FilesDialogState.OpenAs(entry) },
                                    )
                                }
                            }
                        }
                    }
                }
                ToolbarButton(
                    state = state,
                    vm = vm,
                    onCreate = { dialog = FilesDialogState.Create },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = bottomInnerPadding + 12.dp),
                )
            }
        }

        FilesDrawer(visible = drawer, state = state, vm = vm, onClose = { drawer = false })
    }

    FilesDialogs(state, vm, dialog, onDialog = { dialog = it })
}

@Composable
private fun HeaderStats(panel: PanelUi, appNames: Map<Int, String>) {
    val parts = buildList {
        AppView.parse(panel.nav.path)?.let { (pid, _) ->
            add(stringResource(R.string.files_viewing_as, appNames[pid] ?: "pid $pid"))
        }
        add(stringResource(R.string.files_header_counts, panel.folders, panel.files))
        if (panel.selection.isNotEmpty()) add(stringResource(R.string.files_header_selected, panel.selection.size))
        panel.disk?.let { disk ->
            add(stringResource(R.string.files_header_disk, FileFormat.size(disk[0] - disk[1]), FileFormat.size(disk[0])))
        }
    }
    Text(
        text = parts.joinToString("   "),
        fontSize = 11.sp,
        color = colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun OverflowMenu(state: FilesUiState, vm: FilesViewModel, onSort: () -> Unit, onSearch: () -> Unit) {
    GlassButtonGroup {
        Box {
            var show by remember { mutableStateOf(false) }
            val menuMorph = rememberMorphPopupState()
            GlassIconButton(onClick = { show = true }, modifier = Modifier.morphAnchor(menuMorph)) {
                MorphIcon(menuMorph) { Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = colorScheme.onSurface) }
            }
            GlassListPopup(
                show = show,
                popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                morph = menuMorph,
                onDismissRequest = { show = false },
            ) {
                ListPopupColumn {
                    val items: List<Pair<String, () -> Unit>> = listOf(
                        stringResource(R.string.files_refresh) to { vm.refresh() },
                        stringResource(R.string.files_search) to onSearch,
                        stringResource(R.string.files_select_all) to { vm.selectAll() },
                        stringResource(R.string.files_menu_sort) to onSort,
                        (stringResource(R.string.files_show_hidden) + if (state.sort.showHidden) "  ✓" else "") to { vm.toggleHidden() },
                        stringResource(R.string.files_menu_add_bookmark) to { vm.addBookmark(state.activePanel.nav.path) },
                        stringResource(R.string.files_menu_set_home) to { vm.setHome() },
                        stringResource(R.string.files_menu_swap) to { vm.swapPanels() },
                    )
                    items.forEachIndexed { index, (label, action) ->
                        DropdownImpl(
                            text = label,
                            optionSize = items.size,
                            isSelected = false,
                            onSelectedIndexChange = {
                                show = false
                                action()
                            },
                            index = index,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelSwitcher(state: FilesUiState, vm: FilesViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.panels.forEachIndexed { index, panel ->
            val active = index == state.active
            GlassCard(
                modifier = Modifier.weight(1f),
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = if (active) CardDefaults.defaultColors(color = colorScheme.primaryContainer, contentColor = colorScheme.onPrimaryContainer) else null,
                onClick = { vm.setActive(index) },
            ) {
                Text(
                    text = "${index + 1} · ${FsPath.name(panel.nav.path).ifEmpty { "/" }}",
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    wide: Boolean,
    onMenu: (FileEntry) -> Unit,
    onApk: (FileEntry) -> Unit,
    onOpenAs: (FileEntry) -> Unit,
) {
    val panel = state.panels[index]
    val active = index == state.active
    val selecting = panel.selection.isNotEmpty()
    // keyed on what the rows show: the old folder stays up until the new one is listed
    val listState = rememberPanelListState(panel.loadedPath ?: panel.nav.path)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (wide && active) colorScheme.primary.copy(alpha = 0.09f) else Color.Transparent)
            // Any touch in a panel (tap, scroll, empty space) makes it the one the drawer, the
            // toolbar and the menus act on. Initial pass, nothing consumed: the list still scrolls.
            .pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    vm.setActive(index)
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical(),
            // room for the floating toolbar button
            contentPadding = PaddingValues(bottom = 80.dp),
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
                        val inArchive = Archives.isInside(entry.path)
                        when {
                            selecting -> vm.toggleSelection(index, entry.path)
                            entry.isDir -> vm.navigate(index, entry.path)
                            // a device, fifo or socket has nothing to open: its menu instead
                            entry.type == FileEntry.TYPE_OTHER -> onMenu(entry)
                            // MT: an APK asks Install / View; other archives open like folders
                            !inArchive && FileKinds.of(entry) == FileKind.APK -> onApk(entry)
                            !inArchive && Archives.canBrowse(entry.name) -> vm.browseArchive(index, entry)
                            // MT: text opens in its editor; unknown files ask how to open them
                            FileKinds.of(entry) == FileKind.TEXT -> vm.edit(entry)
                            FileKinds.of(entry) == FileKind.OTHER || FileKinds.of(entry) == FileKind.DISK_IMAGE -> onOpenAs(entry)
                            else -> vm.openWith(entry, FileKinds.mime(entry))
                        }
                    },
                    onMenu = {
                        vm.setActive(index)
                        onMenu(entry)
                    },
                    onSwipe = {
                        vm.setActive(index)
                        vm.toggleSelection(index, entry.path)
                    },
                )
            }
            val errno = panel.errorErrno
            if (!panel.loading && panel.entries.isEmpty() && errno != null) {
                item(key = "error") {
                    Text(
                        text = errnoText(LocalContext.current, errno),
                        color = colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                }
            }
        }
        if (panel.loading && panel.entries.isEmpty()) {
            InfiniteProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
        }
    }
}

private class PanelScroll(var path: String) {
    val positions = HashMap<String, Pair<Int, Int>>()
}

/**
 * Scrolled back to where it was when a folder is reopened. Applied in the same frame as the new
 * rows (requestScrollToItem before the next measure), so the list never shows the new folder
 * at the old one's position first.
 */
@Composable
private fun rememberPanelListState(path: String): LazyListState {
    val listState = rememberLazyListState()
    val scroll = remember { PanelScroll(path) }
    SideEffect {
        if (scroll.path != path) {
            scroll.positions[scroll.path] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            val (item, offset) = scroll.positions[path] ?: (0 to 0)
            listState.requestScrollToItem(item, offset)
            scroll.path = path
        }
    }
    return listState
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(FileKind.FOLDER, link = false)
        Spacer(Modifier.width(10.dp))
        Text("..", fontSize = 14.sp)
    }
}

private val dateFormat = SimpleDateFormat("yy-MM-dd HH:mm", Locale.ROOT)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntry, selected: Boolean, onOpen: () -> Unit, onMenu: () -> Unit, onSwipe: () -> Unit) {
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    val details = buildString {
        // folders only implied by paths inside an archive have no date
        if (entry.mtime > 0) append(dateFormat.format(Date(entry.mtime))).append("  ")
        // a link to a file shows the file's size, not the length of the link
        if (!entry.isDir) append(FileFormat.size(entry.contentSize))
        if (entry.isLink) append("  → ").append(entry.linkTarget ?: "?")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent)
            // MT: a sideways swipe on a row selects it
            .pointerInput(entry.path) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = { if (abs(dx) > swipeThreshold) onSwipe() },
                ) { change, amount ->
                    dx += amount
                    change.consume()
                }
            }
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // photos, APK icons and video frames, loaded as the row shows up
        val thumb by androidx.compose.runtime.produceState(Thumbnails.cached(entry), entry.path, entry.mtime, entry.contentSize) {
            if (value == null && Thumbnails.wants(entry)) value = Thumbnails.load(entry)
        }
        KindTile(FileKinds.of(entry), link = entry.isLink, broken = entry.linkBroken, thumb = thumb)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = details,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.linkBroken) colorScheme.error else colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun kindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> Color(0xFF37474F)
    FileKind.ARCHIVE -> Color(0xFF8D6E63)
    FileKind.APK -> Color(0xFF43A047)
    FileKind.IMAGE -> Color(0xFFAB47BC)
    FileKind.DISK_IMAGE -> Color(0xFF607D8B)
    FileKind.TEXT -> Color(0xFF1E88E5)
    FileKind.AUDIO -> Color(0xFFF4511E)
    FileKind.VIDEO -> Color(0xFFE53935)
    FileKind.OTHER -> Color(0xFF757575)
}

private fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Rounded.Folder
    FileKind.ARCHIVE -> Icons.Rounded.Inventory2
    FileKind.APK -> Icons.Rounded.Android
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.DISK_IMAGE -> Icons.Rounded.Storage
    FileKind.TEXT -> Icons.AutoMirrored.Rounded.TextSnippet
    FileKind.AUDIO -> Icons.Rounded.Audiotrack
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** MT's rounded square icon, colored by kind; a link badge for symlinks. */
@Composable
fun KindTile(kind: FileKind, link: Boolean, broken: Boolean = false, thumb: androidx.compose.ui.graphics.ImageBitmap? = null) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (broken) colorScheme.error else if (thumb != null && kind == FileKind.APK) Color.Transparent else kindColor(kind)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(kindIcon(kind), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        if (link) {
            Icon(
                Icons.Rounded.Link,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp)),
            )
        }
    }
}

/**
 * MT's bottom toolbar folded into one round button; tapped, the bar slides out to its left.
 * It opens by itself when a selection starts, since the selection tools live there.
 */
@Composable
private fun ToolbarButton(state: FilesUiState, vm: FilesViewModel, onCreate: () -> Unit, modifier: Modifier) {
    val panel = state.activePanel
    val selecting = panel.selection.isNotEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selecting) { if (selecting) expanded = true }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            GlassCard(cornerRadius = 28.dp, insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                Row {
                    if (selecting) {
                        ToolButton(Icons.Rounded.SelectAll, R.string.files_select_all) { vm.selectAll() }
                        ToolButton(Icons.Rounded.UnfoldMore, R.string.files_sel_range) { vm.selectRange() }
                        ToolButton(Icons.Rounded.Close, R.string.files_sel_exit) { vm.clearSelection() }
                        ToolButton(Icons.Rounded.Flip, R.string.files_sel_invert) { vm.invertSelection() }
                        ToolButton(Icons.Rounded.DoneAll, R.string.files_sel_same) { vm.selectSameType() }
                    } else {
                        ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, R.string.files_back, enabled = panel.nav.back.isNotEmpty()) { vm.historyBack() }
                        ToolButton(Icons.AutoMirrored.Rounded.ArrowForward, R.string.files_forward, enabled = panel.nav.forward.isNotEmpty()) { vm.historyForward() }
                        ToolButton(Icons.Rounded.Add, R.string.files_new, onClick = onCreate)
                        ToolButton(Icons.Rounded.SwapHoriz, R.string.files_sync) { vm.syncOther() }
                        ToolButton(Icons.Rounded.ArrowUpward, R.string.files_up, enabled = panel.nav.parent != null) { vm.goUp() }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        GlassCard(
            cornerRadius = 28.dp,
            insideMargin = PaddingValues(0.dp),
            colors = if (selecting) CardDefaults.defaultColors(color = colorScheme.primaryContainer, contentColor = colorScheme.onPrimaryContainer) else null,
            onClick = { expanded = !expanded },
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (expanded) Icons.Rounded.Close else Icons.Rounded.Apps,
                    contentDescription = null,
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(label), tint = colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** MT's left drawer: local roots with usage bars, then bookmarks. */
@Composable
private fun FilesDrawer(visible: Boolean, state: FilesUiState, vm: FilesViewModel, onClose: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible = visible,
        // iOS sheets: a spring in, a quicker critically damped spring out
        enter = slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { -it },
        exit = slideOutHorizontally(spring(dampingRatio = 1f, stiffness = 520f)) { -it },
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .padding(8.dp)
                // the glass alone samples a busy file list under it; keep the drawer readable
                .background(colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(24.dp)),
            insideMargin = PaddingValues(vertical = 8.dp),
        ) {
            val go = { path: String ->
                vm.navigateActive(path)
                onClose()
            }
            LazyColumn {
                item { DrawerSection(stringResource(R.string.files_drawer_local)) }
                item { DrawerRoot(Icons.Rounded.PhoneAndroid, stringResource(R.string.files_drawer_root), "/") { go("/") } }
                item { DrawerRoot(Icons.Rounded.SdCard, stringResource(R.string.files_drawer_storage), STORAGE) { go(STORAGE) } }
                item {
                    DrawerItem(Icons.Rounded.Home, stringResource(R.string.files_drawer_home), null, onClick = {
                        vm.goHome()
                        onClose()
                    })
                }
                item { DrawerSection(stringResource(R.string.files_drawer_tools)) }
                item {
                    DrawerItem(Icons.Rounded.Apps, stringResource(R.string.files_view_as_app), stringResource(R.string.files_view_as_app_summary), onClick = {
                        vm.chooseApp()
                        onClose()
                    })
                }
                item {
                    DrawerItem(Icons.Rounded.Terminal, stringResource(R.string.terminal), stringResource(R.string.terminal_summary), onClick = {
                        vm.openTerminal()
                        onClose()
                    })
                }
                item {
                    DrawerItem(Icons.Rounded.Delete, stringResource(R.string.files_trash), stringResource(R.string.files_trash_summary), onClick = {
                        vm.showTrash()
                        onClose()
                    })
                }
                item {
                    DrawerItem(Icons.Rounded.Storage, stringResource(R.string.files_partitions), stringResource(R.string.files_partitions_summary), onClick = {
                        vm.showPartitions()
                        onClose()
                    })
                }
                item { DrawerSection(stringResource(R.string.files_drawer_bookmarks)) }
                items(state.bookmarks, key = { it }) { path ->
                    DrawerItem(
                        Icons.Rounded.Bookmark,
                        FsPath.name(path).ifEmpty { "/" },
                        path,
                        onClick = { go(path) },
                        onLongClick = { vm.removeBookmark(path) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.files_drawer_restore),
                        fontSize = 12.sp,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.restoreBookmarks() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private const val STORAGE = "/storage/emulated/0"

@Composable
private fun DrawerSection(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerRoot(icon: ImageVector, title: String, path: String, onClick: () -> Unit) {
    var usage by remember { mutableStateOf<LongArray?>(null) }
    LaunchedEffect(path) { usage = runCatching { CamFileClient.statFs(path) }.getOrNull() }
    val disk = usage
    DrawerItem(
        icon,
        title,
        disk?.let { stringResource(R.string.files_drawer_used, FileFormat.size(it[0] - it[1]), FileFormat.size(it[1])) },
        progress = disk?.takeIf { it[0] > 0 }?.let { (it[0] - it[1]).toFloat() / it[0] },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    progress: Float? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.onSurface.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colorScheme.surface, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            progress?.let {
                Box(
                    modifier = Modifier
                        .padding(vertical = 3.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(colorScheme.dividerLine),
                ) {
                    Box(Modifier.fillMaxWidth(it).fillMaxHeight().background(colorScheme.primary))
                }
            }
            subtitle?.let { Text(it, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.StartEllipsis) }
        }
    }
}
