package me.weishu.kernelsu.ui.screen.susfs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassCard
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import me.weishu.kernelsu.ui.component.glass.GlassDropdownPreference
import me.weishu.kernelsu.ui.component.glass.GlassIconButton
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.BootconfigMode
import me.weishu.kernelsu.ui.util.HideSusMnts
import me.weishu.kernelsu.ui.util.KSTAT_FIELDS
import me.weishu.kernelsu.ui.util.KstatEntry
import me.weishu.kernelsu.ui.util.OpenRedirectEntry
import me.weishu.kernelsu.ui.util.RedirectStage
import me.weishu.kernelsu.ui.util.SusPathEntry
import me.weishu.kernelsu.ui.util.UnameStage
import me.weishu.kernelsu.ui.util.VoldAppData
import me.weishu.kernelsu.ui.util.getAutoBootconfig
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Which text editor dialog is open. */
private enum class Editor {
    UnameRelease, UnameVersion, Bootconfig, VbmetaSize, VbmetaDigest,
    SusPaths, SusPathLoops, SusMaps, TryUmounts, OpenRedirects, LegitMounts,
}

@Composable
internal fun SusfsSettingsMiuix(
    state: SusfsSettingsUiState,
    actions: SusfsSettingsActions,
    snackbarHost: SnackbarHostState,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var editor by rememberSaveable { mutableStateOf<Editor?>(null) }
    // -1: a new entry
    var kstatIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.susfs_settings),
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
                    actions = {
                        GlassIconButton(
                            onClick = actions.onSave,
                            enabled = state.dirty && !state.saving && state.available,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                tint = if (state.dirty) colorScheme.primary else colorScheme.onSurfaceVariantActions,
                                contentDescription = stringResource(R.string.susfs_save),
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
                item { StatusCard(state, actions) }
                if (state.available) {
                    item { GeneralCard(state, actions) }
                    item { UnameCard(state, actions) { editor = it } }
                    item { BootconfigCard(state, actions) { editor = it } }
                    item { HidingCard(state, actions) { editor = it } }
                    item { ListsCard(state) { editor = it } }
                    item { KstatCard(state, actions) { kstatIndex = it } }
                    item { PropsCard(state, actions) { editor = it } }
                    item { BackupCard(actions) }
                    item {
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            text = stringResource(if (state.saving) R.string.susfs_saving else R.string.susfs_save),
                            enabled = state.dirty && !state.saving,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = actions.onSave,
                        )
                    }
                }
                item {
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

    EditorDialogs(state, actions, editor) { editor = null }
    KstatDialog(
        index = kstatIndex,
        entries = state.edited.susKstats,
        onDismissRequest = { kstatIndex = null },
        onSave = { index, entry ->
            actions.onUpdate {
                copy(susKstats = if (index < 0) susKstats + entry else susKstats.toMutableList().also { it[index] = entry })
            }
        },
    )
}

// ---------------------------------------------------------------------------
// cards

@Composable
private fun StatusCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions) {
    val info = state.state.info
    var featuresShown by rememberSaveable { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            InfoRow(
                stringResource(R.string.susfs_version),
                info?.let { "${it.version} (${it.variant})" } ?: stringResource(R.string.gki_susfs_none),
            )
            InfoRow(
                stringResource(R.string.susfs_uname_now),
                "${state.state.currentRelease}\n${state.state.currentVersion}".trim().ifBlank { "-" },
            )
            InfoRow(
                stringResource(R.string.susfs_applied_by),
                stringResource(if (state.state.moduleActive) R.string.susfs_applied_by_module else R.string.susfs_applied_by_ksud),
            )
        }
        if (info != null) {
            BasicComponent(
                title = stringResource(R.string.susfs_features_count, info.features.size),
                onClick = { featuresShown = !featuresShown },
                endActions = { ExpandIcon(featuresShown) },
            )
            AnimatedVisibility(
                visible = featuresShown,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    info.features.forEach { Line("✓  $it") }
                }
            }
            ArrowPreference(
                title = stringResource(R.string.susfs_log),
                summary = stringResource(R.string.susfs_log_summary),
                onClick = actions.onShowLog,
            )
        }
    }
    when {
        state.loading -> Note(stringResource(R.string.susfs_loading))
        info == null -> Note(stringResource(R.string.susfs_unavailable), warning = true)
        state.state.moduleActive -> Note(stringResource(R.string.susfs_module_active), warning = true)
        else -> Note(stringResource(R.string.susfs_intro))
    }
}

