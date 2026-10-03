package me.weishu.kernelsu.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.Random
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin

/**
 * How the four stand about between rounds. A different one each round so they are not always
 * lined up like a police line-up; left to right they stay Bơ, Mochi, Soda, Chanh, so every scene's
 * opening still knows who is next to whom.
 */
internal enum class Formation {
    /** The plain row. */
    Line,

    /** A lopsided four-sided huddle: two at the back, two in front. */
    Quad,

    /** A smile-shaped arc, the middle two further back. */
    Arc,

    /** A diagonal staircase from Bơ at the back left to Chanh at the front right. */
    Steps,

    /** Two pairs chatting with a gap between them. */
    Pairs,

    /** Three in a row while Chanh roams about until Mochi fetches him back. */
    Loose,
}

/** Home spots (x on the stage, depth in the box) for one formation. */
internal class Homes(val formation: Formation, val x: FloatArray, val z: FloatArray)

internal fun Stage.lineHomes() = Homes(Formation.Line, homes.copyOf(), homeDepth.copyOf())

/** Home spots for [f], jittered by [rnd] so the same formation is never quite the same. */
internal fun Stage.homesFor(f: Formation, rnd: Random): Homes {
    fun j(range: Float) = (rnd.nextFloat() - 0.5f) * 2f * range
    val x = FloatArray(SLIME_COUNT)
    val z = FloatArray(SLIME_COUNT)
    when (f) {
        Formation.Line -> return lineHomes()
        Formation.Quad -> {
            val half = r * (4.2f + 1.6f * rnd.nextFloat())
            x[BO] = center - half * 0.9f + j(r * 0.3f)
            z[BO] = -2.2f + j(0.5f)
            x[MOCHI] = center - half * 0.35f + j(r * 0.3f)
            z[MOCHI] = 0.9f + j(0.4f)
            x[SODA] = center + half * 0.3f + j(r * 0.3f)
            z[SODA] = -1.8f + j(0.6f)
            x[CHANH] = center + half * 0.9f + j(r * 0.3f)
            z[CHANH] = 0.8f + j(0.4f)
        }

        Formation.Arc -> {
            val step = r * (2.4f + 0.4f * rnd.nextFloat())
            val order = intArrayOf(BO, MOCHI, SODA, CHANH)
            for ((i, k) in order.withIndex()) {
                val u = i - 1.5f
                x[k] = center + u * step + j(r * 0.15f)
                z[k] = -2.4f + u * u * 0.95f
            }
        }

        Formation.Steps -> {
            val step = r * (2.3f + 0.5f * rnd.nextFloat())
            val order = intArrayOf(BO, MOCHI, SODA, CHANH)
            for ((i, k) in order.withIndex()) {
                x[k] = center + (i - 1.5f) * step
                z[k] = -2.6f + i * 1.15f
            }
        }

        Formation.Pairs -> {
            val gap = r * (2.2f + rnd.nextFloat())
            x[BO] = center - gap - (radius[BO] + radius[MOCHI]) * 0.95f - radius[MOCHI]
            x[MOCHI] = center - gap - radius[MOCHI] * 0.3f
            x[SODA] = center + gap + radius[SODA] * 0.3f
            x[CHANH] = center + gap + (radius[SODA] + radius[CHANH]) * 0.95f + radius[SODA] * 0.2f
            z[BO] = -0.8f + j(0.3f)
            z[MOCHI] = -0.4f + j(0.3f)
            z[SODA] = -0.6f + j(0.3f)
            z[CHANH] = 0.2f + j(0.3f)
        }

        Formation.Loose -> {
            val line = lineHomes()
            for (k in 0 until SLIME_COUNT) {
                x[k] = line.x[k] - r * 0.6f
                z[k] = line.z[k]
            }
            x[CHANH] = line.x[CHANH] + r * 0.8f
        }
    }
    return Homes(f, x, z)
}

/** The next formation: never the same twice in a row. */
internal fun Stage.nextHomes(after: Formation, rnd: Random): Homes {
    val pick = Formation.entries.filter { it != after }
    return homesFor(pick[rnd.nextInt(pick.size)], rnd)
}

/** Length of one round of the Loose skit: Chanh roams, gets fetched, sulks. */
private const val ROAM_CYCLE = 7f
private const val ROAM_UNTIL = 3.6f
private const val FETCH = 4.0f
private const val HOME_AT = 4.7f
private const val ROAM_HOPS = 4

/**
 * Between-round business that depends on the formation; [t] is the time since they settled.
 * Returns false when the formation has nothing special (the small random acts play instead).
 */
