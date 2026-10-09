package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Output of `camd hiding-audit`: what a non-root app can still see, each finding with the
 * setting that hides it ([Finding.fix]), when camd can turn one on.
 */
@Immutable
data class HidingAudit(
    val findings: List<Finding>,
    val fixable: Int,
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
    )

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
                )
            }
            HidingAudit(
                findings = findings,
                fixable = obj.optInt("fixable", findings.count { it.fix != null }),
            )
        }.getOrNull()

        /** Output of `camd hiding-audit --apply`: whether some of it only takes effect after a reboot. */
        fun rebootNeededAfterApply(json: String): Boolean = runCatching {
            JSONObject(json).optBoolean("rebootNeeded")
        }.getOrDefault(false)
    }
}
