package me.weishu.kernelsu.ui.screen.home.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** What a scene intro can read and drive each frame. */
internal class IntroCtx(val stage: Stage, val poses: Array<SlimePose>) {
    /** Seconds into the intro. */
    var t = 0f
    /** Global clock, for idle motion that should not restart. */
    var now = 0f
    /** Screen shake the intro asks for this frame (px), and a roll of the whole box (degrees). */
    var shake = 0f
    var roll = 0f
    /** Where everyone stood when the intro began, and whether the 3D perspective applies. */
    lateinit var homes: Homes
    var projected = false
    lateinit var say: (String) -> TextLayoutResult
    lateinit var shout: (String) -> TextLayoutResult
    var zero: TextLayoutResult? = null
    var one: TextLayoutResult? = null

    val r get() = stage.r
    fun radius(k: Int) = stage.radius[k]
    fun home(k: Int) = homes.x[k]
    fun depth(k: Int) = homes.z[k]
    fun bodyH(k: Int) = poses[k].bodyHeight(stage.radius[k], now)
    fun head(k: Int) = poses[k].headTop(stage.radius[k], stage.groundY, now)
    fun mouth(k: Int) = poses[k].mouth(stage.radius[k], stage.groundY, now)
    fun hand(k: Int, side: Int) = poses[k].hand(side, stage.radius[k], stage.groundY, now)
    fun base(k: Int) = poses[k].base(stage.groundY)
    fun aim(k: Int, side: Int, target: Offset) = poses[k].aimAt(side, target, stage.radius[k], stage.groundY, now)

    /** Perspective scale of slime [k] this frame (1 on the flat stage). */
    fun vs(k: Int) = poses[k].viewScale
    fun scaleAt(z: Float) = if (projected) stage.viewScale(z) else 1f

    /** A point [lift] px above the floor at stage x [x], [z] deep in the box, on screen. */
    fun point(x: Float, lift: Float, z: Float): Offset {
        val k = scaleAt(z)
        return Offset(stage.center + (x - stage.center) * k, stage.eyeY + (stage.groundY - lift - stage.eyeY) * k)
    }

    fun floor(x: Float, z: Float) = point(x, 0f, z)

    /** A point next to slime [k]: [dx] across and [h] above its feet, at its depth. */
    fun near(k: Int, dx: Float, h: Float): Offset {
        val p = poses[k]
        return p.project(Offset(p.x + p.shakeX + dx, stage.groundY - p.lift - h))
    }

    /** 0..1 progress of the window [start, start + duration] at the current time. */
    fun win(start: Float, duration: Float) = progress(t, start, duration)
    fun inWin(start: Float, duration: Float) = t >= start && t < start + duration
}

/**
 * The scene-specific opening of a round: what the slimes get up to before it all ends in the brawl
 * cloud. Poses start from the idle stance each frame; [pose] moves them, [back] and [front] draw
 * props behind and in front of the slimes. By [seconds] everyone should be piling into the middle.
 * Each opening also uses the "lost" prop that tumbled in from the scene before.
 */
internal interface SceneIntro {
    val seconds: Float
    /** Times of light taps and of heavy thumps, for haptics. */
    val taps: FloatArray get() = FloatArray(0)
    val thumps: FloatArray get() = FloatArray(0)
    fun pose(c: IntroCtx)
    fun DrawScope.back(c: IntroCtx) {}
    fun DrawScope.front(c: IntroCtx) {}
}

internal fun DrawScope.bubble(c: IntroCtx, k: Int, text: String, start: Float, duration: Float) {
    if (!c.inWin(start, duration)) return
    drawSpeechBubble(c.mouth(k), c.say(text), c.win(start, duration), c.radius(k) * c.vs(k))
}

internal fun DrawScope.comic(c: IntroCtx, text: String, at: Offset, start: Float, duration: Float, tilt: Float = -8f) {
    if (!c.inWin(start, duration)) return
    drawComicWord(c.shout(text), at, c.win(start, duration), tilt, 3f * c.stage.px)
}

/** Hop shaped as a sine arch inside [start, start + duration]. */
internal fun IntroCtx.hop(start: Float, duration: Float, height: Float): Float {
    val e = win(start, duration)
    return if (e in 0.0001f..0.9999f) sin(PI_F * e) * height else 0f
}

/** Everyone charges into the middle from [start]; they vanish into the cloud as it closes. */
internal fun IntroCtx.pileIn(start: Float, duration: Float = 0.7f, except: Int = -1) {
    val e = win(start, duration)
    if (e <= 0f) return
    for (k in 0 until SLIME_COUNT) {
        if (k == except) continue
        poses[k].apply {
            val f = easeInOutCubic(e)
            x = lerp(x, stage.center + (k - 1.5f) * r * 0.35f, f)
            depth = lerp(depth, 0f, f)
            lift = lerp(lift, 0f, f) + abs(sin(e * PI_F * 2.5f)) * r * 0.35f
            rotation *= 1f - f
            mood = Mood.Angry
            anger = 1f
            whistle = 0f
            cross = 0f
            akimbo = 0f
            sleep = 0f
            if (e > 0.85f) visible = false
        }
    }
}

/** The brawl cloud swelling up from [start], continued by the shared brawl. */
internal fun DrawScope.cloudIn(c: IntroCtx, start: Float) {
    if (c.t > start) drawBrawlCloud(c.stage.brawl, c.r, c.now, c.win(start, 0.5f), 0f)
}

/** A jelly hand shooting out of the cloud to grab [to]. */
internal fun DrawScope.drawGrab(c: IntroCtx, to: Offset, e: Float) {
    if (e <= 0f || e >= 1f) return
    val from = c.stage.brawl + Offset(c.r * 0.9f * sign(to.x - c.stage.brawl.x + 0.01f), -c.r * 0.2f)
    val grab = lerp(from, to, sin(PI_F * e))
    drawLine(Cast[BO].deep, from, grab, c.r * 0.24f, StrokeCap.Round, alpha = 0.6f)
    drawLine(Cast[BO].body, from, grab, c.r * 0.18f, StrokeCap.Round)
    drawCircle(Cast[BO].body, c.r * 0.12f, grab)
}

