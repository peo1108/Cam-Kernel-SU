package me.weishu.kernelsu.ui.screen.features

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.data.model.AttestationInfo
import me.weishu.kernelsu.data.model.BootGuardStatus
import me.weishu.kernelsu.data.model.HideBootloaderStatus
import me.weishu.kernelsu.data.model.ModuleConflict
import me.weishu.kernelsu.data.model.RevokedCert
import me.weishu.kernelsu.data.model.visible
import me.weishu.kernelsu.ui.util.SusfsInfo

@Immutable
data class GkiStatus(
    /** uname -r of the running kernel */
    val kernelRelease: String = "",
    /** e.g. android16-6.12: picks the project build that fits this device */
    val kmi: String = "",
    /** A/B device: installing to the inactive slot is possible */
    val abDevice: Boolean = false,
    /** GKI 6.1 or newer: the only kernels the SFS AnyKernel3 builds exist for */
    val supported: Boolean = false,
    /** KernelSU is built into the kernel rather than loaded as an LKM */
    val builtIn: Boolean = false,
    /** null when the kernel has no SUSFS or it could not be read */
    val susfs: SusfsInfo? = null,
)

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
    val gki: GkiStatus = GkiStatus(),
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
    val onGkiInstallDirect: () -> Unit,
    val onGkiInstallLocal: () -> Unit,
    val onGkiInstallInactive: () -> Unit,
)
