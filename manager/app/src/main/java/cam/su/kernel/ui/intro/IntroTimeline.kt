package cam.su.kernel.ui.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import cam.su.kernel.ui.screen.home.arena.hash
import cam.su.kernel.ui.screen.home.arena.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The start intro's beats, in seconds from its first frame on screen. Pure math, so the JVM
 * tests can hold it to its promises: beats in order, every plate in before the blast, a bounded wait.
 */
internal object IntroTimeline {
    /** HUD elements flicker on one after another. */
    const val HUD_STEP = 0.035f
    const val HUD_FLICKER = 0.10f

    /** The triangle's plates: the outer frame sweeps in round the outline, then the inner frame, the core and the reticle. */
    const val OUTER_FROM = 0.20f
    const val OUTER_STEP = 0.033f
    const val OUTER_FLIGHT = 0.18f
    const val INNER_FROM = 0.46f
    const val INNER_STEP = 0.04f
    const val INNER_FLIGHT = 0.16f
    const val CORE_FROM = 0.62f
    const val CORE_STEP = 0.04f
    const val CORE_FLIGHT = 0.15f
    const val RETICLE_AT = 0.90f
    const val RETICLE_FLIGHT = 0.22f

    /** A landed plate's white flash. */
    const val PLATE_FLASH = 0.12f

    /** Lightning crawls out of the rivets along the outline until it closes the loop. */
    const val CHARGE_FROM = 0.62f
    const val CRAWL_LEN = 0.28f

    /** The rangefinder counts down, then LOCK blinks. */
    const val RANGE_FROM = 0.70f
    const val RANGE_LEN = 0.25f

    /** The last shot, fired from the viewer, reaches the reticle: flash, chain explosions. */
    const val BLAST_AT = 1.08f
    const val CENTER_FLIGHT = 0.10f
    const val CHAIN_STEP = 0.03f

    /** 前輩內部 slams in a character at a time, then INNER VETERANS lights up letter by letter. */
    val kanjiAt = floatArrayOf(1.22f, 1.28f, 1.34f, 1.40f)
    const val KANJI_SLAM = 0.12f
    const val NAME_AT = 1.30f
    const val TEXT_END = 1.50f

    /**
     * The opening never starts before [MIN_REVEAL_AT], nor before the app has drawn underneath;
     * when the 3D slimes are on the first page it also waits for them to run, at most
     * [ARENA_WAIT] after the app showed. If the app never shows, it gives up at [GIVE_UP_AT].
     */
    const val MIN_REVEAL_AT = 1.55f
    const val ARENA_WAIT = 0.9f
    const val GIVE_UP_AT = 4.5f
    const val REVEAL_LEN = 0.35f

    /** How long a rivet shot is in the air. */
    const val SHOT_FLIGHT = 0.10f

    /**
     * One plate of the triangle: [ring] 0 is the outer frame, 1 the inner frame, 2 the core and 3
     * the reticle. It sits at [outline] (empty for the reticle disc) and flies in from [from]
     * (unit vector, [reach] logo pixels out), starting [depth] times its size as if thrown at the
     * screen and turning [spin] degrees, to land at [landAt] after [flight] seconds.
     */
    class Plate(
        val ring: Int,
        val outline: Array<Offset>,
        val center: Offset,
        val edge: Int,
        val along: Float,
        val landAt: Float,
        val flight: Float,
        val from: Offset,
        val reach: Float,
        val depth: Float,
        val spin: Float,
    )

    /** A shot nailing outer plate [plate] down as it lands at [time], into [point], fired from [from]. */
    class Rivet(val plate: Int, val time: Float, val point: Offset, val from: Offset, val edge: Int, val along: Float)

    val plates: List<Plate> = buildPlates()

    val rivets: List<Rivet> = plates.indices
        .filter { plates[it].ring == 0 && it % 2 == 1 }
        .map { i ->
            val p = plates[i]
            Rivet(i, p.landAt, IntroGeometry.bandPoint(p.edge, p.along, IntroGeometry.RIVET_BAND), p.from, p.edge, p.along)
        }

    /** Every plate has landed (the reticle last). */
    val assembled: Float = plates.maxOf { it.landAt }