/** Đá cầu shuttlecock: stacked rubber washers and a fan of colored feathers trailing [rotation]. */
internal fun DrawScope.drawShuttlecock(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        val fan = Path().apply {
            moveTo(c.x - s * 0.16f, c.y)
            lineTo(c.x - s * 0.62f, c.y - s * 1.35f)
            quadraticTo(c.x, c.y - s * 1.6f, c.x + s * 0.62f, c.y - s * 1.35f)
            lineTo(c.x + s * 0.16f, c.y)
            close()
        }
        drawPath(fan, Brush.verticalGradient(listOf(Color.White, Color(0xFFF1F3F5)), c.y - s * 1.6f, c.y))
        val tips = listOf(Color(0xFFE03131), Color(0xFF1C7ED6), Color(0xFFFCC419), Color(0xFF37B24D), Color(0xFFE03131))
        tips.forEachIndexed { i, col ->
            val f = i / 4f - 0.5f
            val tip = Offset(c.x + f * s * 1.15f, c.y - s * (1.38f + 0.12f * (1f - abs(f) * 2f)))
            drawLine(Color(0xFFADB5BD), c, tip, s * 0.05f)
            drawCircle(col, s * 0.13f, tip)
        }
        drawPath(fan, Color(0xFFADB5BD), style = Stroke(s * 0.06f, join = StrokeJoin.Round))
        drawRoundRect(Color(0xFFE03131), Offset(c.x - s * 0.24f, c.y - s * 0.06f), Size(s * 0.48f, s * 0.32f), CornerRadius(s * 0.08f))
        for (k in 1..2) {
            drawLine(Color(0xFF9B1C1C), Offset(c.x - s * 0.24f, c.y - s * 0.06f + k * s * 0.1f), Offset(c.x + s * 0.24f, c.y - s * 0.06f + k * s * 0.1f), s * 0.03f)
        }
    }
}

/** Rotation that makes something point its "up" side against [velocity] (feathers trail). */
internal fun trailing(velocity: Offset): Float =
    Math.toDegrees(atan2(-velocity.x.toDouble(), velocity.y.toDouble())).toFloat()

/** A point along a ballistic arc from [a] to [b] peaking [height] above the higher end. */
internal fun arc(a: Offset, b: Offset, height: Float, u: Float): Offset {
    val top = min(a.y, b.y) - height
    val ctrl = Offset((a.x + b.x) / 2f, 2f * top - (a.y + b.y) / 2f)
    return quadBezier(a, ctrl, b, u)
}

// ---------------------------------------------------------------------------------------------
// Việt Nam: đá cầu ở Hạ Long
// ---------------------------------------------------------------------------------------------

/**
 * Bơ dozes in the cardboard box that flew in from the cat kingdom, a snore bubble swelling and
 * popping. Mochi and Chanh keep a shuttlecock up, higher every kick, Chanh showing off (back
 * kick, header, butt kick), until his spinning kick sends it round the bay into the tip of Bơ's
 * nón lá. Bơ wakes, reddening from the chin up; Chanh points at Mochi and whistles; Mochi, hands
 * on hips: "Em á?!". Bơ climbs out and stomps (the box goes flat), Mochi and Chanh flip through
 * the air; Soda trots in posing, "thôi mà~", and is dragged into the dust by her scarf.
 */
internal object VietnamIntro : SceneIntro {
    private val hits = floatArrayOf(0.5f, 1.15f, 1.85f, 2.65f, 3.55f, 4.35f)
    private const val LAND = 5.2f
    private const val POP = 2.9f
    private const val STOMP = 6.75f
    private const val CLOUD = 7.3f
    override val seconds = 8.2f
    override val taps = hits
    override val thumps = floatArrayOf(LAND, STOMP)

    private fun hitter(i: Int) = if (i % 2 == 0) MOCHI else CHANH

    private fun kickPoint(c: IntroCtx, k: Int): Offset {
        val toward = sign(c.stage.center - c.home(k))
        return c.point(c.home(k) + toward * c.radius(k) * 0.2f, 1.72f * c.radius(k) * Cast[k].stretch * 1.05f, c.depth(k))
    }

    /** The tip of Bơ's nón lá. */
    private fun hatTip(c: IntroCtx): Offset {
        val h = 1.72f * c.radius(BO) * Cast[BO].stretch
        return c.point(c.home(BO), h * 1.24f, c.depth(BO))
    }

