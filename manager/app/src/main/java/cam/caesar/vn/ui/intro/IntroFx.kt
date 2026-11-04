package cam.su.kernel.ui.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cam.su.kernel.ui.screen.home.arena.TAU
import cam.su.kernel.ui.screen.home.arena.drawGlow
import cam.su.kernel.ui.screen.home.arena.easeOutCubic
import cam.su.kernel.ui.screen.home.arena.hash
import cam.su.kernel.ui.screen.home.arena.lerp
import cam.su.kernel.ui.screen.home.arena.progress
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

// Everything here draws in the logo's own pixels (see IntroGeometry); the overlay scales it to the screen.

internal val IntroTeal = Color(0xFF2EE6D6)
internal val IntroCyan = Color(0xFF8AFBFF)

/** The colours of a shot: its glow, its hot core, its sparks, the slug left in the metal. */
internal class ShotColors(val glow: Color, val hot: Color, val spark: Color, val slug: Color)

/** Shots strike cold: ice-blue glow, white-hot core, pale sparks, a steel-blue slug. */
internal val ColdShot = ShotColors(Color(0xFF4FB8FF), Color(0xFFD6F2FF), Color(0xFFBFE6FF), Color(0xFF0A1A2A))

/** Reused paths and scratch space, kept by the player's thread (no allocation per frame). */
internal class IntroPaths {
    /** The frame's lightning, drawn thick. */
    val bolts = Path()

    /** Branches and the crackle round the slugs, drawn thin. */
    val branches = Path()
    val strand = Path()
    val hole = Path()
    val scratch = FloatArray(Bolt.POINTS * 2)

    /** The HUD: the picture above the lettering, without the triangle (its plates bring that). */
    val hud = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, IntroGeometry.SIZE, IntroGeometry.TEXT_TOP))
        val c = Array(3) { IntroGeometry.grown(it) }
        moveTo(c[0].x, c[0].y)
        lineTo(c[1].x, c[1].y)
        lineTo(c[2].x, c[2].y)
        close()
    }
}

/** A white silhouette of whatever is drawn with it, for the flash as a plate lands. */
internal val WhiteFlash = ColorFilter.tint(Color.White, BlendMode.SrcIn)

/** Adds one bolt from [a] to [b] to [path], jagged by about [amp] logo pixels. */
internal fun IntroPaths.appendBolt(path: Path, a: Offset, b: Offset, seed: Int, amp: Float) {
    val len = (b - a).getDistance()
    if (len < 0.5f) return
    val n = Bolt.build(a.x, a.y, b.x, b.y, seed, rough = amp / len, out = scratch)
    path.moveTo(scratch[0], scratch[1])
    for (i in 1 until n) path.lineTo(scratch[2 * i], scratch[2 * i + 1])
}

/**
 * Adds lightning along the triangle's outline from [from] to [to] (distances round it from the
 * apex; [to] may pass the apex again). It is cut at the corners and every [OUTLINE_PIECE] pixels,
 * the cuts slid by [phase] so the pinched points move with each new bolt.
 */
internal fun IntroPaths.appendOutline(path: Path, from: Float, to: Float, seed: Int, amp: Float, phase: Float) {
    var s = from
    var k = 0
    while (s < to - 0.5f) {
        // the next regular cut, never leaving a sliver
        val grid = phase + (floor((s - phase) / OUTLINE_PIECE) + 1f) * OUTLINE_PIECE
        val cut = minOf(to, nextCorner(s), if (grid - s < 24f) grid + OUTLINE_PIECE else grid)
        appendBolt(path, IntroGeometry.perimeterPoint(s), IntroGeometry.perimeterPoint(cut), seed * 31 + k, amp)
        s = cut
        k++
    }
}

private const val OUTLINE_PIECE = 190f

/** The first corner past [s] round the outline: they sit at 0, L0 and L0 + L1, again each lap. */
private fun nextCorner(s: Float): Float {
    val p = IntroGeometry.perimeter
    val lap = floor(s / p) * p
    val l0 = IntroGeometry.edgeLength(0)
    val l1 = l0 + IntroGeometry.edgeLength(1)
    val r = s - lap
    return lap + when {
        r < l0 - 0.5f -> l0
        r < l1 - 0.5f -> l1
        r < p - 0.5f -> p
        else -> p + l0
    }
}

