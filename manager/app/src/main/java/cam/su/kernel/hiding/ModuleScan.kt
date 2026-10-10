package cam.su.kernel.hiding

import cam.su.kernel.data.model.HidingAudit
import org.json.JSONArray
import org.json.JSONObject

/** One module as `camd module list` shows it, reduced to what says whether it changed. */
data class ModuleState(
    val id: String,
    val name: String,
    /** version code and name: a new one means the module was updated */
    val stamp: String,
    val enabled: Boolean,
    /** installed or updated, but only mounted from the next boot on */
    val pending: Boolean,
    val removing: Boolean,
) {
    /** mounted on this boot, as the list shows it */
    val running: Boolean
        get() = enabled && !pending && !removing
}

/**
 * The scan after a boot that brought a module in: which modules run now that did not run
 * as they are at the last check (installed, updated, enabled again), and which leaks of a
 * hiding audit name them. A module only mounts from the boot after it was installed, so the
 * check runs after boot ([ModuleScanWorker]), against the modules seen then ([baseline]).
 */
object ModuleScan {

    /** Output of `camd module list`; null when it is not a list. */
    fun parseModules(json: String): List<ModuleState>? = runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            ModuleState(
                id = obj.getString("id"),
                name = obj.optString("name"),
                stamp = "${obj.optString("versionCode")}:${obj.optString("version")}",
                enabled = obj.optString("enabled") != "false",
                pending = obj.optString("update") == "true",
                removing = obj.optString("remove") == "true",
            )
        }
    }.getOrNull()

    /** Modules running now that [baseline] does not have running as they are, in list order. */
    fun changed(baseline: Map<String, String>, modules: List<ModuleState>): List<String> =
        modules.filter { it.running && baseline[it.id] != it.stamp }.map { it.id }

    /**
     * What to compare the next boot with: the modules running now. One waiting for its
     * install or update keeps what it was, so the boot that mounts it still sees it change.
     */
    fun nextBaseline(baseline: Map<String, String>, modules: List<ModuleState>): Map<String, String> =
        buildMap {
            for (m in modules) {
                when {
                    m.running -> put(m.id, m.stamp)
                    m.pending && !m.removing -> baseline[m.id]?.let { put(m.id, it) }
                }
            }
        }

    /**
     * Leaks [audit] puts on the [changed] modules: module id to the ids of the findings that
     * name it, findings the user chose to ignore left out. Modules with no leak are left out.
     */
    fun leaksBy(audit: HidingAudit, changed: Collection<String>, ignored: Set<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (finding in audit.findings) {
            if (!finding.leak || finding.id in ignored) continue
            for (id in finding.modules) {
                if (id !in changed) continue
                val list = out.getOrPut(id) { mutableListOf() }
                if (finding.id !in list) list += finding.id
            }
        }
        // in the order the modules changed
        return changed.mapNotNull { id -> out[id]?.let { id to it.toList() } }.toMap()
    }

    fun baselineToJson(baseline: Map<String, String>): String = JSONObject(baseline).toString()

    fun parseBaseline(json: String): Map<String, String>? = runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    }.getOrNull()
}