    /** Shuttle position at intro time [t], or null when it is not in play. */
    private fun shuttle(c: IntroCtx, t: Float): Offset? {
        val h = c.stage.h
        if (t < hits[0]) {
            val k = kickPoint(c, MOCHI)
            return Offset(k.x, lerp(h * 0.05f, k.y, easeInCubic((t / hits[0]).coerceIn(0f, 1f))))
        }
        for (i in 0 until hits.size - 1) {
            if (t < hits[i + 1]) {
                val a = kickPoint(c, hitter(i))
                val b = kickPoint(c, hitter(i + 1))
                val u = (t - hits[i]) / (hits[i + 1] - hits[i])
                return arc(a, b, h * (0.12f + 0.055f * i), u)
            }
        }
        if (t < LAND) {
            val u = easeInOutCubic((t - hits.last()) / (LAND - hits.last()))
            val p0 = kickPoint(c, CHANH)
            val p3 = hatTip(c)
            val c1 = Offset(c.stage.w * 0.92f, h * 0.02f)
            val c2 = Offset(c.stage.w * 0.06f, h * 0.0f)
            val v = 1f - u
            return p0 * (v * v * v) + c1 * (3f * v * v * u) + c2 * (3f * v * u * u) + p3 * (u * u * u)
        }
        return hatTip(c)
    }

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        // Kickers: a hop on every touch, Chanh's touches are tricks.
        for ((i, at) in hits.withIndex()) {
            val k = hitter(i)
            val e = c.win(at - 0.2f, 0.4f)
            if (e <= 0f || e >= 1f) continue
            p[k].lift += sin(PI_F * e) * r * 0.5f
            p[k].squash = 1f + 0.15f * sin(PI_F * e)
            if (k == CHANH) {
                when (i) {
                    1 -> p[k].rotation = 360f * easeInOutCubic(e)
                    3 -> p[k].lift += sin(PI_F * e) * r * 0.5f
                    5 -> {
                        p[k].lookX = 1f
                        p[k].rotation = -540f * easeInOutCubic(e)
                    }
                }
                p[k].mood = Mood.Happy
            }
        }
        val ball = shuttle(c, t)
        if (ball != null && t < LAND) {
            for (k in intArrayOf(MOCHI, SODA)) {
                p[k].lookX = ((ball.x - p[k].x) / (r * 3f)).coerceIn(-1f, 1f)
                p[k].lookY = ((ball.y - c.stage.groundY + r * 2f) / (r * 3f)).coerceIn(-1f, 1f)
            }
        }
        // Soda poses with the scarf the whole time.
        p[SODA].armR = 150f + 10f * sin(c.now * 3f)
        p[SODA].sparkle = 1f
        p[SODA].rotation = 6f * sin(c.now * 1.4f)

        // Bơ dozes in the box; the snore bubble swells and pops, he startles and nods off again.
        val bo = p[BO]
        bo.squash *= 0.94f
        when {
            t < POP -> {
                bo.sleep = 1f
                bo.snore = 0.45f + 0.22f * sin(c.now * 2.4f) + 0.8f * c.win(POP - 0.9f, 0.9f)
            }

            t < POP + 0.35f -> {
                bo.mood = Mood.Surprised
                bo.lift += c.hop(POP, 0.3f, r * 0.3f)
            }

            t < LAND + 0.05f -> {
                bo.sleep = 1f
                bo.snore = 0.35f * c.win(POP + 0.4f, 1f) + 0.15f * sin(c.now * 2.4f)
            }

            t < LAND + 0.4f -> bo.sleep = 0.55f
            else -> {
                bo.flush = c.win(LAND + 0.4f, 0.8f)
                bo.steam = c.win(LAND + 0.9f, 0.3f)
                bo.mood = if (t > LAND + 0.6f) Mood.Angry else Mood.Surprised
                bo.lookX = 1f
            }
        }
        if (t > LAND && t < LAND + 0.5f) bo.squash = 1f - 0.08f * sin(PI_F * c.win(LAND, 0.25f))
        // Climbs out of the box for the stomp: rise, slam, everything shakes.
        if (t > STOMP - 0.4f) {
            bo.lift += c.hop(STOMP - 0.4f, 0.7f, r * 0.9f).let { if (t < STOMP) it else 0f }
            if (t > STOMP && t < STOMP + 0.25f) bo.squash = 1f - 0.3f * sin(PI_F * c.win(STOMP, 0.25f))
            if (t > STOMP) c.shake = r * 0.32f * exp(-(t - STOMP) * 5f)
            bo.x = c.home(BO) + c.radius(BO) * 0.6f * c.win(STOMP - 0.4f, 0.35f)
        }

