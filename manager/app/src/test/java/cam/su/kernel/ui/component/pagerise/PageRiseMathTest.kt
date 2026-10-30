package cam.su.kernel.ui.component.pagerise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRiseMathTest {

    @Test
    fun theFirstCardLeadsAndTheLastLandsAsTheRiseEnds() {
        assertEquals(0f, riseTime(0f, 0f, 0f), 0.001f)
        assertTrue(riseTime(0.2f, 0f, 0f) > 0f)
        // the bottom of the page is still waiting
        assertEquals(0f, riseTime(0.2f, 1f, 0f), 0.001f)
        assertEquals(1f, riseTime(1f, 1f, 1f), 0.001f)
    }

    @Test
    fun aCardBelowThePageWaitsNoLongerThanTheBottomOne() {
        assertEquals(riseTime(0.5f, 1f, 0f), riseTime(0.5f, 3f, 0f), 0.001f)
    }

    @Test
    fun thePathStartsStillAndLandsExactly() {
        assertEquals(0f, riseProgress(0f), 0.001f)
        assertEquals(1f, riseProgress(1f), 0.001f)
        // soft start: well behind a straight line at first
        assertTrue(riseProgress(0.05f) < 0.05f)
        var last = 0f
        for (i in 1..100) {
            val next = riseProgress(i / 100f)
            assertTrue(next >= last)
            last = next
        }
    }

    @Test
    fun lowerCardsComeFromFurther() {
        assertEquals(56f, riseDistanceDp(0f), 0.001f)
        assertEquals(120f, riseDistanceDp(1f), 0.001f)
        assertEquals(120f, riseDistanceDp(2f), 0.001f)
    }

    @Test
    fun aCardShowsEarlyInItsRise() {
        assertEquals(0f, riseAlpha(0f), 0.001f)
        assertEquals(1f, riseAlpha(0.35f), 0.001f)
        assertEquals(1f, riseAlpha(1f), 0.001f)
    }
}
