package cam.su.kernel.ui.bunker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BunkerMotionTest {

    @Test
    fun lightningIsCalmWithSurges() {
        var calm = 0
        var surging = 0
        var t = 0f
        while (t < 120f) {
            val s = BunkerMotion.surge(t)
            assertTrue("surge $s at $t", s in BunkerMotion.CALM..1f)
            if (s < BunkerMotion.CALM + 0.1f) calm++
            if (s > 0.7f) surging++
            t += 0.05f
        }
        // mostly calm, but it does surge, several times in two minutes
        assertTrue(calm > surging * 2)
        assertTrue(surging > 20)
    }

    @Test
    fun lightSweepsTheMetalOncePerCell() {
        var sweeps = 0
        var lastSweep = -1f
        var t = 0f
        while (t < 130f) {
            val s = BunkerMotion.sweepAt(t)
            assertTrue(s == -1f || s in 0f..1f)
            if (s >= 0f && lastSweep < 0f) sweeps++
            lastSweep = s
            t += 0.01f
        }
        // one per cell: about 20 sweeps in 130 s, never back to back
        assertEquals((130f / BunkerMotion.SWEEP_CELL).toDouble(), sweeps.toDouble(), 1.0)
    }

    @Test
    fun eventsComeEvery20To40Seconds() {
        for (k in 0..200) {
            assertTrue(BunkerMotion.eventGap(k) in 20f..40f)
        }
    }

    @Test
    fun rangeCountsInTensWithinTheScale() {
        var t = 0f
        var last = BunkerMotion.range(0f)
        while (t < 60f) {
            val r = BunkerMotion.range(t)
            assertTrue(r in 100..900)
            assertEquals(0, r % 10)
            assertTrue("range jumps at $t", abs(r - last) <= 60)
            last = r
            t += 0.02f
        }
    }

    @Test
    fun hudFlickersOnePieceNowAndThen() {
        var on = 0
        var t = 0f
        while (t < 80f) {
            val k = BunkerMotion.hudFlicker(t, 9)
            assertTrue(k in -1..8)
            if (k >= 0) on++
            t += 0.01f
        }
        assertTrue(on in 1..(8000 / 20))
    }

    @Test
    fun wallpaperKeepsTheLogoAboveTheDock() {
        // the tablet in landscape and in portrait, and a phone
        for ((w, h) in listOf(3040f to 1904f, 1904f to 3040f, 1080f to 2400f)) {
            val fit = bunkerWallpaperFit(w, h)
            val top = fit.top + DRAWN_TOP * fit.scale
            val bottom = fit.top + DRAWN_BOTTOM * fit.scale
            assertTrue("$w x $h: top $top", top >= WALL_TOP * h - 0.5f)
            assertTrue("$w x $h: bottom $bottom", bottom <= WALL_BOTTOM * h + 0.5f)
            // centered across, inside the screen
            assertEquals(w / 2f, fit.left + 625f * fit.scale, 0.5f)
            assertTrue(fit.left + 111f * fit.scale >= 0f && fit.left + 1139f * fit.scale <= w)
        }
        // on the landscape tablet it fills the room it has
        val fit = bunkerWallpaperFit(3040f, 1904f)
        assertEquals((WALL_BOTTOM - WALL_TOP) * 1904f, (DRAWN_BOTTOM - DRAWN_TOP) * fit.scale, 0.5f)
    }

    @Test
    fun parallaxFollowsATiltAndDriftsBack() {
        val f = TiltFollower()
        f.update(0f, 1f, 0f)
        assertEquals(0f, f.x, 0f)
        // tilt sideways
        f.update(0.25f, 0.97f, 0.016f)
        assertTrue(f.x > 0.5f)
        // held there, it drifts back to the middle
        repeat(600) { f.update(0.25f, 0.97f, 0.016f) }
        assertTrue(abs(f.x) < 0.05f && abs(f.y) < 0.05f)
        // and never goes past full
        f.update(-1f, 0f, 0.016f)
        assertTrue(f.x >= -1f && f.y in -1f..1f)
    }
}
