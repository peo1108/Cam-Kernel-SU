package cam.su.kernel.ui.component.skeleton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeletonTimingTest {

    @Test
    fun fastLoadNeverShows() {
        // done at 100 ms, before the 150 ms delay: nothing at any time
        for (now in listOf(0L, 100L, 150L, 400L)) assertFalse(skeletonShown(0, finishedAt = 100, now = now))
        assertNull(skeletonNextChange(0, finishedAt = 100, now = 100))
    }

    @Test
    fun slowLoadShowsAfterTheDelay() {
        assertFalse(skeletonShown(0, finishedAt = null, now = 149))
        assertTrue(skeletonShown(0, finishedAt = null, now = 150))
        assertEquals(150L, skeletonNextChange(0, finishedAt = null, now = 20))
        // still loading and already shown: nothing to wait for until the load ends
        assertNull(skeletonNextChange(0, finishedAt = null, now = 200))
    }

    @Test
    fun shownSkeletonStaysAtLeastTheMinimum() {
        // appeared at 150, load done at 200: stays until 150 + 300
        assertTrue(skeletonShown(0, finishedAt = 200, now = 449))
        assertFalse(skeletonShown(0, finishedAt = 200, now = 450))
        assertEquals(450L, skeletonNextChange(0, finishedAt = 200, now = 200))
    }

    @Test
    fun longLoadHidesAsSoonAsItEnds() {
        assertTrue(skeletonShown(0, finishedAt = null, now = 2_000))
        assertFalse(skeletonShown(0, finishedAt = 2_000, now = 2_000))
        assertNull(skeletonNextChange(0, finishedAt = 2_000, now = 2_000))
    }
}