@Composable
private fun GeneralCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions) {
    val c = state.edited
    SectionTitle(stringResource(R.string.susfs_section_general))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.susfs_enable_log),
            summary = stringResource(R.string.susfs_enable_log_summary),
            checked = c.enableLog,
            enabled = state.has("ENABLE_LOG"),
            onCheckedChange = { v -> actions.onUpdate { copy(enableLog = v) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_avc_spoofing),
            summary = stringResource(R.string.susfs_avc_spoofing_summary),
            checked = c.avcLogSpoofing,
            onCheckedChange = { v -> actions.onUpdate { copy(avcLogSpoofing = v) } },
        )
        val modes = HideSusMnts.entries
        GlassDropdownPreference(
            title = stringResource(R.string.susfs_hide_mnts),
            summary = stringResource(R.string.susfs_hide_mnts_summary),
            items = listOf(
                stringResource(R.string.susfs_off),
                stringResource(R.string.susfs_hide_mnts_always),
                stringResource(R.string.susfs_hide_mnts_until_boot),
            ),
            selectedIndex = modes.indexOf(c.hideSusMnts),
            enabled = state.has("SUS_MOUNT"),
            onSelectedIndexChange = { i -> actions.onUpdate { copy(hideSusMnts = modes[i]) } },
        )
    }
}

@Composable
private fun UnameCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions, open: (Editor) -> Unit) {
    val c = state.edited
    val enabled = state.has("SPOOF_UNAME")
    val real = stringResource(R.string.susfs_real_value)
    SectionTitle(stringResource(R.string.susfs_section_uname))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.susfs_uname_release),
            summary = c.unameRelease.ifEmpty { real },
            enabled = enabled,
            onClick = { open(Editor.UnameRelease) },
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_uname_version),
            summary = c.unameVersion.ifEmpty { real },
            enabled = enabled,
            onClick = { open(Editor.UnameVersion) },
        )
        val stages = UnameStage.entries
        GlassDropdownPreference(
            title = stringResource(R.string.susfs_apply_at),
            summary = stringResource(R.string.susfs_uname_stage_summary),
            items = listOf(stringResource(R.string.susfs_stage_boot_completed), stringResource(R.string.susfs_stage_post_fs_data)),
            selectedIndex = stages.indexOf(c.unameStage),
            enabled = enabled,
            onSelectedIndexChange = { i -> actions.onUpdate { copy(unameStage = stages[i]) } },
        )
        TextButton(
            text = stringResource(R.string.susfs_stock_build_date),
            enabled = enabled,
            onClick = actions.onUseStockBuild,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BootconfigCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions, open: (Editor) -> Unit) {
    val c = state.edited
    val enabled = state.has("SPOOF_CMDLINE_OR_BOOTCONFIG")
    SectionTitle(stringResource(R.string.susfs_section_bootconfig))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        val modes = BootconfigMode.entries
        GlassDropdownPreference(
            title = stringResource(R.string.susfs_bootconfig),
            summary = stringResource(R.string.susfs_bootconfig_summary),
            items = listOf(
                stringResource(R.string.susfs_off),
                stringResource(R.string.susfs_bootconfig_auto),
                stringResource(R.string.susfs_bootconfig_custom),
            ),
            selectedIndex = modes.indexOf(c.bootconfigMode),
            enabled = enabled,
            onSelectedIndexChange = { i -> actions.onUpdate { copy(bootconfigMode = modes[i]) } },
        )
        when (c.bootconfigMode) {
            BootconfigMode.Auto -> ArrowPreference(
                title = stringResource(R.string.susfs_bootconfig_preview),
                onClick = actions.onPreviewBootconfig,
            )

            BootconfigMode.Custom -> ArrowPreference(
                title = stringResource(R.string.susfs_bootconfig_edit),
                summary = c.fakeBootconfig.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: stringResource(R.string.susfs_empty),
                onClick = { open(Editor.Bootconfig) },
            )

            BootconfigMode.Off -> {}
        }
    }
}

