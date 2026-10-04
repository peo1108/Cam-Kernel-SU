package me.weishu.kernelsu.ui.screen.features

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Layers
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
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.ConflictKind
import me.weishu.kernelsu.data.model.PropCheck
import me.weishu.kernelsu.ui.component.glass.GlassDropdownPreference
import me.weishu.kernelsu.ui.component.glass.GlassExpandableCard
import me.weishu.kernelsu.ui.theme.LocalEnableBlur
import me.weishu.kernelsu.ui.util.BlurredBar
import me.weishu.kernelsu.ui.util.rememberBlurBackdrop
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
