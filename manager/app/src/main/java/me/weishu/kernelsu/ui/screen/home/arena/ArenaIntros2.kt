package me.weishu.kernelsu.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Korea: tập nhảy K-pop
// ---------------------------------------------------------------------------------------------

/**
 * Soda arrives still covered in the sakura petals from Japan, takes them for confetti and bows to
 * every side. Then she and Mochi dance in perfect sync, finger hearts floating up in a row. Chanh
 * joins half a beat late, then double speed, spins like a top and bowls both over, the hearts
 * popping. In the corner Bơ reads; every crash sends a page flying. He slaps his fan shut: CẠCH!
 * Silence, and every spotlight swings onto him. Chanh panics into a spinning "HI-YAH!" that misses
 * and lands on Bơ's backside, then points at Soda.
 */
internal object KoreaIntro : SceneIntro {
    private const val BOWS = 0.2f
    private const val DANCE = 1.4f
    private val hearts = floatArrayOf(2.3f, 2.65f)
    private const val JOIN = 2.6f
    private const val BOWL = 3.5f
    private const val CLACK = 4.7f
    private const val KICK = 5.6f
    private const val BUTT = 6.05f
    private const val BLAME = 6.4f
    private const val CHARGE = 7.2f
    private const val CLOUD = 7.9f
    override val seconds = 8.9f
    override val taps = floatArrayOf(hearts[0], hearts[1], BOWL, BLAME)
    override val thumps = floatArrayOf(CLACK, BUTT)