    /** Rivet indices in order round the frame, which is the order they blow up in. */
    private val chain: IntArray = rivets.indices
        .sortedBy { IntroGeometry.perimeterOf(rivets[it].edge, rivets[it].along) }
        .toIntArray()

    /** When rivet [i] blows up. */
    fun explodeAt(i: Int): Float = BLAST_AT + 0.04f + CHAIN_STEP * chain.indexOf(i)

    /** Where the lightning starts crawling from (round the outline from the apex, in order)... */
    val crawlFrom: FloatArray = rivets.map { IntroGeometry.perimeterOf(it.edge, it.along) }.sorted().toFloatArray()

    /** ...how far each start is from the next one, and how far the ends crawl to close the loop. */
    val crawlGap = FloatArray(crawlFrom.size) { i ->
        val next = crawlFrom[(i + 1) % crawlFrom.size]
        (next - crawlFrom[i] + IntroGeometry.perimeter) % IntroGeometry.perimeter
    }
    val crawlReach: Float = crawlGap.max() / 2f

    /** When HUD element [i] starts flickering on. */
    fun hudAt(i: Int): Float = 0.02f + HUD_STEP * i

    /** Shot travel: fast, then a bullet-time crawl into the target (still moving when it hits). */
    fun bulletEase(f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        return SLOW * x + (1f - SLOW) * (1f - (1f - x).pow(6))
    }

    private const val SLOW = 0.25f

    /**
     * When the opening starts, or null while it still waits. [shownAt] is when the app first drew
     * underneath, [warmAt] when the 3D slimes got past their start-up hitches (intro time, null
     * while not yet). [arenaExpected]: the slimes are on the first page.
     */
    fun revealStart(now: Float, shownAt: Float?, arenaExpected: Boolean, warmAt: Float?): Float? {
        if (shownAt == null) return if (now >= GIVE_UP_AT) GIVE_UP_AT else null
        val earliest = max(MIN_REVEAL_AT, shownAt)
        if (!arenaExpected) return earliest
        val deadline = max(earliest, shownAt + ARENA_WAIT)
        if (warmAt != null) return warmAt.coerceIn(earliest, deadline)
        return if (now >= deadline) deadline else null
    }

    private fun buildPlates(): List<Plate> {
        val g = IntroGeometry
        val out = ArrayList<Plate>()
        // Outer frame: swept in round the outline, each plate thrown in on a swirl.
        var n = 0
        for (e in 0..2) {
            val cuts = g.outerCuts[e]
            for (k in 0 until cuts.size - 1) {
                val a = cuts[k]
                val b = cuts[k + 1]
                val outline = arrayOf(g.bandPoint(e, a, g.BAND_OUT), g.bandPoint(e, b, g.BAND_OUT), g.bandPoint(e, b, g.BAND_MID), g.bandPoint(e, a, g.BAND_MID))
                val along = (a + b) / 2f
                out += Plate(
                    ring = 0, outline = outline, center = g.bandPoint(e, along, (g.BAND_OUT + g.BAND_MID) / 2f), edge = e, along = along,
                    landAt = OUTER_FROM + OUTER_STEP * n, flight = OUTER_FLIGHT,
                    from = swirl(g.edgeNormal(e), g.edgeTangent(e), 40f), reach = 640f + 120f * hash(n + 11),
                    depth = 1.9f, spin = sign(n) * (50f + 40f * hash(n + 23)),
                )
                n++
            }
        }
        // Inner frame: mostly from in front, a little out from the middle.
        n = 0
        for (e in 0..2) {
            val cuts = g.innerCuts[e]
            for (k in 0 until cuts.size - 1) {
                val a = cuts[k]
                val b = cuts[k + 1]
                val outline = arrayOf(g.bandPoint(e, a, g.BAND_MID), g.bandPoint(e, b, g.BAND_MID), g.bandPoint(e, b, g.BAND_IN), g.bandPoint(e, a, g.BAND_IN))
                val along = (a + b) / 2f
                out += Plate(
                    ring = 1, outline = outline, center = g.bandPoint(e, along, (g.BAND_MID + g.BAND_IN) / 2f), edge = e, along = along,
                    landAt = INNER_FROM + INNER_STEP * n, flight = INNER_FLIGHT,
                    from = swirl(g.edgeNormal(e), g.edgeTangent(e), -25f), reach = 220f,
                    depth = 2.4f, spin = sign(n + 1) * (25f + 15f * hash(n + 41)),
                )
                n++
            }
        }
        // The core, round a socket for the reticle.
        for (e in 0..2) {
            val outline = arrayOf(g.bandPoint(e, 0f, g.BAND_IN), g.bandPoint(e, 1f, g.BAND_IN), g.centroid)
            val center = Offset((outline[0].x + outline[1].x + outline[2].x) / 3f, (outline[0].y + outline[1].y + outline[2].y) / 3f)
            out += Plate(
                ring = 2, outline = outline, center = center, edge = e, along = 0.5f,
                landAt = CORE_FROM + CORE_STEP * e, flight = CORE_FLIGHT,
                from = g.edgeNormal(e), reach = 90f, depth = 2.8f, spin = sign(e) * 15f,
            )
        }
        out += Plate(
            ring = 3, outline = emptyArray(), center = g.aim, edge = -1, along = 0f,
            landAt = RETICLE_AT, flight = RETICLE_FLIGHT, from = Offset(0f, -1f), reach = 0f, depth = 0f, spin = 720f,
        )
        return out
    }

