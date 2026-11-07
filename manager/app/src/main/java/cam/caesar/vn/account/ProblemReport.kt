package cam.caesar.vn.account

import org.json.JSONObject

/**
 * A problem report for Cam (site/api/routes/report.mjs, docs/CAM_CHANGES.md row 98): named texts
 * the Manager gathered ([sections]), a few short facts for the list in /quan-tri ([meta]) and what
 * the customer wrote ([note]). Built only through [build], which keeps it under the server's
 * limits; the gathering itself is ProblemReportCollect.kt (root shell, Android only).
 */
class ProblemReport private constructor(
    val meta: Map<String, String>,
    val note: String,
    val sections: Map<String, String>,
) {
    fun toJson(): String = JSONObject()
        .put("meta", JSONObject(meta))
        .put("note", note)
        .put("sections", JSONObject(sections))
        .toString()

    companion object {
        /** Per section, below the server's 512 KiB. */
        const val SECTION_MAX = 256 * 1024

        /** All sections, below the server's 1.5 MiB. */
        const val TOTAL_MAX = 1200 * 1024

        /** The JSON sent, below the server's 2 MiB body limit (non-ASCII logs take 2-3 bytes a char). */
        const val BODY_MAX = 1900 * 1024

        const val NOTE_MAX = 2000

        /** Logs: what matters is at their end. Other sections are cut at their end instead. */
        val TAIL_SECTIONS = setOf("cam_log", "pstore", "dmesg", "logcat", "logcat_crash")

        private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
        private const val SECTIONS_MAX = 24
        private const val META_MAX = 120
        private const val SHRINK_FLOOR = 4096

        /**
         * The report from what was gathered, in the order given. Every section is cut to
         * [SECTION_MAX]; when they still add up to more than [TOTAL_MAX] (or the JSON to more than
         * [BODY_MAX]) the biggest ones are cut further. A cut says how much went.
         */
        fun build(meta: Map<String, String>, note: String, sections: List<Pair<String, String>>): ProblemReport {
            val kept = LinkedHashMap<String, String>()
            for ((name, text) in sections) {
                require(NAME.matches(name)) { "bad section name $name" }
                if (kept.size == SECTIONS_MAX) break
                kept[name] = cut(name, text, SECTION_MAX)
            }
            val cleanMeta = meta.filterValues { it.isNotBlank() }.mapValues { it.value.trim().take(META_MAX) }
            val cleanNote = note.trim().take(NOTE_MAX)
            var budget = TOTAL_MAX
            while (true) {
                shrink(kept, budget)
                val report = ProblemReport(cleanMeta, cleanNote, LinkedHashMap(kept))
                if (report.toJson().toByteArray().size <= BODY_MAX || budget <= SHRINK_FLOOR * kept.size) return report
                budget = budget * 3 / 4
            }
        }

        /** Cuts the biggest sections until all of them fit in [budget] characters. */
        private fun shrink(sections: MutableMap<String, String>, budget: Int) {
            var total = sections.values.sumOf { it.length }
            while (total > budget) {
                val (name, text) = sections.entries.maxBy { it.value.length }
                if (text.length <= SHRINK_FLOOR) return
                val target = maxOf(SHRINK_FLOOR, text.length - (total - budget))
                val shorter = cut(name, text, target)
                if (shorter.length >= text.length) return
                sections[name] = shorter
                total += shorter.length - text.length
            }
        }

        /**
         * [text] in at most [max] characters: a log keeps its end, anything else its start, both
         * on whole lines where there are some, with a line saying how much was cut.
         */
        internal fun cut(name: String, text: String, max: Int): String {
            if (text.length <= max) return text
            val tail = name in TAIL_SECTIONS
            // room for the marker, whatever the number in it
            val room = (max - 64).coerceAtLeast(0)
            return if (tail) {
                var start = text.length - room
                val nl = text.indexOf('\n', start)
                if (nl in start until text.length - 1) start = nl + 1
                "[... ${start} characters cut from the start ...]\n" + text.substring(start)
            } else {
                var end = room
                val nl = text.lastIndexOf('\n', end)
                if (nl > 0) end = nl
                text.substring(0, end) + "\n[... ${text.length - end} characters cut from the end ...]"
            }
        }
    }
}

/** The report's code from the server's answer (`{"code":"R7K2Q9X"}`), or null. */
internal fun parseReportCode(json: String): String? = runCatching {
    JSONObject(json).optString("code").takeIf { Regex("^R[A-Z0-9]{6}$").matches(it) }
}.getOrNull()
