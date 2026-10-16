package cam.su.kernel.ui.intro

import cam.su.kernel.ui.intro.IntroTimeline.ARENA_WAIT
import cam.su.kernel.ui.intro.IntroTimeline.BLAST_AT
import cam.su.kernel.ui.intro.IntroTimeline.CENTER_FLIGHT
import cam.su.kernel.ui.intro.IntroTimeline.CHARGE_FROM
import cam.su.kernel.ui.intro.IntroTimeline.CRAWL_LEN
import cam.su.kernel.ui.intro.IntroTimeline.GIVE_UP_AT
import cam.su.kernel.ui.intro.IntroTimeline.KANJI_SLAM
import cam.su.kernel.ui.intro.IntroTimeline.MIN_REVEAL_AT
import cam.su.kernel.ui.intro.IntroTimeline.NAME_AT
import cam.su.kernel.ui.intro.IntroTimeline.RANGE_FROM
import cam.su.kernel.ui.intro.IntroTimeline.RANGE_LEN
import cam.su.kernel.ui.intro.IntroTimeline.REVEAL_LEN
import cam.su.kernel.ui.intro.IntroTimeline.SHOT_FLIGHT
import cam.su.kernel.ui.intro.IntroTimeline.TEXT_END
import cam.su.kernel.ui.intro.IntroTimeline.bulletEase
import cam.su.kernel.ui.intro.IntroTimeline.explodeAt
import cam.su.kernel.ui.intro.IntroTimeline.kanjiAt
import cam.su.kernel.ui.intro.IntroTimeline.plates
import cam.su.kernel.ui.intro.IntroTimeline.revealStart
import cam.su.kernel.ui.intro.IntroTimeline.rivets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroTimelineTest {

    @Test
    fun beatsRunInOrder() {
        val hudDone = IntroTimeline.hudAt(IntroGeometry.hud.size - 1) + IntroTimeline.HUD_FLICKER
        assertTrue(hudDone < CHARGE_FROM)
        // the lightning closes its loop and the range reads zero before the last shot
        assertTrue(CHARGE_FROM + CRAWL_LEN < BLAST_AT - CENTER_FLIGHT)
        assertTrue(RANGE_FROM + RANGE_LEN < BLAST_AT)
        assertTrue(IntroTimeline.assembled < BLAST_AT - CENTER_FLIGHT)
        // the lettering comes after the blast, one character after the other, and ends before the opening
        assertTrue(kanjiAt.first() - KANJI_SLAM >= BLAST_AT)
        for (i in 1 until kanjiAt.size) assertTrue(kanjiAt[i] > kanjiAt[i - 1])
        assertTrue(NAME_AT < TEXT_END && kanjiAt.last() <= TEXT_END)
        assertTrue(MIN_REVEAL_AT >= TEXT_END)
    }

    @Test
    fun platesLandRingByRing() {
        // 12 outer, 6 inner, 3 core, the reticle
        assertEquals(listOf(12, 6, 3, 1), (0..3).map { r -> plates.count { it.ring == r } })
        for (r in 1..3) {
            val before = plates.filter { it.ring == r - 1 }.minOf { it.landAt }
            assertTrue("ring $r starts after ring ${r - 1}", plates.filter { it.ring == r }.minOf { it.landAt } > before)
        }
        assertEquals(IntroTimeline.RETICLE_AT, IntroTimeline.assembled, 0f)
        for (p in plates) {
            assertTrue(p.landAt - p.flight >= 0f)
            assertEquals(1f, p.from.getDistance(), 0.001f)
        }
        // the outer frame sweeps round the outline in order
        val outer = plates.filter { it.ring == 0 }
        val round = outer.map { IntroGeometry.perimeterOf(it.edge, it.along) }
        assertEquals(round.sorted(), round)
        assertEquals(outer.map { it.landAt }.sorted(), outer.map { it.landAt })
    }

    @Test
    fun rivetsNailOuterPlatesAsTheyLand() {
        assertEquals(6, rivets.size)
        for (r in rivets) {
            val p = plates[r.plate]
            assertEquals(0, p.ring)
            assertEquals(p.landAt, r.time, 0f)
            assertTrue("shot at ${r.time} leaves after the start", r.time - SHOT_FLIGHT >= 0f)
            assertEquals(p.edge, r.edge)
        }
        // two on every edge
        for (e in 0..2) assertEquals(2, rivets.count { it.edge == e })
    }

    @Test
    fun rivetsBlowUpOneByOneAfterTheBlast() {
        val times = rivets.indices.map { explodeAt(it) }.sorted()
        assertTrue(times.first() > BLAST_AT)
        for (i in 1 until times.size) assertEquals(IntroTimeline.CHAIN_STEP, times[i] - times[i - 1], 0.0001f)
        assertTrue(times.last() < TEXT_END)
    }

    @Test
    fun crawlStartsCoverTheWholeOutline() {
        assertEquals(rivets.size, IntroTimeline.crawlFrom.size)
        assertEquals(IntroGeometry.perimeter, IntroTimeline.crawlGap.sum(), 0.5f)
        assertTrue(IntroTimeline.crawlGap.all { it > 0f })
    }

    @Test
    fun waitsForTheAppToShow() {
        assertNull(revealStart(2f, shownAt = null, arenaExpected = false, warmAt = null))
        assertEquals(GIVE_UP_AT, revealStart(GIVE_UP_AT, shownAt = null, arenaExpected = true, warmAt = null))
        // shown late: open right then, without the arena
        assertEquals(2.1f, revealStart(2.1f, shownAt = 2.1f, arenaExpected = false, warmAt = null))
    }

    @Test
    fun withoutTheArenaOpensAtTheMinimum() {
        assertEquals(MIN_REVEAL_AT, revealStart(0.5f, shownAt = 0.4f, arenaExpected = false, warmAt = null))
    }

    @Test
    fun arenaWarmEarlyOpensAtTheMinimum() {
        assertEquals(MIN_REVEAL_AT, revealStart(1.0f, shownAt = 0.4f, arenaExpected = true, warmAt = 0.9f))
    }

    @Test
    fun arenaWarmingLateOpensThen() {
        assertNull(revealStart(1.6f, shownAt = 0.9f, arenaExpected = true, warmAt = null))
        assertEquals(1.7f, revealStart(1.7f, shownAt = 0.9f, arenaExpected = true, warmAt = 1.7f))
    }

    @Test
    fun arenaThatNeverWarmsIsWaitedForAWhileAfterTheAppShows() {
        val shown = 1.2f
        assertNull(revealStart(shown + ARENA_WAIT - 0.01f, shownAt = shown, arenaExpected = true, warmAt = null))
        assertEquals(shown + ARENA_WAIT, revealStart(shown + ARENA_WAIT, shownAt = shown, arenaExpected = true, warmAt = null)!!, 0.0001f)
        // warm after the deadline still opens at the deadline
        assertEquals(shown + ARENA_WAIT, revealStart(3f, shownAt = shown, arenaExpected = true, warmAt = 2.9f)!!, 0.0001f)
    }

    @Test
    fun appShownQuicklyOpensWithinTwoAndAHalfSeconds() {
        // the usual start: the app draws within half a second of the intro
        var latest = 0f
        for (expected in listOf(true, false)) for (shown in listOf(0.1f, 0.5f)) for (warm in listOf(null, 0.3f, 1.4f, 9f)) {
            var t = 0f
            var start: Float? = null
            while (start == null) {
                start = revealStart(t, shown.takeIf { it <= t }, expected, warm?.takeIf { it <= t })?.takeIf { t >= it }
                t += 1f / 60f
            }
            latest = maxOf(latest, start + REVEAL_LEN)
        }
        assertTrue("ends at $latest", latest <= maxOf(MIN_REVEAL_AT, 0.5f + ARENA_WAIT) + REVEAL_LEN + 0.0001f)
        assertTrue(latest <= 2.5f)
    }

    @Test
    fun bulletEaseRunsFastThenSlowFromZeroToOne() {
        assertEquals(0f, bulletEase(0f), 0f)
        assertEquals(1f, bulletEase(1f), 0.0001f)
        var last = 0f
        val step = 0.001f
        var f = step
        while (f <= 1f) {
            val v = bulletEase(f)
            assertTrue("monotone at $f", v >= last)
            assertTrue("continuous at $f", v - last < 0.01f)
            last = v
            f += step
        }
        // most of the way in the first half, then the bullet-time crawl (still moving at the end)
        assertTrue(bulletEase(0.5f) > 0.8f)
        assertTrue(bulletEase(1f) - bulletEase(0.98f) > 0f)
        assertEquals(0f, bulletEase(-1f), 0f)
        assertEquals(1f, bulletEase(2f), 0.0001f)
    }
}