    private fun sign(i: Int) = if (i % 2 == 0) 1f else -1f

    /** [normal] turned [degrees] toward [tangent]. */
    private fun swirl(normal: Offset, tangent: Offset, degrees: Float): Offset {
        val a = (degrees * PI / 180.0).toFloat()
        val v = normal * cos(a) - tangent * sin(a)
        return v / v.getDistance()
    }
}

/** The logo picture's landmarks, in its own pixels (1254 square), measured on `intro_logo.webp`. */
internal object IntroGeometry {
    const val SIZE = 1254f

    /** The triangle's outer corners: apex, bottom left, bottom right. Edge i runs from corner i to i + 1. */
    val corners = arrayOf(Offset(627f, 56f), Offset(193f, 800f), Offset(1060f, 800f))
    val centroid = Offset((627f + 193f + 1060f) / 3f, (56f + 800f + 800f) / 3f)

    /**
     * The triangle's bands, as scales about the centroid: the outer metal frame (reaching a bit
     * past the outline to take its glow along), the inner frame with the vents, the carbon core.
     */
    const val BAND_OUT = 1.04f
    const val BAND_MID = 0.78f
    const val BAND_IN = 0.50f

    /** Rivets sit on the outer frame's metal. */
    const val RIVET_BAND = 0.93f

    /** Where the frames are cut into plates, along each edge. */
    val outerCuts = arrayOf(
        floatArrayOf(0f, 0.2f, 0.46f, 0.72f, 1f),
        floatArrayOf(0f, 0.27f, 0.5f, 0.73f, 1f),
        floatArrayOf(0f, 0.28f, 0.54f, 0.8f, 1f),
    )
    val innerCuts = arrayOf(floatArrayOf(0f, 0.45f, 1f), floatArrayOf(0f, 0.5f, 1f), floatArrayOf(0f, 0.55f, 1f))

    /** The reticle's center, where the last shot lands and the opening starts, and its disc. */
    val aim = Offset(627f, 547f)
    const val AIM_RADIUS = 68f

    /** Below this line is lettering: 前輩內部, then INNER VETERANS, then the bottom ornament. */
    const val TEXT_TOP = 826f
    const val KANJI_BOTTOM = 1060f
    const val NAME_BOTTOM = 1112f

    /** 前, 輩, 內, 部 (left and right edges), and the teal slashes either side of them. */
    val kanji = floatArrayOf(190f, 409f, 617f, 835f, 1062f)
    val flourishes = arrayOf(Rect(96f, 880f, 190f, 1030f), Rect(1062f, 880f, 1160f, 1030f))

    /** INNER VETERANS, one entry per letter: left and right edges. */
    val letterLeft = floatArrayOf(334f, 361f, 408f, 455f, 498f, 569f, 617f, 657f, 701f, 742f, 786f, 835f, 881f)
    val letterRight = floatArrayOf(341f, 388f, 435f, 478f, 524f, 599f, 640f, 683f, 723f, 769f, 817f, 862f, 906f)

