package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Beats of one round: the scene's own intro, then the shared signature sequence. */
internal enum class Phase { Intro, Brawl, Scatter, Recover, Gather, Eat, Choke, Vomit, Settle, Wander }

/** How a slime comes down when the brawl cloud bursts. */
internal enum class Landing { Plop, Glass, Stack, Upside }

/** Each cast member's own way of getting about. */
internal enum class Gait { Hop, Slide, Glide, Spring }

/** Mochi hops evenly, Bơ inches along like a caterpillar, Soda glides in a twirl, Chanh boings. */
private val Gaits = arrayOf(Gait.Hop, Gait.Slide, Gait.Glide, Gait.Spring)

internal const val FALL_SECONDS = 0.3f
internal const val IDLE_SECONDS = 6f
internal const val FIRST_SHOW_DELAY = 2.2f
/** Choke beats (seconds into the phase). */
internal val DOMINO = floatArrayOf(0.95f, 1.07f, 1.19f)
internal const val RAM_AT = 1.9f
/** Depth of a slime splatted on the inside of the glass. */
internal const val GLASS_DEPTH = Box.GLASS - 0.55f

/** One mouthful in the buffet: who eats which letters, and when they leave the word. */
internal class Bite(val eater: Int, val glyphs: IntArray, val launch: Float) {
    val arrive get() = launch + FALL_SECONDS
}

/** Haptic kinds, in the order [Vomit.buzz] numbers them. */
private enum class Buzz { Tap, Thump, Confirm }

