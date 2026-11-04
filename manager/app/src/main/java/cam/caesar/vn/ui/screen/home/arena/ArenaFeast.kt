package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * The feast and the choking, in several versions that take turns round after round so the middle
 * of the show is not the same every time.
 */
internal enum class EatStyle {
    /** Side by side at the "buffet"; Mochi shouts "Xếp hàng!" and pushes in first. */
    Buffet,

    /** A proper single file; Chanh jumps the queue and Mochi hauls him to the back. */
    Queue,

    /** Letters tossed high like popcorn: everyone leaps to catch theirs, Bơ just holds his mouth open. */
    Popcorn,

    /** Bơ inhales like a vacuum cleaner; the others have to snatch their letters out of the draught. */
    Vacuum,
}

internal enum class ChokeStyle {
    /** Back slaps down the queue like dominoes, then Chanh's run-up into the belly. */
    Domino,

    /** Mochi hugs Bơ from behind and squeezes, Soda counts "Một! Hai! Ba!". */
    Heimlich,

    /** Mochi and Soda tip Bơ upside down and shake him; Chanh slaps his bottom. */
    Flip,

    /** Feathers all round until he laughs so hard he hiccups: "HÍC!". */
    Tickle,

    /** Soda tiptoes behind him and jumps out with a monster face: "BÙ!!". */
    Scare,
}

// ---------------------------------------------------------------------------------------------
// Seating
// ---------------------------------------------------------------------------------------------

/** Fills each diner's place for [style] (stage x in [x], depth in [z]). */
internal fun seatDiners(style: EatStyle, s: Stage, rnd: Random, x: FloatArray, z: FloatArray) {
    when (style) {
        EatStyle.Buffet -> {
            var at = s.center - (s.radius.sum() * 1.96f + s.r * 0.7f * 3f) / 2f
            for (k in intArrayOf(BO, MOCHI, SODA, CHANH)) {
                x[k] = at + s.radius[k] * 0.98f
                at += s.radius[k] * 1.96f + s.r * 0.7f
                z[k] = 0.15f
            }
        }

        EatStyle.Queue -> {
            // A file running back and to the right from the front of the queue.
            for ((i, k) in intArrayOf(MOCHI, CHANH, SODA, BO).withIndex()) {
                x[k] = s.center - s.r * 2.6f + i * s.r * 1.8f
                z[k] = 1.0f - i * 1.1f
            }
        }

        EatStyle.Popcorn -> {
            val spots = listOf(-4.2f to -0.6f, -1.4f to 0.8f, 1.4f to -1.5f, 4.0f to 0.4f).shuffled(rnd)
            for ((i, k) in intArrayOf(BO, MOCHI, SODA, CHANH).withIndex()) {
                x[k] = s.center + spots[i].first * s.r + (rnd.nextFloat() - 0.5f) * s.r * 0.6f
                z[k] = spots[i].second
            }
        }

        EatStyle.Vacuum -> {
            x[BO] = s.center - s.r * 0.4f
            z[BO] = 0.9f
            x[MOCHI] = s.center - s.r * 3.4f
            z[MOCHI] = -0.9f
            x[SODA] = s.center + s.r * 0.9f
            z[SODA] = -1.7f
            x[CHANH] = s.center + s.r * 3.4f
            z[CHANH] = -0.4f
        }
    }
}

/** Where a letter's flight bends on its way to [mouth] in each style. */
internal fun eatControl(style: EatStyle, start: Offset, mouth: Offset, boMouth: Offset, toBo: Boolean, height: Float): Offset = when (style) {
    EatStyle.Buffet, EatStyle.Queue -> Offset(lerp(start.x, mouth.x, 0.45f), start.y - height * 0.08f)
    EatStyle.Popcorn -> Offset(lerp(start.x, mouth.x, 0.5f), min(start.y, mouth.y) - height * 0.32f)
    EatStyle.Vacuum -> if (toBo) Offset(lerp(start.x, mouth.x, 0.3f), start.y + height * 0.06f) else boMouth
}

