package cam.su.kernel.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferRateTest {
    private val ms = 1_000_000L
    private val mb = 1024L * 1024

    @Test
    fun noRateUntilHalfASecondOfSamples() {
        val rate = TransferRate()
        rate.add(0, 0)
        rate.add(5 * mb, 300 * ms)
        assertNull(rate.perSecond())
        assertNull(rate.secondsLeft(5 * mb, 100 * mb))
        rate.add(10 * mb, 600 * ms)
        assertEquals(10.0 * mb / 0.6, rate.perSecond()!!, 1.0)
    }

    @Test
    fun steadyRateGivesTimeLeft() {
        val rate = TransferRate()
        for (i in 0..20) rate.add(i * mb, i * 100 * ms) // 10 MB/s
        assertEquals(10.0 * mb, rate.perSecond()!!, 1.0)
        assertEquals(5L, rate.secondsLeft(50 * mb, 100 * mb))
        // rounded up: 0.1 s left is still "1 s"
        assertEquals(1L, rate.secondsLeft(99 * mb, 100 * mb))
        assertEquals(0L, rate.secondsLeft(100 * mb, 100 * mb))
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        val rate = TransferRate(windowNanos = 2_000 * ms)
        // 2 s at 100 MB/s, then 4 s at 1 MB/s: only the slow part is left in the window
        for (i in 0..20) rate.add(i * 10 * mb, i * 100 * ms)
        val fast = 200 * mb
        for (i in 1..40) rate.add(fast + i * mb / 10, 2_000 * ms + i * 100 * ms)
        assertEquals(1.0 * mb, rate.perSecond()!!, 0.05 * mb)
    }

    @Test
    fun goingBackwardsStartsOver() {
        val rate = TransferRate()
        for (i in 0..10) rate.add(i * mb, i * 100 * ms)
        rate.add(0, 1_100 * ms)
        assertNull(rate.perSecond())
    }

    @Test
    fun aStallHasNoTimeLeft() {
        val rate = TransferRate()
        for (i in 0..10) rate.add(mb, i * 100 * ms)
        assertEquals(0.0, rate.perSecond()!!, 0.0)
        assertNull(rate.secondsLeft(mb, 10 * mb))
    }

    @Test
    fun unknownTotalHasNoTimeLeft() {
        val rate = TransferRate()
        for (i in 0..10) rate.add(i * mb, i * 100 * ms)
        assertNull(rate.secondsLeft(5 * mb, 0))
    }
}
