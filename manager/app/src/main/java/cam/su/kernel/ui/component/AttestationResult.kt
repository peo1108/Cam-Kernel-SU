package cam.su.kernel.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.ui.util.AttestationReport
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * What the key attestation says: security level, lock state, verified boot and revocation.
 * Shared by the hide bootloader card and the root hiding check page.
 */
@Composable
fun AttestationResult(report: AttestationReport) {
    val info = report.info
    if (info == null) {
        AttestationLine(stringResource(R.string.features_attestation_unreadable), ok = false)
        return
    }
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
    AttestationLine(stringResource(R.string.features_attestation_level, level), ok = info.securityLevel != 0)
    AttestationLine(
        stringResource(
            R.string.features_attestation_locked,
            stringResource(if (info.deviceLocked) R.string.features_attestation_yes else R.string.features_attestation_no),
        ),
        ok = info.deviceLocked,
    )
    AttestationLine(stringResource(R.string.features_attestation_state, bootState), ok = info.verifiedBootState == 0)
    val revoked = report.revoked
    when {
        revoked == null -> AttestationLine(stringResource(R.string.features_attestation_revocation_unknown), ok = false)
        revoked.isEmpty() -> AttestationLine(stringResource(R.string.features_attestation_chain_ok, report.chainSize), ok = true)
        else -> revoked.forEach { cert ->
            AttestationLine(stringResource(R.string.features_attestation_revoked, cert.index + 1, cert.reason), ok = false)
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

@Composable
private fun AttestationLine(text: String, ok: Boolean) {
    Text(
        text = "${if (ok) "✓" else "⚠"}  $text",
        fontSize = 13.sp,
        color = if (ok) colorScheme.onSurfaceVariantSummary else colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
    )
}