        // Chanh blames Mochi and whistles at the sky.
        val ch = p[CHANH]
        if (t > LAND + 0.25f && t < STOMP) {
            ch.armL = ch.aimAt(-1, c.head(MOCHI), c.radius(CHANH), c.stage.groundY, c.now)
            ch.reachL = 1.25f
            ch.whistle = c.win(LAND + 0.5f, 0.2f)
            ch.lookY = -1f
            ch.lookX = 0.5f
            ch.mood = Mood.Normal
        }
        // Mochi: "Em á?!", hands on hips, storming toward Chanh.
        val mo = p[MOCHI]
        if (t > 5.9f && t < STOMP) {
            mo.akimbo = c.win(5.9f, 0.15f)
            mo.mood = Mood.Angry
            mo.anger = c.win(6f, 0.3f)
            mo.steam = c.win(6.1f, 0.2f)
            mo.x = lerp(c.home(MOCHI), lerp(c.home(MOCHI), c.home(CHANH), 0.45f), easeInOutCubic(c.win(6.2f, 0.4f)))
            mo.lookX = 1f
        }
        // Thrown by the stomp: three flips each, landing in the middle.
        if (t >= STOMP) {
            val e = c.win(STOMP, 0.6f)
            for ((k, dir) in listOf(MOCHI to -1f, CHANH to 1f)) {
                val from = if (k == MOCHI) lerp(c.home(MOCHI), c.home(CHANH), 0.45f) else c.home(CHANH)
                p[k].x = lerp(from, c.stage.center + dir * r * 0.7f, easeOutCubic(e))
                p[k].depth = lerp(c.depth(k), 0f, e)
                p[k].lift = sin(PI_F * e) * r * 2.1f
                p[k].rotation = dir * 1080f * easeOutCubic(e)
                p[k].mood = Mood.Dizzy
                p[k].akimbo = 0f
                p[k].steam = 0f
                p[k].whistle = 0f
            }
        }
        // Soda trots in to calm everyone down and is yanked in by the scarf.
        if (t > 6.9f) {
            p[SODA].x = lerp(c.home(SODA), c.stage.center + r * 1.4f, easeInOutCubic(c.win(6.9f, 0.45f)))
            p[SODA].depth = lerp(c.depth(SODA), 0f, c.win(6.9f, 0.45f))
            p[SODA].lift += abs(sin(c.win(6.9f, 0.45f) * PI_F * 3f)) * r * 0.2f
            if (t > 7.35f) {
                p[SODA].x = lerp(c.stage.center + r * 1.4f, c.stage.center, easeInCubic(c.win(7.35f, 0.2f)))
                p[SODA].rotation = -200f * c.win(7.35f, 0.2f)
                p[SODA].mood = Mood.Surprised
            }
        }
        c.pileIn(7.5f, 0.6f)
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        // The box Bơ sleeps in, squashed flat by his stomp.
        val boxAt = c.floor(c.home(BO), c.depth(BO) + 0.6f)
        val k = c.scaleAt(c.depth(BO) + 0.6f)
        if (t < 7.6f) drawCardboardBox(boxAt, c.radius(BO) * 2.6f * k, c.radius(BO) * 1.0f * k, easeOutCubic(c.win(STOMP, 0.2f)), c.now)
        val ball = shuttle(c, t)
        if (ball != null) {
            val rot = if (t < LAND) {
                val next = shuttle(c, t + 0.016f) ?: ball
                trailing(next - ball)
            } else {
                180f + sin((t - LAND) * 40f) * 22f * exp(-(t - LAND) * 3f)
            }
            drawShuttlecock(ball, c.r * 0.38f, rot)
        }
        hits.forEachIndexed { i, at ->
            val kp = kickPoint(c, hitter(i))
            comic(c, if (i == hits.lastIndex) "BỘP!" else "bộp", kp + Offset(0f, -c.r * 0.7f), at - 0.02f, 0.45f, if (i % 2 == 0) -10f else 10f)
        }
        comic(c, "póc", c.mouth(BO) + Offset(c.r * 0.5f, -c.r * 0.8f), POP, 0.5f)
        comic(c, "RẦM!", c.head(BO) + Offset(0f, -c.r * 0.9f), STOMP, 0.55f, -6f)
        comic(c, "bẹp", boxAt + Offset(c.r * 1.2f, -c.r * 0.3f), STOMP + 0.05f, 0.5f, 8f)
        bubble(c, MOCHI, "Em á?!", 5.95f, 0.8f)
        bubble(c, SODA, "thôi mà~", 6.95f, 0.65f)
        if (t > STOMP) drawDust(c.base(BO), c.win(STOMP, 0.5f), c.radius(BO), 1.8f)
        cloudIn(c, CLOUD)
        if (t > CLOUD) drawGrab(c, c.hand(SODA, 1), c.win(7.35f, 0.25f))
    }
}

// ---------------------------------------------------------------------------------------------
// United Kingdom: tiệc trà ở London
// ---------------------------------------------------------------------------------------------

/**
 * The shuttlecock that came along from Hạ Long is perched on Soda's top hat; at her first sip it
 * drops into her tea. She fishes it out with two fingers, inspects it through the monocle and
 * tosses it away, then sips on, pinky rising with every sip until she spins right round. Chanh
 * dunks his biscuit too long: it flops in and the tea geysers onto Mochi, the royal guard who must
 * not move. Mochi shakes, reddens to the top of the bearskin while Chanh's tea boils with her;
 * Big Ben strikes, she explodes. Chanh holds up the paper: "MOCHI PHÁ TIỆC TRÀ!". Bơ puffs his
 * pipe, follows the tiny wet footprints with his magnifier... straight to Chanh: "Elementary!".
 * Mochi lunges, Chanh ducks behind Bơ, the cloud rolls over them, and Soda sails in still seated.
 */
internal object LondonIntro : SceneIntro {
    private const val SIP = 0.45f
    private const val DROP = 0.75f
    private const val TOSS = 1.3f
    private val sips = floatArrayOf(1.6f, 2.3f, 3.0f)
    private const val SOG = 2.0f
    private const val PLOP = 2.9f
    private const val SPLASH = 3.4f
    private const val BOONG = 5.6f
    private const val PAPER = 5.85f
    private const val SLEUTH = 6.3f
    private const val ELEMENTARY = 7.5f
    private const val LUNGE = 7.8f
    private const val CLOUD = 8.1f
    override val seconds = 9.2f
    override val taps = floatArrayOf(DROP, PLOP, SPLASH, PAPER)
    override val thumps = floatArrayOf(BOONG, CLOUD)

