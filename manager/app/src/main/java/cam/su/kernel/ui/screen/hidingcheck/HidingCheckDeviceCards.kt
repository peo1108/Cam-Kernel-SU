package cam.su.kernel.ui.screen.hidingcheck

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.data.model.WidevineDiagnosis
import cam.su.kernel.data.model.diagnoseWidevine
import cam.su.kernel.data.repository.HidingRulesStatus
import cam.su.kernel.ui.component.AttestationResult
import cam.su.kernel.ui.component.glass.GlassCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
internal fun DetectorCard(
    status: HidingRulesStatus,
    checking: Boolean,
    actions: HidingCheckActions,
    format: (Long) -> String,
) {
    GlassCard(modifier = cardModifier) {
        val rules = status.rules
        BasicComponent(
            title = stringResource(R.string.hiding_rules_title),
            summary = buildList {
                add(stringResource(R.string.hiding_rules_summary, rules.version, rules.updated))
                status.checkedAt?.let { add(stringResource(R.string.hiding_rules_checked, format(it))) }
                if (status.updated) add(stringResource(R.string.hiding_rules_updated, rules.version))
                if (status.failed) add(stringResource(R.string.hiding_rules_failed))
            }.joinToString(" · "),
            startAction = { RowIcon(Icons.Rounded.Description, if (status.updated) colorScheme.primary else colorScheme.onBackground) },
        )
        BasicComponent(
            title = stringResource(R.string.hiding_duck_title),
            summary = buildList {
                add(stringResource(R.string.hiding_duck_following, rules.duckDetector.take(7)))
                when {
                    status.duckFailed -> add(stringResource(R.string.hiding_duck_failed))
                    status.duckAhead -> add(
                        stringResource(R.string.hiding_duck_ahead, status.duckLatest.orEmpty().take(7), status.duckLatestAt.orEmpty().take(10))
                    )
                    status.duckLatest != null -> add(stringResource(R.string.hiding_duck_current))
                }
                if (rules.duckPending.isNotEmpty()) add(stringResource(R.string.hiding_duck_pending, rules.duckPending.joinToString()))
            }.joinToString(" · "),
            startAction = { RowIcon(Icons.Rounded.NewReleases, if (status.duckAhead) colorScheme.primary else colorScheme.onBackground) },
        )
        BasicComponent(
            title = stringResource(if (checking) R.string.hiding_checking else R.string.hiding_check_updates),
            summary = stringResource(R.string.hiding_check_updates_summary),
            startAction = { RowIcon(Icons.Rounded.Refresh, colorScheme.onBackground) },
            onClick = { if (!checking) actions.onCheckUpdates() },
        )
    }
}

