package cam.su.kernel.ui.util

import android.util.Log
import cam.su.kernel.data.model.BootGuardStatus

// Cam-only camd commands, kept out of CamCli.kt (upstream's KsuCli.kt) so upstream merges
// touch less of it. Boot guard: camd turns modules off after failed boots.
private const val TAG = "BootGuardCli"

fun getBootGuardStatus(): BootGuardStatus = BootGuardStatus.parse(camdStdout("boot-guard status"))

fun clearBootGuard(): Boolean {
    val result = execCamd("boot-guard clear", true)
    Log.i(TAG, "boot-guard clear result: $result")
    return result
}

/** `null` keeps a setting; [threshold] is the boot attempt that triggers (2..5). */
fun setBootGuardConfig(enabled: Boolean? = null, threshold: Int? = null, disableAll: Boolean? = null): Boolean {
    val args = buildList {
        enabled?.let { add("--enabled $it") }
        threshold?.let { add("--threshold $it") }
        disableAll?.let { add("--mode ${if (it) "all" else "suspects"}") }
    }
    if (args.isEmpty()) return true
    val result = execCamd("boot-guard set ${args.joinToString(" ")}", true)
    Log.i(TAG, "boot-guard set $args result: $result")
    return result
}
