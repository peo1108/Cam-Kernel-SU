package cam.su.kernel.ui.intro

import cam.su.kernel.ui.screen.home.arena.hash
import kotlin.math.hypot

/** Lightning bolts by midpoint displacement, written into a caller's array (no allocation per frame). */
internal object Bolt {
    const val DEPTH = 5
    const val POINTS = (1 shl DEPTH) + 1

    /**
     * Splits the segment from ([ax], [ay]) to ([bx], [by]) [depth] times, pushing each new midpoint
     * sideways by up to [rough] x length, half as far at each level, so no point strays more than
     * 2 x [rough] x length from the segment. Writes 2^[depth] + 1 points as x, y pairs into [out]
     * (ends exactly on the two endpoints) and returns how many. The same [seed] gives the same bolt.
     */
    fun build(ax: Float, ay: Float, bx: Float, by: Float, seed: Int, depth: Int = DEPTH, rough: Float, out: FloatArray): Int {
        val n = 1 shl depth
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        val nx = if (len > 0f) -dy / len else 0f
        val ny = if (len > 0f) dx / len else 0f
        // Sideways offsets first, kept in the x slots.
        out[0] = 0f
        out[2 * n] = 0f
        var step = n
        var amp = rough * len
        while (step > 1) {
            val half = step / 2
            var i = half
            while (i < n) {
                val jolt = (hash(seed * 97 + i * 13 + step) * 2f - 1f) * amp
                out[2 * i] = (out[2 * (i - half)] + out[2 * (i + half)]) / 2f + jolt
                i += step
            }
            amp *= 0.5f
            step = half
        }
        for (i in 0..n) {
            val f = i.toFloat() / n
            val o = out[2 * i]
            out[2 * i] = ax + dx * f + nx * o
            out[2 * i + 1] = ay + dy * f + ny * o
        }
        return n + 1
    }
}