    /** The shared dance: hops on the beat, arms swapping, a sway. */
    private fun dance(c: IntroCtx, k: Int, offset: Float, speed: Float) {
        val p = c.poses[k]
        val beat = (c.t - DANCE) * 2.8f * speed + offset
        val phase = beat - floor(beat)
        p.lift += abs(sin(PI_F * phase)) * c.r * 0.25f
        val left = floor(beat).toInt() % 2 == 0
        p.armL = if (left) 160f else 40f
        p.armR = if (left) 40f else 160f
        p.rotation = (if (left) -10f else 10f) * sin(PI_F * phase)
        p.mood = Mood.Happy
        p.sparkle = 0.6f
    }

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val so = p[SODA]
        val mo = p[MOCHI]
        val ch = p[CHANH]
        val bo = p[BO]
        // Bowing to the "fans": left, right, front, back.
        if (t < DANCE) {
            val bow = (t - BOWS) / 0.3f
            if (bow > 0f) {
                val i = floor(bow).toInt().coerceAtMost(3)
                val e = bow - i
                so.squash = 1f - 0.18f * sin(PI_F * e)
                so.lookY = 0.8f * sin(PI_F * e)
                so.rotation = listOf(-14f, 14f, 0f, 0f)[i] * sin(PI_F * e)
                so.mood = Mood.Happy
                so.sparkle = 1f
            }
        }
        // Mochi and Soda in sync; finger hearts.
        if (t > DANCE && t < BOWL) {
            dance(c, SODA, 0f, 1f)
            dance(c, MOCHI, 0f, 1f)
            for (at in hearts) if (t > at && t < at + 0.25f) {
                so.armR = 120f
                mo.armR = 120f
            }
        }
        // Chanh: late, then twice as fast, then a spinning top straight through them.
        if (t > JOIN && t < BOWL + 0.3f) {
            val e = c.win(JOIN, BOWL - JOIN)
            if (e < 0.4f) dance(c, CHANH, -0.5f, 1f) else dance(c, CHANH, 0f, 2f)
            if (e > 0.6f) {
                ch.rotation = 1440f * easeInCubic(c.win(JOIN + (BOWL - JOIN) * 0.6f, (BOWL - JOIN) * 0.4f))
                ch.x = lerp(c.home(CHANH), lerp(c.home(MOCHI), c.home(SODA), 0.5f), easeInCubic(c.win(JOIN + (BOWL - JOIN) * 0.6f, (BOWL - JOIN) * 0.4f + 0.2f)))
                ch.depth = lerp(c.depth(CHANH), (c.depth(MOCHI) + c.depth(SODA)) / 2f, c.win(JOIN + (BOWL - JOIN) * 0.6f, 0.5f))
            }
        }
        // Bowled over.
        if (t > BOWL) {
            val e = c.win(BOWL, 0.4f)
            for ((k, dir) in listOf(MOCHI to -1f, SODA to 1f)) {
                p[k].rotation = dir * 85f * easeOutCubic(e)
                p[k].lift = sin(PI_F * e) * r * 0.6f
                p[k].x = c.home(k) + dir * r * 0.6f * e
                p[k].mood = Mood.Dizzy
                p[k].dizzy = 1f
            }
            ch.x = lerp(c.home(MOCHI), c.home(SODA), 0.5f)
            ch.depth = (c.depth(MOCHI) + c.depth(SODA)) / 2f
            ch.rotation = 0f
            ch.mood = Mood.Surprised
        }
        // Bơ reads; the clack, the spotlights, the kick in the backside.
        bo.lookY = 0.8f
        bo.armL = 60f
        bo.armR = 60f
        bo.mood = Mood.Normal
        if (t > CLACK - 0.3f) {
            bo.armR = 150f * sin(PI_F * c.win(CLACK - 0.3f, 0.4f)).coerceAtLeast(0f)
            bo.lookY = 0f
            bo.mood = Mood.Angry
        }
        if (t > CLACK && t < KICK) {
            // Everyone freezes.
            for (k in intArrayOf(MOCHI, SODA, CHANH)) {
                p[k].mood = Mood.Surprised
                p[k].lookX = sign(bo.x - p[k].x)
                p[k].jiggle = 0f
            }
        }
        if (t > KICK && t < BUTT + 0.2f) {
            val e = c.win(KICK, BUTT - KICK)
            ch.x = lerp(ch.x, bo.x + c.radius(BO) * 1.1f, easeInCubic(e))
            ch.depth = lerp(ch.depth, c.depth(BO), e)
            ch.lift = sin(PI_F * e) * r * 0.9f
            ch.rotation = -720f * e
            ch.mood = Mood.Angry
            ch.armR = 160f
        }
        if (t > BUTT) {
            bo.lift += c.hop(BUTT, 0.4f, r * 0.5f)
            bo.mood = if (t < BUTT + 0.3f) Mood.Surprised else Mood.Angry
            bo.flush = c.win(BUTT + 0.2f, 0.6f)
            bo.steam = c.win(BUTT + 0.5f, 0.3f)
            ch.x = bo.x + c.radius(BO) * 1.1f + c.radius(CHANH)
            ch.depth = c.depth(BO)
            ch.rotation = 0f
            ch.mood = Mood.Surprised
        }
        if (t > BLAME && t < CHARGE) {
            ch.armL = ch.aimAt(-1, c.head(SODA), c.radius(CHANH), c.stage.groundY, c.now)
            ch.armR = 18f
            ch.reachL = 1.3f
            ch.whistle = 1f
            ch.lookY = -1f
            ch.mood = Mood.Normal
            for (k in intArrayOf(MOCHI, SODA)) {
                p[k].rotation *= 1f - c.win(BLAME, 0.4f)
                p[k].mood = Mood.Angry
            }
        }
        c.pileIn(CHARGE, 0.8f)
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        // Petals from Japan stuck all over Soda, shaken off with every bow.
        if (t < DANCE + 0.3f) {
            val left = 1f - c.win(BOWS, DANCE - BOWS + 0.3f)
            val base = c.base(SODA)
            val h = c.bodyH(SODA)
            repeat((14 * left).toInt()) { k ->
                val a = hash(k + 300) * PI_F
                drawSakuraPetal(base + Offset(cos(a) * c.radius(SODA) * 0.8f, -h * (0.15f + 0.75f * hash(k + 301))), r * 0.1f, hash(k + 302) * 360f)
            }
            repeat(6) { k ->
                val e = ((t - BOWS) * 1.2f + k / 6f) % 1f
                if (t > BOWS) drawSakuraPetal(base + Offset((hash(k + 310) - 0.5f) * r * 2f, -h * 0.6f + e * h * 0.7f), r * 0.09f, e * 500f)
            }
        }
        // Finger hearts drifting up in a row.
        for ((i, at) in hearts.withIndex()) {
            val e = c.win(at, 1.6f)
            if (e <= 0f || e >= 1f) continue
            for (k in intArrayOf(MOCHI, SODA)) {
                val from = c.hand(k, 1)
                val pop = t > BOWL && t < BOWL + 0.3f
                val at2 = from + Offset(i * r * 0.3f, -e * r * 1.6f)
                if (t < BOWL) drawHeart(at2, r * 0.14f, 1f - e * 0.4f)
                if (pop) drawImpact(at2, c.win(BOWL, 0.3f), r * 0.2f)
            }
        }
        comic(c, "bép bép", c.head(MOCHI) + Offset(r, -r * 0.6f), BOWL, 0.5f)
        // Bơ's book, pages flying at every noise.
        if (t < CHARGE) drawBook(c.near(BO, 0f, c.radius(BO) * 0.55f) + Offset(0f, 0f), r * 0.55f * c.vs(BO))
        for ((i, at) in listOf(hearts[0], hearts[1], BOWL, BOWL + 0.15f).withIndex()) {
            val e = c.win(at, 1.2f)
            if (e > 0f && e < 1f) drawPage(c.near(BO, (i - 1.5f) * r * 0.4f * e, c.radius(BO) * 0.6f + e * r * 2f), r * 0.35f, e * 300f * (if (i % 2 == 0) 1f else -1f), 1f - e)
        }
        comic(c, "CẠCH!", c.hand(BO, 1) + Offset(0f, -r * 0.5f), CLACK, 0.6f)
        // Spotlights swing onto Bơ and stay there while it is silent.
        if (t > CLACK && t < CHARGE) {
            val e = easeOutCubic(c.win(CLACK, 0.5f))
            val target = c.base(BO)
            for ((i, from) in listOf(Offset(c.stage.w * 0.15f, 0f), Offset(c.stage.w * 0.85f, 0f), Offset(c.stage.w * 0.5f, 0f)).withIndex()) {
                val wander = Offset(c.stage.w * (0.2f + 0.3f * i), c.stage.groundY)
                drawSpotlight(from, lerp(wander, target, e), r * 1.1f, Color(0xFFFFF3BF), 0.8f)
            }
        }
        bubble(c, CHANH, "HI-YAH!", KICK, 0.45f)
        if (t > BUTT) drawImpact(c.near(BO, c.radius(BO) * 0.9f, c.radius(BO) * 0.4f), c.win(BUTT, 0.35f), r * 0.5f)
        bubble(c, CHANH, "Soda đó!", BLAME, 0.7f)
        cloudIn(c, CLOUD)
    }
}

