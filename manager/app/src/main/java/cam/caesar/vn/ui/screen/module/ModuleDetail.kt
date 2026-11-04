package cam.su.kernel.ui.screen.module

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.data.model.Module
import cam.su.kernel.data.model.ModuleUpdateInfo
import cam.su.kernel.ui.component.dialog.rememberLoadingDialog
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassSwitch
import cam.su.kernel.ui.component.listdetail.PaneMotion
import cam.su.kernel.ui.component.listdetail.DetailButton
import cam.su.kernel.ui.component.listdetail.DetailPaneScaffold
import cam.su.kernel.ui.component.listdetail.DetailTwoColumnWidth
import cam.su.kernel.ui.component.listdetail.FlightPart
import cam.su.kernel.ui.component.listdetail.OverviewCard
import cam.su.kernel.ui.component.listdetail.OverviewLine
import cam.su.kernel.ui.component.listdetail.PaneHint
import cam.su.kernel.ui.component.listdetail.flightTarget
import cam.su.kernel.ui.component.listdetail.paneReveal
import cam.su.kernel.ui.theme.isInDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** A module's name as its row shows it; [compact] (a row beside a detail, the detail's header) keeps it to one line. */
@Composable
internal fun ModuleName(
    module: Module,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    Text(
        text = module.name,
        modifier = modifier,
        fontSize = 17.sp,
        fontWeight = FontWeight(550),
        color = colorScheme.onSurface,
        textDecoration = if (module.remove) TextDecoration.LineThrough else null,
        maxLines = if (compact) 1 else Int.MAX_VALUE,
        softWrap = !compact,
        onTextLayout = onTextLayout,
    )
}

/**
 * The picked module beside the list. Its card comes first as in the list (name, version, chips,
 * switch) with every button labelled; below, the whole description and what the module says about
 * itself. Holding Action or WebUI makes a shortcut, as on the list's buttons.
 */