@Composable
private fun HidingCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions, open: (Editor) -> Unit) {
    val c = state.edited
    val susPath = state.has("SUS_PATH")
    SectionTitle(stringResource(R.string.susfs_section_hiding))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.susfs_hide_loops),
            summary = stringResource(R.string.susfs_hide_loops_summary),
            checked = c.hideLoops,
            enabled = susPath,
            onCheckedChange = { v -> actions.onUpdate { copy(hideLoops = v) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_hide_vendor_sepolicy),
            summary = stringResource(R.string.susfs_hide_vendor_sepolicy_summary),
            checked = c.hideVendorSepolicy,
            enabled = state.has("SUS_KSTAT"),
            onCheckedChange = { v -> actions.onUpdate { copy(hideVendorSepolicy = v) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_hide_compat_matrix),
            summary = stringResource(R.string.susfs_hide_compat_matrix_summary),
            checked = c.hideCompatMatrix,
            enabled = state.has("SUS_KSTAT"),
            onCheckedChange = { v -> actions.onUpdate { copy(hideCompatMatrix = v) } },
        )
        GlassDropdownPreference(
            title = stringResource(R.string.susfs_hide_cusrom),
            summary = stringResource(R.string.susfs_hide_cusrom_summary),
            items = listOf(stringResource(R.string.susfs_off)) +
                (1..5).map { stringResource(R.string.susfs_level, it) },
            selectedIndex = c.hideCusrom.coerceIn(0, 5),
            enabled = susPath,
            onSelectedIndexChange = { i -> actions.onUpdate { copy(hideCusrom = i) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_hide_gapps),
            summary = stringResource(R.string.susfs_hide_gapps_summary),
            checked = c.hideGapps,
            enabled = susPath,
            onCheckedChange = { v -> actions.onUpdate { copy(hideGapps = v) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_hide_revanced),
            summary = stringResource(R.string.susfs_hide_revanced_summary),
            checked = c.hideRevanced,
            onCheckedChange = { v -> actions.onUpdate { copy(hideRevanced = v) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_hide_lsposed),
            summary = stringResource(R.string.susfs_hide_lsposed_summary),
            checked = c.forceHideLsposed,
            onCheckedChange = { v -> actions.onUpdate { copy(forceHideLsposed = v) } },
        )
        val vold = VoldAppData.entries
        GlassDropdownPreference(
            title = stringResource(R.string.susfs_emulate_vold),
            summary = stringResource(R.string.susfs_emulate_vold_summary),
            items = listOf(stringResource(R.string.susfs_off), "sus_path", "sus_path_loop"),
            selectedIndex = vold.indexOf(c.emulateVoldAppData),
            enabled = susPath,
            onSelectedIndexChange = { i -> actions.onUpdate { copy(emulateVoldAppData = vold[i]) } },
        )
        SwitchPreference(
            title = stringResource(R.string.susfs_auto_try_umount),
            summary = stringResource(R.string.susfs_auto_try_umount_summary),
            checked = c.autoTryUmount,
            onCheckedChange = { v -> actions.onUpdate { copy(autoTryUmount = v) } },
        )
        AnimatedVisibility(visible = c.autoTryUmount) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.susfs_skip_legit),
                    summary = stringResource(R.string.susfs_skip_legit_summary),
                    checked = c.skipLegitMounts,
                    onCheckedChange = { v -> actions.onUpdate { copy(skipLegitMounts = v) } },
                )
                ArrowPreference(
                    title = stringResource(R.string.susfs_legit_mounts),
                    summary = stringResource(R.string.susfs_entries, c.legitMounts.size),
                    enabled = c.skipLegitMounts,
                    onClick = { open(Editor.LegitMounts) },
                )
            }
        }
    }
}