/** A [halo] (teal), a [body] (cyan) and a white-hot core: the look of every bolt in [path]. */
internal fun DrawScope.drawBolts(path: Path, width: Float, alpha: Float, halo: Color = IntroTeal, body: Color = IntroCyan) {
    if (alpha <= 0.01f) return
    drawPath(path, halo, alpha = 0.16f * alpha, style = Stroke(width * 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, body, alpha = 0.55f * alpha, style = Stroke(width * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White, alpha = 0.95f * alpha, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * A shot in the air heading along [dir]: a light trail [trail] long (longer while it is fast),
 * two rifling strands twisting round it, and a hot head.
 */
internal fun DrawScope.drawBullet(paths: IntroPaths, head: Offset, dir: Offset, trail: Float, t: Float, colors: ShotColors = ColdShot) {
    val tail = head - dir * trail
    drawLine(
        Brush.linearGradient(listOf(Color.Transparent, colors.glow.copy(alpha = 0.5f), colors.hot, Color.White), tail, head),
        tail,
        head,
        strokeWidth = 7f,
        cap = StrokeCap.Round,
    )
    val perp = Offset(-dir.y, dir.x)
    val strand = paths.strand
    for (side in 0..1) {
        strand.rewind()
        for (k in 0..20) {
            val f = k / 20f
            val o = lerp(tail, head, f) + perp * (sin(f * trail / 16f - t * 80f + side * PI.toFloat()) * 10f * f)
            if (k == 0) strand.moveTo(o.x, o.y) else strand.lineTo(o.x, o.y)
        }
        drawPath(strand, if (side == 0) IntroCyan else colors.hot, alpha = 0.75f, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawGlow(head, 34f, colors.glow, 0.9f)
    drawGlow(head, 15f, colors.hot, 1f)
    drawCircle(Color.White, 4.5f, head)
}

/**
 * Where a shot struck the metal, [age] seconds ago: a faint cold haze and hairline cracks running
 * out of the hit, cut dark into the steel with a pale edge. [heat] (1 fresh to 0) fades it all out.
 */
internal fun DrawScope.drawEmbedded(p: Offset, age: Float, seed: Int, heat: Float = 1f, colors: ShotColors = ColdShot) {
    if (heat <= 0.01f) return
    val fresh = 1f - progress(age, 0f, 0.2f)
    // the cracks spread out quickly, then stay
    val grow = easeOutCubic(progress(age, 0f, 0.12f))
    drawGlow(p, 18f + 22f * fresh, colors.glow, heat * (0.18f + 0.3f * fresh))
    for (k in 0 until CRACKS) {
        val a = (k + 0.6f * hash(seed * 7 + k)) / CRACKS * TAU
        val len = (12f + 26f * hash(seed * 11 + k)) * grow
        var from = p
        var dir = Offset(cos(a), sin(a))
        // each crack bends twice on its way out
        for (j in 0..2) {
            val seg = len / 3f
            val bend = (hash(seed * 13 + k * 5 + j) - 0.5f) * 0.9f
            dir = Offset(dir.x * cos(bend) - dir.y * sin(bend), dir.x * sin(bend) + dir.y * cos(bend))
            val to = from + dir * seg
            val w = 1.6f * (1f - j * 0.28f)
            drawLine(Color.Black, from, to, strokeWidth = w + 0.8f, cap = StrokeCap.Round, alpha = 0.55f * heat)
            drawLine(colors.spark, from + Offset(0.7f, -0.7f), to + Offset(0.7f, -0.7f), strokeWidth = w * 0.5f, cap = StrokeCap.Round, alpha = heat * (0.18f + 0.3f * fresh), blendMode = BlendMode.Plus)
            from = to
        }
    }
}

private const val CRACKS = 7

/** Sparks thrown back out of an impact at [p] toward [back] (unit), [age] seconds after it. */
internal fun DrawScope.drawSparks(p: Offset, back: Offset, age: Float, seed: Int, count: Int, colors: ShotColors = ColdShot) {
    if (age < 0f || age > SPARK_LIFE) return
    val fade = 1f - age / SPARK_LIFE
    val base = atan2(back.y, back.x)
    repeat(count) { k ->
        val a = base + (hash(seed + k) - 0.5f) * 2.4f
        val speed = 520f + 900f * hash(seed + k + 40)
        val d = Offset(cos(a), sin(a))
        val pos = p + d * (speed * age) + Offset(0f, 900f * age * age)
        drawLine(if (k % 3 == 0) Color.White else colors.spark, pos - d * (speed * 0.022f), pos, strokeWidth = 2.6f, cap = StrokeCap.Round, alpha = fade)
    }
    drawGlow(p, 60f + 60f * age, colors.hot, 0.7f * fade)
}

private const val SPARK_LIFE = 0.32f

/** A slug going off: white core, orange ball in a teal halo, a shock ring and flying debris. */
internal fun DrawScope.drawFireball(c: Offset, age: Float, size: Float, seed: Int, life: Float = 0.5f, colors: ShotColors = ColdShot) {
    if (age < 0f || age > life) return
    val e = age / life
    val fade = 1f - e
    val r = size * (0.35f + 0.9f * easeOutCubic(progress(age, 0f, 0.16f)))
    drawGlow(c, r * 2.1f, IntroTeal, 0.45f * fade)
    drawGlow(c, r, colors.glow, 0.95f * fade.pow(0.6f))
    drawGlow(c, r * 0.55f, colors.hot, fade)
    drawGlow(c, r * 0.3f, Color.White, 1f - progress(age, 0f, 0.14f))
    drawCircle(colors.hot, size * (0.4f + 2.2f * easeOutCubic(e)), c, alpha = 0.6f * fade, style = Stroke(size * 0.07f * fade + 0.5f))
    repeat(9) { k ->
        val a = hash(seed + k * 7) * TAU
        val d = Offset(cos(a), sin(a))
        val speed = size * (3.5f + 5f * hash(seed + k * 7 + 3))
        val pos = c + d * (speed * age) + Offset(0f, size * 9f * age * age)
        drawLine(if (k % 2 == 0) colors.spark else colors.glow, pos - d * (speed * 0.03f), pos, strokeWidth = size * 0.07f, cap = StrokeCap.Round, alpha = fade)
    }
}

/** The rim of the opening hole: a soft teal ring with a white edge, [width] wide. In screen pixels. */
internal fun DrawScope.drawShockwave(c: Offset, radius: Float, width: Float, alpha: Float) {
    if (alpha <= 0.01f || radius <= 1f) return
    val outer = radius + width * 2f
    val inner = ((radius - width * 2f) / outer).coerceAtLeast(0f)
    val mid = (radius / outer).coerceIn(inner + 0.001f, 0.999f)
    drawCircle(
        Brush.radialGradient(
            inner to Color.Transparent,
            mid to IntroTeal.copy(alpha = 0.7f * alpha),
            1f to Color.Transparent,
            center = c,
            radius = outer,
        ),
        outer,
        c,
    )
    drawCircle(Color.White, radius, c, alpha = 0.9f * alpha, style = Stroke(width * 0.3f))
}

/** One color channel of the picture, for the RGB split. */
private fun keep(channel: Int) = ColorFilter.colorMatrix(
    ColorMatrix(FloatArray(20).also { m ->
        m[channel * 5 + channel] = 1f
        m[18] = 1f
    }),
)

internal val KeepChannel = Array(3) { keep(it) }

/**
 * The part of [logo] at [src] ([size] big) drawn split into red, green and blue, shifted [dx]
 * apart and added back together on the black: one channel each side of where it belongs.
 */
internal fun DrawScope.drawSplit(logo: ImageBitmap, src: IntOffset, size: IntSize, dx: Int, alpha: Float = 1f) {
    for (c in 0..2) {
        drawImage(logo, src, size, IntOffset(src.x + (c - 1) * dx, src.y), size, alpha = alpha, colorFilter = KeepChannel[c], blendMode = BlendMode.Plus)
    }
}

/** [drawSplit], with [slices] rows of it torn sideways by up to [tear]. */
internal fun DrawScope.drawGlitchSlice(logo: ImageBitmap, src: IntOffset, size: IntSize, dx: Int, tear: Float, seed: Int, slices: Int) {
    drawSplit(logo, src, size, dx)
    repeat(slices) { k ->
        val h = 5 + (hash(seed + k * 5) * 18f).toInt()
        val y = src.y + (hash(seed + k * 5 + 1) * (size.height - h).coerceAtLeast(1)).toInt()
        val shift = ((hash(seed + k * 5 + 2) - 0.5f) * 2f * tear).toInt()
        val slice = IntSize(size.width, h)
        drawRect(Color.Black, Offset(src.x.toFloat(), y.toFloat()), Size(size.width.toFloat(), h.toFloat()))
        drawImage(logo, IntOffset(src.x, y), slice, IntOffset(src.x + shift, y), slice, colorFilter = KeepChannel[k % 3].takeIf { k % 2 == 0 })
    }
}
