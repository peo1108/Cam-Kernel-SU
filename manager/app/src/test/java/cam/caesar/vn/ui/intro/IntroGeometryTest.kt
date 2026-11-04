package cam.su.kernel.ui.intro

import androidx.compose.ui.geometry.Offset
import cam.su.kernel.ui.intro.IntroGeometry.SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroGeometryTest {

    private fun assertNear(expected: Offset, actual: Offset) =
        assertTrue("$actual is not $expected", (expected - actual).getDistance() < 0.01f)

    private fun inside(p: Offset): Boolean {
        val c = IntroGeometry.corners
        val tri = IntroGeometry.area(c)
        val sum = IntroGeometry.area(arrayOf(p, c[0], c[1])) + IntroGeometry.area(arrayOf(p, c[1], c[2])) + IntroGeometry.area(arrayOf(p, c[2], c[0]))
        return sum <= tri * (1f + 1e-4f)
    }

    @Test
    fun platesTileTheTriangle() {
        val whole = IntroGeometry.area(Array(3) { IntroGeometry.grown(it) })
        val parts = IntroTimeline.plates.filter { it.ring < 3 }.sumOf { IntroGeometry.area(it.outline).toDouble() }
        assertEquals(whole.toDouble(), parts, whole * 1e-4)
        // the reticle's disc sits in the core, clear of the inner frame
        val core = IntroGeometry.BAND_IN * (IntroGeometry.corners[1].y - IntroGeometry.centroid.y)
        assertTrue((IntroGeometry.aim - IntroGeometry.centroid).getDistance() + IntroGeometry.AIM_RADIUS < core)
    }

    @Test
    fun fitKeepsTheLogoOnScreen() {
        for ((w, h) in listOf(1904f to 3040f, 3040f to 1904f, 1080f to 2400f, 2400f to 1080f, 800f to 800f)) {
            val fit = IntroGeometry.fit(w, h)
            val a = fit.toScreen(Offset.Zero)
            val b = fit.toScreen(Offset(SIZE, SIZE))
            assertTrue("$w x $h", a.x >= 0f && a.y >= 0f && b.x <= w && b.y <= h)
            // centered
            assertEquals(w / 2f, (a.x + b.x) / 2f, 0.01f)
            assertEquals(h / 2f, (a.y + b.y) / 2f, 0.01f)
        }
    }

    @Test
    fun outlineWalksTheCorners() {
        val l0 = IntroGeometry.edgeLength(0)
        val l1 = IntroGeometry.edgeLength(1)
        assertNear(IntroGeometry.corners[0], IntroGeometry.perimeterPoint(0f))
        assertNear(IntroGeometry.corners[1], IntroGeometry.perimeterPoint(l0))
        assertNear(IntroGeometry.corners[2], IntroGeometry.perimeterPoint(l0 + l1))
        assertNear(IntroGeometry.corners[0], IntroGeometry.perimeterPoint(IntroGeometry.perimeter))
        assertNear(IntroGeometry.corners[1], IntroGeometry.perimeterPoint(IntroGeometry.perimeter + l0))
        assertEquals(0, IntroGeometry.perimeterEdge(1f))
        assertEquals(1, IntroGeometry.perimeterEdge(l0 + 1f))
        assertEquals(2, IntroGeometry.perimeterEdge(IntroGeometry.perimeter - 1f))
    }

    @Test
    fun normalsPointOutAndRivetsSitOnTheFrame() {
        for (e in 0..2) {
            val n = IntroGeometry.edgeNormal(e)
            val mid = IntroGeometry.edgePoint(e, 0.5f)
            assertTrue("edge $e", (mid + n * 10f - IntroGeometry.centroid).getDistance() > (mid - IntroGeometry.centroid).getDistance())
            assertEquals(1f, n.getDistance(), 0.0001f)
            assertEquals(0f, n.x * IntroGeometry.edgeTangent(e).x + n.y * IntroGeometry.edgeTangent(e).y, 0.0001f)
        }
        for (r in IntroTimeline.rivets) assertTrue("rivet at ${r.time} inside the triangle", inside(r.point))
    }

    @Test
    fun landmarksSitWhereThePictureHasThem() {
        // the reticle sits on the centroid's vertical inside the triangle, the lettering below it
        assertEquals(IntroGeometry.centroid.x, IntroGeometry.aim.x, 1f)
        assertTrue(inside(IntroGeometry.aim))
        assertTrue(IntroGeometry.TEXT_TOP > IntroGeometry.corners[1].y)
        assertTrue(IntroGeometry.TEXT_TOP < IntroGeometry.KANJI_BOTTOM && IntroGeometry.KANJI_BOTTOM < IntroGeometry.NAME_BOTTOM)
        val k = IntroGeometry.kanji
        assertEquals(5, k.size)
        for (i in 1 until k.size) assertTrue(k[i] > k[i - 1])
        val l = IntroGeometry.letterLeft
        val r = IntroGeometry.letterRight
        assertEquals(13, l.size)
        for (i in l.indices) {
            assertTrue(l[i] < r[i])
            if (i > 0) assertTrue(r[i - 1] < l[i])
        }
        // the HUD lies above the lettering, on the picture
        for (h in IntroGeometry.hud) assertTrue(h.left >= 0f && h.right <= SIZE && h.bottom <= IntroGeometry.TEXT_TOP)
    }
}
