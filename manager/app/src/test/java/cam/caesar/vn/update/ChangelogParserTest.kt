package cam.su.kernel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogParserTest {

    private val text = """
        # Nhật ký cập nhật Cam Kernel SU

        ## 3.0.1 - 2026-10-20
        ### Sửa lỗi
        - Sửa lỗi A

        ## 3.0.0 - 2026-10-12
        - Thêm OTA
    """.trimIndent()

    private fun entry(version: String) = ChangelogEntry(version, "2026-10-10", "- $version")

    private fun versions(entries: List<ChangelogEntry>) = entries.map { it.version }

    @Test
    fun parsesEntriesInFileOrder() {
        val entries = ChangelogParser.parse(text)
        assertEquals(listOf("3.0.1", "3.0.0"), versions(entries))
        assertEquals("2026-10-20", entries[0].date)
        assertEquals("### Sửa lỗi\n- Sửa lỗi A", entries[0].body)
        assertEquals("- Thêm OTA", entries[1].body)
    }

    @Test
    fun crlfMatchesLf() {
        assertEquals(ChangelogParser.parse(text), ChangelogParser.parse(text.replace("\n", "\r\n")))
    }

    @Test
    fun semverRejectsNonPure() {
        assertNull(parseSemver("3.0.0-5-gabc1234"))
        assertNull(parseSemver("21536a17"))
        assertNull(parseSemver("v3.0.0"))
        assertEquals(Triple(3, 10, 0), parseSemver("3.10.0"))
    }

    @Test
    fun newerThanOrdersBySemver() {
        val entries = listOf(entry("3.9.0"), entry("3.10.0"), entry("3.2.0"))
        assertEquals(listOf("3.10.0", "3.9.0"), versions(ChangelogParser.entriesNewerThan(entries, "3.2.0", "3.10.0")))
    }

    @Test
    fun skippedVersionsAreAggregated() {
        val entries = listOf(entry("3.2.0"), entry("3.1.0"), entry("3.0.0"))
        assertEquals(listOf("3.2.0", "3.1.0"), versions(ChangelogParser.entriesNewerThan(entries, "3.0.0", "3.2.0")))
    }

    @Test
    fun nonSemverCurrentGivesNothing() {
        val entries = listOf(entry("3.0.0"))
        assertTrue(ChangelogParser.entriesNewerThan(entries, null, "3.0.0-5-gabc").isEmpty())
    }

    @Test
    fun shippedChangelogParses() {
        // runs from manager/app, like HidingRulesRepositoryTest
        val entries = ChangelogParser.parse(File("../../CHANGELOG.md").readText())
        assertTrue(entries.isNotEmpty())
        assertNotNull(parseSemver(entries[0].version))
        assertTrue(entries[0].body.isNotBlank())
    }
}
