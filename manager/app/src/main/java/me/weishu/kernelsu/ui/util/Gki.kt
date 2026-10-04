package me.weishu.kernelsu.ui.util

import android.os.Parcelable
import android.system.Os
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.Ksu
import me.weishu.kernelsu.getKernelVersion

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

suspend fun loadGkiStatus(): GkiStatus = withContext(Dispatchers.IO) {
    val version = getKernelVersion()
    val builtIn = Ksu.isAvailable && !Ksu.isLkmMode
    GkiStatus(
        kernelRelease = Os.uname().release,
        kmi = runCatching { getCurrentKmi().trim() }.getOrDefault(""),
        abDevice = runCatching { isAbDevice() }.getOrDefault(false),
        supported = version.major > 6 || (version.major == 6 && version.patchLevel >= 1),
        builtIn = builtIn,
        // only a built-in kernel can carry SUSFS, so skip the root call otherwise
        susfs = if (builtIn) getSusfsInfo() else null,
    )
}

/** A boot image saved by `ksud flash-ak3` before it flashed a zip. */
@Immutable
@Parcelize
data class Ak3Backup(
    val path: String,
    /** slot suffix, e.g. _a; empty on non-A/B devices */
    val slot: String,
    /** the kernel from before the very first flash, never overwritten */
    val original: Boolean,
    val size: Long,
    /** seconds since the epoch */
    val modified: Long,
) : Parcelable