// ---------------------------------------------------------------------------------------------
// Eating: the style's own business on top of the shared biting
// ---------------------------------------------------------------------------------------------

/** Time to the next bite of slime [k] and since its last one, for anticipation. */
private fun Show.nextBite(k: Int, lt: Float): Float {
    var next = Float.MAX_VALUE
    for (b in bites) if (b.eater == k && b.arrive > lt && b.arrive < next) next = b.arrive
    return next - lt
}

internal fun Array<SlimePose>.eatStyled(sh: Show, s: Stage, lt: Float, now: Float) {
    val r = s.r
    when (sh.eatStyle) {
        EatStyle.Buffet -> {
            // Mochi: "Xếp hàng!" — hands on hips, then cuts in first anyway.
            this[MOCHI].apply {
                if (lt < 0.7f) {
                    akimbo = 1f
                    mood = Mood.Angry
                    mouthOpen = 0.3f + 0.2f * abs(sin(now * 16f))
                }
            }
        }

        EatStyle.Queue -> {
            // Chanh hops to the front; Mochi hauls him to the back of the line.
            val ch = this[CHANH]
            val mo = this[MOCHI]
            val front = sh.spotX[MOCHI] + r * 1.2f
            val frontZ = sh.spotZ[MOCHI] + 0.4f
            val back = sh.spotX[BO] + r * 1.8f
            val backZ = sh.spotZ[BO] - 1.0f
            when {
                lt < 0.15f -> {}
                lt < 0.45f -> {
                    val e = progress(lt, 0.15f, 0.3f)
                    ch.x = lerp(sh.spotX[CHANH], front, e)
                    ch.depth = lerp(sh.spotZ[CHANH], frontZ, e)
                    ch.lift += sin(PI_F * e) * r * 0.7f
                    ch.mood = Mood.Happy
                }

                lt < 0.7f -> {
                    ch.x = front
                    ch.depth = frontZ
                    ch.mood = Mood.Happy
                    ch.whistle = 1f
                    mo.akimbo = 1f
                    mo.mood = Mood.Angry
                    mo.lookX = 1f
                    mo.mouthOpen = 0.3f + 0.2f * abs(sin(now * 16f))
                }

                lt < 1.1f -> {
                    val e = easeInOutCubic(progress(lt, 0.7f, 0.4f))
                    ch.x = lerp(front, back, e)
                    ch.depth = lerp(frontZ, backZ, e)
                    ch.lift += sin(PI_F * e) * r * 0.5f
                    ch.rotation = 360f * e
                    ch.mood = Mood.Surprised
                    mo.armR = 100f
                    mo.reachR = 1.5f
                    mo.mood = Mood.Angry
                }

                else -> {
                    // Sulking at the back of the queue, then eating from there.
                    ch.x = back
                    ch.depth = backZ
                    if (lt < 1.8f) ch.lookY = -1f
                }
            }
        }

        EatStyle.Popcorn -> {
            for (k in 0 until SLIME_COUNT) this[k].apply {
                val toNext = sh.nextBite(k, lt)
                if (k == BO) {
                    // Bơ does not jump: he waits with his mouth wide open.
                    mouthOpen = max(mouthOpen, 0.75f)
                    lookY = -1f
                    lift = 0f
                } else if (toNext < 0.5f) {
                    val e = 1f - toNext / 0.5f
                    lift += sin(PI_F * e.coerceAtMost(1f) * 0.9f) * r * 1.1f
                    armL = 150f
                    armR = 150f
                    squash = 1.15f
                }
            }
        }

        EatStyle.Vacuum -> {
            val bo = this[BO]
            bo.mouthOpen = 0.95f
            bo.mood = Mood.Squeeze
            bo.squash = 1.05f + 0.05f * sin(now * 18f)
            bo.lookY = -0.8f
            for (k in intArrayOf(MOCHI, SODA, CHANH)) this[k].apply {
                // Leaning into the draught, hanging on, then snatching.
                val toward = sign(bo.x - x)
                rotation = toward * (10f + 4f * sin(now * 20f + k))
                if (sh.nextBite(k, lt) < 0.35f) {
                    armL = 130f
                    armR = 130f
                    reachL = 1.4f
                    reachR = 1.4f
                    mood = Mood.Angry
                } else {
                    sweat = 0.6f
                    mood = Mood.Worried
                }
            }
        }
    }
}