/** One round, fixed when it starts (including where everyone lands when the brawl bursts). */
internal class Show(
    val start: Float,
    val from: Int,
    val next: Int,
    val intro: SceneIntro,
    run: GlyphRun,
    s: Stage,
    seed: Int,
    /** How Bơ throws the next country up this round. */
    val style: VomitStyle,
    /** How they line up for the letters, and how they get Bơ to cough up the one he chokes on. */
    val eatStyle: EatStyle,
    val chokeStyle: ChokeStyle,
) {
    val bites: List<Bite>
    /** Letters that stick in Bơ's throat (his last, too-big mouthful). */
    val chokeOn: IntArray
    val durations: FloatArray
    val starts: FloatArray
    val total: Float
    /** When each slime's new costume started falling (-1 until the carpet reaches it). */
    val dropAt = FloatArray(SLIME_COUNT) { -1f }

    val landing = Array(SLIME_COUNT) { Landing.Plop }
    val landX = FloatArray(SLIME_COUNT)
    val landZ = FloatArray(SLIME_COUNT)
    /** Who a [Landing.Stack] slime landed on. */
    var stackBase = -1
    /** Where each one is once it has picked itself up. */
    val recX = FloatArray(SLIME_COUNT)
    val recZ = FloatArray(SLIME_COUNT)
    /** Places at the "buffet" under the word, Bơ leftmost so the back-slap queue forms to his right. */
    val spotX = FloatArray(SLIME_COUNT)
    val spotZ = FloatArray(SLIME_COUNT) { 0.15f }

    private val events: List<Pair<Float, Buzz>>
    private var fired = 0

    init {
        val list = run.eatList
        val chunk = min(3, max(1, list.size / 4))
        chokeOn = list.copyOfRange(list.size - chunk, list.size)
        val rest = list.copyOfRange(0, list.size - chunk)
        val order = intArrayOf(MOCHI, CHANH, SODA, BO)
        val out = ArrayList<Bite>()
        var i = 0
        var turn = 0
        var time = 0.75f
        while (i < rest.size) {
            val eater = order[turn % order.size]
            val take = if (eater == BO) min(2, rest.size - i) else 1
            out += Bite(eater, rest.copyOfRange(i, i + take), time)
            time += 0.28f
            i += take
            turn++
        }
        out += Bite(BO, chokeOn, time + 0.15f)
        bites = out
        durations = floatArrayOf(intro.seconds, 2.4f, 1.0f, 3.0f, 1.9f, out.last().arrive + 0.4f, 2.4f, style.seconds, 1.5f, 2.6f)
        starts = FloatArray(durations.size)
        var acc = 0f
        for (k in durations.indices) {
            starts[k] = acc
            acc += durations[k]
        }
        total = acc

        val rnd = Random(seed.toLong())
        planLandings(s, rnd)
        seatDiners(eatStyle, s, rnd, spotX, spotZ)

        val ev = ArrayList<Pair<Float, Buzz>>()
        intro.taps.forEach { ev += it to Buzz.Tap }
        intro.thumps.forEach { ev += it to Buzz.Thump }
        val brawl = starts[Phase.Brawl.ordinal]
        for (k in 0 until 10) ev += brawl + k * 0.22f to Buzz.Tap
        ev += starts[Phase.Scatter.ordinal] to Buzz.Thump
        ev += starts[Phase.Recover.ordinal] to Buzz.Thump
        bites.forEach { ev += starts[Phase.Eat.ordinal] + it.arrive to Buzz.Tap }
        chokeTaps(chokeStyle).forEach { ev += starts[Phase.Choke.ordinal] + it to Buzz.Tap }
        ev += starts[Phase.Choke.ordinal] + RAM_AT to Buzz.Thump
        for ((at, kind) in Vomit.buzz(style)) ev += starts[Phase.Vomit.ordinal] + at to Buzz.entries[kind]
        events = ev.sortedBy { it.first }
    }

    /** Random landing spots and stunts, kept apart so nobody lands inside somebody else. */
    private fun planLandings(s: Stage, rnd: Random) {
        val ids = (0 until SLIME_COUNT).shuffled(rnd)
        if (rnd.nextFloat() < 0.85f) landing[ids[0]] = Landing.Glass
        val base = if (landing[BO] != Landing.Glass) BO else ids.first { it != BO && landing[it] != Landing.Glass }
        val stacker = listOf(CHANH, MOCHI, SODA).firstOrNull { it != base && landing[it] == Landing.Plop && Cast[it].size < Cast[base].size }
        if (stacker != null && rnd.nextFloat() < 0.7f) {
            landing[stacker] = Landing.Stack
            stackBase = base
        }
        ids.firstOrNull { landing[it] == Landing.Plop && it != base && rnd.nextFloat() < 0.65f }?.let { landing[it] = Landing.Upside }

        val placed = ArrayList<Int>()
        fun free(k: Int, x: Float, z: Float) = placed.all { j ->
            abs(x - landX[j]) > (s.radius[k] + s.radius[j]) * 1.15f || abs(z - landZ[j]) > 1.4f
        }
        val order = (0 until SLIME_COUNT).sortedBy { if (landing[it] == Landing.Stack) 1 else 0 }
        for (k in order) {
            when (landing[k]) {
                Landing.Stack -> {
                    landX[k] = landX[stackBase]
                    landZ[k] = landZ[stackBase] + 0.02f
                }

                Landing.Glass -> {
                    landX[k] = s.w * (0.18f + 0.64f * rnd.nextFloat())
                    landZ[k] = GLASS_DEPTH
                }

                else -> {
                    var tries = 0
                    do {
                        landX[k] = s.w * (0.1f + 0.8f * rnd.nextFloat())
                        landZ[k] = -3.2f + 4.0f * rnd.nextFloat()
                        tries++
                    } while (!free(k, landX[k], landZ[k]) && tries < 24)
                }
            }
            placed += k
        }
        for (k in 0 until SLIME_COUNT) {
            recX[k] = landX[k]
            recZ[k] = landZ[k]
            when (landing[k]) {
                Landing.Glass -> recZ[k] = Box.GLASS - 1.6f
                Landing.Stack -> {
                    val side = if (landX[stackBase] < s.center) 1f else -1f
                    recX[k] = landX[stackBase] + side * (s.radius[stackBase] + s.radius[k]) * 1.05f
                }

                else -> {}
            }
        }
    }

    fun phaseAt(elapsed: Float): Phase? {
        if (elapsed >= total) return null
        for (k in durations.indices.reversed()) if (elapsed >= starts[k]) return Phase.entries[k]
        return Phase.Intro
    }

    fun progressOf(phase: Phase, elapsed: Float) = ((elapsed - starts[phase.ordinal]) / durations[phase.ordinal]).coerceIn(0f, 1f)
    fun local(phase: Phase, elapsed: Float) = elapsed - starts[phase.ordinal]

    fun buzz(elapsed: Float, haptic: HapticFeedback) {
        while (fired < events.size && events[fired].first <= elapsed) {
            haptic.performHapticFeedback(
                when (events[fired].second) {
                    Buzz.Tap -> HapticFeedbackType.SegmentTick
                    Buzz.Thump -> HapticFeedbackType.LongPress
                    Buzz.Confirm -> HapticFeedbackType.Confirm
                }
            )
            fired++
        }
    }

    /** Where the rolling brawl cloud is, [lt] seconds into the brawl. */
    fun cloud(s: Stage, lt: Float) = Offset(
        s.center + sin(lt * 2.1f) * s.w * 0.14f,
        s.groundY - s.r * 1.15f - abs(sin(lt * 7f)) * s.r * 0.12f,
    )

    /** Where Bơ's teammates queue up behind him (to his right) to slap his back. */
    fun queueX(s: Stage, k: Int): Float {
        var x = spotX[BO]
        var prev = BO
        for (q in intArrayOf(MOCHI, SODA, CHANH)) {
            x += (s.radius[prev] + s.radius[q]) * 0.92f
            if (q == k) return x
            prev = q
        }
        return x
    }

    /** Where Chanh ends up after bouncing off Bơ's belly. */
    fun rammedX(s: Stage) = spotX[BO] + (s.radius[BO] + s.radius[CHANH]) * 0.9f + s.r * 2f
}

