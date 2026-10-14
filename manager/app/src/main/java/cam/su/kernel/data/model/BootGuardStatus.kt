package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** Why camd names a module (or initrc.d file) as the likely cause of the failed boots. */
enum class BootGuardReason(val key: String) {
    /** installed, updated or enabled again since the last good boot */
    Changed("changed"),

    /** has an init rc, the only module part that runs that early */
    InitRc("init_rc"),

    /** a file in /data/adb/initrc.d */
    InitrcD("initrc_d"),

    /** the failed boot's logs name it */
    Log("log");

    companion object {
        fun of(key: String): BootGuardReason? = entries.firstOrNull { it.key == key }
    }
}

@Immutable
data class BootGuardCulprit(
    /** a module id, or [INITRC_PREFIX] + a file name */
    val id: String,
    val reasons: List<BootGuardReason>,
    /** a line of the failed boot's logs that names it */
    val evidence: String? = null,
) {
    /** the /data/adb/initrc.d file name, when this is not a module */
    val initrcName: String? get() = id.removePrefix(INITRC_PREFIX).takeIf { id.startsWith(INITRC_PREFIX) }

    companion object {
        const val INITRC_PREFIX = "initrc.d/"
    }
}

/**
 * Output of `camd boot-guard status`. When it triggers, camd disables every module; [culprits]
 * are the likely causes, most likely first. [autoDisabled], [initrcDisabled] and [culprits] only
 * list what is still off. `lastTrigger` is left out: post-fs-data runs before the clock is synced.
 */
@Immutable
data class BootGuardStatus(
    val failCount: Int,
    /** boot attempt that triggers: failed boots tolerated + 1 */
    val threshold: Int,
    val autoDisabled: List<String>,
    val enabled: Boolean = true,
    /** /data/adb/initrc.d files camd took the exec bit from */
    val initrcDisabled: List<String> = emptyList(),
    val culprits: List<BootGuardCulprit> = emptyList(),
) {
    /** whether there is anything for the user to look at */
    val hasNotice: Boolean get() = autoDisabled.isNotEmpty() || initrcDisabled.isNotEmpty()

    /** the module ids among [culprits] */
    val culpritModules: Set<String> get() = culprits.filter { it.initrcName == null }.mapTo(HashSet()) { it.id }

    companion object {
        val Empty = BootGuardStatus(failCount = 0, threshold = 3, autoDisabled = emptyList())

        private fun JSONArray?.strings(): List<String> =
            (0 until (this?.length() ?: 0)).mapNotNull { this?.optString(it)?.takeIf(String::isNotEmpty) }

        fun parse(json: String): BootGuardStatus = runCatching {
            val obj = JSONObject(json)
            val culprits = obj.optJSONArray("culprits")
            BootGuardStatus(
                failCount = obj.optInt("failCount", 0),
                threshold = obj.optInt("threshold", Empty.threshold),
                autoDisabled = obj.optJSONArray("autoDisabled").strings(),
                enabled = obj.optBoolean("enabled", true),
                initrcDisabled = obj.optJSONArray("initrcDisabled").strings(),
                culprits = (0 until (culprits?.length() ?: 0)).mapNotNull { i ->
                    val item = culprits?.optJSONObject(i) ?: return@mapNotNull null
                    val id = item.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    BootGuardCulprit(
                        id = id,
                        reasons = item.optJSONArray("reasons").strings().mapNotNull(BootGuardReason::of),
                        evidence = item.optString("evidence").takeIf { !item.isNull("evidence") && it.isNotEmpty() },
                    )
                },
            )
        }.getOrDefault(Empty)
    }
}
