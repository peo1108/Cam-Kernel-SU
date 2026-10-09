package cam.su.kernel.ui.screen.features

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.data.model.ConflictKind
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.model.PropCheck
import cam.su.kernel.ui.component.glass.GlassDropdownPreference
import cam.su.kernel.ui.component.glass.GlassExpandableCard
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun FeaturesPagerMiuix(
    state: FeaturesUiState,
    actions: FeaturesActions,
    bottomInnerPadding: Dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.features),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item { Spacer(Modifier.height(12.dp)) }
                item { BootGuardCard(state, actions) }
                item { ConflictCard(state, actions) }
                item { HideBootloaderCard(state, actions) }
                item { HidingAuditCard(state, actions) }
                item { Spacer(Modifier.height(bottomInnerPadding)) }
            }
        }
    }
}

@Composable
private fun BootGuardCard(state: FeaturesUiState, actions: FeaturesActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val bootGuard = state.bootGuard
    val failures = (bootGuard.threshold - 1).coerceIn(1, 4)

    GlassExpandableCard(
        icon = Icons.Rounded.HealthAndSafety,
        title = stringResource(R.string.features_boot_guard),
        summary = if (bootGuard.enabled) {
            stringResource(R.string.features_boot_guard_on, failures)
        } else {
            stringResource(R.string.features_off)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        SwitchPreference(
            title = stringResource(R.string.features_boot_guard_enable),
            summary = stringResource(R.string.features_boot_guard_enable_summary),
            checked = bootGuard.enabled,
            onCheckedChange = actions.onSetBootGuardEnabled,
        )
        AnimatedVisibility(visible = bootGuard.enabled) {
            Column {
                GlassDropdownPreference(
                    title = stringResource(R.string.features_boot_guard_failures),
                    items = listOf("1", "2", "3", "4"),
                    selectedIndex = failures - 1,
                    onSelectedIndexChange = { actions.onSetBootGuardFailures(it + 1) },
                )
                GlassDropdownPreference(
                    title = stringResource(R.string.features_boot_guard_mode),
                    items = listOf(
                        stringResource(R.string.features_boot_guard_mode_suspects),
                        stringResource(R.string.features_boot_guard_mode_all),
                    ),
                    selectedIndex = if (bootGuard.disableAll) 1 else 0,
                    onSelectedIndexChange = { actions.onSetBootGuardDisableAll(it == 1) },
                )
            }
        }
        if (bootGuard.autoDisabled.isNotEmpty()) {
            SectionTitle(stringResource(R.string.boot_guard_dialog_title))
            bootGuard.autoDisabled.forEach { id ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = id, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        text = stringResource(R.string.boot_guard_reenable),
                        onClick = { actions.onReenableModule(id) },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            TextButton(
                text = stringResource(R.string.boot_guard_dismiss),
                onClick = actions.onDismissBootGuard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ConflictCard(state: FeaturesUiState, actions: FeaturesActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val conflicts = state.conflicts

    GlassExpandableCard(
        icon = Icons.Rounded.Layers,
        title = stringResource(R.string.features_conflicts),
        summary = when {
            !state.conflictDetection -> stringResource(R.string.features_off)
            conflicts.isEmpty() -> stringResource(R.string.features_conflicts_none)
            else -> stringResource(R.string.features_conflicts_count, conflicts.size)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        SwitchPreference(
            title = stringResource(R.string.features_conflicts_enable),
            summary = stringResource(R.string.features_conflicts_enable_summary),
            checked = state.conflictDetection,
            onCheckedChange = actions.onSetConflictDetection,
        )
        AnimatedVisibility(visible = state.conflictDetection) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.features_conflicts_warn),
                    checked = state.conflictWarnOnFlash,
                    onCheckedChange = actions.onSetConflictWarnOnFlash,
                )
                SwitchPreference(
                    title = stringResource(R.string.features_conflicts_props),
                    checked = state.conflictIncludeProps,
                    onCheckedChange = actions.onSetConflictIncludeProps,
                )
                if (conflicts.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.features_conflicts_count, conflicts.size))
                    conflicts.forEach { conflict ->
                        val res = when (conflict.kind) {
                            ConflictKind.File -> R.string.features_conflict_file
                            ConflictKind.Replace -> R.string.features_conflict_replace
                            ConflictKind.Prop -> R.string.features_conflict_prop
                        }
                        Text(
                            text = stringResource(res, conflict.path, conflict.modules.joinToString(", ")),
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                TextButton(
                    text = stringResource(
                        if (state.scanning) R.string.features_conflicts_scanning else R.string.features_conflicts_rescan
                    ),
                    enabled = !state.scanning,
                    onClick = actions.onRescanConflicts,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HideBootloaderCard(state: FeaturesUiState, actions: FeaturesActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val status = state.hideBootloader

    GlassExpandableCard(
        icon = Icons.Rounded.VisibilityOff,
        title = stringResource(R.string.features_bootloader),
        summary = when {
            status.leaks > 0 -> stringResource(R.string.features_bootloader_leaks, status.leaks)
            status.enabled -> stringResource(R.string.features_bootloader_on)
            else -> stringResource(R.string.features_bootloader_clean)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        SwitchPreference(
            title = stringResource(R.string.features_bootloader),
            summary = stringResource(R.string.features_bootloader_enable_summary),
            checked = status.enabled,
            onCheckedChange = actions.onSetHideBootloader,
        )
        CheckSection(stringResource(R.string.features_bootloader_props), status.props)
        CheckSection(stringResource(R.string.features_bootloader_bootconfig), status.bootconfig)

        SectionTitle(stringResource(R.string.features_attestation))
        if (state.attestationChecked) {
            val info = state.attestation
            if (info == null) {
                CheckLine(stringResource(R.string.features_attestation_unreadable), ok = false)
            } else {
                val level = when (info.securityLevel) {
                    1 -> "TEE"
                    2 -> "StrongBox"
                    else -> stringResource(R.string.features_attestation_software)
                }
                val bootState = when (info.verifiedBootState) {
                    0 -> "Verified"
                    1 -> "SelfSigned"
                    2 -> "Unverified"
                    else -> "Failed"
                }
                CheckLine(stringResource(R.string.features_attestation_level, level), ok = info.securityLevel != 0)
                CheckLine(
                    stringResource(
                        R.string.features_attestation_locked,
                        stringResource(if (info.deviceLocked) R.string.features_attestation_yes else R.string.features_attestation_no),
                    ),
                    ok = info.deviceLocked,
                )
                CheckLine(stringResource(R.string.features_attestation_state, bootState), ok = info.verifiedBootState == 0)
                val revoked = state.revoked
                when {
                    revoked == null -> CheckLine(stringResource(R.string.features_attestation_revocation_unknown), ok = false)
                    revoked.isEmpty() -> CheckLine(stringResource(R.string.features_attestation_chain_ok, state.chainSize), ok = true)
                    else -> revoked.forEach { cert ->
                        CheckLine(stringResource(R.string.features_attestation_revoked, cert.index + 1, cert.reason), ok = false)
                    }
                }
                if (!info.deviceLocked || info.verifiedBootState != 0) {
                    Text(
                        text = stringResource(R.string.features_attestation_layer2),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        TextButton(
            text = stringResource(
                if (state.checkingAttestation) R.string.features_attestation_checking else R.string.features_attestation_check
            ),
            enabled = !state.checkingAttestation,
            onClick = actions.onCheckAttestation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Revealing entries one per line; the ones already fine are only counted. */
@Composable
private fun CheckSection(title: String, checks: List<PropCheck>) {
    if (checks.isEmpty()) return
    SectionTitle(title)
    checks.filterNot { it.ok }.forEach { check ->
        CheckLine("${check.name}: ${check.value} → ${check.safe}", ok = false)
    }
    val fine = checks.count { it.ok }
    if (fine > 0) CheckLine(stringResource(R.string.features_bootloader_fine, fine), ok = true)
}

/** Items shown per finding; the rest are only counted. */
private const val AUDIT_ITEMS_SHOWN = 6

@Composable
private fun HidingAuditCard(state: FeaturesUiState, actions: FeaturesActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val audit = state.audit
    val leaks = audit?.findings?.count { it.leak } ?: 0

    GlassExpandableCard(
        icon = Icons.Rounded.Shield,
        title = stringResource(R.string.audit_title),
        summary = when {
            state.auditing -> stringResource(R.string.audit_running)
            !state.auditRun -> stringResource(R.string.audit_summary)
            audit == null -> stringResource(R.string.audit_failed)
            audit.findings.isEmpty() -> stringResource(R.string.audit_clean)
            else -> stringResource(R.string.audit_count, leaks, audit.findings.size - leaks, audit.fixable)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (audit != null) {
            if (audit.findings.isEmpty()) {
                CheckLine(stringResource(R.string.audit_clean), ok = true)
            }
            audit.findings.forEach { finding -> AuditFinding(finding) }
            if (state.auditRebootNeeded) {
                Text(
                    text = stringResource(R.string.audit_reboot),
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TextButton(
                text = stringResource(if (state.auditRun) R.string.audit_rescan else R.string.audit_scan),
                enabled = !state.auditing,
                onClick = actions.onRunAudit,
                modifier = Modifier.weight(1f),
            )
            if (audit != null && audit.fixable > 0) {
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.audit_apply, audit.fixable),
                    enabled = !state.auditing,
                    onClick = actions.onApplyAuditFixes,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AuditFinding(finding: HidingAudit.Finding) {
    val title = when (finding.id) {
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
    Text(
        text = "${if (finding.leak) "⚠" else "•"}  ${title?.let { stringResource(it) } ?: finding.id}",
        fontSize = 13.sp,
        fontWeight = FontWeight(600),
        color = if (finding.leak) colorScheme.error else colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
    val shown = finding.items.take(AUDIT_ITEMS_SHOWN)
    val more = finding.items.size - shown.size
    (shown + if (more > 0) listOf(stringResource(R.string.audit_more, more)) else emptyList()).forEach { item ->
        Text(
            text = item,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
        )
    }
    val fix = when (finding.fix) {
        "kernelUmount" -> R.string.audit_fix_kernel_umount
        "selinuxHide" -> R.string.audit_fix_selinux_hide
        "hideBootloader" -> R.string.audit_fix_hide_bootloader
        else -> R.string.audit_fix_none
    }
    Text(
        text = "→ ${stringResource(fix)}",
        fontSize = 12.sp,
        color = if (finding.fix != null) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun CheckLine(text: String, ok: Boolean) {
    Text(
        text = "${if (ok) "✓" else "⚠"}  $text",
        fontSize = 13.sp,
        color = if (ok) colorScheme.onSurfaceVariantSummary else colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight(600),
        color = colorScheme.onSurfaceVariantActions,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}
