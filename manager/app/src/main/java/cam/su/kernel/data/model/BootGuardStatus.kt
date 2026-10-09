package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Output of `camd boot-guard status`. [autoDisabled] only lists modules that are still disabled.
 * `lastTrigger` is left out: post-fs-data runs before the clock is synced.
 */
@Immutable
data class BootGuardStatus(
    val failCount: Int,
    /** boot attempt that triggers: failed boots tolerated + 1 */
    val threshold: Int,
    val autoDisabled: List<String>,
    val enabled: Boolean = true,
    /** disable every enabled module instead of the suspects first */
    val disableAll: Boolean = false,
) {
    companion object {
        val Empty = BootGuardStatus(failCount = 0, threshold = 3, autoDisabled = emptyList())

        fun parse(json: String): BootGuardStatus = runCatching {
            val obj = JSONObject(json)
            val ids = obj.optJSONArray("autoDisabled")
            BootGuardStatus(
                failCount = obj.optInt("failCount", 0),
                threshold = obj.optInt("threshold", Empty.threshold),
                autoDisabled = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optString(it)?.takeIf(String::isNotEmpty) },
                enabled = obj.optBoolean("enabled", true),
                disableAll = obj.optString("mode") == "all",
            )
        }.getOrDefault(Empty)
    }
}
