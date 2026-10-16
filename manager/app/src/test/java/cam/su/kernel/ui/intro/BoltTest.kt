package cam.su.kernel.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class BoltTest {

    private fun bolt(seed: Int, rough: Float = 0.1f, ax: Float = 10f, ay: Float = 20f, bx: Float = 310f, by: Float = 420f): FloatArray {
        val out = FloatArray(Bolt.POINTS * 2)
        assertEquals(Bolt.POINTS, Bolt.build(ax, ay, bx, by, seed, rough = rough, out = out))
        return out
    }

    @Test
    fun hasThirtyThreePoints() {
        assertEquals(33, Bolt.POINTS)
    }

    @Test
    fun endsOnTheEndpoints() {
        for (seed in 0..20) {
            val p = bolt(seed)
            assertEquals(10f, p[0], 0.001f)
            assertEquals(20f, p[1], 0.001f)
            assertEquals(310f, p[2 * (Bolt.POINTS - 1)], 0.001f)
            assertEquals(420f, p[2 * (Bolt.POINTS - 1) + 1], 0.001f)
        }
    }

    @Test
    fun staysWithinTwiceTheRoughness() {
        val len = hypot(300f, 400f)
        for (seed in 0..200) {
            val p = bolt(seed, rough = 0.12f)
            for (i in 0 until Bolt.POINTS) {
                // distance from the straight segment's line
                val d = abs((p[2 * i] - 10f) * 400f - (p[2 * i + 1] - 20f) * 300f) / len
                assertTrue("seed $seed point $i strays $d", d <= 2f * 0.12f * len)
            }
        }
    }

    @Test
    fun seedDecidesTheShape() {
        assertTrue(bolt(7).contentEquals(bolt(7)))
        assertFalse(bolt(7).contentEquals(bolt(8)))
    }

    @Test
    fun actuallyJags() {
        val p = bolt(3)
        val len = hypot(300f, 400f)
        val off = (1 until Bolt.POINTS - 1).maxOf { i -> abs((p[2 * i] - 10f) * 400f - (p[2 * i + 1] - 20f) * 300f) / len }
        assertTrue(off > 1f)
    }

    @Test
    fun zeroLengthStaysPut() {
        val p = bolt(5, ax = 50f, ay = 50f, bx = 50f, by = 50f)
        for (i in 0 until Bolt.POINTS) {
            assertEquals(50f, p[2 * i], 0f)
            assertEquals(50f, p[2 * i + 1], 0f)
        }
    }
}
