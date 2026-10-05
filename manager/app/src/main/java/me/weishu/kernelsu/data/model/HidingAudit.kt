package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Output of `ksud susfs audit`: what a non-root app can still see, each finding with the
 * setting that hides it ([Finding.fix]), when ksud can apply one.
 */
@Immutable
data class HidingAudit(
    /** SUSFS is in the kernel and ksud applies its settings; otherwise only hide bootloader can be fixed */
    val susfs: Boolean,
    val findings: List<Finding>,
    val fixable: Int,
) {
    @Immutable
    data class Finding(
        /** moduleMounts, ksuMounts, susMounts, maps, props, bootArgs, lsposed, revanced, customRom, files, selinux, adb */
        val id: String,
        /** an app can see it now; otherwise only worth a look */
        val leak: Boolean,
        val items: List<String>,
        /** tryUmount, autoTryUmount, hideSusMounts, susMap, susPath, fakeBootconfig, forceHideLsposed,
         *  hideRevanced, hideCusrom, hideBootloader; null when ksud cannot fix it */
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
                susfs = obj.optBoolean("susfs"),
                findings = findings,
                fixable = obj.optInt("fixable", findings.count { it.fix != null }),
            )
        }.getOrNull()

        /** Output of `ksud susfs audit --apply`: whether some of it only takes effect after a reboot. */
        fun rebootNeededAfterApply(json: String): Boolean = runCatching {
            JSONObject(json).optJSONObject("susfs")?.optBoolean("rebootNeeded") ?: false
        }.getOrDefault(false)
    }
}
