package cam.su.kernel.ui.component.expand

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandMathTest {

    private val page = Size(1000f, 2000f)
    private val row = Rect(100f, 400f, 900f, 520f)

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }

    @Test
    fun progressMapsDepthToZeroOne() {
        assertEquals(0f, expandProgress(-1f), 0.001f)
        assertEquals(0.25f, expandProgress(-0.75f), 0.001f)
        assertEquals(1f, expandProgress(0f), 0.001f)
        assertEquals(0f, expandProgress(-2f), 0.001f)
        assertEquals(1f, coverProgress(1.5f), 0.001f)
        assertEquals(0f, coverProgress(-0.2f), 0.001f)
    }

    @Test
    fun movingOnlyWhileGrowingOrShrinking() {
        assertTrue(expandMoving(-0.5f, running = true))
        // first frame of a push, before the animation reports running
        assertTrue(expandMoving(-1f, running = false))
        // a back swipe that has just started
        assertTrue(expandMoving(0f, running = true))
        assertFalse(expandMoving(0f, running = false))
        // covered by another page: drawn as covered, not as expanding
        assertFalse(expandMoving(0.5f, running = true))
    }

    @Test
    fun windowStartsOnTheTappedRowAndEndsAsThePage() {
        assertRect(row, containerRect(row, page, 0f))
        assertRect(Rect(Offset.Zero, page), containerRect(row, page, 1f))
    }

    @Test
    fun windowFollowsTheRowOnTheSinkingPage() {
        // halfway the covered page is scaled about its center; the window starts from where the
        // row is drawn then, so a back swipe never bends away from it
        val t = 0.5f
        val sunk = scaledAboutCenter(row, page, coveredScale(t))
        val window = containerRect(row, page, t)
        assertEquals((sunk.left + 0f) / 2f, window.left, 0.01f)
        assertEquals((sunk.top + 0f) / 2f, window.top, 0.01f)
        assertEquals((sunk.right + page.width) / 2f, window.right, 0.01f)
        assertEquals((sunk.bottom + page.height) / 2f, window.bottom, 0.01f)
    }

    @Test
    fun scaleAboutCenterKeepsTheCenterAndShrinksTheRest() {
        val center = Rect(400f, 900f, 600f, 1100f)
        val scaled = scaledAboutCenter(center, page, 0.5f)
        assertRect(Rect(450f, 950f, 550f, 1050f), scaled)
        assertRect(row, scaledAboutCenter(row, page, 1f))
    }

    @Test
    fun coveredPageSinksByEightPercent() {
        assertEquals(1f, coveredScale(0f), 0.0001f)
        assertEquals(0.92f, coveredScale(1f), 0.0001f)
    }

    @Test
    fun containerFadesInOverTheFirstThird() {
        assertEquals(0f, containerAlpha(0f), 0.001f)
        assertEquals(0.5f, containerAlpha(0.15f), 0.001f)
        assertEquals(1f, containerAlpha(0.3f), 0.001f)
        assertEquals(1f, containerAlpha(1f), 0.001f)
    }

    @Test
    fun barComesInLast() {
        assertEquals(0f, barAlpha(0.5f), 0.001f)
        assertEquals(1f, barAlpha(0.9f), 0.001f)
        assertTrue(barAlpha(0.7f) in 0.4f..0.6f)
    }

    @Test
    fun cornerGoesFromCardToScreen() {
        assertEquals(16f, containerCorner(16f, 40f, 0f), 0.001f)
        assertEquals(28f, containerCorner(16f, 40f, 0.5f), 0.001f)
        assertEquals(40f, containerCorner(16f, 40f, 1f), 0.001f)
    }

    @Test
    fun piecesLandTopToBottom() {
        val t = 0.4f
        val top = pieceProgress(t, 0f)
        val middle = pieceProgress(t, 0.5f)
        val bottom = pieceProgress(t, 1f)
        assertTrue(top > middle)
        assertTrue(middle > bottom)
        // nothing moves before the first piece starts, everything is in place at the end
        assertEquals(0f, pieceProgress(0.1f, 0f), 0.001f)
        assertEquals(1f, pieceProgress(1f, 0f), 0.001f)
        assertEquals(1f, pieceProgress(1f, 1f), 0.001f)
    }

    @Test
    fun pieceStartsOnTheSourceAndEndsInPlace() {
        val from = Offset(500f, 460f)
        val piece = Offset(500f, 1200f)
        assertOffset(Offset(0f, -740f), pieceOffset(from, piece, 0f))
        assertOffset(Offset(0f, -370f), pieceOffset(from, piece, 0.5f))
        assertOffset(Offset.Zero, pieceOffset(from, piece, 1f))
    }

    // Offset equality is bitwise: -0f != 0f
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test
    fun lostSourceShrinksToTheCenter() {
        val rect = fallbackRect(page)
        assertEquals(page.width / 2f, rect.center.x, 0.01f)
        assertEquals(page.height / 2f, rect.center.y, 0.01f)
        assertEquals(page.width * FALLBACK_SHARE, rect.width, 0.01f)
    }

    @Test
    fun rotationIsADifferentPage() {
        assertTrue(samePageSize(page, Size(1000.4f, 1999.6f)))
        assertFalse(samePageSize(page, Size(2000f, 1000f)))
    }

    @Test
    fun openReplaysTheTimeTheFirstCompositionTook() {
        // the page composed when the push was 40% done: that point plays as the start
        val from = openStart(0.4f)
        assertEquals(0.4f, from, 0.001f)
        assertEquals(0f, openProgress(0.4f, from), 0.001f)
        assertEquals(0.5f, openProgress(0.7f, from), 0.001f)
        assertEquals(1f, openProgress(1f, from), 0.001f)
        assertEquals(0f, openProgress(0.1f, from), 0.001f)
        // nothing to replay once settled, or when the page composed almost at the end
        assertEquals(0.3f, openProgress(0.3f, 0f), 0.001f)
        assertEquals(0f, openStart(0.95f), 0.001f)
        assertEquals(0f, openStart(-0.2f), 0.001f)
    }

    @Test
    fun launchNeedsAVisibleSourceAndAnimations() {
        assertTrue(expandCanLaunch(animationsOff = false, sourceVisible = 1f))
        assertTrue(expandCanLaunch(animationsOff = false, sourceVisible = 0.9f))
        assertFalse(expandCanLaunch(animationsOff = false, sourceVisible = 0.5f))
        assertFalse(expandCanLaunch(animationsOff = true, sourceVisible = 1f))
    }
}