    /** The HUD's pieces round the triangle, in the order they light up. */
    val hud = arrayOf(
        Rect(190f, 255f, 350f, 302f), // coordinates
        Rect(790f, 255f, 1065f, 350f), // TGT, RNG and its line
        Rect(190f, 305f, 470f, 340f), // the bar and its line
        Rect(960f, 405f, 1060f, 570f), // range scale
        Rect(360f, 380f, 430f, 430f), // bracket
        Rect(190f, 430f, 340f, 530f), // IV-001
        Rect(820f, 380f, 890f, 430f), // bracket
        Rect(955f, 595f, 1065f, 625f), // level line
        Rect(190f, 595f, 340f, 625f), // level line
    )

    /** The "0500M" range readout (covered while it counts down), its digits' baseline and pitch. */
    val rng = Rect(978f, 300f, 1037f, 318f)
    val rngBaseline = Offset(981f, 314.5f)
    const val RNG_PITCH = 10.2f

    /** Where LOCK blinks: right of the TGT label's reticle, on its baseline. */
    val lockBaseline = Offset(1013f, 288.5f)

    fun corner(i: Int) = corners[i % 3]

    fun edgeLength(edge: Int) = (corner(edge + 1) - corner(edge)).getDistance()

    val perimeter = edgeLength(0) + edgeLength(1) + edgeLength(2)

    /** The point [along] (0..1) edge [edge]. */
    fun edgePoint(edge: Int, along: Float) = lerp(corner(edge), corner(edge + 1), along)

    /** The point [along] edge [edge] on the band at [scale] about the centroid. */
    fun bandPoint(edge: Int, along: Float, scale: Float) = centroid + (edgePoint(edge, along) - centroid) * scale

    /** Unit vector along [edge], from corner [edge] to the next. */
    fun edgeTangent(edge: Int): Offset {
        val d = corner(edge + 1) - corner(edge)
        return d / d.getDistance()
    }

    /** Unit normal of [edge], pointing out of the triangle. */
    fun edgeNormal(edge: Int): Offset {
        val d = edgeTangent(edge)
        val n = Offset(d.y, -d.x)
        val mid = edgePoint(edge, 0.5f)
        return if ((mid - centroid).let { it.x * n.x + it.y * n.y } >= 0f) n else -n
    }

    /** How far round the outline a point [along] edge [edge] is, from the apex. */
    fun perimeterOf(edge: Int, along: Float): Float {
        var s = 0f
        for (e in 0 until edge) s += edgeLength(e)
        return s + edgeLength(edge) * along
    }

    /** Which edge the point [s] (wrapped) round the outline from the apex is on. */
    fun perimeterEdge(s: Float): Int {
        var r = ((s % perimeter) + perimeter) % perimeter
        for (e in 0..1) {
            val l = edgeLength(e)
            if (r <= l) return e
            r -= l
        }
        return 2
    }

    /** The point [s] (wrapped) round the outline from the apex. */
    fun perimeterPoint(s: Float): Offset {
        var r = ((s % perimeter) + perimeter) % perimeter
        for (e in 0..2) {
            val l = edgeLength(e)
            if (r <= l || e == 2) return edgePoint(e, (r / l).coerceIn(0f, 1f))
            r -= l
        }
        return corners[0]
    }

    /** Corner [i] on the outer band, where the outer frame plates end. */
    fun grown(i: Int): Offset = centroid + (corner(i) - centroid) * BAND_OUT

    /** Area of a polygon (shoelace). */
    fun area(points: Array<Offset>): Float {
        var s = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** Where the logo goes on a [w] x [h] screen: 82% of min(w, 0.8 h), centered. */
    fun fit(w: Float, h: Float): Fit {
        val side = 0.82f * min(w, h * 0.8f)
        return Fit(side / SIZE, (w - side) / 2f, (h - side) / 2f)
    }

    /** [scale] screen pixels per logo pixel, logo's top left corner at ([left], [top]). */
    class Fit(val scale: Float, val left: Float, val top: Float) {
        fun toScreen(p: Offset) = Offset(left + p.x * scale, top + p.y * scale)
    }

    /** Distance from [p] to the farthest corner of a [w] x [h] screen. */
    fun farthestCorner(p: Offset, w: Float, h: Float): Float =
        maxOf(hypot(p.x, p.y), hypot(w - p.x, p.y), hypot(p.x, h - p.y), hypot(w - p.x, h - p.y))
}