@Composable
internal fun AttestationCard(state: HidingCheckUiState, actions: HidingCheckActions) {
    GlassCard(modifier = cardModifier) {
        Text(
            text = stringResource(R.string.hiding_attestation_summary),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        state.attestation?.let { AttestationResult(it) }
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

/**
 * The Widevine DRM level streaming apps see (L1 plays HD, L3 is SD only), why a device is below
 * L1, and a way to clear and fetch the DRM certificates again when that can help.
 */
@Composable
internal fun WidevineCard(state: HidingCheckUiState, actions: HidingCheckActions) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val busy = state.reprovisionStep != null
    GlassCard(modifier = cardModifier) {
        Text(
            text = stringResource(R.string.hiding_widevine_summary),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        val info = state.widevine
        if (info != null) {
            if (info.supported) {
                WidevineLine(stringResource(R.string.hiding_widevine_level, info.securityLevel ?: "?"), ok = info.isL1)
                info.systemId?.let { WidevineLine(stringResource(R.string.hiding_widevine_system_id, it), ok = null) }
                info.version?.let { WidevineLine(stringResource(R.string.hiding_widevine_version, it), ok = null) }
                info.hdcpLevel?.let { hdcp ->
                    WidevineLine(stringResource(R.string.hiding_widevine_hdcp, hdcp, info.maxHdcpLevel ?: "?"), ok = null)
                }
                info.provisioned?.let {
                    WidevineLine(
                        stringResource(
                            when {
                                !it -> R.string.hiding_widevine_not_provisioned
                                info.justProvisioned -> R.string.hiding_widevine_just_provisioned
                                else -> R.string.hiding_widevine_provisioned
                            }
                        ),
                        ok = it,
                    )
                }
                info.sessionError?.let { WidevineLine(stringResource(R.string.hiding_widevine_session_error, it), ok = false) }
            }
            state.widevineHal?.let {
                WidevineLine(stringResource(if (it) R.string.hiding_widevine_hal_up else R.string.hiding_widevine_hal_down), ok = it)
            }
            val diagnosis = diagnoseWidevine(info, state.widevineHal, state.attestation?.info?.deviceLocked)
            Text(
                text = stringResource(diagnosis.text()),
                fontSize = 12.sp,
                color = if (diagnosis == WidevineDiagnosis.L1) colorScheme.onSurfaceVariantSummary else colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            state.reprovision?.let { result ->
                val text = when {
                    result.restored -> stringResource(R.string.hiding_widevine_reprovision_restored, result.after.securityLevel ?: "?")
                    result.error != null -> stringResource(R.string.hiding_widevine_reprovision_failed, result.error)
                    else -> stringResource(
                        R.string.hiding_widevine_reprovision_done,
                        result.before.securityLevel ?: "?",
                        result.after.securityLevel ?: "?",
                    )
                }
                WidevineLine(text, ok = result.after.isL1 && result.error == null)
                if (result.error != null && !result.restored) {
                    WidevineLine(stringResource(R.string.hiding_widevine_reprovision_backup, result.backup), ok = null)
                }
            }
            TextButton(
                text = stringResource(R.string.hiding_widevine_copy),
                onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("widevine", info.summary()))
                    cam.su.kernel.ui.component.CamToast.show(context, R.string.hiding_widevine_copied)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            if (info.supported && state.widevineHal != false) {
                TextButton(
                    text = stringResource(
                        when (state.reprovisionStep) {
                            null -> R.string.hiding_widevine_reprovision
                            "backup" -> R.string.hiding_widevine_step_backup
                            "clear" -> R.string.hiding_widevine_step_clear
                            "provision" -> R.string.hiding_widevine_step_provision
                            else -> R.string.hiding_widevine_step_restore
                        }
                    ),
                    enabled = !busy,
                    onClick = { confirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = if (diagnosis.reprovisionHelps) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                )
            }
        }
        TextButton(
            text = stringResource(
                if (state.checkingWidevine) R.string.features_attestation_checking else R.string.hiding_widevine_check
            ),
            enabled = !state.checkingWidevine && !busy,
            onClick = actions.onCheckWidevine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    cam.su.kernel.ui.component.glass.GlassDialog(
        show = confirm,
        title = stringResource(R.string.hiding_widevine_reprovision),
        summary = stringResource(
            if (state.widevine?.isL1 == true) R.string.hiding_widevine_reprovision_confirm_l1 else R.string.hiding_widevine_reprovision_confirm
        ),
        onDismissRequest = { confirm = false },
    ) {
        Row {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { confirm = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.hiding_widevine_reprovision_go),
                onClick = {
                    confirm = false
                    actions.onReprovisionWidevine()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun WidevineDiagnosis.text(): Int = when (this) {
    WidevineDiagnosis.L1 -> R.string.hiding_widevine_diag_l1
    WidevineDiagnosis.NoPlugin -> R.string.hiding_widevine_diag_no_plugin
    WidevineDiagnosis.HalDown -> R.string.hiding_widevine_diag_hal_down
    WidevineDiagnosis.NotProvisioned -> R.string.hiding_widevine_diag_not_provisioned
    WidevineDiagnosis.BootloaderUnlocked -> R.string.hiding_widevine_diag_unlocked
    WidevineDiagnosis.KeyboxMissing -> R.string.hiding_widevine_diag_keybox
    WidevineDiagnosis.Unknown -> R.string.hiding_widevine_diag_unknown
}

/** [ok] null: a neutral fact. */
@Composable
private fun WidevineLine(text: String, ok: Boolean?) {
    Text(
        text = "${when (ok) { true -> "✓"; false -> "⚠"; null -> "·" }}  $text",
        fontSize = 13.sp,
        color = if (ok == false) colorScheme.error else colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
    )
}
