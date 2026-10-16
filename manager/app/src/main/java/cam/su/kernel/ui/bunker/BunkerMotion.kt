package cam.su.kernel.ui.bunker

import cam.su.kernel.ui.screen.home.arena.easeInOutCubic
import cam.su.kernel.ui.screen.home.arena.easeOutBack
import cam.su.kernel.ui.screen.home.arena.easeOutCubic
import cam.su.kernel.ui.screen.home.arena.hash
import cam.su.kernel.ui.screen.home.arena.progress
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The bunker's moves as pure functions of time (seconds on the shared clock), so the JVM tests
 * can hold them to their promises and the drawing keeps no state for them.
 */
internal object BunkerMotion {
    /** The lightning's surges: one every [SURGE_CELL] seconds or so, of different strength. */
    const val SURGE_CELL = 6f
    private const val SURGE_WIDTH = 0.6f
    const val CALM = 0.45f

    /** How hard the frame's lightning is running at [t]: [CALM] most of the time, up to 1 in a surge. */
    fun surge(t: Float): Float {
        val cell = floor(t / SURGE_CELL).toInt()
        var level = 0f
        for (c in cell - 1..cell + 1) {
            val center = c * SURGE_CELL + 1f + 4f * hash(c * 31 + 7)
            val height = 0.5f + 0.5f * hash(c * 17 + 3)
            val x = (t - center) / SURGE_WIDTH
            level = maxOf(level, height * exp(-x * x))
        }
        return (CALM + (1f - CALM) * level).coerceIn(CALM, 1f)
    }

    /** Light sweeping across the metal: one pass in every [SWEEP_CELL] s, [SWEEP_LEN] long. */
    const val SWEEP_CELL = 6.5f
    const val SWEEP_LEN = 1.3f

    /** How far (0..1) the sweep has crossed the triangle at [t], or -1 between sweeps. */
    fun sweepAt(t: Float): Float {
        val cell = floor(t / SWEEP_CELL).toInt()
        val start = cell * SWEEP_CELL + (SWEEP_CELL - SWEEP_LEN) * hash(cell * 23 + 9)
        val f = (t - start) / SWEEP_LEN
        return if (f in 0f..1f) f else -1f
    }

    /** The random events: one every 20 to 40 s. */
    fun eventGap(k: Int): Float = 20f + 20f * hash(k * 13 + 5)

    /** Event [k] is a shot nailing the frame (0) or an outer plate popping out and back (1). */
    fun eventKind(k: Int): Int = if (hash(k * 29 + 11) < 0.5f) SHOT else POP

    const val SHOT = 0
    const val POP = 1
    const val SHOT_LEN = 3.2f
    const val POP_LEN = 0.75f

    /** How far an outer plate is out (0..1) [age] seconds into a pop: up, a beat, slammed back. */
    fun popOut(age: Float): Float = when {
        age < 0f || age >= POP_LEN -> 0f
        age < 0.25f -> easeOutCubic(age / 0.25f)
        age < 0.5f -> 1f
        else -> 1f - progress(age, 0.5f, 0.12f).let { it * it }
    }

    /** The triangle spins one full turn per tab, the way the tabs went, and stops upright. */
    const val TURN_DEGREES = 360f
    const val TURN_LEN = 0.7f

    /** The triangle's angle at [now] after turning from [fromSteps] to [toSteps] tabs at [turnAt]. */
    fun turnAngle(now: Float, turnAt: Float, fromSteps: Int, toSteps: Int): Float {
        val e = easeOutBack(progress(now - turnAt, 0f, TURN_LEN), 1.3f)
        return (fromSteps + (toSteps - fromSteps) * e) * TURN_DEGREES
    }

    /** The rangefinder: a new target every [RANGE_CELL] s, counted to over its first second. */
    const val RANGE_CELL = 5f

    fun range(t: Float): Int {
        val cell = floor(t / RANGE_CELL).toInt()
        val from = rangeTarget(cell - 1)
        val to = rangeTarget(cell)
        val f = easeInOutCubic(progress(t - cell * RANGE_CELL, 0f, 1.2f))
        return ((from + (to - from) * f) / 10f).roundToInt() * 10
    }

    private fun rangeTarget(cell: Int): Float = 100f + 800f * hash(cell * 7 + 19)

    /** Which HUD piece (of [pieces]) is flickering at [t], or -1: one, briefly, every 8 s or so. */
    fun hudFlicker(t: Float, pieces: Int): Int {
        val cell = floor(t / 8f).toInt()
        val start = cell * 8f + 6f * hash(cell * 5 + 2)
        if (t < start || t > start + 0.35f) return -1
        if (hash(floor(t * 40f).toInt()) < 0.5f) return -1
        return (hash(cell * 11 + 1) * pieces).toInt().coerceIn(0, pieces - 1)
    }
}

/**
 * Parallax from the device's tilt: follows the gravity direction, minus a slow average of it, so
 * a tilt slides the layers and holding still lets them drift back to the middle.
 */
internal class TiltFollower {
    var x = 0f
        private set
    var y = 0f
        private set
    private var restX = Float.NaN
    private var restY = Float.NaN

    /** Feeds one reading of gravity across the screen ([gx], [gy], unit length when upright), [dt] seconds after the last. */
    fun update(gx: Float, gy: Float, dt: Float) {
        if (restX.isNaN()) {
            restX = gx
            restY = gy
        }
        val k = 1f - exp(-dt / REST_SECONDS)
        restX += (gx - restX) * k
        restY += (gy - restY) * k
        x = (gx - restX).coerceIn(-MAX, MAX) / MAX
        y = (gy - restY).coerceIn(-MAX, MAX) / MAX
    }

    private companion object {
        const val REST_SECONDS = 2f

        /** About 17°: a tilt this big reaches the full shift. */
        const val MAX = 0.3f
    }
}
