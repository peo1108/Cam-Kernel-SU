package cam.su.kernel.ui.util

import android.util.Log
import cam.su.kernel.data.model.HideBootloaderStatus
import cam.su.kernel.data.model.HidingAudit
import org.json.JSONObject
import java.io.File

// Cam-only camd commands, kept out of CamCli.kt (upstream's KsuCli.kt) so upstream merges
// touch less of it. Root hiding check and hide bootloader.
private const val TAG = "HidingCli"

/** What non-root apps can still see; null when camd could not run the audit. [rules]: hiding rules JSON for camd. */
fun runHidingAudit(rules: File? = null): HidingAudit? =
    HidingAudit.parse(camdStdout("hiding-audit" + rulesArg(rules)))

/**
 * What the app with [uid] sees from its own running processes, and whether any runs;
 * null when camd could not run the audit.
 */
fun runAppHidingAudit(uid: Int, rules: File? = null): Pair<HidingAudit, Boolean>? {
    val out = camdStdout("hiding-audit --uid $uid" + rulesArg(rules))
    val audit = HidingAudit.parse(out) ?: return null
    return audit to runCatching { JSONObject(out).optBoolean("running") }.getOrDefault(false)
}

private fun rulesArg(rules: File?): String = rules?.let { " --rules '${it.absolutePath}'" }.orEmpty()

/** Turns on every fix the audit offers; true when some of it needs a reboot. */
fun applyHidingFixes(): Boolean = HidingAudit.rebootNeededAfterApply(camdStdout("hiding-audit --apply"))

fun getHideBootloaderStatus(): HideBootloaderStatus =
    HideBootloaderStatus.parse(camdStdout("hide-bootloader status"))

fun setHideBootloader(enabled: Boolean): Boolean {
    val result = execCamd("hide-bootloader ${if (enabled) "enable" else "disable"}", true)
    Log.i(TAG, "hide-bootloader $enabled result: $result")
    return result
}

/** The device boots with a user-set AVB key: an on-device patched boot would not verify. */
fun isCustomAvbKey(): Boolean = getHideBootloaderStatus().customAvbKey

/** Whether the RKP/Duck network block is on; false when camd could not report. */
fun getBlockRkpEnabled(): Boolean =
    runCatching { JSONObject(camdStdout("block-rkp status").orEmpty()).optBoolean("enabled") }
        .getOrDefault(false)

fun setBlockRkp(enabled: Boolean): Boolean {
    val result = execCamd("block-rkp ${if (enabled) "enable" else "disable"}", true)
    Log.i(TAG, "block-rkp $enabled result: $result")
    return result
}