@Composable
internal fun ModuleDetailPane(
    module: Module,
    updateInfo: ModuleUpdateInfo,
    actions: ModuleActions,
    motion: PaneMotion?,
    bottomPadding: Dp,
    onClose: () -> Unit,
    onAddShortcut: (ShortcutType) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val hasUpdate = updateInfo.downloadUrl.isNotEmpty()
    val onUpdate: () -> Unit = {
        scope.launch { loadingDialog.withLoading { actions.onRequestUpdateConfirmation(module, updateInfo) } }
    }
    DetailPaneScaffold(
        title = stringResource(R.string.module_detail_title),
        onClose = onClose,
        bottomPadding = bottomPadding,
        header = {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.paneReveal(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                        ModuleDetailTitle(module, motion)
                        Text(
                            text = "${stringResource(R.string.module_version)}: ${module.version} · " +
                                "${stringResource(R.string.module_author)}: ${module.author}",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                            fontWeight = FontWeight(550),
                            color = colorScheme.onSurfaceVariantSummary,
                            textDecoration = if (module.remove) TextDecoration.LineThrough else null,
                        )
                        module.preinitWarning()?.let {
                            Text(
                                text = stringResource(it),
                                fontSize = 12.sp,
                                fontWeight = FontWeight(550),
                                color = colorScheme.error,
                            )
                        }
                        ModuleStatusBadges(
                            module = module,
                            onRestoreBackup = { actions.onRestoreModule(module) },
                            onDisableModule = { id ->
                                scope.launch { loadingDialog.withLoading { actions.onDisableModule(id) } }
                            },
                            newVersion = updateInfo.version.takeIf { hasUpdate },
                            onUpdate = onUpdate,
                        )
                    }
                    GlassSwitch(
                        enabled = !module.update,
                        checked = module.enabled,
                        onCheckedChange = {
                            if (it != module.enabled) {
                                scope.launch { loadingDialog.withLoading { actions.onToggleModule(module) } }
                            }
                        },
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.paneReveal().padding(vertical = 10.dp),
                    thickness = 0.5.dp,
                    color = colorScheme.outline.copy(alpha = 0.5f),
                )
                ModuleDetailButtons(
                    modifier = Modifier.paneReveal(),
                    module = module,
                    hasUpdate = hasUpdate,
                    onExecute = { actions.onExecuteModuleAction(module) },
                    onOpenWebUi = { actions.onOpenWebUi(module) },
                    onAddShortcut = onAddShortcut,
                    onUpdate = onUpdate,
                    onUninstall = { actions.onRequestUninstallConfirmation(module) },
                    onUndoUninstall = {
                        scope.launch { loadingDialog.withLoading { actions.onUndoUninstallModule(module) } }
                    },
                )
            }
        },
    ) {
        item(key = "body") {
            val about = @Composable { ModuleDescriptionCard(module) }
            val facts = @Composable { ModuleFactsCard(module, updateInfo) }
            BoxWithConstraints {
                if (maxWidth >= DetailTwoColumnWidth) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { about() }
                        Column(Modifier.weight(1f)) { facts() }
                    }
                } else {
                    Column {
                        about()
                        facts()
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleDetailTitle(module: Module, motion: PaneMotion?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ModuleName(
            module = module,
            compact = true,
            modifier = Modifier
                .weight(1f, fill = false)
                .flightTarget(motion, module.id, FlightPart.Label)
                .basicMarquee(),
        )
        if (module.metamodule) {
            Text(
                text = "META",
                fontSize = 12.sp,
                color = colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colorScheme.tertiaryContainer.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                fontWeight = FontWeight(750),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The list card's buttons, each with its label: there is room for them here. */
@Composable
private fun ModuleDetailButtons(
    modifier: Modifier,
    module: Module,
    hasUpdate: Boolean,
    onExecute: () -> Unit,
    onOpenWebUi: () -> Unit,
    onAddShortcut: (ShortcutType) -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onUndoUninstall: () -> Unit,
) {
    val buttonBg = colorScheme.secondaryContainer.copy(alpha = 0.8f)
    val tint = colorScheme.onSurface.copy(alpha = if (isInDarkTheme()) 0.7f else 0.9f)
    // as in the list: no running or opening a module that is off or about to change
    val live = module.enabled && !module.remove && !module.update
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live && module.hasActionScript) {
            DetailButton(
                icon = Icons.Rounded.PlayArrow,
                label = stringResource(R.string.action),
                background = buttonBg,
                tint = tint,
                onClick = onExecute,
                onLongClick = { onAddShortcut(ShortcutType.Action) },
            )
        }
        if (live && module.hasWebUi) {
            DetailButton(
                icon = Icons.Rounded.Code,
                label = stringResource(R.string.open),
                background = buttonBg,
                tint = tint,
                onClick = onOpenWebUi,
                onLongClick = { onAddShortcut(ShortcutType.WebUI) },
            )
        }
        Spacer(Modifier.weight(1f))
        if (hasUpdate && !module.remove) {
            DetailButton(
                icon = MiuixIcons.UploadCloud,
                label = stringResource(R.string.module_update),
                background = colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                tint = colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                onClick = onUpdate,
            )
        }
        DetailButton(
            icon = if (module.remove) MiuixIcons.Undo else MiuixIcons.Delete,
            label = stringResource(if (module.remove) R.string.undo else R.string.uninstall),
            background = buttonBg,
            tint = tint,
            onClick = if (module.remove) onUndoUninstall else onUninstall,
        )
    }
}

@Composable
private fun ModuleDescriptionCard(module: Module) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = stringResource(R.string.module_detail_description),
            fontSize = 13.sp,
            fontWeight = FontWeight(600),
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = module.description.ifBlank { stringResource(R.string.module_detail_no_description) },
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = if (module.description.isBlank()) colorScheme.onSurfaceVariantSummary else colorScheme.onSurface,
        )
    }
}

@Composable
private fun ModuleFactsCard(module: Module, updateInfo: ModuleUpdateInfo) {
    val updateSource = when {
        updateInfo.fromLibrary -> stringResource(R.string.module_repos)
        module.updateJson.isNotBlank() -> remember(module.updateJson) { Uri.parse(module.updateJson).host } ?: module.updateJson
        else -> "—"
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        listOf(
            stringResource(R.string.module_detail_id) to module.id,
            stringResource(R.string.module_detail_version_code) to module.versionCode.toString(),
            stringResource(R.string.module_detail_update_source) to updateSource,
        ).forEach { (title, value) ->
            BasicComponent(
                title = title,
                endActions = {
                    Text(
                        text = value,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantActions,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                },
            )
        }
    }
}

/** What the Module pane shows while nothing is picked. */
@Composable
internal fun ModuleOverview(uiState: ModuleUiState, bottomPadding: Dp) {
    val modules = uiState.modules
    DetailPaneScaffold(
        title = stringResource(R.string.list_detail_overview),
        onClose = null,
        bottomPadding = bottomPadding,
    ) {
        item {
            OverviewCard(
                lines = listOf(
                    OverviewLine(stringResource(R.string.module_overview_enabled), "${modules.count { it.enabled }} / ${modules.size}"),
                    OverviewLine(
                        stringResource(R.string.module_badge_new_version),
                        uiState.updateInfo.count { it.value.downloadUrl.isNotBlank() }.toString(),
                    ),
                    OverviewLine(
                        stringResource(R.string.module_overview_attention),
                        modules.count { it.risk != null || it.conflicts.isNotEmpty() || (it.autoDisabled && !it.enabled) }.toString(),
                    ),
                ),
            )
            PaneHint(stringResource(R.string.module_overview_hint))
        }
    }
}