    private fun cup(c: IntroCtx, k: Int) = c.hand(k, 1) + Offset(0f, -c.r * 0.05f)

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        // Soda: sip, the shuttle drops in, fish it out, inspect, toss, sip on, pinky rising.
        val so = p[SODA]
        so.armR = 70f
        so.lookY = 0.2f
        if (t < TOSS + 0.4f) {
            val sip = c.win(SIP - 0.2f, 0.6f)
            so.armR = 70f + 70f * sin(PI_F * sip)
            if (t > DROP) {
                so.mood = Mood.Surprised
                so.lookY = 0.6f
                so.lookX = 0.6f
                so.armL = lerp(18f, 150f, c.win(DROP + 0.1f, 0.25f))
                so.reachL = 1.2f
            }
            if (t > TOSS) so.armL = lerp(150f, 170f, c.win(TOSS, 0.2f))
        } else {
            so.mood = Mood.Happy
            so.sparkle = 0.5f
        }
        sips.forEachIndexed { i, at ->
            val e = c.win(at - 0.25f, 0.6f)
            if (e > 0f && e < 1f) {
                so.armR = 70f + 75f * sin(PI_F * e)
                so.armL = 18f + (40f + 40f * i) * sin(PI_F * e)
                so.sparkle = 1f
                if (i == sips.lastIndex) so.rotation = 360f * easeInOutCubic(e)
            }
        }
        // Chanh dunks the biscuit, too long; it drops, the tea shoots up.
        val ch = p[CHANH]
        ch.armR = 60f
        ch.lookY = 0.7f
        if (t > SOG && t < PLOP) ch.mood = Mood.Normal
        if (t > PLOP && t < SPLASH + 0.3f) {
            ch.mood = Mood.Surprised
            ch.lift += c.hop(PLOP, 0.3f, r * 0.25f)
        }
        // Mochi: on guard, can't move, can't wipe her face.
        val mo = p[MOCHI]
        mo.armL = 10f
        mo.armR = 10f
        mo.lookX = 0f
        mo.squash = 1.03f
        if (t > SPLASH) {
            mo.mood = if (t < SPLASH + 0.5f) Mood.Surprised else Mood.Angry
            val boil = c.win(SPLASH + 0.4f, BOONG - SPLASH - 0.4f)
            mo.shakeX = sin(c.now * 70f) * r * 0.05f * boil
            mo.flush = boil
            mo.anger = boil
            mo.sweat = 0.4f * boil
            mo.steam = c.win(BOONG - 1f, 0.5f)
        }
        if (t > BOONG) {
            val e = c.win(BOONG, 0.6f)
            mo.lift += sin(PI_F * e) * r * 1.2f
            mo.armL = 160f
            mo.armR = 160f
            mo.steam = 1f
            mo.squash = 1f + 0.2f * sin(PI_F * e)
            if (t < BOONG + 0.3f) c.shake = r * 0.18f
        }
        // Chanh's front page.
        if (t > PAPER) {
            ch.armL = 120f
            ch.armR = 120f
            ch.mood = Mood.Happy
            ch.whistle = c.win(PAPER + 0.6f, 0.2f)
        }
        // Bơ the detective: pipe bubbles, then off along the footprints with the magnifier.
        val bo = p[BO]
        bo.armR = 70f
        bo.lookY = 0.3f
        if (t > SLEUTH) {
            val walk = easeInOutCubic(c.win(SLEUTH, ELEMENTARY - SLEUTH - 0.1f))
            bo.x = lerp(c.home(BO), c.home(CHANH) - (c.radius(BO) + c.radius(CHANH)) * 1.05f, walk)
            bo.depth = lerp(c.depth(BO), c.depth(CHANH), walk)
            bo.lift += abs(sin(walk * PI_F * 5f)) * r * 0.12f
            bo.lookY = 0.8f
            bo.lookX = 1f
            bo.armR = 120f
            bo.reachR = 1.3f
        }
        if (t > ELEMENTARY) {
            bo.mood = Mood.Happy
            bo.lookY = 0f
            ch.mood = Mood.Surprised
            ch.whistle = 0f
        }
        // Mochi lunges, Chanh ducks behind Bơ.
        if (t > LUNGE) {
            val e = c.win(LUNGE, 0.35f)
            mo.x = lerp(c.home(MOCHI), c.home(CHANH), easeInCubic(e))
            mo.depth = lerp(c.depth(MOCHI), c.depth(CHANH), e)
            mo.lift = sin(PI_F * e) * r * 0.8f
            ch.x = lerp(c.home(CHANH), bo.x - c.radius(BO) * 0.5f, easeOutCubic(c.win(LUNGE, 0.25f)))
            ch.depth = bo.depth - 0.6f
        }
        // Soda, still sipping, is pulled in chair and all.
        if (t > 8.4f) {
            val e = c.win(8.4f, 0.45f)
            so.x = lerp(c.home(SODA), c.stage.center, easeInCubic(e))
            so.depth = lerp(c.depth(SODA), 0f, e)
            so.squash = 0.88f
            so.armR = 140f
            so.rotation = 0f
            so.mood = Mood.Happy
        }
        c.pileIn(CLOUD, 0.5f, except = SODA)
        if (t > 8.85f) so.visible = false
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        val so = c.poses[SODA]
        // Soda's cup (and, early on, the shuttlecock riding her top hat).
        val sodaCup = cup(c, SODA)
        val s = r * 0.42f * c.vs(SODA)
        if (t < 8.85f) drawTeacup(sodaCup, s, 0f, 1f)
        val hat = c.head(SODA) + Offset(0f, -c.radius(SODA) * 0.55f * c.vs(SODA))
        when {
            t < DROP -> drawShuttlecock(hat + Offset(0f, -r * 0.02f), r * 0.3f, 160f + sin(c.now * 9f) * 6f)
            t < DROP + 0.25f -> drawShuttlecock(lerp(hat, sodaCup, easeInCubic(c.win(DROP, 0.25f))), r * 0.3f, 180f)
            t < TOSS -> drawShuttlecock(c.hand(SODA, -1), r * 0.3f, 200f + sin(c.now * 6f) * 10f)
            t < TOSS + 0.6f -> {
                val e = c.win(TOSS, 0.6f)
                drawShuttlecock(arc(c.hand(SODA, -1), Offset(-r, c.stage.h * 0.4f), c.stage.h * 0.3f, e), r * 0.3f, e * 720f)
            }
        }
        if (t in DROP + 0.2f..DROP + 0.5f) drawSplash(sodaCup + Offset(0f, -s * 0.5f), s * 0.6f, c.win(DROP + 0.2f, 0.3f))
        comic(c, "tõm", sodaCup + Offset(r * 0.5f, -r * 0.4f), DROP + 0.2f, 0.5f, 10f)
        // Chanh's cup, his drooping biscuit, the geyser onto Mochi.
        val chCup = cup(c, CHANH)
        val cs = r * 0.36f * c.vs(CHANH)
        if (t < CLOUD) drawTeacup(chCup, cs, 0f, 1f)
        if (t < PLOP) {
            val sag = c.win(SOG, PLOP - SOG)
            val tip = c.hand(CHANH, -1)
            val bis = lerp(tip, chCup + Offset(0f, -cs * 0.5f), 0.6f)
            rotate(30f + 70f * sag, bis) {
                drawRoundRect(Color(0xFFD9A066), bis - Offset(cs * 0.35f, cs * 0.12f), Size(cs * 0.7f, cs * 0.24f), CornerRadius(cs * 0.08f))
            }
        }
        if (t > PLOP && t < SPLASH + 0.2f) {
            val e = c.win(PLOP + 0.05f, SPLASH - PLOP - 0.05f)
            val target = c.mouth(MOCHI)
            repeat(8) { i ->
                val u = (e - i * 0.04f).coerceIn(0f, 1f)
                if (u > 0f) drawCircle(Color(0xFF9C6B3E), r * 0.08f * (1f - i * 0.08f), arc(chCup, target, c.stage.h * 0.35f, u), alpha = 0.9f)
            }
        }
        comic(c, "tõm!", chCup + Offset(0f, -r * 0.6f), PLOP, 0.5f, -10f)
        if (t > SPLASH) drawSplash(c.mouth(MOCHI), r * 0.5f, c.win(SPLASH, 0.5f))
        // The tea boils with Mochi's temper.
        if (t > SPLASH + 0.6f && t < CLOUD) repeat(4) { i ->
            val e = (c.now * 1.3f + i / 4f) % 1f
            drawCircle(Color.White, r * 0.06f * (1f - e), chCup + Offset((hash(i + 40) - 0.5f) * cs, -cs * 0.6f - e * r * 0.8f), alpha = 0.6f * (1f - e) * c.win(SPLASH + 0.6f, 0.5f))
        }
        comic(c, "BOONG!", Offset(c.stage.w * 0.82f, c.stage.h * 0.18f), BOONG, 0.7f, 6f)
        if (t > PAPER && t < CLOUD) {
            val paper = lerp(c.hand(CHANH, -1), c.hand(CHANH, 1), 0.5f) + Offset(0f, -r * 0.1f)
            drawNewspaper(paper, r * 0.7f * c.vs(CHANH) * easeOutBack(c.win(PAPER, 0.3f)), c.shout("MOCHI PHÁ TIỆC TRÀ!"))
        }
        // Bơ's pipe bubbles and magnifier, and the footprints he follows.
        repeat(5) { i ->
            val e = (c.now * 0.7f + i / 5f) % 1f
            val at = c.mouth(BO) + Offset(c.r * (0.45f + 0.3f * e), -e * r * 1.6f)
            drawCircle(Color.White, r * (0.05f + 0.06f * e), at, alpha = 0.5f * (1f - e), style = Stroke(r * 0.015f))
        }
        if (t > SLEUTH - 0.6f) {
            val from = c.floor(c.home(MOCHI) + c.radius(MOCHI), c.depth(MOCHI) + 0.4f)
            val to = c.floor(c.home(CHANH), c.depth(CHANH) + 0.4f)
            drawFootprints(from, to, 9, r * 0.6f, c.win(SLEUTH - 0.6f, 0.6f), 1f - c.win(CLOUD, 0.3f))
        }
        if (t > SLEUTH && t < CLOUD) drawMagnifier(c.hand(BO, 1) + Offset(r * 0.2f, r * 0.2f), r * 0.35f * c.vs(BO), 40f)
        bubble(c, BO, "Elementary!", ELEMENTARY, 0.6f)
        cloudIn(c, CLOUD)
    }
}