// ---------------------------------------------------------------------------------------------
// Russia: ném tuyết
// ---------------------------------------------------------------------------------------------

/**
 * The idol's microphone from Seoul is stuck in a snow pile; Mochi has a snowman built round it
 * ("Đầu to hơn!" ... "Không, nhỏ lại!") and it starts to sing. Chanh, rolling the next snowball,
 * gets rolled up inside it, feet sticking out. He bursts free and throws one at Mochi, misses, and
 * hits sleeping Bơ full in the face: a snow mask that cracks, rắc... rắc... Mochi hides among
 * matryoshka dolls that look exactly like her; Bơ knocks them down one by one, bốp, bốp, bốp...
 * "ÁI!". Bơ rolls a snowball twice his size; Mochi and Chanh look at each other and run; Soda,
 * pirouetting on the ice, spins right into its path and is rolled up. The snowball sprouts arms
 * and legs and becomes the fight.
 */
internal object RussiaIntro : SceneIntro {
    private const val BIGGER = 0.3f
    private const val SMALLER = 1.25f
    private const val ROLL = 1.7f
    private const val INSIDE = 2.3f
    private const val BURST = 3.0f
    private const val THROW = 3.2f
    private const val HIT = 3.55f
    private const val CRACK_OFF = 4.7f
    private const val HIDE = 4.7f
    private val bops = floatArrayOf(5.1f, 5.45f, 5.8f, 6.15f)
    private const val GIANT = 6.4f
    private const val RUN = 7.0f
    private const val SPIN = 7.0f
    private const val ROLLED = 7.9f
    private const val CLOUD = 8.6f
    override val seconds = 9.5f
    override val taps = floatArrayOf(BIGGER, SMALLER, THROW, bops[0], bops[1], bops[2])
    override val thumps = floatArrayOf(HIT, bops[3], ROLLED)

    private fun snowmanAt(c: IntroCtx) = c.floor(c.home(CHANH) + c.r * 2.6f, c.depth(CHANH) - 0.6f)

    /** Where Bơ's giant snowball is: rolling from beside him toward the middle. */
    private fun giant(c: IntroCtx): Offset {
        val e = c.win(GIANT, CLOUD - GIANT)
        val x = lerp(c.home(BO) + c.radius(BO) * 1.6f, c.stage.center, easeInOutCubic(e))
        val z = lerp(c.depth(BO), 0f, e)
        val rr = c.radius(BO) * (0.4f + 1.6f * c.win(GIANT, 1.2f))
        return c.point(x, rr, z)
    }

