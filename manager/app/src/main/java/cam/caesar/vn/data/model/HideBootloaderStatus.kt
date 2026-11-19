package cam.caesar.vn.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * One watched property or boot argument, with the value a locked device reports. [tell]: a value other
 * than [safe] gives the bootloader away; false for `veritymode=eio` or `sys.oem_unlock_allowed`, which a
 * locked device can show too (camd's `hide_bootloader::is_tell`). A camd without the field counts as true.
 */
@Immutable
data class PropCheck(val name: String, val value: String, val safe: String, val ok: Boolean, val tell: Boolean = true) {
    /** Not what a locked device reports, and that says the bootloader is open. */
    val leaks: Boolean get() = !ok && tell
}

/** Output of `camd hide-bootloader status`. */
@Immutable
data class HideBootloaderStatus(
    val enabled: Boolean,
    val props: List<PropCheck>,
    /** from /proc/bootconfig and /proc/cmdline: reported, never rewritten */
    val bootconfig: List<PropCheck>,
) {
    /**
     * Only properties count: SELinux keeps apps out of /proc/bootconfig and /proc/cmdline. Values a locked
     * device shows too (not [PropCheck.tell]) do not, as in `camd hiding-audit`.
     */
    val leaks: Int
        get() = props.count { it.leaks }

    /**
     * The bootloader verified boot against a user-set AVB key (avbroot and the like): AVB state yellow.
     * Read from the bootloader's own arguments first, since hiding the bootloader rewrites the property
     * to green. Such a device must never get an on-device patched boot: it would fail verification.
     */
    val customAvbKey: Boolean
        get() {
            val state = bootconfig.firstOrNull { it.name == "androidboot.verifiedbootstate" }
                ?: props.firstOrNull { it.name == "ro.boot.verifiedbootstate" }
            return state?.value.equals("yellow", ignoreCase = true)
        }

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
                    tell = item.optBoolean("tell", true),
                )
            }
    }
}