// ---------------------------------------------------------------------------------------------
// Japan: sumo dưới núi Phú Sĩ
// ---------------------------------------------------------------------------------------------

/**
 * Bơ squats in the ring still wearing Mochi's bearskin from London over his topknot. Left foot:
 * RẦM, sakura rains and the bearskin flies off. Right foot: RẦM, Mount Fuji puffs. Chanh the
 * referee waves the fan "Hakkeyoi!" so hard he blows himself backwards. Soda the ninja vanishes in
 * smoke, pops up on Mochi's head, vanishes again, reappears behind Bơ and tickles his armpit with a
 * petal. Bơ giggles like jelly... HẮT XÌ! Soda flies out of the ring into Mochi's tea ceremony.
 * Mochi sulks, arms crossed, then rolls up her sleeves; Chanh: "Mochi phạm luật!". Mochi charges
 * Chanh, Bơ steps in.
 */
internal object JapanIntro : SceneIntro {
    private const val STOMP_L = 0.9f
    private const val STOMP_R = 1.9f
    private const val FAN = 2.6f
    private const val BOMB = 3.2f
    private const val ON_MOCHI = 3.55f
    private const val BOMB2 = 4.05f
    private const val BEHIND = 4.35f
    private const val TICKLE = 4.5f
    private const val SNEEZE = 5.9f
    private const val CRASH = 6.35f
    private const val SULK = 6.6f
    private const val SLEEVES = 7.5f
    private const val FOUL = 7.6f
    private const val CHARGE = 8.0f
    private const val CLOUD = 8.3f
    override val seconds = 9.2f
    override val taps = floatArrayOf(FAN, BOMB, BOMB2, CRASH, FOUL)
    override val thumps = floatArrayOf(STOMP_L, STOMP_R, SNEEZE)