    private fun giantRadius(c: IntroCtx) = c.radius(BO) * (0.4f + 1.6f * c.win(GIANT, 1.2f)) * c.scaleAt(lerp(c.depth(BO), 0f, c.win(GIANT, CLOUD - GIANT)))

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val mo = p[MOCHI]
        val ch = p[CHANH]
        val bo = p[BO]
        val so = p[SODA]
        // Mochi directs the snowman.
        if (t < ROLL) {
            mo.armR = mo.aimAt(1, snowmanAt(c), c.radius(MOCHI), c.stage.groundY, c.now)
            mo.reachR = 1.3f
            mo.akimbo = 0f
            mo.mood = if (t < SMALLER) Mood.Normal else Mood.Angry
            mo.lift += c.hop(BIGGER, 0.3f, r * 0.2f) + c.hop(SMALLER, 0.3f, r * 0.2f)
        }
        // Chanh rolls a snowball and ends up inside it.
        if (t > ROLL && t < BURST) {
            val e = c.win(ROLL, BURST - ROLL)
            ch.x = c.home(CHANH) - easeInOutCubic(e) * r * 1.5f
            ch.lookX = -1f
            ch.mood = Mood.Happy
            ch.armL = 100f
            ch.armR = 100f
            if (t > INSIDE) ch.visible = false
        }
        if (t > BURST) {
            ch.x = c.home(CHANH) - r * 1.5f
            ch.lift += c.hop(BURST, 0.4f, r * 0.6f)
            ch.mood = if (t < THROW + 0.4f) Mood.Happy else Mood.Surprised
            if (t > THROW - 0.1f && t < THROW + 0.2f) {
                ch.armR = 170f
                ch.reachR = 1.4f
            }
        }
        // Bơ asleep in his fur coat, until the snowball; the mask cracks and falls.
        if (t < HIT) {
            bo.sleep = 1f
            bo.snore = 0.4f + 0.2f * sin(c.now * 2.4f)
        } else if (t < CRACK_OFF) {
            bo.mood = Mood.Surprised
            bo.squash = 1f - 0.1f * sin(PI_F * c.win(HIT, 0.25f))
            bo.shakeX = if (t > CRACK_OFF - 0.4f) sin(c.now * 60f) * r * 0.03f else 0f
        } else {
            bo.mood = Mood.Angry
            bo.flush = c.win(CRACK_OFF, 0.5f)
            bo.lookX = 1f
        }
        // Bơ's barrage at the dolls; the last one is Mochi.
        if (t > HIDE && t < GIANT) {
            mo.squash = 0.75f
            mo.lookX = -1f
            mo.mood = Mood.Worried
            mo.sweat = 1f
            for ((i, at) in bops.withIndex()) {
                val e = c.win(at - 0.25f, 0.3f)
                if (e > 0f && e < 1f) {
                    bo.armR = 170f * sin(PI_F * e)
                    bo.reachR = 1.4f
                }
                if (i == bops.lastIndex && t > at) {
                    mo.mood = Mood.Dizzy
                    mo.dizzy = 1f
                    mo.squash = 1f
                    mo.lift += c.hop(at, 0.3f, r * 0.4f)
                }
            }
        }
        // The giant snowball; Mochi and Chanh look at each other and run.
        if (t > GIANT) {
            bo.armL = 90f
            bo.armR = 90f
            bo.reachL = 1.4f
            bo.reachR = 1.4f
            bo.x = lerp(c.home(BO), c.stage.center - c.radius(BO) * 2f, easeInOutCubic(c.win(GIANT, CLOUD - GIANT)))
            bo.depth = lerp(c.depth(BO), 0f, c.win(GIANT, CLOUD - GIANT))
            bo.mood = Mood.Angry
            c.shake = r * 0.06f * c.win(GIANT, 1f)
            if (t < RUN) {
                mo.lookX = sign(ch.x - mo.x)
                ch.lookX = sign(mo.x - ch.x)
                mo.mood = Mood.Surprised
                ch.mood = Mood.Surprised
            } else {
                for (k in intArrayOf(MOCHI, CHANH)) {
                    val e = c.win(RUN, 0.9f)
                    p[k].x = lerp(p[k].x, c.stage.w * 0.88f, easeInCubic(e))
                    p[k].lift += abs(sin(e * PI_F * 4f)) * r * 0.3f
                    p[k].mood = Mood.Worried
                    p[k].sweat = 1f
                    p[k].lookX = 1f
                }
            }
        }
        // Soda's pirouettes, straight into the snowball's path.
        so.armL = 150f
        so.armR = 150f
        so.mood = Mood.Happy
        so.sparkle = 0.8f
        if (t > SPIN) {
            so.rotation = (t - SPIN) * 900f
            val g = giant(c)
            so.x = lerp(c.home(SODA), lerp(c.home(BO), c.stage.center, 0.7f), c.win(SPIN, ROLLED - SPIN))
            if (t > ROLLED) so.visible = false
        } else {
            so.rotation = 10f * sin(c.now * 2f)
        }
        if (t > CLOUD - 0.2f) for (k in 0 until SLIME_COUNT) p[k].visible = false
    }

    override fun DrawScope.back(c: IntroCtx) {
        val t = c.t
        // The snowman round the microphone from Seoul, singing.
        if (t < CLOUD) {
            val at = snowmanAt(c)
            val k = c.scaleAt(c.depth(CHANH) - 0.6f)
            val built = c.win(0f, 0.9f)
            val head = when {
                t < BIGGER -> 1f
                t < SMALLER -> 1f + 0.5f * easeOutBack(c.win(BIGGER, 0.4f))
                else -> lerp(1.5f, 0.7f, easeOutBack(c.win(SMALLER, 0.4f)))
            }
            drawSnowman(at, c.r * 0.8f * k, head, built)
            drawHandMic(at + Offset(c.r * 0.2f * k, -c.r * 1.1f * k), c.r * 0.45f * k, 25f)
            if (t > 0.9f) repeat(3) { i ->
                val e = ((c.now * 0.6f) + i / 3f) % 1f
                drawNote(at + Offset(c.r * (0.6f + 0.4f * e) * k, -c.r * (1.8f + e * 1.2f) * k), c.r * 0.18f * k, 1f - e)
            }
        }
    }

    private fun DrawScope.drawNote(c: Offset, s: Float, alpha: Float) {
        drawCircle(Color(0xFF263238), s * 0.35f, c, alpha = alpha)
        drawLine(Color(0xFF263238), c + Offset(s * 0.3f, 0f), c + Offset(s * 0.3f, -s * 1.1f), s * 0.12f, alpha = alpha)
        drawLine(Color(0xFF263238), c + Offset(s * 0.3f, -s * 1.1f), c + Offset(s * 0.8f, -s * 0.8f), s * 0.12f, StrokeCap.Round, alpha = alpha)
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        bubble(c, MOCHI, "Đầu to hơn!", BIGGER, 0.85f)
        bubble(c, MOCHI, "Không, nhỏ lại!", SMALLER, 0.7f)
        // Chanh's snowball, with him inside it.
        if (t > ROLL && t < BURST + 0.2f) {
            val e = c.win(ROLL, BURST - ROLL)
            val rr = c.radius(CHANH) * (0.4f + 1.2f * e) * c.vs(CHANH)
            val at = c.near(CHANH, -c.radius(CHANH) * (0.9f - 0.9f * c.win(INSIDE, 0.2f)), 0f) + Offset(0f, -rr)
            drawSnowball(at, rr, -e * 720f)
            if (t > INSIDE && t < BURST) {
                for (s in listOf(-1f, 1f)) {
                    val foot = at + Offset(s * rr * 0.35f + sin(c.now * 20f + s) * rr * 0.05f, rr * 0.95f)
                    drawCircle(Cast[CHANH].body, rr * 0.18f, foot)
                }
            }
            if (t > BURST) drawSmokePuff(at, rr, c.win(BURST, 0.4f))
        }
        // The throw: misses Mochi, hits Bơ.
        if (t > THROW && t < HIT) {
            val e = c.win(THROW, HIT - THROW)
            drawSnowball(arc(c.hand(CHANH, 1), c.mouth(BO), r * 1.2f, e), r * 0.2f, e * 400f)
        }
        if (t > HIT && t < CRACK_OFF + 0.6f) {
            val crack = c.win(HIT + 0.3f, CRACK_OFF - HIT - 0.3f)
            drawSnowMask(lerp(c.head(BO), c.mouth(BO), 0.6f), c.radius(BO) * 0.75f * c.vs(BO), max(crack, c.win(CRACK_OFF, 0.6f) * 0.25f + crack))
        }
        comic(c, "BỘP!", c.mouth(BO) + Offset(r * 0.5f, -r * 0.6f), HIT, 0.4f)
        comic(c, "rắc...", c.head(BO) + Offset(r * 0.9f, -r * 0.2f), HIT + 0.5f, 0.5f, 6f)
        comic(c, "rắc...", c.head(BO) + Offset(-r * 0.9f, -r * 0.1f), HIT + 0.9f, 0.5f, -6f)
        // Matryoshka dolls in front of Mochi, knocked flat one by one.
        if (t > HIDE - 0.3f && t < GIANT + 0.5f) {
            val pop = easeOutBack(c.win(HIDE - 0.3f, 0.35f))
            for (i in 0 until 3) {
                val dx = (i - 1f) * c.radius(MOCHI) * 0.75f
                val at = c.floor(c.home(MOCHI) + dx, c.depth(MOCHI) + 0.5f)
                val fall = c.win(bops[i], 0.3f)
                drawMatryoshka(at, c.radius(MOCHI) * 0.55f * pop * c.scaleAt(c.depth(MOCHI) + 0.5f), Color(0xFFC1121F), -90f * easeInCubic(fall) * (if (i % 2 == 0) 1f else -1f))
            }
        }
        for ((i, at) in bops.withIndex()) {
            if (t > at - 0.25f && t < at) {
                val e = c.win(at - 0.25f, 0.25f)
                val target = if (i < 3) c.floor(c.home(MOCHI) + (i - 1f) * c.radius(MOCHI) * 0.75f, c.depth(MOCHI) + 0.5f) + Offset(0f, -c.radius(MOCHI)) else c.head(MOCHI)
                drawSnowball(arc(c.hand(BO, 1), target, r * 0.8f, e), r * 0.16f, e * 360f)
            }
            comic(c, if (i < 3) "bốp" else "ÁI!", c.head(MOCHI) + Offset((i - 1.5f) * r * 0.5f, -r * 0.5f), at, 0.4f, (i - 1.5f) * 8f)
        }
        // The giant snowball, sprouting arms and legs as the fight takes it over.
        if (t > GIANT && t < CLOUD + 0.3f) {
            val at = giant(c)
            val rr = giantRadius(c)
            drawSnowball(at, rr, -(t - GIANT) * 300f)
            if (t > ROLLED) {
                val sprout = c.win(ROLLED, 0.5f)
                for (k in 0 until 4) {
                    val a = k * TAU / 4 + c.now * 3f
                    val from = at + Offset(cos(a), sin(a)) * rr * 0.9f
                    drawLine(Cast[k].body, from, from + Offset(cos(a), sin(a)) * rr * 0.35f * sprout, rr * 0.12f, StrokeCap.Round)
                }
            }
        }
        cloudIn(c, CLOUD)
    }
}