/** The overlay for the eating styles (the stretchy arm, the suction lines). */
internal fun DrawScope.drawEatStyled(sh: Show, poses: Array<SlimePose>, s: Stage, lt: Float, now: Float, say: (String) -> TextLayoutResult) {
    val r = s.r
    fun mouth(k: Int) = poses[k].mouth(s.radius[k], s.groundY, now)
    when (sh.eatStyle) {
        EatStyle.Buffet -> if (lt < 0.75f) drawSpeechBubble(mouth(MOCHI), say("Xếp hàng!"), progress(lt, 0f, 0.75f), s.radius[MOCHI])
        EatStyle.Queue -> {
            if (lt in 0.45f..1.1f) drawSpeechBubble(mouth(MOCHI), say("Xếp hàng!"), progress(lt, 0.45f, 0.65f), s.radius[MOCHI])
            if (lt in 0.7f..1.1f) {
                val from = poses[MOCHI].hand(1, s.radius[MOCHI], s.groundY, now)
                val to = poses[CHANH].hand(-1, s.radius[CHANH], s.groundY, now)
                drawLine(Cast[MOCHI].deep, from, to, r * 0.2f, StrokeCap.Round, alpha = 0.6f)
                drawLine(Cast[MOCHI].body, from, to, r * 0.15f, StrokeCap.Round)
                drawCircle(Cast[MOCHI].body, r * 0.13f, to)
            }
        }

        EatStyle.Popcorn -> {}
        EatStyle.Vacuum -> drawSuction(mouth(BO), now, s.radius[BO], 1f - progress(lt, sh.bites.last().arrive, 0.3f))
    }
}

// ---------------------------------------------------------------------------------------------
// Choking: the rescue attempts other than the domino
// ---------------------------------------------------------------------------------------------

/** Where each one ends up after the rescue (stage x, depth): where the scene change starts from. */
internal fun Show.afterChoke(s: Stage, k: Int): Pair<Float, Float> {
    val bx = spotX[BO]
    val bz = spotZ[BO]
    val br = s.radius[BO]
    return when (chokeStyle) {
        ChokeStyle.Domino -> when (k) {
            BO -> bx to bz
            CHANH -> rammedX(s) to bz
            else -> queueX(s, k) to bz
        }

        ChokeStyle.Heimlich -> when (k) {
            MOCHI -> bx to bz - 0.8f
            else -> spotX[k] to spotZ[k]
        }

        ChokeStyle.Flip -> when (k) {
            MOCHI -> bx - br - s.radius[MOCHI] * 0.8f to bz
            SODA -> bx + br + s.radius[SODA] * 0.8f to bz
            CHANH -> bx + br * 0.4f to bz + 0.9f
            else -> bx to bz
        }

        ChokeStyle.Tickle -> when (k) {
            MOCHI -> bx - br - s.radius[MOCHI] * 0.7f to bz + 0.2f
            SODA -> bx + br + s.radius[SODA] * 0.7f to bz + 0.2f
            CHANH -> bx + br * 0.2f to bz + 1.1f
            else -> bx to bz
        }

        ChokeStyle.Scare -> when (k) {
            SODA -> bx + br * 0.3f to bz - 0.9f
            else -> spotX[k] to spotZ[k]
        }
    }
}

