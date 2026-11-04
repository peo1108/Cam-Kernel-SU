package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Output of `camd hiding-audit`: what a non-root app can still see, each finding with the
 * setting that hides it ([Finding.fix]), when camd can turn one on.
 */
@Immutable
data class HidingAudit(
    val findings: List<Finding>,
    val fixable: Int,
    /** counts next to the findings, by name: rootModuleMounts, modules, appView, appNative, appMounts, processes */
    val stats: Map<String, Int> = emptyMap(),
) {
    @Immutable
    data class Finding(
        /** moduleMounts, camMounts, selinuxRules, maps, props, bootArgs, lsposed, revanced, customRom, files, selinux, adb */
        val id: String,
        /** an app can see it now; otherwise only worth a look */
        val leak: Boolean,
        val items: List<String>,
        /** kernelUmount, selinuxHide, hideBootloader; null when camd cannot fix it */
        val fix: String?,
        /** seen from inside an app without root (AppViewProbe, or camd --uid), not across the device */
        val appView: Boolean = false,
        /** modules the items come from, when their paths name one */
        val modules: List<String> = emptyList(),
    )

    /** The same shape [parse] reads, so a saved audit can be read back. */
    fun toJson(): JSONObject = JSONObject()
        .put("fixable", fixable)
        .put("stats", JSONObject(stats))
        .put("findings", JSONArray(findings.map { f ->
            JSONObject()
                .put("id", f.id)
                .put("level", if (f.leak) "leak" else "review")
                .put("items", JSONArray(f.items))
                .put("fix", f.fix ?: JSONObject.NULL)
                .put("view", if (f.appView) "app" else "root")
                .put("modules", JSONArray(f.modules))
        }))

    /** This audit and [other] as one, e.g. camd's and the app view's. */
    operator fun plus(other: HidingAudit): HidingAudit {
        val all = findings + other.findings
        return HidingAudit(findings = all, fixable = all.count { it.fix != null }, stats = stats + other.stats)
    }

    companion object {
        fun parse(json: String): HidingAudit? = runCatching {
            val obj = JSONObject(json)
            val list = obj.getJSONArray("findings")
            val findings = List(list.length()) { i ->
                val f = list.getJSONObject(i)
                val items = f.optJSONArray("items")
                Finding(
                    id = f.getString("id"),
                    leak = f.optString("level") == "leak",
                    items = List(items?.length() ?: 0) { items!!.getString(it) },
                    fix = if (f.isNull("fix")) null else f.optString("fix").takeIf(String::isNotEmpty),
                    appView = f.optString("view") == "app",
                    modules = strings(f.optJSONArray("modules")),
                )
            }
            val stats = obj.optJSONObject("stats")
            HidingAudit(
                findings = findings,
                fixable = obj.optInt("fixable", findings.count { it.fix != null }),
                stats = stats?.keys()?.asSequence()?.associateWith { stats.optInt(it) }.orEmpty(),
            )
        }.getOrNull()

        private fun strings(array: JSONArray?): List<String> = List(array?.length() ?: 0) { array!!.getString(it) }

        /**
         * Module ids the texts name, as bind roots (`/adb/modules/<id>/...`) and overlay
         * lowerdirs (`/data/adb/modules/<id>/...`) show them; each once, in order. camd's
         * hiding_audit.rs does the same.
         */
        fun moduleIds(texts: Iterable<String>): List<String> {
            val out = LinkedHashSet<String>()
            for (text in texts) {
                var rest = text
                while (true) {
                    val at = rest.indexOf(MODULES_DIR)
                    if (at < 0) break
                    rest = rest.substring(at + MODULES_DIR.length)
                    val id = rest.split('/', ':', ',', ' ').first()
                    if (id.isNotEmpty()) out += id
                }
            }
            return out.toList()
        }

        private const val MODULES_DIR = "adb/modules/"

        /** Output of `camd hiding-audit --apply`: whether some of it only takes effect after a reboot. */
        fun rebootNeededAfterApply(json: String): Boolean = runCatching {
            JSONObject(json).optBoolean("rebootNeeded")
        }.getOrDefault(false)
    }
}