// ---------------------------------------------------------------------------------------------
// Paris: họa sĩ và bánh mì
// ---------------------------------------------------------------------------------------------

/**
 * The giant snowball from Russia rolls to a stop by Mochi's easel; she adds it to her canvas in
 * two dabs as it melts away, then paints the Eiffel Tower, bouncing with every dab. Soda the mime
 * is stuck in an invisible box, feels its walls, leans on one... and slides straight into the
 * easel, smearing the tower into a leaning one. Chanh snatches Bơ's baguette: "En garde!". Bơ
 * twirls his mustache and draws one three times longer from behind his back; crumbs fly like
 * fireworks until Chanh's snaps to a stub, and he points at Mochi. Mochi, paint all over her face,
 * hurls her palette; Soda raises an invisible shield... which does not stop it.
 */
internal object ParisIntro : SceneIntro {
    private const val ARRIVE = 0.7f
    private val dabsSnow = floatArrayOf(0.9f, 1.2f)
    private val dabs = floatArrayOf(1.6f, 2.0f, 2.4f, 2.8f)
    private const val BOX = 1.5f
    private const val LEAN = 3.2f
    private const val SLIP = 3.5f
    private const val SMEAR = 3.8f
    private const val SNATCH = 4.2f
    private const val GARDE = 4.5f
    private const val TWIRL = 5.0f
    private const val DRAW = 5.5f
    private const val DUEL = 6.0f
    private const val SNAP = 7.0f
    private const val BLAME = 7.2f
    private const val THROW = 7.6f
    private const val SPLAT = 8.0f
    private const val CLOUD = 8.5f
    override val seconds = 9.4f
    override val taps = dabsSnow + dabs + floatArrayOf(SMEAR, SNATCH, SNAP)
    override val thumps = floatArrayOf(SLIP, SPLAT)