/** Haptic taps for a rescue style (the release at [RAM_AT] thumps for all of them). */
internal fun chokeTaps(style: ChokeStyle): FloatArray = when (style) {
    ChokeStyle.Domino -> floatArrayOf(0.95f, 1.07f, 1.19f)
    ChokeStyle.Heimlich -> floatArrayOf(0.9f, 1.3f, 1.7f)
    ChokeStyle.Flip -> floatArrayOf(0.85f, 1.2f, 1.5f)
    ChokeStyle.Tickle -> floatArrayOf(0.8f, 1.1f, 1.4f, 1.7f)
    ChokeStyle.Scare -> floatArrayOf(1.55f)
}

/** Moves slime [k] from its place at the table to [x], [z] between [start] and [start] + [dur]. */
private fun SlimePose.walkTo(sh: Show, k: Int, x: Float, z: Float, lt: Float, start: Float, dur: Float, s: Stage) {
    travel(k, sh.spotX[k], sh.spotZ[k], x, z, progress(lt, start, dur), s)
    if (lt >= start + dur) {
        this.x = x
        depth = z
    }
}

/** Poses for every style but the domino (Bơ's own choking is shared and done before this). */
internal fun Array<SlimePose>.chokeStyled(sh: Show, s: Stage, lt: Float, now: Float) {
    val r = s.r
    val bo = this[BO]
    when (sh.chokeStyle) {
        ChokeStyle.Domino -> {}

        ChokeStyle.Heimlich -> {
            val mo = this[MOCHI]
            val (hx, hz) = sh.afterChoke(s, MOCHI)
            mo.walkTo(sh, MOCHI, hx, hz, lt, 0.3f, 0.4f, s)
            if (lt > 0.7f) {
                mo.armL = 95f
                mo.armR = 95f
                mo.reachL = 1.5f
                mo.reachR = 1.5f
                mo.mood = Mood.Angry
                mo.anger = 1f
                for (at in chokeTaps(ChokeStyle.Heimlich) + RAM_AT) {
                    val e = progress(lt, at - 0.1f, 0.25f)
                    if (e > 0f && e < 1f) {
                        bo.squash = 1f - (if (at == RAM_AT) 0.3f else 0.18f) * sin(PI_F * e)
                        mo.squash = 1f - 0.12f * sin(PI_F * e)
                    }
                }
            }
            this[SODA].apply {
                mood = Mood.Happy
                armR = if ((lt * 2.5f).toInt() % 2 == 0) 150f else 90f
            }
            this[CHANH].apply {
                lift += abs(sin(lt * 9f)) * r * 0.25f
                mood = Mood.Happy
                armL = 150f
                armR = 150f
            }
        }

        ChokeStyle.Flip -> {
            val mo = this[MOCHI]
            val so = this[SODA]
            val ch = this[CHANH]
            val (mx, mz) = sh.afterChoke(s, MOCHI)
            val (sx, sz) = sh.afterChoke(s, SODA)
            val (cx, cz) = sh.afterChoke(s, CHANH)
            mo.walkTo(sh, MOCHI, mx, mz, lt, 0.25f, 0.35f, s)
            so.walkTo(sh, SODA, sx, sz, lt, 0.25f, 0.35f, s)
            ch.walkTo(sh, CHANH, cx, cz, lt, 0.4f, 0.4f, s)
            val up = easeOutBack(progress(lt, 0.65f, 0.3f))
            val down = easeInCubic(progress(lt, RAM_AT, 0.3f))
            if (lt > 0.65f) {
                bo.lift = r * 1.2f * up * (1f - down)
                bo.rotation = lerp(0f, 180f, up) + 180f * down
                if (lt in 0.95f..RAM_AT) {
                    bo.shakeX = sin(now * 45f) * r * 0.12f
                    bo.squash = 1f + 0.15f * sin(now * 30f)
                }
                for (p in listOf(mo, so)) {
                    p.armL = 165f
                    p.armR = 165f
                    p.reachL = 1.4f
                    p.reachR = 1.4f
                    p.mood = Mood.Squeeze
                    p.lift = bo.lift * 0.5f
                    p.shakeX = bo.shakeX * 0.6f
                }
            }
            if (lt in 1.35f..1.65f) {
                ch.lift += sin(PI_F * progress(lt, 1.35f, 0.3f)) * r * 1.4f
                ch.armR = 170f
                ch.reachR = 1.4f
                ch.mood = Mood.Happy
            }
        }

        ChokeStyle.Tickle -> {
            for (k in intArrayOf(MOCHI, SODA, CHANH)) this[k].apply {
                val (tx, tz) = sh.afterChoke(s, k)
                walkTo(sh, k, tx, tz, lt, 0.25f + k * 0.05f, 0.35f, s)
                if (lt > 0.65f) {
                    val wiggle = sin(now * 26f + k * 2f)
                    if (x < bo.x) {
                        armR = 100f + 25f * wiggle
                        reachR = 1.4f
                    } else {
                        armL = 100f + 25f * wiggle
                        reachL = 1.4f
                    }
                    mood = Mood.Happy
                }
            }
            if (lt > 0.65f && lt < RAM_AT) {
                val giggle = progress(lt, 0.65f, RAM_AT - 0.65f)
                bo.mood = Mood.Happy
                bo.jiggle = 0.5f + 0.5f * giggle
                bo.squash = 1f + 0.08f * giggle * sin(now * 30f)
                bo.rotation = sin(now * 14f) * 6f * giggle
                bo.choke = 1f - giggle * 0.5f
            }
        }

        ChokeStyle.Scare -> {
            val so = this[SODA]
            val (sx, sz) = sh.afterChoke(s, SODA)
            // Tiptoeing round behind him.
            if (lt < 1.45f) {
                so.walkTo(sh, SODA, sx, sz, lt, 0.3f, 1.1f, s)
                so.squash = 0.82f
                so.mood = Mood.Happy
                so.lookX = sign(bo.x - so.x)
                so.armL = 60f
                so.armR = 60f
            } else {
                so.x = sx
                so.depth = sz
                val jump = progress(lt, 1.45f, 0.35f)
                so.lift = sin(PI_F * jump) * r * 0.9f
                so.armL = 170f
                so.armR = 170f
                so.mouthOpen = 1f
                so.mood = Mood.Angry
                so.anger = 1f
                so.squash = 1.15f
            }
            // The others hold their breath.
            for (k in intArrayOf(MOCHI, CHANH)) this[k].apply {
                lookX = sign(bo.x - x)
                if (lt > 0.6f && lt < 1.55f) {
                    armL = 150f
                    armR = 150f
                    reachL = 0.7f
                    reachR = 0.7f
                    mood = Mood.Worried
                } else if (lt >= 1.55f) {
                    mood = Mood.Surprised
                }
            }
            if (lt > 1.55f && lt < RAM_AT + 0.35f) {
                val fright = progress(lt, 1.55f, 0.6f)
                bo.lift = sin(PI_F * fright) * r * 1.5f
                bo.mood = Mood.Surprised
                bo.armL = 170f
                bo.armR = 170f
                bo.squash = 1.2f
            }
        }
    }
}

