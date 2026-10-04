package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** One watched property or boot argument, with the value a locked device reports. */
@Immutable
data class PropCheck(val name: String, val value: String, val safe: String, val ok: Boolean)

/** Output of `ksud hide-bootloader status`. */
@Immutable
data class HideBootloaderStatus(
    val enabled: Boolean,
    val props: List<PropCheck>,
    /** from /proc/bootconfig and /proc/cmdline: reported, never rewritten */
    val bootconfig: List<PropCheck>,
) {
    val leaks: Int
        get() = props.count { !it.ok } + bootconfig.count { !it.ok }

    companion object {
        val Empty = HideBootloaderStatus(enabled = false, props = emptyList(), bootconfig = emptyList())

        fun parse(json: String): HideBootloaderStatus = runCatching {
            val obj = JSONObject(json)
            HideBootloaderStatus(
                enabled = obj.optBoolean("enabled"),
                props = checks(obj.optJSONArray("props"), valueKey = "current"),
                bootconfig = checks(obj.optJSONArray("bootconfig"), valueKey = "value"),
            )
        }.getOrDefault(Empty)

        private fun checks(array: JSONArray?, valueKey: String): List<PropCheck> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val item = array?.optJSONObject(index) ?: return@mapNotNull null
                PropCheck(
                    name = item.optString("name"),
                    value = item.optString(valueKey),
                    safe = item.optString("safe"),
                    ok = item.optBoolean("ok"),
                )
            }
    }
}
