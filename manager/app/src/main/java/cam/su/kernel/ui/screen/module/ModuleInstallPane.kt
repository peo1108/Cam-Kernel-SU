package cam.su.kernel.ui.screen.module

import android.app.Activity.RESULT_OK
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.data.model.Module
import cam.su.kernel.data.repository.isSoftRebootPreferred
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FileKind
import cam.su.kernel.hiding.ModuleRisk
import cam.su.kernel.ui.component.CamToast
import cam.su.kernel.ui.component.KeyEventBlocker
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.component.listdetail.DetailButton
import cam.su.kernel.ui.component.listdetail.DetailPaneFrame
import cam.su.kernel.ui.component.listdetail.DetailTwoColumnWidth
import cam.su.kernel.ui.component.listdetail.PaneHint
import cam.su.kernel.ui.component.listdetail.panePiece
import cam.su.kernel.ui.screen.files.KindTile
import cam.su.kernel.ui.screen.files.errnoText
import cam.su.kernel.ui.screen.flash.FlashLogCard
import cam.su.kernel.ui.screen.flash.FlashStatusCard
import cam.su.kernel.ui.screen.flash.flashStatusTitle
import cam.su.kernel.ui.util.module.pickedUris
import cam.su.kernel.ui.viewmodel.ModuleInstallStage
import cam.su.kernel.ui.viewmodel.ModuleInstallUi
import cam.su.kernel.ui.viewmodel.ModuleInstallViewModel
import cam.su.kernel.ui.viewmodel.ZipPick
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Installing modules in the pane beside the Module list: a browser that lists folders and zips, and
 * beside it what each picked zip holds, where the install starts; then the flash's log in the same
 * place. Upright the pane has no room, so only the flash shows (taking the list's place): a turn of
 * the tablet must not hide a running flash.
 */
@Composable
internal fun ModuleInstallPane(
    state: ModuleInstallUi,
    vm: ModuleInstallViewModel,
    modules: List<Module>,
    bottomPadding: Dp,
    onClose: () -> Unit,
) {
    if (state.stage == ModuleInstallStage.Picking) {
        InstallPicking(state, vm, modules, bottomPadding, onClose)
    } else {
        InstallFlash(state, vm, bottomPadding, onClose)
    }
}

@Composable
private fun InstallPicking(
    state: ModuleInstallUi,
    vm: ModuleInstallViewModel,
    modules: List<Module>,
    bottomPadding: Dp,
    onClose: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
        result.data?.pickedUris()?.let(vm::addUris)
    }
    DetailPaneFrame(
        title = stringResource(R.string.module_install_title),
        onClose = onClose,
        bottomPadding = bottomPadding,
        actions = {
            GlassIconButton(
                onClick = {
                    picker.launch(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        },
                    )
                },
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = stringResource(R.string.module_install_picker),
                    tint = colorScheme.onSurface,
                )
            }
        },
    ) { padding, scrolling ->
        val direction = LocalLayoutDirection.current
        val start = padding.calculateStartPadding(direction)
        val end = padding.calculateEndPadding(direction)
        val top = padding.calculateTopPadding()
        val bottom = padding.calculateBottomPadding()
        // every folder starts at its top
        val browserState = remember(state.path) { LazyListState() }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= DetailTwoColumnWidth) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = browserState,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(scrolling),
                        contentPadding = PaddingValues(start = start, top = top, bottom = bottom),
                        overscrollEffect = null,
                    ) { browser(state, vm) }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().then(scrolling),
                        contentPadding = PaddingValues(end = end, top = top, bottom = bottom),
                        overscrollEffect = null,
                    ) { preview(state, vm, modules) }
                }
            } else {
                // a narrow pane: what is picked under the browser
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = browserState,
                        modifier = Modifier.weight(1f).fillMaxWidth().then(scrolling),
                        contentPadding = PaddingValues(start = start, end = end, top = top),
                        overscrollEffect = null,
                    ) { browser(state, vm) }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().then(scrolling),
                        contentPadding = PaddingValues(start = start, end = end, top = 8.dp, bottom = bottom),
                        overscrollEffect = null,
                    ) { preview(state, vm, modules) }
                }
            }
        }
    }
}