@Composable
private fun ListsCard(state: SusfsSettingsUiState, open: (Editor) -> Unit) {
    val c = state.edited
    SectionTitle(stringResource(R.string.susfs_section_lists))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.susfs_sus_path),
            summary = stringResource(R.string.susfs_sus_path_summary, c.susPaths.size),
            enabled = state.has("SUS_PATH"),
            onClick = { open(Editor.SusPaths) },
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_sus_path_loop),
            summary = stringResource(R.string.susfs_sus_path_loop_summary, c.susPathLoops.size),
            enabled = state.has("SUS_PATH"),
            onClick = { open(Editor.SusPathLoops) },
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_sus_map),
            summary = stringResource(R.string.susfs_sus_map_summary, c.susMaps.size),
            enabled = state.has("SUS_MAP"),
            onClick = { open(Editor.SusMaps) },
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_try_umount),
            summary = stringResource(R.string.susfs_try_umount_summary, c.tryUmounts.size),
            onClick = { open(Editor.TryUmounts) },
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_open_redirect),
            summary = stringResource(R.string.susfs_open_redirect_summary, c.openRedirects.size),
            enabled = state.has("OPEN_REDIRECT"),
            onClick = { open(Editor.OpenRedirects) },
        )
    }
}

@Composable
private fun KstatCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions, edit: (Int) -> Unit) {
    val entries = state.edited.susKstats
    val enabled = state.has("SUS_KSTAT")
    var shown by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.susfs_section_kstat))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.susfs_sus_kstat),
            summary = stringResource(R.string.susfs_sus_kstat_summary, entries.size),
            enabled = enabled,
            onClick = { shown = !shown },
            endActions = { ExpandIcon(shown) },
        )
        AnimatedVisibility(
            visible = shown && enabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
                entries.forEachIndexed { index, entry ->
                    val spoofed = entry.fields.count { it != "default" }
                    BasicComponent(
                        title = entry.path,
                        summary = stringResource(R.string.susfs_kstat_fields, spoofed),
                        onClick = { edit(index) },
                        endActions = {
                            GlassIconButton(onClick = {
                                actions.onUpdate { copy(susKstats = susKstats.filterIndexed { i, _ -> i != index }) }
                            }) {
                                Icon(Icons.Rounded.Delete, tint = colorScheme.onSurfaceVariantActions, contentDescription = null)
                            }
                        },
                    )
                }
                TextButton(
                    text = stringResource(R.string.susfs_add),
                    onClick = { edit(-1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PropsCard(state: SusfsSettingsUiState, actions: SusfsSettingsActions, open: (Editor) -> Unit) {
    val c = state.edited
    SectionTitle(stringResource(R.string.susfs_section_props))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = stringResource(R.string.susfs_spoof_props),
            summary = stringResource(R.string.susfs_spoof_props_summary),
            checked = c.spoofProps,
            onCheckedChange = { v -> actions.onUpdate { copy(spoofProps = v) } },
        )
        AnimatedVisibility(visible = c.spoofProps) {
            Column {
                ArrowPreference(
                    title = stringResource(R.string.susfs_vbmeta_size),
                    summary = c.vbmetaSize.toString(),
                    onClick = { open(Editor.VbmetaSize) },
                )
                ArrowPreference(
                    title = stringResource(R.string.susfs_vbmeta_digest),
                    summary = c.vbmetaDigest.ifEmpty { stringResource(R.string.susfs_vbmeta_digest_none) },
                    onClick = { open(Editor.VbmetaDigest) },
                )
            }
        }
    }
}

@Composable
private fun BackupCard(actions: SusfsSettingsActions) {
    SectionTitle(stringResource(R.string.susfs_section_backup))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.susfs_export),
            summary = stringResource(R.string.susfs_export_summary),
            onClick = actions.onExport,
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_import),
            summary = stringResource(R.string.susfs_import_summary),
            onClick = actions.onImport,
        )
        ArrowPreference(
            title = stringResource(R.string.susfs_reset),
            summary = stringResource(R.string.susfs_reset_summary),
            onClick = actions.onReset,
        )
    }
}

// ---------------------------------------------------------------------------
// text editors: the lists use the module's one-entry-per-line formats

