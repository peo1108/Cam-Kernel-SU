package me.weishu.kernelsu.ui.screen.gki

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassCard
import me.weishu.kernelsu.ui.component.glass.GlassDropdownPreference
import me.weishu.kernelsu.ui.component.glass.GlassIconButton
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.MoveFile
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun GkiInstallScreenMiuix(
    state: GkiInstallUiState,
    actions: GkiInstallActions,
    snackbarHost: SnackbarHostState,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.gki_install_title),
                    navigationIcon = {
                        GlassIconButton(onClick = actions.onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                tint = colorScheme.onSurface,
                                contentDescription = null,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        snackbarHost = { SnackbarHost(state = snackbarHost, modifier = Modifier.padding(bottom = 20.dp)) },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(top = 12.dp)
                    .padding(horizontal = 16.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    StatusCard(state)
                    MethodCard(state, actions)
                    SourceCards(state, actions)
                    AdvancedCard(state, actions)
                    RestoreCard(state, actions)
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        text = stringResource(R.string.install_next),
                        enabled = state.canInstall,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = actions.onNext,
                    )
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: GkiInstallUiState) {
    val status = state.status
    val susfs = status.susfs
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            InfoRow(stringResource(R.string.gki_label_kernel), status.kernelRelease.ifBlank { "-" })
            InfoRow(
                stringResource(R.string.gki_label_kmi),
                listOf(status.kmi, status.kmiTag).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "-" },
            )
            InfoRow(
                stringResource(R.string.gki_label_mode),
                stringResource(if (status.builtIn) R.string.gki_mode_value_builtin else R.string.gki_mode_value_lkm),
            )
            InfoRow(
                stringResource(R.string.gki_label_susfs),
                if (susfs != null) "${susfs.version} (${susfs.variant})" else stringResource(R.string.gki_susfs_none),
            )
        }
    }
    if (!status.supported) {
        Note(stringResource(R.string.gki_summary_unsupported), warning = true)
    } else {
        Note(stringResource(R.string.gki_flash_ak3_summary))
    }
}

@Composable
private fun MethodCard(state: GkiInstallUiState, actions: GkiInstallActions) {
    val methods = buildList {
        add(GkiMethod.Direct)
        add(GkiMethod.Local)
        if (state.status.abDevice) add(GkiMethod.Inactive)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            methods.forEach { method ->
                val summary = when (method) {
                    GkiMethod.Direct -> stringResource(R.string.gki_install_direct_summary, state.status.kmi.ifBlank { "?" })
                    GkiMethod.Local -> stringResource(R.string.gki_install_local_summary)
                    GkiMethod.Inactive -> stringResource(R.string.gki_install_inactive_summary)
                }
                val interactionSource = remember { MutableInteractionSource() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.method == method,
                            enabled = state.status.supported,
                            onValueChange = { actions.onSelectMethod(method) },
                            role = Role.RadioButton,
                            indication = LocalIndication.current,
                            interactionSource = interactionSource,
                        )
                ) {
                    CheckboxPreference(
                        title = stringResource(method.label),
                        summary = summary,
                        checked = state.method == method,
                        enabled = state.status.supported,
                        onCheckedChange = { actions.onSelectMethod(method) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCards(state: GkiInstallUiState, actions: GkiInstallActions) {
    AnimatedVisibility(
        visible = state.method == GkiMethod.Inactive,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            val sources = GkiSource.entries
            GlassDropdownPreference(
                items = sources.map { stringResource(it.label) },
                selectedIndex = sources.indexOf(state.source),
                title = stringResource(R.string.gki_source),
                onSelectedIndexChange = { actions.onSelectSource(sources[it]) },
                startAction = { RowIcon { Icon(MiuixIcons.ConvertFile, tint = colorScheme.onSurface, contentDescription = null) } },
            )
        }
    }
    AnimatedVisibility(
        visible = state.usesProjectBuild,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        val build = state.build
        val summary = when (val builds = state.builds) {
            BuildsState.Loading -> stringResource(R.string.gki_builds_loading)
            is BuildsState.Failed -> stringResource(R.string.gki_builds_error, builds.message)
            is BuildsState.Loaded -> when {
                build != null -> "${buildTitle(build)}\n${
                    buildSummary(build, latest = builds.builds.firstOrNull() == build)
                }"

                builds.builds.isEmpty() -> stringResource(R.string.gki_builds_empty, state.status.kmi)
                else -> stringResource(R.string.gki_build_none)
            }
        }
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            BasicComponent(
                title = stringResource(R.string.gki_build),
                summary = summary,
                onClick = actions.onPickBuild,
                startAction = { RowIcon { Icon(MiuixIcons.ConvertFile, tint = colorScheme.onSurface, contentDescription = null) } },
                endActions = { Chevron() },
            )
        }
    }
    AnimatedVisibility(
        visible = state.usesLocalZip,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            BasicComponent(
                title = stringResource(R.string.gki_local_zip),
                summary = state.localZipName ?: stringResource(R.string.gki_local_zip_none),
                onClick = actions.onPickLocalZip,
                startAction = { RowIcon { Icon(MiuixIcons.MoveFile, tint = colorScheme.onSurface, contentDescription = null) } },
                endActions = { Chevron() },
            )
        }
    }
}

@Composable
private fun AdvancedCard(state: GkiInstallUiState, actions: GkiInstallActions) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        BasicComponent(
            title = stringResource(R.string.advanced_options),
            onClick = actions.onToggleAdvanced,
            endActions = {
                Icon(
                    if (state.advancedShown) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onSurfaceVariantActions,
                    contentDescription = stringResource(R.string.expand),
                )
            },
        )
        AnimatedVisibility(
            visible = state.advancedShown,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                CheckboxPreference(
                    title = stringResource(R.string.gki_backup_boot),
                    summary = stringResource(R.string.gki_backup_boot_summary),
                    checked = state.backupBoot,
                    onCheckedChange = actions.onSetBackupBoot,
                )
            }
        }
    }
}

@Composable
private fun RestoreCard(state: GkiInstallUiState, actions: GkiInstallActions) {
    val context = LocalContext.current
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        BasicComponent(
            title = stringResource(R.string.gki_restore),
            summary = if (state.backups.isEmpty()) {
                stringResource(R.string.gki_restore_empty)
            } else {
                stringResource(R.string.gki_restore_count, state.backups.size)
            },
            onClick = actions.onToggleRestore,
            endActions = {
                Icon(
                    if (state.restoreShown) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onSurfaceVariantActions,
                    contentDescription = stringResource(R.string.expand),
                )
            },
        )
        AnimatedVisibility(
            visible = state.restoreShown,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                if (state.backups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.gki_restore_none),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                state.backups.forEach { backup ->
                    BasicComponent(
                        title = backupLabel(context, backup),
                        summary = stringResource(
                            R.string.gki_backup_summary,
                            backupDate(context, backup),
                            Formatter.formatShortFileSize(context, backup.size),
                        ),
                        onClick = { actions.onRestore(backup) },
                        endActions = { Chevron() },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 15.sp, modifier = Modifier.weight(0.35f))
        Text(
            text = value,
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (warning) colorScheme.error else colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    )
}

@Composable
private fun RowIcon(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(end = 12.dp)) { content() }
}

@Composable
private fun Chevron() {
    val layoutDirection = LocalLayoutDirection.current
    Icon(
        modifier = Modifier
            .size(width = 10.dp, height = 16.dp)
            .graphicsLayer { scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f },
        imageVector = MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        tint = colorScheme.onSurfaceVariantActions,
    )
}