private fun LazyListScope.browser(state: ModuleInstallUi, vm: ModuleInstallViewModel) {
    item(key = "path") {
        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            insideMargin = PaddingValues(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = vm::up, enabled = state.path != "/") {
                    val direction = LocalLayoutDirection.current
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.module_install_up),
                        modifier = Modifier.graphicsLayer { if (direction == LayoutDirection.Rtl) scaleX = -1f },
                        tint = colorScheme.onSurface,
                    )
                }
                Text(
                    text = state.path,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                GlassIconButton(onClick = { vm.navigate(ModuleInstallViewModel.DOWNLOADS) }) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.module_install_downloads), tint = colorScheme.onSurface)
                }
                Spacer(Modifier.width(6.dp))
                GlassIconButton(onClick = { vm.navigate(ModuleInstallViewModel.STORAGE) }) {
                    Icon(Icons.Rounded.SdStorage, contentDescription = stringResource(R.string.module_install_storage), tint = colorScheme.onSurface)
                }
            }
        }
    }
    items(state.entries, key = { it.path }) { entry ->
        BrowserRow(entry, picked = state.picks.any { it.key == entry.path }) {
            if (entry.isDir) vm.navigate(entry.path) else vm.toggle(entry)
        }
    }
    if (!state.listing && state.entries.isEmpty()) {
        item(key = "empty") {
            val errno = state.errno
            Text(
                text = if (errno != null) errnoText(LocalContext.current, errno) else stringResource(R.string.module_install_empty),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                fontSize = 14.sp,
                color = if (errno != null) colorScheme.error else colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BrowserRow(entry: FileEntry, picked: Boolean, onClick: () -> Unit) {
    val details = buildString {
        if (entry.mtime > 0) append(FileFormat.date(entry.mtime))
        if (!entry.isDir) append("  ").append(FileFormat.size(entry.contentSize))
    }
    Row(
        modifier = Modifier
            .panePiece()
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (picked) colorScheme.primary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(if (entry.isDir) FileKind.FOLDER else FileKind.ARCHIVE, link = entry.isLink, broken = entry.linkBroken)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(text = details, fontSize = 10.sp, maxLines = 1, color = colorScheme.onSurfaceVariantSummary)
        }
        if (picked) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

private fun LazyListScope.preview(state: ModuleInstallUi, vm: ModuleInstallViewModel, modules: List<Module>) {
    if (state.picks.isEmpty()) {
        item(key = "hint") { PaneHint(stringResource(R.string.module_install_hint)) }
        return
    }
    items(state.picks, key = { it.key }) { pick ->
        PickCard(
            pick = pick,
            installed = pick.prop?.let { prop -> modules.find { it.id == prop.id } },
            onRemove = { vm.remove(pick.key) },
            modifier = Modifier.animateItem(),
        )
    }
    item(key = "install") {
        val count = state.picks.count { it.installable }
        if (count > 0 && state.picks.none { it.loading }) {
            Row(
                modifier = Modifier.panePiece().fillMaxWidth().padding(horizontal = 12.dp).animateItem(),
                horizontalArrangement = Arrangement.End,
            ) {
                DetailButton(
                    icon = Icons.Rounded.Extension,
                    label = if (count == 1) stringResource(R.string.module_install) else stringResource(R.string.module_install_count, count),
                    onClick = vm::install,
                )
            }
        }
    }
}

@Composable
private fun PickCard(pick: ZipPick, installed: Module?, onRemove: () -> Unit, modifier: Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                val prop = pick.prop
                Text(
                    text = prop?.name ?: pick.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    pick.loading -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        InfiniteProgressIndicator(size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.module_install_reading), fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                    }

                    prop == null -> Text(
                        text = stringResource(if (pick.failed) R.string.module_install_read_failed else R.string.module_install_not_module),
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 13.sp,
                        color = colorScheme.error,
                    )

                    else -> {
                        Text(
                            text = "${stringResource(R.string.module_version)}: ${prop.version} · ${stringResource(R.string.module_author)}: ${prop.author}",
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight(550),
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = when {
                                installed == null -> stringResource(R.string.module_install_new)
                                installed.version == prop.version -> stringResource(R.string.module_install_same)
                                else -> stringResource(R.string.module_install_update, installed.version)
                            },
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 13.sp,
                            color = colorScheme.primary,
                        )
                        if (prop.description.isNotEmpty()) {
                            Text(
                                text = prop.description,
                                modifier = Modifier.padding(top = 6.dp),
                                fontSize = 14.sp,
                                color = colorScheme.onSurface,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        pick.risk?.let { hit ->
                            Text(
                                text = stringResource(if (pick.blocked) R.string.flash_risk_blocked else R.string.flash_risk_warn) +
                                    "\n" + stringResource(R.string.flash_risk_reason, hit.risk.reason()),
                                modifier = Modifier.padding(top = 8.dp),
                                fontSize = 13.sp,
                                color = if (hit.risk.level == ModuleRisk.Level.Block) colorScheme.error else colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
            GlassIconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.module_install_remove), tint = colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun InstallFlash(state: ModuleInstallUi, vm: ModuleInstallViewModel, bottomPadding: Dp, onClose: () -> Unit) {
    val context = LocalContext.current
    val running = state.stage == ModuleInstallStage.Flashing
    // a module's script may ask its question through the volume keys
    if (running) {
        KeyEventBlocker { it.key == Key.VolumeDown || it.key == Key.VolumeUp }
    }
    DetailPaneFrame(
        title = stringResource(flashStatusTitle(state.status)),
        onClose = onClose.takeIf { !running },
        bottomPadding = bottomPadding,
        actions = if (running) {
            null
        } else {
            {
                GlassIconButton(onClick = { vm.saveLog { CamToast.show(context, it) } }) {
                    Icon(MiuixIcons.Download, contentDescription = stringResource(R.string.save_log), tint = colorScheme.onSurface)
                }
            }
        },
    ) { padding, scrolling ->
        val direction = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            FlashStatusCard(
                status = state.status,
                title = state.picks.filter { it.installable }.joinToString(", ") { it.prop?.name ?: it.name },
                rebootLabel = if (state.showReboot) stringResource(if (isSoftRebootPreferred()) R.string.reboot_soft else R.string.reboot) else null,
                onReboot = vm::reboot,
                onDone = onClose,
            )
            FlashLogCard(state.log, scrolling)
            Spacer(Modifier.height(padding.calculateBottomPadding()))
        }
    }
}
