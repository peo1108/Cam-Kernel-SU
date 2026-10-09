package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassExpandableCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.component.statustag.StatusTag
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Items shown per finding; the rest are only counted. */
private const val ITEMS_SHOWN = 6

private val cardModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp)
    .padding(bottom = 12.dp)

@Composable
fun HidingCheckScreenMiuix(
    state: HidingCheckUiState,
    actions: HidingCheckActions,
) {
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.audit_title),
                    navigationIcon = {
                        GlassIconButton(onClick = actions.onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 16.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item { OverviewCard(state, actions) { dates.format(Date(it)) } }

                if (state.findings.isNotEmpty()) {
                    item { SmallTitle(text = stringResource(R.string.hiding_section_findings)) }
                    items(state.findings, key = { it.finding.id }) { FindingCard(it, actions) }
                }

                item {
                    SmallTitle(text = stringResource(R.string.hiding_section_fixes))
                    FixesCard(state, actions)
                }

                if (state.gone.isNotEmpty()) {
                    item {
                        SmallTitle(text = stringResource(R.string.hiding_section_gone))
                        GlassCard(modifier = cardModifier) {
                            state.gone.forEach { finding ->
                                BasicComponent(
                                    title = findingTitle(finding),
                                    summary = stringResource(R.string.hiding_gone_summary),
                                    startAction = { RowIcon(Icons.Rounded.CheckCircle, colorScheme.primary) },
                                )
                            }
                        }
                    }
                }

                if (state.ignored.isNotEmpty()) {
                    item {
                        SmallTitle(text = stringResource(R.string.hiding_section_ignored))
                        GlassCard(modifier = cardModifier) {
                            state.ignored.forEach { finding ->
                                BasicComponent(
                                    title = findingTitle(finding),
                                    summary = stringResource(R.string.hiding_restore_summary),
                                    startAction = { RowIcon(Icons.Rounded.Restore, colorScheme.onSurfaceVariantActions) },
                                    onClick = { actions.onRestore(finding.id) },
                                )
                            }
                        }
                    }
                }

                if (state.scanTime != null) {
                    item {
                        SmallTitle(text = stringResource(R.string.hiding_section_history))
                        GlassCard(modifier = cardModifier) {
                            BasicComponent(
                                title = stringResource(R.string.hiding_clear_history),
                                summary = stringResource(R.string.hiding_clear_history_summary),
                                startAction = { RowIcon(Icons.Rounded.DeleteSweep, colorScheme.onBackground) },
                                onClick = actions.onClearHistory,
                            )
                        }
                    }
                }

                item {
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(state: HidingCheckUiState, actions: HidingCheckActions, format: (Long) -> String) {
    val alarming = state.leaks > 0
    GlassCard(
        modifier = cardModifier,
        insideMargin = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (alarming) Icons.Rounded.Warning else Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = if (alarming) colorScheme.error else colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
            }
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = when {
                        state.scanning -> stringResource(R.string.audit_running)
                        state.scanTime == null -> stringResource(R.string.hiding_never_scanned)
                        alarming -> stringResource(R.string.hiding_status_leaks, state.leaks)
                        state.findings.isNotEmpty() -> stringResource(R.string.hiding_status_review, state.findings.size)
                        else -> stringResource(R.string.hiding_status_clean)
                    },
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight(550),
                )
                val lines = buildList {
                    state.scanTime?.let { add(stringResource(R.string.hiding_scanned_at, format(it))) }
                    when {
                        state.previousTime != null -> add(stringResource(R.string.hiding_compared_with, format(state.previousTime)))
                        state.scanTime != null -> add(stringResource(R.string.hiding_first_scan))
                    }
                }
                lines.forEach {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (state.scanFailed) {
                    Text(text = stringResource(R.string.audit_failed), fontSize = 12.sp, color = colorScheme.error)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.newCount > 0) {
                    StatusTag(
                        label = stringResource(R.string.hiding_tag_new, state.newCount),
                        backgroundColor = colorScheme.error,
                        contentColor = Color.White,
                    )
                } else if (alarming) {
                    StatusTag(
                        label = stringResource(R.string.hiding_tag_leaks, state.leaks),
                        backgroundColor = colorScheme.error.copy(alpha = 0.8f),
                        contentColor = Color.White,
                    )
                }
                if (state.gone.isNotEmpty()) {
                    StatusTag(
                        label = stringResource(R.string.hiding_tag_gone, state.gone.size),
                        backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                        contentColor = colorScheme.onPrimary,
                    )
                }
            }
        }
        if (state.rebootNeeded) {
            Text(
                text = stringResource(R.string.audit_reboot),
                fontSize = 12.sp,
                color = colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
        }
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)) {
            TextButton(
                text = stringResource(if (state.scanTime == null) R.string.audit_scan else R.string.audit_rescan),
                enabled = !state.scanning,
                onClick = actions.onScan,
                colors = if (state.pendingFixes.isEmpty()) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                modifier = Modifier.weight(1f),
            )
            if (state.pendingFixes.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.audit_apply, state.pendingFixes.size),
                    enabled = !state.scanning,
                    onClick = actions.onApplyFixes,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = stringResource(R.string.hiding_on_demand),
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FindingCard(checked: CheckedFinding, actions: HidingCheckActions) {
    var expanded by rememberSaveable(checked.finding.id) { mutableStateOf(false) }
    val finding = checked.finding
    val summary = buildList {
        add(stringResource(R.string.hiding_items, finding.items.size))
        if (checked.newItems.isNotEmpty()) add(stringResource(R.string.hiding_new_items, checked.newItems.size))
        add(stringResource(fixLabel(finding.fix)))
    }.joinToString(" · ")

    GlassExpandableCard(
        icon = if (finding.leak) Icons.Rounded.Warning else Icons.Rounded.Info,
        title = findingTitle(finding),
        summary = summary,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        header = checked.change?.let { change ->
            {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (change) {
                        FindingChange.NEW -> StatusTag(
                            label = stringResource(R.string.hiding_change_new),
                            backgroundColor = colorScheme.error,
                            contentColor = Color.White,
                        )
                        FindingChange.SAME -> StatusTag(
                            label = stringResource(R.string.hiding_change_same),
                            backgroundColor = colorScheme.secondaryContainer,
                            contentColor = colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
    ) {
        val shown = finding.items.take(ITEMS_SHOWN)
        val more = finding.items.size - shown.size
        shown.forEach { item ->
            val fresh = item in checked.newItems
            Text(
                text = if (fresh) "+ $item" else item,
                fontSize = 12.sp,
                color = if (fresh) colorScheme.error else colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 54.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
            )
        }
        if (more > 0) {
            Text(
                text = stringResource(R.string.audit_more, more),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 54.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "→ ${stringResource(fixLabel(finding.fix))}",
                fontSize = 12.sp,
                color = if (finding.fix != null) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.hiding_ignore),
                onClick = { actions.onIgnore(finding.id) },
            )
        }
    }
}

@Composable
private fun FixesCard(state: HidingCheckUiState, actions: HidingCheckActions) {
    GlassCard(modifier = cardModifier) {
        FixSwitch(
            name = "kernelUmount",
            title = stringResource(R.string.settings_kernel_umount),
            summary = stringResource(R.string.settings_kernel_umount_summary),
            icon = Icons.Rounded.LayersClear,
            state = state,
            actions = actions,
        )
        FixSwitch(
            name = "selinuxHide",
            title = stringResource(R.string.settings_selinux_hide),
            summary = stringResource(R.string.settings_selinux_hide_summary),
            icon = Icons.Rounded.Security,
            state = state,
            actions = actions,
        )
        FixSwitch(
            name = "hideBootloader",
            title = stringResource(R.string.features_bootloader),
            summary = stringResource(R.string.features_bootloader_enable_summary),
            icon = Icons.Rounded.VisibilityOff,
            state = state,
            actions = actions,
        )
    }
}

@Composable
private fun FixSwitch(
    name: String,
    title: String,
    summary: String,
    icon: ImageVector,
    state: HidingCheckUiState,
    actions: HidingCheckActions,
) {
    val fix = state.fix(name)
    val fixes = state.fixes(name)
    SwitchPreference(
        title = title,
        summary = if (fixes > 0 && !fix.enabled) stringResource(R.string.hiding_fix_count, fixes) else summary,
        startAction = { RowIcon(icon, if (fixes > 0 && !fix.enabled) colorScheme.error else colorScheme.onBackground) },
        enabled = fix.available,
        checked = fix.enabled,
        onCheckedChange = { actions.onSetFix(name, it) },
    )
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 6.dp))
}

@Composable
private fun findingTitle(finding: HidingAudit.Finding): String {
    val res = when (finding.id) {
        "moduleMounts" -> R.string.audit_module_mounts
        "camMounts" -> R.string.audit_cam_mounts
        "selinuxRules" -> R.string.audit_selinux_rules
        "maps" -> R.string.audit_maps
        "props" -> R.string.audit_props
        "bootArgs" -> R.string.audit_boot_args
        "lsposed" -> R.string.audit_lsposed
        "revanced" -> R.string.audit_revanced
        "customRom" -> R.string.audit_custom_rom
        "files" -> R.string.audit_files
        "selinux" -> R.string.audit_selinux
        "adb" -> R.string.audit_adb
        else -> null
    }
    return res?.let { stringResource(it) } ?: finding.id
}

private fun fixLabel(fix: String?): Int = when (fix) {
    "kernelUmount" -> R.string.audit_fix_kernel_umount
    "selinuxHide" -> R.string.audit_fix_selinux_hide
    "hideBootloader" -> R.string.audit_fix_hide_bootloader
    else -> R.string.audit_fix_none
}
