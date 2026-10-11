package cam.su.kernel.ui.screen.home

import androidx.compose.runtime.Immutable
import cam.su.kernel.KernelVersion
import cam.su.kernel.data.model.BootGuardStatus
import cam.su.kernel.update.ChangelogEntry
import cam.su.kernel.update.UpdateInfo
import cam.su.kernel.update.UpdateState

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion,
    val camVersion: Int?,
    val managerUAPIVersion: Int,
    val kernelUAPIVersion: Int?,
    val lkmMode: Boolean?,
    val isLkmBundled: Boolean,
    val isManager: Boolean,
    val isManagerPrBuild: Boolean,
    val isKernelPrBuild: Boolean,
    val requiresNewKernel: Boolean,
    val requiresNewManager: Boolean,
    val isRootAvailable: Boolean,
    val isSafeMode: Boolean,
    val isLateLoadMode: Boolean,
    val checkUpdateEnabled: Boolean,
    val update: UpdateInfo? = null,
    val installState: UpdateState = UpdateState.Idle,
    val whatsNew: List<ChangelogEntry> = emptyList(),
    val currentManagerVersionCode: Long,
    // Kernel sources of the running LKM and of the LKM this Manager bundles; "" when unknown
    val lkmSourceId: String = "",
    val bundledLkmSourceId: String = "",
    val systemInfo: SystemInfo,
    val bootGuard: BootGuardStatus = BootGuardStatus.Empty,
) {
    val showBootGuardNotice: Boolean
        get() = bootGuard.autoDisabled.isNotEmpty()

    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    val showGkiWarning: Boolean
        get() = camVersion != null && lkmMode == false

    val showLkmUpdate: Boolean
        get() = isManager &&
                lkmMode == true &&
                isLkmBundled &&
                lkmDiffers &&
                !requiresNewKernel &&
                !requiresNewManager

    // Both source ids known: reinstall only when the kernel sources changed. Otherwise (module
    // older than KSU_IOCTL_GET_SOURCE_ID, or built from uncommitted sources) fall back to the
    // version number, which differs after every update.
    val lkmDiffers: Boolean
        get() = if (lkmSourceId.isNotEmpty() && bundledLkmSourceId.isNotEmpty()) {
            lkmSourceId != bundledLkmSourceId
        } else {
            camVersion?.toLong() != currentManagerVersionCode
        }

    // Jailbreak mode runs on locked bootloaders, so flashing a boot image would brick the device.
    val canInstallKernelUpdate: Boolean
        get() = lkmMode == true && !isLateLoadMode

    val showCustomLkmBadge: Boolean
        get() = lkmMode == true && !isLkmBundled

    val showRootWarning: Boolean
        get() = camVersion != null && !isRootAvailable

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val hasUpdate: Boolean
        get() = update != null
}

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onUpdateClick: () -> Unit = {},
    val onSystemInstallClick: () -> Unit = {},
    val onDismissWhatsNew: () -> Unit = {},
    val onJailbreakClick: () -> Unit = {},
    val onReenableModule: (String) -> Unit = {},
    val onDismissBootGuard: () -> Unit = {},
)
