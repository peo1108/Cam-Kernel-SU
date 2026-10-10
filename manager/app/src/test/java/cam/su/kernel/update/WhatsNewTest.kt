package cam.su.kernel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {

    private fun entries(vararg versions: String) = versions.map { ChangelogEntry(it, "2026-10-10", "- $it") }

    private val all = entries("3.2.0", "3.1.0", "3.0.1", "3.0.0")

    @Test
    fun freshInstallSavesWithoutShowing() {
        val d = decideWhatsNew(null, "3.0.1", all)
        assertTrue(d.show.isEmpty())
        assertEquals("3.0.1", d.saveLastSeen)
    }

    @Test
    fun upgradeShowsNewerEntries() {
        val d = decideWhatsNew("3.0.0", "3.2.0", entries("3.2.0", "3.1.0", "3.0.0"))
        assertEquals(listOf("3.2.0", "3.1.0"), d.show.map { it.version })
        assertEquals("3.2.0", d.saveLastSeen)
    }

    @Test
    fun devBuildDoesNothing() {
        for (current in listOf("3.0.0-5-gabc1234", "21536a17")) {
            val d = decideWhatsNew("3.0.0", current, all)
            assertTrue(d.show.isEmpty())
            assertNull(d.saveLastSeen)
        }
    }

    @Test
    fun sameVersionDoesNothing() {
        val d = decideWhatsNew("3.0.1", "3.0.1", all)
        assertTrue(d.show.isEmpty())
        assertNull(d.saveLastSeen)
    }

    @Test
    fun upgradeWithoutEntrySavesSilently() {
        val d = decideWhatsNew("3.0.0", "3.0.1", entries("3.0.0"))
        assertTrue(d.show.isEmpty())
        assertEquals("3.0.1", d.saveLastSeen)
    }

    @Test
    fun downgradeSavesSilently() {
        val d = decideWhatsNew("3.1.0", "3.0.1", all)
        assertTrue(d.show.isEmpty())
        assertEquals("3.0.1", d.saveLastSeen)
    }
}