private fun lines(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

private fun susPathsText(list: List<SusPathEntry>) =
    list.joinToString("\n") { if (it.wait > 0) "${it.path} ${it.wait}" else it.path }

private fun parseSusPaths(text: String): List<SusPathEntry> = lines(text).map { line ->
    val parts = line.split(Regex("\\s+"))
    SusPathEntry(parts[0], parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 300) ?: 0)
}.distinctBy { it.path }

/** `<original> <redirected> <0 boot-completed | 1 service> [uid scheme]`, as in the module's sus_open_redirect.txt */
private fun redirectsText(list: List<OpenRedirectEntry>) = list.joinToString("\n") {
    "${it.target} ${it.redirect} ${if (it.stage == RedirectStage.Service) 1 else 0} ${it.uidScheme}"
}

private fun parseRedirects(text: String): List<OpenRedirectEntry> = lines(text).mapNotNull { line ->
    val parts = line.split(Regex("\\s+"))
    if (parts.size < 2) return@mapNotNull null
    OpenRedirectEntry(
        target = parts[0],
        redirect = parts[1],
        stage = if (parts.getOrNull(2) == "1") RedirectStage.Service else RedirectStage.BootCompleted,
        uidScheme = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(0, 4) ?: 2,
    )
}.distinctBy { it.target }

private fun pathsText(list: List<String>) = list.joinToString("\n")

private fun parsePaths(text: String): List<String> = lines(text).map { it.split(Regex("\\s+"))[0] }.distinct()

