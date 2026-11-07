package cam.caesar.vn.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report the Manager sends to site/api/routes/report.mjs: layout and the size limits it keeps. */
class ProblemReportTest {

    private fun lines(prefix: String, count: Int) = (1..count).joinToString("\n") { "$prefix line $it ${"x".repeat(60)}" }

    @Test
    fun jsonLayoutKeepsText() {
        val r = ProblemReport.build(
            meta = mapOf("model" to " Lenovo TB323FU ", "cam" to "1.2.5 (33240)", "rules" to ""),
            note = "  Máy khởi động lại\nkhi bật ẩn SELinux  ",
            sections = listOf("basic" to "Model: TB323FU\n\tSDK: 36", "logcat" to "a\nb"),
        )
        val o = JSONObject(r.toJson())
        assertEquals("Máy khởi động lại\nkhi bật ẩn SELinux", o.getString("note"))
        assertEquals("Lenovo TB323FU", o.getJSONObject("meta").getString("model"))
        assertTrue("blank meta is left out", !o.getJSONObject("meta").has("rules"))
        val sections = o.getJSONObject("sections")
        // /quan-tri lays the sections out in its own order: the JSON's order does not matter
        assertEquals(setOf("basic", "logcat"), sections.keys().asSequence().toSet())
        assertEquals(listOf("basic", "logcat"), r.sections.keys.toList())
        assertEquals("Model: TB323FU\n\tSDK: 36", sections.getString("basic"))
    }

    @Test
    fun aLogKeepsItsEndOnWholeLines() {
        val log = lines("log", 10_000)
        val cut = ProblemReport.cut("logcat", log, 50_000)
        assertTrue(cut.length <= 50_000)
        assertTrue(cut.startsWith("[... "))
        assertTrue(cut.endsWith("log line 10000 ${"x".repeat(60)}"))
        // the first kept line is whole
        assertTrue(cut.lines()[1].startsWith("log line "))
        val dropped = Regex("""\[\.\.\. (\d+) characters cut from the start""").find(cut)!!.groupValues[1].toInt()
        assertEquals(log.length, dropped + cut.substringAfter('\n').length)
    }

    @Test
    fun otherSectionsKeepTheirStart() {
        val mounts = lines("mount", 10_000)
        val cut = ProblemReport.cut("mounts", mounts, 50_000)
        assertTrue(cut.length <= 50_000)
        assertTrue(cut.startsWith("mount line 1 "))
        assertTrue(cut.endsWith("characters cut from the end ...]"))
        val kept = cut.substringBeforeLast('\n')
        assertTrue("cut on a line end", mounts.startsWith(kept) && mounts[kept.length] == '\n')
        assertEquals("short text is left alone", "abc", ProblemReport.cut("mounts", "abc", 10))
    }

    @Test
    fun everySectionAndTheWholeFitTheServerLimits() {
        val r = ProblemReport.build(
            meta = emptyMap(),
            note = "n".repeat(5000),
            sections = listOf(
                "basic" to "small",
                "logcat" to lines("logcat", 20_000),
                "dmesg" to lines("dmesg", 20_000),
                "mounts" to lines("mount", 20_000),
                "pstore" to lines("pstore", 20_000),
                "cam_log" to lines("cam", 20_000),
                "hiding_audit" to lines("audit", 20_000),
            ),
        )
        assertEquals(ProblemReport.NOTE_MAX, r.note.length)
        assertTrue(r.sections.values.all { it.length <= ProblemReport.SECTION_MAX })
        assertTrue(r.sections.values.sumOf { it.length } <= ProblemReport.TOTAL_MAX)
        assertEquals("small sections are not cut to make room", "small", r.sections["basic"])
        assertTrue(r.toJson().toByteArray().size <= ProblemReport.BODY_MAX)
    }

    @Test
    fun wideCharactersStillFitTheBody() {
        // Vietnamese and Chinese log lines: 3 UTF-8 bytes a character, so the character budget is not enough
        val wide = (1..20_000).joinToString("\n") { "日志行 $it ${"ẩn".repeat(30)}" }
        val r = ProblemReport.build(emptyMap(), "", listOf("logcat" to wide, "dmesg" to wide, "cam_log" to wide, "pstore" to wide, "mounts" to wide))
        assertTrue(r.toJson().toByteArray().size <= ProblemReport.BODY_MAX)
        assertTrue("logs keep their end", r.sections.getValue("logcat").endsWith("日志行 20000 ${"ẩn".repeat(30)}"))
    }

    @Test
    fun badSectionNamesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { ProblemReport.build(emptyMap(), "", listOf("Bad Name" to "x")) }
    }

    @Test
    fun reportCode() {
        assertEquals("R7K2Q9X", parseReportCode("""{"code":"R7K2Q9X"}"""))
        assertNull(parseReportCode("""{"error":"slow_down"}"""))
        assertNull(parseReportCode("""{"code":"../x"}"""))
        assertNull(parseReportCode("not json"))
    }
}
