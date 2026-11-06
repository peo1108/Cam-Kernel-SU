package cam.caesar.vn.ui.screen.modulerepo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RepoDateTest {

    @Test
    fun isoTimeBecomesALocalDate() {
        // midday UTC: the same calendar day in every time zone from -11 to +11
        val shown = repoDate("2026-11-03T12:00:00Z", Locale.US)
        assertEquals("Nov 3, 2026", shown)
    }

    @Test
    fun vietnameseDateHasNoIsoLeftovers() {
        val shown = repoDate("2026-11-03T12:00:00Z", Locale.forLanguageTag("vi-VN"))
        assertTrue(shown, shown.contains("2026"))
        assertFalse(shown, shown.contains("T12") || shown.endsWith("Z"))
    }

    @Test
    fun anythingElseIsShownAsItCame() {
        assertEquals("", repoDate(""))
        assertEquals("yesterday", repoDate("yesterday"))
        assertEquals("2026-11-03", repoDate("2026-11-03"))
    }
}
