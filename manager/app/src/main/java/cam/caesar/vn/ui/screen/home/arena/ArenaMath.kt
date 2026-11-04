package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

internal const val TAU = (2 * PI).toFloat()

/** Stable pseudo-random value in [0, 1) for [i]: particles are pure functions of (index, time). */
internal fun hash(i: Int): Float {
    val s = sin(i * 12.9898f + 78.233f) * 43758.547f
    return s - floor(s)
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/** Wraps [v] into [0, m). */
internal fun wrap(v: Float, m: Float): Float = ((v % m) + m) % m

internal fun quadBezier(p0: Offset, c: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * p0.x + 2 * u * t * c.x + t * t * p2.x,
        u * u * p0.y + 2 * u * t * c.y + t * t * p2.y,
    )
}

internal fun progress(p: Float, start: Float, duration: Float) = ((p - start) / duration).coerceIn(0f, 1f)

internal fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)

internal fun easeInCubic(t: Float) = t * t * t

internal fun easeInOutCubic(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f

internal fun easeOutBack(t: Float, s: Float = 1.70158f): Float {
    val x = t - 1f
    return 1f + (s + 1f) * x * x * x + s * x * x
}

internal fun easeOutBounce(t: Float): Float {
    val n = 7.5625f
    val d = 2.75f
    return when {
        t < 1f / d -> n * t * t
        t < 2f / d -> (t - 1.5f / d).let { n * it * it + 0.75f }
        t < 2.5f / d -> (t - 2.25f / d).let { n * it * it + 0.9375f }
        else -> (t - 2.625f / d).let { n * it * it + 0.984375f }
    }
}