    private fun easelX(c: IntroCtx) = c.home(BO) - c.r * 3.2f
    private fun easelZ(c: IntroCtx) = c.depth(BO) + 0.3f
    private fun easel(c: IntroCtx) = c.floor(easelX(c), easelZ(c))

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val mo = p[MOCHI]
        val so = p[SODA]
        val ch = p[CHANH]
        val bo = p[BO]
        // Mochi hops over to the easel and paints.
        val go = c.win(0f, ARRIVE)
        val spotX = easelX(c) + r * 1.4f
        val spotZ = easelZ(c) + 0.5f
        mo.travel(MOCHI, c.home(MOCHI), c.depth(MOCHI), spotX, spotZ, go, c.stage)
        if (go >= 1f) {
            mo.x = spotX
            mo.depth = spotZ
            mo.lookX = -1f
            mo.mood = Mood.Happy
            for (at in dabsSnow + dabs) {
                val e = c.win(at - 0.15f, 0.3f)
                if (e > 0f && e < 1f) {
                    mo.armL = mo.aimAt(-1, easel(c) + Offset(0f, -r * 1.6f), c.radius(MOCHI), c.stage.groundY, c.now)
                    mo.reachL = 1.3f
                    mo.lift += sin(PI_F * e) * r * 0.3f
                }
            }
        }
        if (t > SMEAR) {
            mo.mood = Mood.Surprised
            if (t > SMEAR + 0.4f) mo.mood = Mood.Angry
        }
        // Soda, the mime in the invisible box; leans on a wall and slides into the easel.
        if (t > BOX && t < LEAN) {
            val e = (t - BOX) * 2f
            so.armL = 90f + 10f * sin(e * 3f)
            so.armR = 90f + 10f * cos(e * 3f)
            so.reachL = 1.2f
            so.reachR = 1.2f
            so.lookX = sin(e)
            so.mood = Mood.Surprised
            so.shakeX = sin(e * 2f) * r * 0.05f
        }
        if (t > LEAN && t < SLIP) {
            so.rotation = -18f * c.win(LEAN, 0.3f)
            so.armL = 60f
            so.mood = Mood.Happy
        }
        if (t > SLIP) {
            val e = c.win(SLIP, SMEAR - SLIP)
            so.x = lerp(c.home(SODA), easelX(c) + r * 0.6f, easeInCubic(e))
            so.depth = lerp(c.depth(SODA), easelZ(c) + 0.2f, e)
            so.rotation = lerp(-18f, -85f, e)
            so.mood = Mood.Surprised
            if (t > SMEAR) {
                so.x = easelX(c) + r * 0.6f
                so.depth = easelZ(c) + 0.2f
                so.rotation = -85f
                so.mood = Mood.Dizzy
                so.dizzy = 1f
            }
        }
        // The baguette duel.
        bo.armR = 70f
        bo.mood = Mood.Normal
        if (t > SNATCH - 0.3f && t < DUEL) {
            val e = c.win(SNATCH - 0.3f, 0.3f)
            ch.x = lerp(c.home(CHANH), c.home(BO) + c.radius(BO) * 1.5f, easeInOutCubic(e))
            ch.depth = lerp(c.depth(CHANH), c.depth(BO) + 0.4f, e)
            ch.lift += sin(PI_F * e) * r * 0.6f
        }
        if (t > SNATCH) {
            ch.x = c.home(BO) + c.radius(BO) * 1.5f + c.radius(CHANH)
            ch.depth = c.depth(BO) + 0.4f
            bo.mood = Mood.Surprised
            bo.lookX = 1f
        }
        if (t > GARDE) {
            ch.armR = 165f
            ch.mood = Mood.Happy
            ch.lookX = -1f
        }
        if (t > TWIRL && t < DRAW) {
            bo.armL = 130f + 15f * sin(c.now * 20f)
            bo.mood = Mood.Happy
        }
        if (t > DRAW) {
            bo.armR = lerp(30f, 120f, c.win(DRAW, 0.4f))
            bo.mood = Mood.Angry
        }
        if (t > DUEL && t < SNAP) {
            val beat = ((t - DUEL) * 4f).toInt()
            bo.armR = if (beat % 2 == 0) 100f else 140f
            ch.armR = if (beat % 2 == 0) 140f else 100f
            ch.lift += abs(sin((t - DUEL) * 4f * PI_F)) * r * 0.2f
            bo.squash = 1f + 0.04f * sin((t - DUEL) * 25f)
        }
        if (t > SNAP) {
            ch.mood = Mood.Surprised
            ch.armR = 100f
        }
        if (t > BLAME && t < CLOUD) {
            ch.armL = ch.aimAt(-1, c.head(MOCHI), c.radius(CHANH), c.stage.groundY, c.now)
            ch.reachL = 1.3f
            ch.whistle = 1f
            ch.lookY = -1f
        }
        // Mochi throws the palette; Soda's invisible shield.
        if (t > THROW - 0.3f && t < THROW + 0.2f) {
            mo.armL = 165f
            mo.reachL = 1.4f
            mo.mood = Mood.Angry
        }
        if (t > THROW) {
            so.rotation *= 1f - c.win(THROW, 0.2f)
            so.armL = 90f
            so.armR = 90f
            so.reachL = 1.3f
            so.reachR = 1.3f
            so.mood = Mood.Worried
            if (t > SPLAT) {
                so.mood = Mood.Dizzy
                so.lift += c.hop(SPLAT, 0.3f, r * 0.4f)
            }
        }
        c.pileIn(CLOUD, 0.7f)
    }

    override fun DrawScope.back(c: IntroCtx) {
        val t = c.t
        val r = c.r
        if (t > CLOUD + 0.2f) return
        val k = c.scaleAt(easelZ(c))
        val snowman = (c.win(dabsSnow[0], 0.1f) * 0.5f + c.win(dabsSnow[1], 0.1f) * 0.5f)
        var tower = 0f
        for (at in dabs) tower += c.win(at, 0.1f) / dabs.size
        drawEasel(easel(c), r * 0.8f * k, snowman, tower, c.win(SMEAR - 0.2f, 0.3f))
        // The snowball from Russia, rolling in and melting as it goes into the picture.
        val roll = c.win(0f, ARRIVE)
        val melt = c.win(dabsSnow[0], 0.8f)
        if (melt < 1f) {
            val at = c.floor(lerp(easelX(c) - r * 3f, easelX(c) + r * 0.2f, easeOutCubic(roll)), easelZ(c) + 0.4f)
            val rr = r * 0.9f * c.scaleAt(easelZ(c) + 0.4f) * (1f - 0.8f * melt)
            drawSnowball(at + Offset(0f, -rr), rr, roll * 400f)
            if (melt > 0f) drawOval(Color(0xFFB3D4F5), at - Offset(rr * 1.6f * melt, rr * 0.2f), androidx.compose.ui.geometry.Size(rr * 3.2f * melt, rr * 0.4f), alpha = 0.5f * melt)
        }
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        // The mime's invisible box, just hinted where her hands touch it.
        if (t > BOX && t < LEAN + 0.3f) {
            for (side in listOf(-1, 1)) {
                val h = c.hand(SODA, side)
                drawLine(Color.White, h + Offset(0f, -r * 0.4f), h + Offset(0f, r * 0.4f), r * 0.03f, alpha = 0.5f)
                drawSparkle(h, r * 0.08f, Color.White, 0.6f)
            }
        }
        comic(c, "ối", c.head(SODA) + Offset(0f, -r * 0.4f), SLIP, 0.4f)
        if (t > SMEAR) drawPaintSplat(c.head(MOCHI) + Offset(-r * 0.1f, r * 0.5f), r * 0.1f, Color(0xFFE63946), 3, 1f)
        if (t > SMEAR) drawPaintSplat(c.mouth(MOCHI) + Offset(r * 0.25f, -r * 0.2f), r * 0.08f, Color(0xFF2A9D8F), 7, 1f)
        // Baguettes.
        val boLen = if (t < DRAW) r * 1.2f else r * (1.2f + 2.4f * easeOutBack(c.win(DRAW, 0.5f)))
        if (t < SNATCH) {
            drawBaguette(c.hand(BO, 1) + Offset(r * 0.3f, 0f), r * 1.2f * c.vs(BO), -30f)
        } else if (t > DRAW - 0.1f && t < CLOUD) {
            drawBaguette(c.hand(BO, 1) + Offset(boLen * 0.4f, -r * 0.2f), boLen * c.vs(BO), -25f + (if (t > DUEL) sin((t - DUEL) * 25f) * 15f else 0f))
        }
        if (t > SNATCH && t < CLOUD) {
            val len = if (t < SNAP) r * 1.2f else r * 0.45f
            drawBaguette(c.hand(CHANH, 1) + Offset(-len * 0.4f, -r * 0.3f), len * c.vs(CHANH), 200f + (if (t > DUEL && t < SNAP) sin((t - DUEL) * 25f) * 15f else 0f))
            if (t > SNAP) {
                val e = c.win(SNAP, 0.6f)
                if (e < 1f) drawBaguette(c.hand(CHANH, 1) + Offset(-r * 0.6f, e * r * 1.4f), r * 0.6f * c.vs(CHANH), e * 300f)
            }
        }
        bubble(c, CHANH, "En garde!", GARDE, 0.8f)
        if (t > DUEL && t < SNAP + 0.3f) {
            val mid = lerp(c.hand(BO, 1), c.hand(CHANH, 1), 0.5f) + Offset(0f, -r * 0.4f)
            repeat(10) { i ->
                val e = ((t - DUEL) * 1.6f + i / 10f) % 1f
                val a = hash(i + 700) * TAU
                drawCircle(Color(0xFFF6C27A), r * 0.05f * (1f - e), mid + Offset(cos(a), sin(a) - 0.4f) * (r * 1.2f * e), alpha = 1f - e)
            }
        }
        comic(c, "RẮC!", c.hand(CHANH, 1) + Offset(0f, -r * 0.6f), SNAP, 0.4f)
        // The palette in flight, and paint all over Soda.
        if (t > THROW && t < SPLAT) {
            val e = c.win(THROW, SPLAT - THROW)
            val at = arc(c.hand(MOCHI, -1), c.head(SODA), r * 1.2f, e)
            drawOval(Color(0xFFD9A066), at - Offset(r * 0.35f, r * 0.22f), androidx.compose.ui.geometry.Size(r * 0.7f, r * 0.44f))
            for (k in 0 until 4) drawCircle(listOf(Color(0xFFE63946), Color(0xFFFFC300), Color(0xFF2A9D8F), Color(0xFF8E44AD))[k], r * 0.06f, at + Offset((k - 1.5f) * r * 0.13f, -r * 0.04f))
        }
        if (t > SPLAT) {
            val e = c.win(SPLAT, 0.3f)
            for (k in 0 until 4) drawPaintSplat(c.head(SODA) + Offset((k - 1.5f) * r * 0.3f, r * (0.3f + 0.1f * k)), r * 0.12f * easeOutBack(e), listOf(Color(0xFFE63946), Color(0xFFFFC300), Color(0xFF2A9D8F), Color(0xFF8E44AD))[k], k + 20)
        }
        comic(c, "BẸT!", c.head(SODA) + Offset(r * 0.6f, -r * 0.4f), SPLAT, 0.5f)
        cloudIn(c, CLOUD)
    }
}
