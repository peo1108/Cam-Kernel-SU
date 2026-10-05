package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Output of `ksud ak3-check status`: the check the first boot after `flash-ak3` runs on the
 * new kernel. [pending] while the flashed kernel has not been booted yet.
 */
@Immutable
data class KernelCheck(
    val pending: Boolean = false,
    /** the last outcome; null when none is kept (never flashed, or dismissed) */
    val result: Result? = null,
) {
    @Immutable
    data class Result(
        val ok: Boolean,
        val zip: String,
        /** null when the zip did not say which kernel it carries */
        val expectedRelease: String?,
        val runningRelease: String,
        val builtIn: Boolean,
        /** SUSFS version of the running kernel, null without SUSFS */
        val susfs: String?,
        /** ksud's codes: [PROBLEM_RELEASE], [PROBLEM_LKM], [PROBLEM_SUSFS] */
        val problems: List<String>,
        /** the boot backup made before flashing, to restore from */
        val backup: String?,
    )

    companion object {
        const val PROBLEM_RELEASE = "release"
        const val PROBLEM_LKM = "lkm"
        const val PROBLEM_SUSFS = "susfs"

        val Empty = KernelCheck()

        fun parse(json: String): KernelCheck = runCatching {
            val obj = JSONObject(json)
            val result = obj.optJSONObject("result")?.let { r ->
                val expected = r.optJSONObject("expected") ?: JSONObject()
                val running = r.optJSONObject("running") ?: JSONObject()
                val problems = r.optJSONArray("problems")
                Result(
                    ok = r.optBoolean("ok"),
                    zip = r.stringOrNull("zip").orEmpty(),
                    expectedRelease = expected.stringOrNull("release"),
                    runningRelease = running.stringOrNull("release").orEmpty(),
                    builtIn = running.optBoolean("builtIn"),
                    susfs = running.stringOrNull("susfs"),
                    problems = (0 until (problems?.length() ?: 0)).mapNotNull {
                        problems?.optString(it)?.takeIf(String::isNotEmpty)
                    },
                    backup = r.stringOrNull("backup"),
                )
            }
            KernelCheck(pending = obj.optBoolean("pending"), result = result)
        }.getOrDefault(Empty)

        /** optString turns a JSON null into "null" */
        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
    }
}
