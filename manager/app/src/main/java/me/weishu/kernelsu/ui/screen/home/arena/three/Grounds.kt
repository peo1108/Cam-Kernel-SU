package me.weishu.kernelsu.ui.screen.home.arena.three

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** What the diorama floor is made of in each country. */
internal enum class Ground(val roughness: Float) {
    Water(0.04f),
    Cobble(0.55f),
    Gravel(0.9f),
    Wood(0.3f),
    Snow(0.8f),
    Grid(0.18f),
    Deck(0.4f),
    Rug(0.95f),
}

private const val N = 256
private const val TAU = (2 * PI).toFloat()

private fun hash(i: Int): Float {
    val s = sin(i * 12.9898f + 78.233f) * 43758.547f
    return s - floor(s)
}

/** Smooth tileable value noise on an [n]-cell lattice. */
private fun noise(x: Float, y: Float, cells: Int, seed: Int): Float {
    val fx = x / N * cells
    val fy = y / N * cells
    val ix = floor(fx).toInt()
    val iy = floor(fy).toInt()
    val tx = fx - ix
    val ty = fy - iy
    fun h(a: Int, b: Int) = hash(((a % cells + cells) % cells) * 131 + ((b % cells + cells) % cells) * 977 + seed)
    val sx = tx * tx * (3 - 2 * tx)
    val sy = ty * ty * (3 - 2 * ty)
    val top = h(ix, iy) + (h(ix + 1, iy) - h(ix, iy)) * sx
    val bottom = h(ix, iy + 1) + (h(ix + 1, iy + 1) - h(ix, iy + 1)) * sx
    return top + (bottom - top) * sy
}

/** Tileable 256² floor texture for [style], built around the country's ground color [base]. */
internal fun groundBitmap(style: Ground, base: Color): Bitmap {
    val px = IntArray(N * N)
    for (y in 0 until N) for (x in 0 until N) {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val c: Color = when (style) {
            Ground.Water -> {
                val ripple = sin(fx / N * TAU * 3 + sin(fy / N * TAU * 2) * 1.5f) * sin(fy / N * TAU * 4 + sin(fx / N * TAU) * 2f)
                val caustic = (1f - abs(ripple)).let { it * it * it }
                // Ha Long's emerald water, only faintly tinted by the sky.
                lerp(lerp(Color(0xFF169C94), base, 0.15f), Color(0xFFDFFFF8), caustic * 0.3f + noise(fx, fy, 8, 3) * 0.08f)
            }

            Ground.Cobble -> {
                val row = (fy / 32f).toInt()
                val sx = (fx + if (row % 2 == 0) 0f else 16f) % 32f
                val sy = fy % 32f
                val dx = (sx - 16f) / 15f
                val dy = (sy - 16f) / 15f
                val r = sqrt(dx * dx + dy * dy)
                val stone = (1f - r).coerceIn(0f, 1f)
                val tone = 0.75f + 0.25f * hash(row * 17 + ((fx + if (row % 2 == 0) 0f else 16f) / 32f).toInt())
                if (r > 0.92f) lerp(base, Color.Black, 0.45f) else lerp(lerp(base, Color.Gray, 0.35f), Color.White, stone * 0.25f * tone + noise(fx, fy, 32, 5) * 0.1f)
            }

            Ground.Gravel -> {
                val rake = (sin(fy / N * TAU * 10 + sin(fx / N * TAU * 2) * 0.8f) + 1f) / 2f
                val grit = noise(fx, fy, 64, 9)
                lerp(lerp(Color(0xFFE9E4D8), base, 0.12f), Color(0xFF9C9488), rake * 0.18f + grit * 0.22f)
            }

            Ground.Wood -> {
                val plank = (fx / 32f).toInt()
                val shade = 0.86f + 0.14f * hash(plank * 7 + ((fy + plank * 97) / 160f).toInt())
                val grain = 0.92f + 0.08f * sin((fx % 32f) * 0.9f + sin(fy * 0.045f + plank) * 3f)
                val seam = if (fx % 32f < 1f || (fy + plank * 97) % 160f < 1f) 0.6f else 1f
                lerp(Color(0xFFD9A066), base, 0.12f).let { Color(it.red * shade * grain * seam, it.green * shade * grain * seam, it.blue * shade * grain * seam) }
            }

            Ground.Snow -> {
                val soft = noise(fx, fy, 16, 11) * 0.06f
                val sparkle = if (hash(x * 7919 + y * 104729) > 0.995f) 0.3f else 0f
                lerp(Color(0xFFF1F6FF), Color(0xFFB9CCF0), soft).let { lerp(it, Color.White, sparkle) }
            }

            Ground.Grid -> {
                val gx = fx % 32f
                val gy = fy % 32f
                val line = if (gx < 1.5f || gy < 1.5f) 1f else 0f
                lerp(Color(0xFF041A12), Color(0xFF39FF88), line * 0.7f + noise(fx, fy, 16, 13) * 0.05f)
            }

            Ground.Deck -> {
                val plate = fx % 64f < 1.5f || fy % 64f < 1.5f
                val rivet = run {
                    val rx = fx % 64f - 6f
                    val ry = fy % 64f - 6f
                    rx * rx + ry * ry < 6f
                }
                val diamond = ((sin((fx + fy) * 0.6f) * sin((fx - fy) * 0.6f)) > 0.7f)
                val metal = lerp(Color(0xFF5C6B7A), base, 0.2f)
                when {
                    plate -> lerp(metal, Color.Black, 0.4f)
                    rivet -> lerp(metal, Color.White, 0.35f)
                    diamond -> lerp(metal, Color.White, 0.12f)
                    else -> lerp(metal, Color.Black, noise(fx, fy, 32, 17) * 0.1f)
                }
            }

            Ground.Rug -> {
                val fiber = noise(fx, fy, 128, 19)
                val paw = run {
                    val cx = fx % 64f - 32f
                    val cy = fy % 64f - 32f
                    cx * cx + (cy - 4f) * (cy - 4f) < 60f || listOf(-9f to -8f, -3f to -12f, 3f to -12f, 9f to -8f).any { (dx, dy) -> (cx - dx) * (cx - dx) + (cy - dy) * (cy - dy) < 12f }
                }
                lerp(lerp(base, Color.White, 0.25f), if (paw) Color.White else Color(0xFFC2185B), if (paw) 0.25f else fiber * 0.12f)
            }
        }
        px[y * N + x] = c.toArgb()
    }
    return Bitmap.createBitmap(px, N, N, Bitmap.Config.ARGB_8888)
}

/** Sky gradient for the ceiling: [edge] where it meets the walls (v = 0), [middle] overhead (v = 1). */
internal fun skyBitmap(edge: Color, middle: Color): Bitmap {
    val w = 4
    val h = 128
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val f = y / (h - 1f)
        val c = lerp(edge, middle, f * f * (3f - 2f * f)).toArgb()
        for (x in 0 until w) px[y * w + x] = c
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
