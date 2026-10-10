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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import cam.su.kernel.R
import cam.su.kernel.adb.WirelessAdb
import cam.su.kernel.data.model.ConflictKind
import cam.su.kernel.data.model.PropCheck
import cam.su.kernel.ui.component.AttestationResult
import cam.su.kernel.ui.component.glass.GlassDropdownPreference
import cam.su.kernel.ui.component.glass.GlassExpandableCard
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.AttestationReport
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
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
import java.text.DateFormat
import java.util.Date

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
                item { HidingCheckEntry(state, actions) }
                item { WirelessAdbCard(state, actions) }
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
            AttestationResult(AttestationReport(state.attestation, state.chainSize, state.revoked))
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
private fun HidingCheckEntry(state: FeaturesUiState, actions: FeaturesActions) {
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val time = state.hidingScanTime
    val status = when {
        time == null -> stringResource(R.string.audit_summary)
        state.hidingLeaks > 0 -> stringResource(R.string.hiding_status_leaks, state.hidingLeaks)
        state.hidingFindings > 0 -> stringResource(R.string.hiding_status_review, state.hidingFindings)
        else -> stringResource(R.string.hiding_status_clean)
    }
    // the whole check lives on its own page; this card only opens it
    GlassExpandableCard(
        icon = Icons.Rounded.Shield,
        title = stringResource(R.string.audit_title),
        summary = if (time == null) status else "$status · ${dates.format(Date(time))}",
        expanded = false,
        onToggle = actions.onOpenHidingCheck,
    ) { }
}

@Composable
private fun WirelessAdbCard(state: FeaturesUiState, actions: FeaturesActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val adb = state.wirelessAdb
    val address = state.wifiAddress?.let { "$it:${adb.port ?: WirelessAdb.PORT}" }
    val timeouts = WirelessAdb.TIMEOUTS

    GlassExpandableCard(
        icon = Icons.Rounded.Wifi,
        title = stringResource(R.string.features_wireless_adb),
        summary = when {
            !adb.on -> stringResource(R.string.features_off)
            address == null -> stringResource(R.string.features_wireless_adb_no_wifi)
            else -> stringResource(R.string.features_wireless_adb_on, address)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        SwitchPreference(
            title = stringResource(R.string.features_wireless_adb_enable),
            summary = stringResource(R.string.features_wireless_adb_enable_summary, WirelessAdb.PORT),
            checked = adb.on,
            enabled = !state.adbBusy,
            onCheckedChange = actions.onSetWirelessAdb,
        )
        if (adb.on && address != null) {
            val command = "adb connect $address"
            BasicComponent(
                title = command,
                summary = stringResource(R.string.features_wireless_adb_copy),
                endActions = {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = colorScheme.onSurfaceVariantActions)
                },
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("adb", command))
                    Toast.makeText(context, R.string.features_wireless_adb_copied, Toast.LENGTH_SHORT).show()
                },
            )
        }
        GlassDropdownPreference(
            title = stringResource(R.string.features_wireless_adb_timeout),
            items = timeouts.map { minutes ->
                if (minutes == 0) {
                    stringResource(R.string.features_wireless_adb_timeout_never)
                } else {
                    stringResource(R.string.features_wireless_adb_timeout_minutes, minutes)
                }
            },
            selectedIndex = timeouts.indexOf(state.adbTimeout).coerceAtLeast(0),
            onSelectedIndexChange = { actions.onSetAdbTimeout(timeouts[it]) },
        )
        Text(
            text = stringResource(R.string.features_wireless_adb_note),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
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