@Composable
private fun EditorDialogs(
    state: SusfsSettingsUiState,
    actions: SusfsSettingsActions,
    editor: Editor?,
    onDismissRequest: () -> Unit,
) {
    val c = state.edited
    val scope = rememberCoroutineScope()
    val update = actions.onUpdate
    when (editor) {
        null -> {}
        Editor.UnameRelease -> TextEditDialog(
            title = stringResource(R.string.susfs_uname_release),
            hint = stringResource(R.string.susfs_uname_hint, state.state.currentRelease),
            initial = c.unameRelease,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(unameRelease = v.trim()) } },
        )

        Editor.UnameVersion -> TextEditDialog(
            title = stringResource(R.string.susfs_uname_version),
            hint = stringResource(R.string.susfs_uname_hint, state.state.currentVersion),
            initial = c.unameVersion,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(unameVersion = v.trim()) } },
        )

        Editor.Bootconfig -> TextEditDialog(
            title = stringResource(R.string.susfs_bootconfig_custom),
            hint = stringResource(R.string.susfs_bootconfig_hint),
            initial = c.fakeBootconfig,
            multiline = true,
            extra = stringResource(R.string.susfs_bootconfig_generate) to { set: (String) -> Unit ->
                scope.launch { set(getAutoBootconfig()) }
            },
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(fakeBootconfig = v) } },
        )

        Editor.VbmetaSize -> TextEditDialog(
            title = stringResource(R.string.susfs_vbmeta_size),
            hint = "8192",
            initial = c.vbmetaSize.toString(),
            number = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(vbmetaSize = v.trim().toIntOrNull()?.takeIf { it > 0 } ?: 8192) } },
        )

        Editor.VbmetaDigest -> TextEditDialog(
            title = stringResource(R.string.susfs_vbmeta_digest),
            hint = stringResource(R.string.susfs_vbmeta_digest_hint),
            initial = c.vbmetaDigest,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(vbmetaDigest = v.trim().lowercase()) } },
        )

        Editor.SusPaths -> TextEditDialog(
            title = stringResource(R.string.susfs_sus_path),
            hint = stringResource(R.string.susfs_sus_path_hint),
            initial = susPathsText(c.susPaths),
            multiline = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(susPaths = parseSusPaths(v)) } },
        )

        Editor.SusPathLoops -> TextEditDialog(
            title = stringResource(R.string.susfs_sus_path_loop),
            hint = stringResource(R.string.susfs_sus_path_hint),
            initial = susPathsText(c.susPathLoops),
            multiline = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(susPathLoops = parseSusPaths(v)) } },
        )

        Editor.SusMaps -> TextEditDialog(
            title = stringResource(R.string.susfs_sus_map),
            hint = stringResource(R.string.susfs_sus_map_hint),
            initial = pathsText(c.susMaps),
            multiline = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(susMaps = parsePaths(v)) } },
        )

        Editor.TryUmounts -> TextEditDialog(
            title = stringResource(R.string.susfs_try_umount),
            hint = stringResource(R.string.susfs_try_umount_hint),
            initial = pathsText(c.tryUmounts),
            multiline = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(tryUmounts = parsePaths(v)) } },
        )

        Editor.OpenRedirects -> TextEditDialog(
            title = stringResource(R.string.susfs_open_redirect),
            hint = stringResource(R.string.susfs_open_redirect_hint),
            initial = redirectsText(c.openRedirects),
            multiline = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(openRedirects = parseRedirects(v)) } },
        )

        Editor.LegitMounts -> TextEditDialog(
            title = stringResource(R.string.susfs_legit_mounts),
            hint = stringResource(R.string.susfs_legit_mounts_hint),
            initial = pathsText(c.legitMounts),
            multiline = true,
            onDismissRequest = onDismissRequest,
            onConfirm = { v -> update { copy(legitMounts = parsePaths(v)) } },
        )
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    hint: String,
    initial: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
    multiline: Boolean = false,
    number: Boolean = false,
    /** a button that fills the field, e.g. from the device */
    extra: Pair<String, ((String) -> Unit) -> Unit>? = null,
) {
    var text by rememberSaveable(title) { mutableStateOf(initial) }
    GlassDialog(
        show = true,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = hint,
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = !multiline,
            textStyle = if (multiline) {
                top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            } else {
                top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main
            },
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Ascii),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (multiline) Modifier.heightIn(min = 160.dp, max = 360.dp) else Modifier),
        )
        if (extra != null) {
            TextButton(
                text = extra.first,
                onClick = { extra.second { text = it } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
        DialogButtons(
            onCancel = onDismissRequest,
            onConfirm = {
                onConfirm(text)
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun KstatDialog(
    index: Int?,
    entries: List<KstatEntry>,
    onDismissRequest: () -> Unit,
    onSave: (Int, KstatEntry) -> Unit,
) {
    if (index == null) return
    val start = entries.getOrNull(index) ?: KstatEntry("")
    var path by rememberSaveable(index) { mutableStateOf(start.path) }
    val fields = remember(index) {
        start.fields.map { mutableStateOf(if (it == "default") "" else it) }
    }
    GlassDialog(
        show = true,
        title = stringResource(R.string.susfs_sus_kstat),
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.susfs_kstat_hint),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            TextField(
                value = path,
                onValueChange = { path = it },
                label = stringResource(R.string.susfs_path),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
            KSTAT_FIELDS.forEachIndexed { i, name ->
                TextField(
                    value = fields[i].value,
                    onValueChange = { v -> fields[i].value = v.filter { it.isDigit() || it == '-' } },
                    label = "$name (default)",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )
            }
        }
        DialogButtons(
            onCancel = onDismissRequest,
            confirmEnabled = path.trim().startsWith("/"),
            onConfirm = {
                onSave(index, KstatEntry(path.trim(), fields.map { it.value.trim().ifEmpty { "default" } }))
                onDismissRequest()
            },
        )
    }
}

@Composable
internal fun TextSheetDialog(sheet: TextSheet?, onDismissRequest: () -> Unit) {
    if (sheet == null) return
    GlassDialog(
        show = true,
        title = sheet.title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = sheet.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
            )
        }
        TextButton(
            text = stringResource(android.R.string.ok),
            onClick = onDismissRequest,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )
    }
}

@Composable
private fun DialogButtons(onCancel: () -> Unit, onConfirm: () -> Unit, confirmEnabled: Boolean = true) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        TextButton(
            text = stringResource(android.R.string.cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(20.dp))
        TextButton(
            text = stringResource(R.string.confirm),
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

// ---------------------------------------------------------------------------
// small pieces

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
private fun Line(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (warning) colorScheme.error else colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight(600),
        color = colorScheme.onSurfaceVariantActions,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ExpandIcon(expanded: Boolean) {
    Icon(
        if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
        modifier = Modifier.size(16.dp),
        tint = colorScheme.onSurfaceVariantActions,
        contentDescription = stringResource(R.string.expand),
    )
}
