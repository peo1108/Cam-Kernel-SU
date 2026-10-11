package cam.su.kernel.ui.util

import android.util.Log
import cam.su.kernel.data.model.ModuleConflict
import cam.su.kernel.data.model.parseModuleConflicts
import org.json.JSONArray

// Cam-only camd commands, kept out of CamCli.kt (upstream's KsuCli.kt) so upstream merges
// touch less of it. Module conflicts, restore after boot guard, and turning every module off before a direct install.
private const val TAG = "ModuleToolsCli"

fun listModuleConflicts(): List<ModuleConflict> = parseModuleConflicts(camdStdout("module conflicts"))

fun restoreModule(id: String): Boolean {
    val result = execCamd("module restore $id", true)
    Log.i(TAG, "restore module $id result: $result")
    return result
}

/** Ids of the modules that are on right now; null when the list cannot be read. */
private fun enabledModuleIds(): List<String>? = runCatching {
    val array = JSONArray(listModules())
    (0 until array.length()).map { array.getJSONObject(it) }
        .filter { it.optBoolean("enabled") }
        .map { it.getString("id") }
}.getOrNull()

/**
 * Turns off every enabled module before a direct install, so the first boot on the new
 * Cam Kernel SU runs without them. False when a module could not be turned off.
 */
fun disableAllModules(onStdout: (String) -> Unit): Boolean {
    val ids = enabledModuleIds() ?: run {
        onStdout("! Failed to read the module list")
        return false
    }
    if (ids.isEmpty()) {
        onStdout("- No enabled modules")
        return true
    }
    onStdout("- Disabling ${ids.size} module(s) before install")
    var ok = true
    for (id in ids) {
        if (toggleModule(id, enable = false)) {
            onStdout("  - $id")
        } else {
            onStdout("! Failed to disable $id")
            ok = false
        }
    }
    return ok
}
