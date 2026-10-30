package cam.su.kernel.ui.component.listdetail

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDetailMathTest {

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }

    @Test
    fun splitsOnALandscapeTabletOnly() {
        // TB323FU landscape at 374 dpi: 1301 dp, 80 of it the side bar's column
        assertTrue(showListDetail(1301f - 80f))
        // the largest page scale (110%)
        assertTrue(showListDetail(1301f / 1.1f - 80f))
        // the same tablet upright, and a phone
        assertFalse(showListDetail(815f))
        assertFalse(showListDetail(411f))
        assertTrue(showListDetail(900f))
        assertFalse(showListDetail(899.9f))
    }

    @Test
    fun dropGlidesFromTheOldRowToTheNewOne() {
        val old = Rect(12f, 100f, 452f, 164f)
        val new = Rect(12f, 404f, 452f, 468f)
        val delta = Rect(old.left - new.left, old.top - new.top, old.right - new.right, old.bottom - new.bottom)
        assertRect(old, glidingRect(new, delta, 0f))
        assertRect(new, glidingRect(new, delta, 1f))
        assertRect(Rect(12f, 252f, 452f, 316f), glidingRect(new, delta, 0.5f))
    }

    @Test
    fun dropRidesAlongWhenTheListScrollsMidGlide() {
        val delta = Rect(0f, -300f, 0f, -300f)
        val scrolled = Rect(12f, 304f, 452f, 368f)
        // halfway, after the list moved up 100 px: still halfway between the rows, 100 px higher
        assertRect(Rect(12f, 154f, 452f, 218f), glidingRect(scrolled, delta, 0.5f))
    }

    @Test
    fun copyStartsOverItsRowAndLandsInPlace() {
        val from = Rect(22f, 300f, 70f, 348f)   // the row's 48 dp icon
        val to = Rect(500f, 90f, 564f, 154f)    // the header's 64 dp icon
        val start = flightFrame(from, to, 0f)
        assertEquals(from.left - to.left, start.translation.x, 0.01f)
        assertEquals(from.top - to.top, start.translation.y, 0.01f)
        assertEquals(0.75f, start.scale, 0.001f)
        val end = flightFrame(from, to, 1f)
        assertEquals(0f, end.translation.x, 0.01f)
        assertEquals(0f, end.translation.y, 0.01f)
        assertEquals(1f, end.scale, 0.001f)
    }

    @Test
    fun cardsLandOneAfterAnotherDownThePane() {
        // halfway through a pick the top card is further along than one lower down
        val top = panePieceProgress(0.5f, 0.1f, 0f)
        val lower = panePieceProgress(0.5f, 0.6f, 0f)
        assertTrue(top > lower)
        // and the right column a little behind the left one at the same height
        assertTrue(panePieceProgress(0.5f, 0.3f, 0f) > panePieceProgress(0.5f, 0.3f, 0.5f))
        // nothing has moved at the start, everything has landed at the end
        assertEquals(0f, panePieceProgress(0f, 0f, 0f), 0.001f)
        assertEquals(1f, panePieceProgress(1f, 1f, 1f), 0.001f)
    }

    @Test
    fun aCardStartsPartWayTowardThePickedRow() {
        val row = Offset(200f, 600f)
        val card = Offset(900f, 300f)
        val start = panePieceOffset(row, card, 0f)
        assertEquals((row.x - card.x) * PIECE_REACH, start.x, 0.01f)
        assertEquals((row.y - card.y) * PIECE_REACH, start.y, 0.01f)
        val landed = panePieceOffset(row, card, 1f)
        assertEquals(0f, landed.x, 0.001f)
        assertEquals(0f, landed.y, 0.001f)
    }

    @Test
    fun headerTextIsInBeforeTheIconAndNameLand() {
        assertEquals(0f, headerRevealAlpha(0.2f), 0.001f)
        assertEquals(1f, headerRevealAlpha(FLIGHT_END), 0.001f)
    }

    @Test
    fun aNameKeepsItsShapeWhenClippedNarrowerInItsRow() {
        val row = Rect(70f, 300f, 270f, 321f)      // one line, clipped by the row
        val header = Rect(500f, 90f, 900f, 111f)   // the same line, whole
        assertEquals(1f, flightFrame(row, header, 0f).scale, 0.001f)
    }
}
