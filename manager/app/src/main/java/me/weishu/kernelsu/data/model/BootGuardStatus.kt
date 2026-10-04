package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Output of `ksud boot-guard status`. [autoDisabled] only lists modules that are still disabled.
 * `lastTrigger` is left out: post-fs-data runs before the clock is synced.
 */
@Immutable
data class BootGuardStatus(
    val failCount: Int,
    val threshold: Int,
    val autoDisabled: List<String>,
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
            )
        }.getOrDefault(Empty)
    }
}