    private fun stomp(c: IntroCtx, at: Float, side: Float) {
        val bo = c.poses[BO]
        val rise = c.win(at - 0.45f, 0.45f)
        if (rise > 0f && rise < 1f) {
            bo.rotation = side * 14f * sin(PI_F * rise)
            bo.lift += sin(PI_F * rise * 0.5f) * c.r * 0.5f
        }
        if (c.t > at && c.t < at + 0.3f) {
            bo.squash = 1f - 0.25f * sin(PI_F * c.win(at, 0.3f))
            c.shake = c.r * 0.28f * exp(-(c.t - at) * 6f)
        }
    }

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val bo = p[BO]
        bo.squash = 0.92f
        bo.mood = Mood.Angry
        bo.lookX = 0.6f
        stomp(c, STOMP_L, -1f)
        stomp(c, STOMP_R, 1f)
        // Chanh the referee.
        val ch = p[CHANH]
        ch.armR = 120f + 20f * sin(c.now * 4f)
        if (t > FAN - 0.2f && t < FAN + 0.8f) {
            val e = c.win(FAN - 0.2f, 0.3f)
            ch.armR = lerp(120f, 165f, e)
            ch.mood = Mood.Angry
            val blown = c.win(FAN, 0.5f)
            ch.x = c.home(CHANH) + easeOutCubic(blown) * r * 1.2f
            ch.rotation = 20f * sin(PI_F * blown)
            ch.squash = 1f - 0.15f * sin(PI_F * blown)
        } else if (t > FAN + 0.8f) {
            ch.x = c.home(CHANH) + r * 1.2f
            ch.mood = Mood.Surprised
        }
        // Soda the ninja: smoke, on Mochi's head, smoke, behind Bơ, tickling.
        val so = p[SODA]
        so.armL = 90f
        so.armR = 90f
        when {
            t < BOMB -> so.mood = Mood.Normal
            t < ON_MOCHI -> so.visible = false
            t < BOMB2 -> {
                so.x = p[MOCHI].x
                so.depth = c.depth(MOCHI) - 0.05f
                so.lift = c.bodyH(MOCHI) * 0.9f
                so.mood = Mood.Happy
                so.lookY = 0.5f
            }

            t < BEHIND -> so.visible = false
            t < SNEEZE -> {
                so.x = c.home(BO) - c.radius(BO) * 0.9f
                so.depth = c.depth(BO) - 0.7f
                so.armR = 70f + 25f * sin(c.now * 18f)
                so.reachR = 1.4f
                so.mood = Mood.Happy
                so.lookX = 1f
            }

            t < CRASH -> {
                val e = c.win(SNEEZE, CRASH - SNEEZE)
                so.x = lerp(c.home(BO) - c.radius(BO) * 0.9f, p[MOCHI].x - c.radius(MOCHI) * 0.6f, e)
                so.depth = lerp(c.depth(BO) - 0.7f, c.depth(MOCHI), e)
                so.lift = sin(PI_F * e) * r * 1.6f
                so.rotation = 720f * e
                so.mood = Mood.Surprised
            }

            else -> {
                so.x = p[MOCHI].x - c.radius(MOCHI) * 0.6f
                so.depth = c.depth(MOCHI) + 0.05f
                so.rotation = -70f * c.win(CRASH, 0.2f)
                so.mood = Mood.Dizzy
                so.dizzy = 1f
            }
        }
        // Bơ giggles while tickled, then sneezes.
        if (t > TICKLE && t < SNEEZE) {
            bo.mood = Mood.Happy
            bo.jiggle = 0.6f + 0.4f * sin(c.now * 9f)
            bo.squash = 1f + 0.05f * sin(c.now * 22f)
            bo.lookX = -0.6f
            if (t > SNEEZE - 0.5f) {
                bo.mood = Mood.Squeeze
                bo.squash = 1f + 0.1f * c.win(SNEEZE - 0.5f, 0.5f)
            }
        }
        if (t > SNEEZE && t < SNEEZE + 0.4f) {
            bo.mouthOpen = 1f
            bo.mood = Mood.Surprised
            bo.squash = 1f - 0.2f * sin(PI_F * c.win(SNEEZE, 0.3f))
            c.shake = c.r * 0.2f
        }
        // Mochi at her tea ceremony, knocked over, sulking, then rolling up her sleeves.
        val mo = p[MOCHI]
        mo.lookY = 0.6f
        mo.armR = 50f + 15f * sin(c.now * 7f)
        if (t > CRASH) {
            mo.rotation = 40f * sin(PI_F * c.win(CRASH, 0.5f))
            mo.mood = Mood.Surprised
        }
        if (t > SULK && t < SLEEVES) {
            mo.cross = 1f
            mo.lookX = -1f
            mo.lookY = -0.2f
            mo.mood = Mood.Angry
            mo.steam = 0.6f
            mo.rotation = -6f
        }
        if (t > SLEEVES) {
            mo.armL = 165f
            mo.armR = 165f
            mo.mood = Mood.Angry
            mo.anger = 1f
            mo.lookX = 1f
        }
        if (t > FOUL && t < CHARGE) {
            ch.armL = ch.aimAt(-1, c.head(MOCHI), c.radius(CHANH), c.stage.groundY, c.now)
            ch.reachL = 1.3f
            ch.mood = Mood.Happy
        }
        if (t > CHARGE) {
            val e = c.win(CHARGE, 0.3f)
            mo.x = lerp(c.home(MOCHI), ch.x, easeInCubic(e) * 0.7f)
            bo.x = lerp(c.home(BO), lerp(c.home(MOCHI), ch.x, 0.5f), e)
        }
        c.pileIn(CLOUD, 0.6f)
    }

    override fun DrawScope.back(c: IntroCtx) {
        // The ring round Bơ.
        val at = c.floor(c.home(BO), c.depth(BO) + 0.2f)
        val k = c.scaleAt(c.depth(BO) + 0.2f)
        drawDohyo(at, c.radius(BO) * 2.2f * k, c.radius(BO) * 0.55f * k)
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        // The bearskin from London, knocked off by the first stomp.
        val head = c.head(BO)
        when {
            t < STOMP_L -> drawBearskin(head + Offset(0f, c.radius(BO) * 0.15f), c.radius(BO) * 0.75f * c.vs(BO), 0f)
            t < STOMP_L + 1.0f -> {
                val e = c.win(STOMP_L, 1f)
                drawBearskin(arc(head, Offset(-r * 2f, c.stage.h * 0.3f), c.stage.h * 0.5f, e), c.radius(BO) * 0.75f * c.vs(BO), -540f * e)
            }
        }
        // Sakura shaken loose by the stomps.
        for ((i, at) in listOf(STOMP_L, STOMP_R).withIndex()) {
            val e = c.win(at, 2.2f)
            if (e > 0f && e < 1f) repeat(14) { k ->
                val x = c.stage.w * (0.15f + 0.7f * hash(k * 7 + i * 3))
                val y = -r + e * c.stage.h * (0.8f + 0.4f * hash(k + 9))
                drawSakuraPetal(Offset(x + sin(e * 9f + k) * r * 0.3f, y), r * 0.09f, e * 400f + k * 30f)
            }
        }
        comic(c, "RẦM!", head + Offset(-r * 0.8f, -r * 0.6f), STOMP_L, 0.5f, -8f)
        comic(c, "RẦM!", head + Offset(r * 0.8f, -r * 0.6f), STOMP_R, 0.5f, 8f)
        // Fuji puffs at the second stomp.
        drawSmokePuff(Offset(c.stage.w * 0.55f, c.stage.h * 0.22f), r * 0.5f, c.win(STOMP_R + 0.1f, 0.9f))
        comic(c, "phụt", Offset(c.stage.w * 0.58f, c.stage.h * 0.12f), STOMP_R + 0.1f, 0.6f)
        bubble(c, CHANH, "Hakkeyoi!", FAN - 0.1f, 0.8f)
        if (t > FAN && t < FAN + 0.6f) repeat(4) { i ->
            val e = c.win(FAN + i * 0.05f, 0.4f)
            val y = c.head(CHANH).y + i * r * 0.2f
            drawLine(Color.White, Offset(c.base(CHANH).x - r * (0.5f + e), y), Offset(c.base(CHANH).x - r * (1.2f + e), y), r * 0.04f, StrokeCap.Round, alpha = 0.7f * (1f - e))
        }
        // Smoke bombs.
        val bombAt = c.near(SODA, 0f, c.radius(SODA) * 0.8f)
        drawSmokePuff(bombAt, r * 0.7f, c.win(BOMB, 0.5f))
        comic(c, "PỤP", bombAt + Offset(0f, -r * 0.6f), BOMB, 0.4f)
        drawSmokePuff(c.near(MOCHI, 0f, c.bodyH(MOCHI) * 1.4f), r * 0.6f, c.win(BOMB2, 0.5f))
        drawSmokePuff(c.near(BO, -c.radius(BO) * 0.9f, c.radius(BO)), r * 0.6f, c.win(BEHIND - 0.1f, 0.4f))
        // The tickling petal.
        if (t > TICKLE && t < SNEEZE) drawSakuraPetal(c.hand(SODA, 1) + Offset(sin(c.now * 18f) * r * 0.1f, 0f), r * 0.14f, sin(c.now * 18f) * 30f)
        comic(c, "HẮT XÌ!", c.mouth(BO) + Offset(r * 0.8f, -r * 0.3f), SNEEZE, 0.6f, 6f)
        // Mochi's tea ceremony.
        if (t < CLOUD) drawTeaBowl(c.near(MOCHI, c.radius(MOCHI) * 0.9f, c.radius(MOCHI) * 0.2f), r * 0.35f * c.vs(MOCHI), c.win(CRASH, 0.5f))
        comic(c, "OÁI!", c.head(MOCHI) + Offset(0f, -r * 0.4f), CRASH, 0.5f)
        bubble(c, MOCHI, "Hứ!", SULK + 0.1f, 0.9f)
        bubble(c, CHANH, "Mochi phạm luật!", FOUL, 0.7f)
        cloudIn(c, CLOUD)
    }
}