/** The overlay for the rescue styles: counts, feathers, shakes, the scare. */
internal fun DrawScope.drawChokeStyled(sh: Show, poses: Array<SlimePose>, s: Stage, lt: Float, now: Float, say: (String) -> TextLayoutResult, words: List<TextLayoutResult>) {
    val r = s.r
    fun head(k: Int) = poses[k].headTop(s.radius[k], s.groundY, now)
    fun mouth(k: Int) = poses[k].mouth(s.radius[k], s.groundY, now)
    fun hand(k: Int, side: Int) = poses[k].hand(side, s.radius[k], s.groundY, now)
    val bo = poses[BO]
    when (sh.chokeStyle) {
        ChokeStyle.Domino -> {}

        ChokeStyle.Heimlich -> {
            for ((i, at) in chokeTaps(ChokeStyle.Heimlich).withIndex()) {
                if (lt in at..at + 0.35f) {
                    drawSpeechBubble(mouth(SODA), say(listOf("Một!", "Hai!", "Ba!")[i]), progress(lt, at, 0.35f), s.radius[SODA])
                    drawImpact(bo.base(s.groundY) + Offset(0f, -s.radius[BO] * bo.viewScale), progress(lt, at, 0.3f), r * 0.5f)
                }
            }
        }

        ChokeStyle.Flip -> {
            if (lt in 0.95f..RAM_AT) repeat(3) { i ->
                val c = head(BO) + Offset(0f, s.radius[BO] * 0.6f)
                val side = if (i % 2 == 0) -1f else 1f
                drawLine(Color.White, c + Offset(side * r * (1.1f + 0.2f * i), -r * 0.3f * i), c + Offset(side * r * (1.5f + 0.2f * i), -r * 0.3f * i), r * 0.05f, StrokeCap.Round, alpha = 0.7f)
            }
            if (lt in 1.5f..1.8f) drawImpact(bo.base(s.groundY), progress(lt, 1.5f, 0.3f), r * 0.6f)
        }

        ChokeStyle.Tickle -> {
            if (lt > 0.6f && lt < RAM_AT + 0.1f) for (k in intArrayOf(MOCHI, SODA, CHANH)) {
                val side = if (poses[k].x < bo.x) 1 else -1
                drawFeather(hand(k, side), r * 0.45f * poses[k].viewScale, sin(now * 26f + k) * 35f + (if (side > 0) -40f else 40f))
            }
            for ((i, at) in chokeTaps(ChokeStyle.Tickle).withIndex()) {
                val text = if (i < 2) "hihi" else "HAHA"
                if (lt in at..at + 0.45f) drawSpeechBubble(mouth(BO), say(text), progress(lt, at, 0.45f), s.radius[BO])
            }
            if (lt in RAM_AT..RAM_AT + 0.5f) drawComicWord(words.getOrElse(3) { words[0] }, head(BO) + Offset(r, -r * 0.3f), progress(lt, RAM_AT, 0.5f), 8f, 4f * s.px)
        }

        ChokeStyle.Scare -> {
            if (lt in 1.5f..2.1f) drawComicWord(words.getOrElse(4) { words[0] }, head(SODA) + Offset(0f, -r * 0.5f), progress(lt, 1.5f, 0.6f), -8f, 4f * s.px)
            if (lt in 1.55f..2.0f) repeat(6) { i ->
                val a = i / 6f * TAU
                val c = head(BO)
                val e = progress(lt, 1.55f, 0.45f)
                drawLine(Color.White, c + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (r * (0.5f + 0.3f * e)), c + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (r * (0.8f + 0.5f * e)), r * 0.05f, StrokeCap.Round, alpha = 1f - e)
            }
        }
    }
}

/** A tickling feather. */
internal fun DrawScope.drawFeather(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        val vane = Path().apply {
            moveTo(c.x, c.y)
            quadraticTo(c.x - s * 0.35f, c.y - s * 0.6f, c.x, c.y - s * 1.2f)
            quadraticTo(c.x + s * 0.35f, c.y - s * 0.6f, c.x, c.y)
            close()
        }
        drawPath(vane, Color(0xFFFFF3F8))
        drawPath(vane, Color(0xFFFFB3C6), style = Stroke(s * 0.04f))
        drawLine(Color(0xFFE5989B), c + Offset(0f, s * 0.2f), c + Offset(0f, -s * 1.15f), s * 0.04f, StrokeCap.Round)
    }
}
