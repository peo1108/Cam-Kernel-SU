package cam.su.kernel.hiding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttrTimingTest {

    private fun pairs(count: Int, a: (Int) -> Long, b: (Int) -> Long) =
        LongArray(count * 2) { if (it % 2 == 0) a(it / 2) else b(it / 2) }

    @Test
    fun hookParsingFirstIsFound() {
        // what Duck Detector saw on Cam before a3803700: A about 780 ns slower in 255 of 256 pairs
        val result = AttrTiming.evaluate(pairs(256, { if (it == 0) 1000 else 2136 }, { 1355 }))
        result as AttrTiming.Measured
        assertEquals(781, result.gapMedianNs)
        assertEquals(255, result.aSlower)
        assertTrue(result.leaks)
    }

    @Test
    fun equalTimesAreClean() {
        val result = AttrTiming.evaluate(pairs(256, { 1300L + it % 7 }, { 1300L + (it + 3) % 7 })) as AttrTiming.Measured
        assertFalse(result.leaks)
    }

    @Test
    fun aGapInOneHalfOnlyIsClean() {
        // a burst of noise (frequency change) in the first half must not count
        val result = AttrTiming.evaluate(pairs(256, { if (it < 128) 2500 else 1300 }, { 1300 })) as AttrTiming.Measured
        assertEquals(1200, result.firstHalfNs)
        assertFalse(result.leaks)
    }

    @Test
    fun failureCodesAreUnavailable() {
        assertEquals(AttrTiming.Unavailable(-3), AttrTiming.evaluate(longArrayOf(-3)))
        assertEquals(AttrTiming.Unavailable(0), AttrTiming.evaluate(null))
    }

    @Test
    fun rulesCarryPendingChecks() {
        val shipped = HidingRules.parse(java.io.File("src/main/assets/hiding-rules.json").readText())!!
        assertEquals(listOf("heap residue"), shipped.duckPending)
        // a copy written back keeps them; rules from before version 3 have none
        assertEquals(shipped, HidingRules.parse(shipped.toJson()))
        val old = shipped.toJson().replace(Regex(",\"duckPending\":\\[[^]]*]"), "")
        assertEquals(emptyList<String>(), HidingRules.parse(old)!!.duckPending)
    }
}