// ---------------------------------------------------------------------------------------------
// Fallback for scenes whose own story is not written yet: a play-bump that turns into a pile-up.
// ---------------------------------------------------------------------------------------------

internal object DefaultIntro : SceneIntro {
    override val seconds = 3.2f
    override val taps = floatArrayOf(0.9f, 1.05f, 1.2f)
    override val thumps = floatArrayOf(2.6f)

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        for (k in 0 until SLIME_COUNT) {
            p[k].lift += abs(sin(c.now * (3.2f + k * 0.45f) + k)) * r * 0.18f * (1f - c.win(0.8f, 0.2f))
        }
        val ch = p[CHANH]
        ch.x = lerp(c.home(CHANH), c.home(SODA) + (c.radius(SODA) + c.radius(CHANH)) * 0.95f, sin(PI_F * c.win(0.6f, 0.6f)))
        p[SODA].rotation = -18f * sin(PI_F * c.win(1.0f, 0.4f))
        p[MOCHI].rotation = -14f * sin(PI_F * c.win(1.15f, 0.4f))
        if (t > 1.2f) {
            p[MOCHI].mood = Mood.Angry
            p[MOCHI].anger = c.win(1.2f, 0.3f)
            p[MOCHI].armR = p[MOCHI].aimAt(1, c.head(CHANH), c.radius(MOCHI), c.stage.groundY, c.now)
            ch.armL = ch.aimAt(-1, c.head(BO), c.radius(CHANH), c.stage.groundY, c.now)
            ch.whistle = 1f
            ch.lookY = -1f
            p[BO].flush = c.win(1.4f, 0.8f)
            p[BO].mood = Mood.Angry
        }
        c.pileIn(2.2f, 0.9f)
    }

    override fun DrawScope.front(c: IntroCtx) {
        cloudIn(c, 2.6f)
    }
}

internal fun introFor(lang: ArenaLanguage): SceneIntro = when (lang.costume) {
    Costume.NonLa -> VietnamIntro
    Costume.TopHat -> LondonIntro
    Costume.Hachimaki -> JapanIntro
    Costume.Gat -> KoreaIntro
    Costume.Ushanka -> RussiaIntro
    Costume.Beret -> ParisIntro
    Costume.Hacker -> BinaryIntro
    Costume.Radio -> MorseIntro
    Costume.Cat -> CatIntro
}