internal fun Array<SlimePose>.idleSkit(homes: Homes, s: Stage, t: Float, now: Float): Boolean {
    when (homes.formation) {
        Formation.Loose -> {
            val ch = this[CHANH]
            val mo = this[MOCHI]
            val e = t % ROAM_CYCLE
            val round = floor(t / ROAM_CYCLE).toInt()
            val home = homes.x[CHANH]
            val homeZ = homes.z[CHANH]
            // Four bounces to random spots round the box, a moment of alarm, then the haul back.
            val hopLen = (ROAM_UNTIL - 0.5f) / ROAM_HOPS
            fun spot(i: Int): Pair<Float, Float> =
                if (i < 0) home to homeZ else (s.w * (0.2f + 0.6f * hash(round * 97 + i * 13))) to (-2.8f + 3.8f * hash(round * 97 + i * 13 + 5))
            val (lastX, lastZ) = spot(ROAM_HOPS - 1)
            when {
                e < 0.5f -> {}
                e < ROAM_UNTIL -> {
                    val hop = floor((e - 0.5f) / hopLen).toInt()
                    val (fx, fz) = spot(hop - 1)
                    val (tx, tz) = spot(hop)
                    ch.travel(CHANH, fx, fz, tx, tz, ((e - 0.5f) - hop * hopLen) / hopLen, s)
                    ch.mood = Mood.Happy
                }

                e < FETCH -> {
                    ch.x = lastX
                    ch.depth = lastZ
                    ch.mood = Mood.Surprised
                    ch.lookX = sign(mo.x - ch.x)
                }

                e < HOME_AT -> {
                    val pull = easeInCubic(progress(e, FETCH, HOME_AT - FETCH))
                    ch.x = lerp(lastX, home, pull)
                    ch.depth = lerp(lastZ, homeZ, pull)
                    ch.lift = sin(PI_F * pull) * s.r * 0.6f
                    ch.rotation = -sign(home - lastX + 0.01f) * 360f * pull
                    ch.mood = Mood.Surprised
                    ch.armL = 150f
                    ch.armR = 150f
                }

                else -> {
                    // Back in line, sulking and whistling at the ceiling.
                    ch.whistle = 1f - progress(e, HOME_AT + 1.4f, 0.4f)
                    ch.lookY = -1f
                }
            }
            // Mochi: watches, loses patience, reaches out and hauls him back.
            if (e in 2.4f..HOME_AT + 0.3f) {
                mo.lookX = sign(ch.x - mo.x)
                if (e < FETCH) {
                    mo.akimbo = progress(e, 2.4f, 0.3f)
                    mo.mood = Mood.Angry
                    mo.steam = progress(e, 3.2f, 0.3f)
                } else {
                    mo.mood = Mood.Angry
                    mo.armR = 100f
                    mo.reachR = 1.5f
                    mo.squash = 1f - 0.08f * sin(PI_F * progress(e, FETCH, 0.7f))
                }
            }
            return true
        }

        Formation.Pairs -> {
            // Two chats: they face each other and take turns talking; now and then a laugh.
            for ((a, b) in listOf(BO to MOCHI, SODA to CHANH)) {
                this[a].lookX = sign(this[b].x - this[a].x)
                this[b].lookX = sign(this[a].x - this[b].x)
                val turn = floor(now / 1.3f + a).toInt() % 2
                val talker = if (turn == 0) this[a] else this[b]
                talker.mouthOpen = 0.15f + 0.3f * kotlin.math.abs(sin(now * 13f + a))
                talker.squash = 1f + 0.03f * sin(now * 13f)
                if (hash(floor(now / 2.5f).toInt() * 7 + a) > 0.7f) {
                    val e = (now % 2.5f) / 2.5f
                    if (e < 0.4f) for (p in listOf(this[a], this[b])) {
                        p.mood = Mood.Happy
                        p.jiggle = 1f - e / 0.4f
                        p.lift += sin(PI_F * e / 0.4f) * s.r * 0.15f
                    }
                }
            }
            return true
        }

        Formation.Arc -> {
            // Bơ, at the back of the arc, nods off; the others peek at him.
            val bo = this[BO]
            bo.sleep = 1f
            bo.snore = 0.35f + 0.2f * sin(now * 2.4f)
            if (hash(floor(now / 3f).toInt() + 11) > 0.5f) {
                for (k in intArrayOf(MOCHI, SODA)) this[k].lookX = sign(bo.x - this[k].x)
            }
            return false
        }

        Formation.Steps -> {
            // A Mexican wave running down the stairs every few seconds.
            val e = now % 4.5f
            for (k in 0 until SLIME_COUNT) {
                val w = progress(e, 0.2f + k * 0.18f, 0.45f)
                if (w > 0f && w < 1f) {
                    this[k].lift += sin(PI_F * w) * s.r * 0.4f
                    this[k].armL = 18f + 140f * sin(PI_F * w)
                    this[k].armR = this[k].armL
                    this[k].mood = Mood.Happy
                }
            }
            return true
        }

        else -> return false
    }
}

/** The overlay part of the idle skits: Mochi's stretchy arm hauling Chanh back. */
internal fun DrawScope.drawIdleSkit(poses: Array<SlimePose>, homes: Homes, s: Stage, t: Float, now: Float) {
    if (homes.formation != Formation.Loose) return
    val e = t % ROAM_CYCLE
    if (e !in FETCH - 0.15f..HOME_AT) return
    val from = poses[MOCHI].hand(1, s.radius[MOCHI], s.groundY, now)
    val to = poses[CHANH].hand(-1, s.radius[CHANH], s.groundY, now)
    val reach = if (e < FETCH) easeOutCubic(progress(e, FETCH - 0.15f, 0.15f)) else 1f
    val grab = lerp(from, to, reach)
    drawLine(Cast[MOCHI].deep, from, grab, s.r * 0.2f, StrokeCap.Round, alpha = 0.6f)
    drawLine(Cast[MOCHI].body, from, grab, s.r * 0.15f, StrokeCap.Round)
    drawCircle(Cast[MOCHI].body, s.r * 0.13f, grab)
    drawCircle(androidx.compose.ui.graphics.Color.White, s.r * 0.04f, grab + Offset(-s.r * 0.03f, -s.r * 0.04f), alpha = 0.8f)
}
