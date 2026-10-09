package me.weishu.kernelsu.ui.screen.features

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.AttestationInfo
import me.weishu.kernelsu.data.model.BootGuardStatus
import me.weishu.kernelsu.data.model.HideBootloaderStatus
import me.weishu.kernelsu.data.model.HidingAudit
import me.weishu.kernelsu.data.model.ModuleConflict
import me.weishu.kernelsu.data.model.RevokedCert
import me.weishu.kernelsu.data.model.visible

@Immutable
data class FeaturesUiState(
    val bootGuard: BootGuardStatus = BootGuardStatus.Empty,
    /** raw scan result; [conflicts] applies the options */
    val allConflicts: List<ModuleConflict> = emptyList(),
    val conflictDetection: Boolean = true,
    val conflictWarnOnFlash: Boolean = true,
    val conflictIncludeProps: Boolean = true,
    val scanning: Boolean = false,
    val hideBootloader: HideBootloaderStatus = HideBootloaderStatus.Empty,
    /** null until checked, or when the certificate could not be read */
    val attestation: AttestationInfo? = null,
    val attestationChecked: Boolean = false,
    val chainSize: Int = 0,
    /** null when the revocation list could not be fetched */
    val revoked: List<RevokedCert>? = null,
    val checkingAttestation: Boolean = false,
    /** null until the hiding audit has run (or when ksud could not run it) */
    val audit: HidingAudit? = null,
    val auditRun: Boolean = false,
    val auditing: Boolean = false,
    /** the last applied fixes take full effect only after a reboot */
    val auditRebootNeeded: Boolean = false,
) {
    val conflicts: List<ModuleConflict>
        get() = allConflicts.visible(conflictDetection, conflictIncludeProps)
}

@Immutable
data class FeaturesActions(
    val onSetBootGuardEnabled: (Boolean) -> Unit,
    /** failed boots tolerated, 1..4 */
    val onSetBootGuardFailures: (Int) -> Unit,
    val onSetBootGuardDisableAll: (Boolean) -> Unit,
    val onReenableModule: (String) -> Unit,
    val onDismissBootGuard: () -> Unit,
    val onSetConflictDetection: (Boolean) -> Unit,
    val onSetConflictWarnOnFlash: (Boolean) -> Unit,
    val onSetConflictIncludeProps: (Boolean) -> Unit,
    val onRescanConflicts: () -> Unit,
    val onSetHideBootloader: (Boolean) -> Unit,
    val onCheckAttestation: () -> Unit,
    val onRunAudit: () -> Unit,
    val onApplyAuditFixes: () -> Unit,
)
