package cam.su.kernel.ui.component.miuix.effect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BgEffectTintTest {

    @Test
    fun greySeedReturnsBaseUnchanged() {
        val base = BgEffectConfig.get(DeviceType.PHONE, isDark = false)
        assertSame(base, BgEffectConfig.tint(base, 0xFF808080.toInt()))
    }

    @Test
    fun redSeedShiftsHuesByOffsets() {
        val out = BgEffectConfig.tint(BgEffectConfig.get(DeviceType.PHONE, isDark = false), 0xFFFF0000.toInt())
        listOf(out.colors1, out.colors2, out.colors3).forEach { arr ->
            assertEquals(16, arr.size)
            floatArrayOf(330f, 350f, 15f, 35f).forEachIndexed { i, h ->
                assertHueNear(h, hsvOf(arr, i)[0], 1f)
            }
        }
    }

    @Test
    fun valueAndAlphaPreserved() {
        for (dark in listOf(false, true)) {
            val base = BgEffectConfig.get(DeviceType.PHONE, isDark = dark)
            val out = BgEffectConfig.tint(base, 0xFF2196F3.toInt())
            listOf(base.colors1 to out.colors1, base.colors2 to out.colors2, base.colors3 to out.colors3).forEach { (b, o) ->
                for (i in 0 until 4) {
                    assertEquals(hsvOf(b, i)[2], hsvOf(o, i)[2], 0.01f)
                    assertEquals(b[i * 4 + 3], o[i * 4 + 3], 0.01f)
                }
            }
        }
    }

    @Test
    fun pointsAndTimingCopiedFromBase() {
        val base = BgEffectConfig.get(DeviceType.PAD, isDark = true)
        val out = BgEffectConfig.tint(base, 0xFF4CAF50.toInt())
        assertArrayEquals(base.points, out.points, 0f)
        assertEquals(base.colorInterpPeriod, out.colorInterpPeriod, 0f)
        assertEquals(base.lightOffset, out.lightOffset, 0f)
        assertEquals(base.saturateOffset, out.saturateOffset, 0f)
        assertEquals(base.pointOffset, out.pointOffset, 0f)
    }

    private fun hsvOf(arr: FloatArray, point: Int): FloatArray {
        val r = arr[point * 4]
        val g = arr[point * 4 + 1]
        val b = arr[point * 4 + 2]
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    private fun assertHueNear(expected: Float, actual: Float, tolerance: Float) {
        val diff = abs(expected - actual) % 360f
        val circular = min(diff, 360f - diff)
        assertTrue("hue $actual not within $tolerance of $expected", circular <= tolerance)
    }
}