/**
 * Moves a slime from (fromX, fromZ) to (toX, toZ) over [e] (0..1) in its own [Gait]: hops, an
 * inchworm slide, a twirling glide or springy bounces, with squash, stretch and facing to match.
 */
internal fun SlimePose.travel(k: Int, fromX: Float, fromZ: Float, toX: Float, toZ: Float, e: Float, s: Stage) {
    val t = e.coerceIn(0f, 1f)
    val dist = hypot(toX - fromX, (toZ - fromZ) * s.r)
    val dir = sign(toX - fromX + 0.001f)
    fun place(f: Float) {
        x = lerp(fromX, toX, f)
        depth = lerp(fromZ, toZ, f)
    }
    if (t <= 0f) {
        place(0f)
        return
    }
    if (t >= 1f) {
        place(1f)
        return
    }
    lookX = dir
    when (Gaits[k]) {
        Gait.Hop -> {
            val hops = max(1, (dist / (s.r * 1.4f)).roundToInt())
            val ph = t * hops
            val n = floor(ph)
            val local = ph - n
            place((n + easeInOutCubic(local)) / hops)
            lift = sin(PI_F * local) * s.r * 0.45f
            squash = 1f + 0.18f * sin(PI_F * local) - if (local < 0.1f || local > 0.9f) 0.14f else 0f
        }

        Gait.Slide -> {
            val waves = max(2, (dist / (s.r * 0.9f)).roundToInt())
            place(easeInOutCubic(t))
            squash = 1f + 0.13f * sin(t * waves * 2f * PI_F)
            rotation = dir * 5f
            jiggle = 0.3f
            mood = Mood.Normal
        }

        Gait.Glide -> {
            place(easeInOutCubic(t))
            lift = sin(PI_F * t) * s.r * 0.35f
            rotation = dir * 360f * easeInOutCubic(t)
            sparkle = sin(PI_F * t)
            armL = 100f
            armR = 100f
            mood = Mood.Happy
        }

        Gait.Spring -> {
            val hops = max(1, (dist / (s.r * 2.2f)).roundToInt())
            val ph = t * hops
            val n = floor(ph)
            val local = ph - n
            place((n + local) / hops)
            lift = sin(PI_F * local) * s.r * 1.0f
            squash = when {
                local < 0.12f -> 0.72f + local / 0.12f * 0.5f
                local > 0.88f -> 1.22f - (local - 0.88f) / 0.12f * 0.5f
                else -> 1.22f
            }
            mood = Mood.Happy
        }
    }
}
