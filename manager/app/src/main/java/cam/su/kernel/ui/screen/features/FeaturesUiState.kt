package cam.su.kernel.ui.screen.features

import androidx.compose.runtime.Immutable
import cam.su.kernel.data.model.AttestationInfo
import cam.su.kernel.data.model.BootGuardStatus
import cam.su.kernel.data.model.HideBootloaderStatus
import cam.su.kernel.data.model.ModuleConflict
import cam.su.kernel.data.model.RevokedCert
import cam.su.kernel.data.model.visible

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
    /** when the root hiding check last ran; null when it never did */
    val hidingScanTime: Long? = null,
    /** leaks and other findings of that check, ignored ones left out */
    val hidingLeaks: Int = 0,
    val hidingFindings: Int = 0,
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
    val onOpenHidingCheck: () -> Unit,
)
