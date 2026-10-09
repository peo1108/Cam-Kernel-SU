package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Unit shapes (radius 1, centered on the origin), placed with [drawUnit].
// ---------------------------------------------------------------------------------------------

internal val UnitStar: Path = Path().apply {
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) 1f else 0.48f
        val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
        if (i == 0) moveTo(rad * cos(a), rad * sin(a)) else lineTo(rad * cos(a), rad * sin(a))
    }
    close()
}

internal val UnitHeart: Path = Path().apply {
    moveTo(0f, 0.9f)
    cubicTo(-1.25f, 0.05f, -0.8f, -1f, 0f, -0.42f)
    cubicTo(0.8f, -1f, 1.25f, 0.05f, 0f, 0.9f)
    close()
}

/** Comic "POW" burst with uneven spikes. */
internal val UnitBurst: Path = Path().apply {
    val spikes = 14
    for (i in 0 until spikes * 2) {
        val rad = if (i % 2 == 0) 0.88f + 0.12f * hash(i + 2000) else 0.55f + 0.08f * hash(i + 2100)
        val a = i * PI.toFloat() / spikes
        if (i == 0) moveTo(rad * cos(a), rad * sin(a)) else lineTo(rad * cos(a), rad * sin(a))
    }
    close()
}

/** Teardrop with the point up. */
internal val UnitDrop: Path = Path().apply {
    moveTo(0f, -1f)
    cubicTo(0.2f, -0.6f, 0.7f, -0.1f, 0.7f, 0.3f)
    cubicTo(0.7f, 0.75f, 0.35f, 1f, 0f, 1f)
    cubicTo(-0.35f, 1f, -0.7f, 0.75f, -0.7f, 0.3f)
    cubicTo(-0.7f, -0.1f, -0.2f, -0.6f, 0f, -1f)
    close()
}

/** Leaf growing up from the origin. */
internal val UnitLeaf: Path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.55f, -0.25f, 0.5f, -0.8f, 0f, -1f)
    cubicTo(-0.5f, -0.8f, -0.55f, -0.25f, 0f, 0f)
    close()
}

internal val UnitSpiral: Path = Path().apply {
    val steps = 48
    for (i in 0..steps) {
        val f = i / steps.toFloat()
        val a = f * 3.6f * PI.toFloat()
        val rad = 0.1f + 0.9f * f
        if (i == 0) moveTo(rad * cos(a), rad * sin(a)) else lineTo(rad * cos(a), rad * sin(a))
    }
}

internal inline fun DrawScope.drawUnit(center: Offset, radius: Float, rotation: Float = 0f, block: DrawScope.() -> Unit) {
    withTransform({
        translate(center.x, center.y)
        rotate(rotation, Offset.Zero)
        scale(radius, radius, Offset.Zero)
    }, block)
}

internal fun DrawScope.drawCartoonStar(center: Offset, radius: Float, rotation: Float, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0f) return
    drawUnit(center, radius, rotation) {
        drawPath(UnitStar, Color(0xFFFFD43B), alpha = alpha)
        drawPath(UnitStar, Color(0xFFF59F00), alpha = alpha, style = Stroke(0.14f, join = StrokeJoin.Round))
        scale(0.45f, 0.45f, Offset(-0.18f, -0.28f)) {
            drawPath(UnitStar, Color.White, alpha = alpha * 0.55f)
        }
    }
}

internal fun DrawScope.drawHeart(center: Offset, radius: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    drawUnit(center, radius) {
        drawPath(UnitHeart, Brush.verticalGradient(listOf(Color(0xFFFF8FAB), Color(0xFFFF4D6D)), -1f, 1f), alpha = alpha)
        drawPath(UnitHeart, Color(0xFFC9184A), alpha = alpha * 0.6f, style = Stroke(0.1f, join = StrokeJoin.Round))
        drawOval(Color.White, Offset(-0.62f, -0.55f), Size(0.36f, 0.22f), alpha = alpha * 0.8f)
    }
}

/** Four-point twinkle with a soft halo. */
internal fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f || radius <= 0f) return
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.45f * alpha), Color.Transparent), center, radius * 1.6f),
        radius * 1.6f,
        center,
    )
    val stroke = radius * 0.28f
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), stroke, StrokeCap.Round, alpha = alpha)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), stroke, StrokeCap.Round, alpha = alpha)
    drawCircle(Color.White, stroke * 0.7f, center, alpha = alpha)
}

/** Soft radial glow, e.g. around a sun, a lantern or the impact point. */
internal fun DrawScope.drawGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.35f to color.copy(alpha = alpha * 0.45f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius,
        center,
    )
}

/** Slowly turning light shafts fanning out of [center]. */
internal fun DrawScope.drawGodRays(center: Offset, length: Float, t: Float, color: Color, alpha: Float) {
    repeat(7) { k ->
        val a = t * 0.05f + k * TAU / 7 + 0.3f * sin(t * 0.3f + k)
        val spread = 0.07f + 0.04f * hash(k + 3000)
        val path = Path().apply {
            moveTo(center.x, center.y)
            lineTo(center.x + length * cos(a - spread), center.y + length * sin(a - spread))
            lineTo(center.x + length * cos(a + spread), center.y + length * sin(a + spread))
            close()
        }
        drawPath(
            path,
            Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, length),
        )
    }
}

/** Out-of-focus light dots drifting slowly upward; shared by every scene for depth. */
internal fun DrawScope.drawBokeh(t: Float, seed: Int, count: Int, tint: Color, unit: Float) {
    val w = size.width
    val h = size.height
    repeat(count) { i ->
        val radius = unit * (3f + 9f * hash(seed + i))
        val y = h + radius - wrap(t * unit * (2f + 4f * hash(seed + i * 3)) + hash(seed + i * 7) * h * 1.3f, h * 1.3f + radius * 2)
        val x = hash(seed + i * 11) * w + sin(t * 0.4f + i) * unit * 6f
        val a = 0.12f + 0.14f * ((sin(t * 0.8f + i * 1.9f) + 1f) / 2f)
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = a), tint.copy(alpha = a * 0.4f), Color.Transparent), Offset(x, y), radius),
            radius,
            Offset(x, y),
        )
        drawCircle(tint, radius * 0.92f, Offset(x, y), alpha = a * 0.5f, style = Stroke(unit * 0.5f))
    }
}

/** Crackling bolt between two glaring slimes, re-rolled 16 times a second, with sparks running along it. */
internal fun DrawScope.drawLightning(a: Offset, b: Offset, t: Float, r: Float, alpha: Float) {
    if (alpha <= 0f) return
    val seed = floor(t * 16f).toInt()
    val points = ArrayList<Offset>(9)
    points += a
    for (i in 1 until 8) {
        val p = lerp(a, b, i / 8f)
        val amp = sin(PI.toFloat() * i / 8f) * r * 0.45f
        points += Offset(p.x, p.y + (hash(seed * 13 + i) - 0.5f) * 2f * amp)
    }
    points += b
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, Color(0xFFFFE066), alpha = 0.25f * alpha, style = Stroke(r * 0.32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color(0xFFFFD43B), alpha = 0.8f * alpha, style = Stroke(r * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Color.White, alpha = alpha, style = Stroke(r * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val mid = lerp(a, b, 0.5f)
    drawGlow(mid, r * 0.9f, Color(0xFFFFE066), 0.6f * alpha)
    drawCartoonStar(mid, r * 0.16f * (1f + 0.25f * sin(t * 30f)), t * 400f, alpha)
    repeat(4) { k ->
        val f = wrap(t * 2.5f + k / 4f, 1f)
        val idx = (f * (points.size - 1)).toInt().coerceIn(0, points.size - 2)
        val p = lerp(points[idx], points[idx + 1], f * (points.size - 1) - idx)
        drawCircle(Color.White, r * 0.035f, p, alpha = alpha)
    }
}

/** The clash: comic burst, shock ring and stars thrown out; [e] runs 0..1 after impact. */
internal fun DrawScope.drawImpact(center: Offset, e: Float, r: Float) {
    if (e !in 0f..1f) return
    val fade = 1f - e
    drawGlow(center, r * (1.2f + 2f * e), Color.White, 0.9f * fade)
    val burst = r * (0.5f + 0.9f * easeOutBack(progress(e, 0f, 0.35f))) * (1f - 0.4f * progress(e, 0.5f, 0.5f))
    val burstAlpha = 1f - progress(e, 0.45f, 0.4f)
    drawUnit(center, burst, e * 25f) {
        drawPath(UnitBurst, Color(0xFFFFD43B), alpha = burstAlpha)
        drawPath(UnitBurst, Color(0xFFF76707), alpha = burstAlpha, style = Stroke(0.07f, join = StrokeJoin.Round))
        scale(0.62f, 0.62f, Offset.Zero) { drawPath(UnitBurst, Color.White, alpha = burstAlpha) }
    }
    drawCircle(Color.White, r * (0.5f + 2.6f * easeOutCubic(e)), center, alpha = fade * 0.75f, style = Stroke(r * 0.06f * fade + 1f))
    repeat(8) { i ->
        val a = i * TAU / 8 + 0.4f + hash(i + 900) * 0.5f
        val d = r * (0.6f + 2.2f * easeOutCubic(e)) * (0.8f + 0.4f * hash(i + 910))
        val p = center + Offset(cos(a) * d, sin(a) * d + r * 1.2f * e * e)
        drawCartoonStar(p, r * (0.14f + 0.06f * hash(i + 920)) * (1f - 0.5f * e), e * 360f * (if (i % 2 == 0) 1f else -1f), fade)
    }
}

/** Puffs of dust kicked up where a slime lands; [e] runs 0..1. */
internal fun DrawScope.drawDust(ground: Offset, e: Float, r: Float, spread: Float = 1f) {
    if (e !in 0f..1f) return
    val fade = (1f - e)
    repeat(6) { k ->
        val dir = if (k % 2 == 0) 1f else -1f
        val reach = r * (0.5f + 0.9f * hash(k + 1500)) * spread
        val c = ground + Offset(dir * reach * easeOutCubic(e), -r * (0.1f + 0.35f * hash(k + 1510)) * easeOutCubic(e))
        val rad = r * (0.1f + 0.14f * hash(k + 1520)) * (0.6f + 0.6f * e)
        drawCircle(Color.White, rad, c, alpha = 0.55f * fade)
        drawCircle(Color.White, rad, c, alpha = 0.3f * fade, style = Stroke(r * 0.02f))
    }
}

/** Air streaking into the winner's open mouth. */
internal fun DrawScope.drawSuction(mouth: Offset, t: Float, r: Float, alpha: Float) {
    if (alpha <= 0f) return
    repeat(9) { k ->
        val f = wrap(t * 1.6f + hash(k + 1600), 1f)
        val a = -PI.toFloat() / 2 + (k - 4) * 0.28f
        val d = r * 3f * (1f - easeInCubic(f))
        val len = r * 0.5f * (1f - f)
        val p = mouth + Offset(cos(a) * d, sin(a) * d)
        val q = mouth + Offset(cos(a + 0.25f) * (d - len).coerceAtLeast(0f), sin(a + 0.25f) * (d - len).coerceAtLeast(0f))
        val c = lerp(p, q, 0.5f) + Offset(cos(a + PI.toFloat() / 2) * len * 0.3f, sin(a + PI.toFloat() / 2) * len * 0.3f)
        val path = Path().apply {
            moveTo(p.x, p.y)
            quadraticTo(c.x, c.y, q.x, q.y)
        }
        drawPath(path, Color.White, alpha = alpha * (1f - f) * 0.75f, style = Stroke(r * 0.04f, cap = StrokeCap.Round))
    }
}

/** Little bubbles from a big burp; [p] is the burp progress. */
internal fun DrawScope.drawBurpPuffs(mouth: Offset, p: Float, r: Float) {
    repeat(5) { k ->
        val e = progress(p, k * 0.1f, 0.6f)
        if (e <= 0f || e >= 1f) return@repeat
        val a = -PI.toFloat() / 2 + (k - 2f) * 0.42f
        val c = mouth + Offset(cos(a), sin(a)) * (r * 1.5f * easeOutCubic(e))
        val rad = r * (0.08f + 0.18f * e)
        drawCircle(Color.White, rad, c, alpha = (1f - e) * 0.25f)
        drawCircle(Color.White, rad, c, alpha = (1f - e) * 0.85f, style = Stroke(r * 0.03f))
        drawCircle(Color.White, rad * 0.22f, c + Offset(-rad * 0.35f, -rad * 0.35f), alpha = (1f - e))
    }
}

/** Comic speech bubble rising from [anchor] with the burp sound of the next language. */
internal fun DrawScope.drawSpeechBubble(anchor: Offset, text: TextLayoutResult, e: Float, r: Float) {
    if (e <= 0f || e >= 1f) return
    val pop = easeOutBack(progress(e, 0f, 0.3f), 2.4f)
    val fade = 1f - progress(e, 0.75f, 0.25f)
    val padX = r * 0.22f
    val padY = r * 0.1f
    val bw = text.size.width + padX * 2
    val bh = text.size.height + padY * 2
    val center = anchor + Offset(r * 0.9f, -r * 1.5f - r * 0.25f * e)
    withTransform({ scale(pop, pop, anchor) }) {
        val tail = Path().apply {
            moveTo(center.x - bw * 0.18f, center.y + bh / 2 - 2f)
            lineTo(anchor.x + r * 0.25f, anchor.y - r * 0.35f)
            lineTo(center.x + bw * 0.02f, center.y + bh / 2 - 2f)
            close()
        }
        drawPath(tail, Color.White, alpha = 0.95f * fade)
        drawRoundRect(Color.White, center - Offset(bw / 2, bh / 2), Size(bw, bh), CornerRadius(bh / 2), alpha = 0.95f * fade)
        drawRoundRect(
            Color(0x22000000), center - Offset(bw / 2, bh / 2), Size(bw, bh), CornerRadius(bh / 2),
            alpha = fade, style = Stroke(r * 0.03f),
        )
        drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f), alpha = fade)
    }
}

/**
 * Confetti in the new country's colors bursting from the winner's mouth: paper chips that flip
 * (their width follows the cosine of a spin), dots and curly ribbons. [tt] is seconds since launch.
 */
internal fun DrawScope.drawConfetti(origin: Offset, tt: Float, colors: List<Color>, r: Float) {
    val life = 1.8f
    if (tt !in 0f..life) return
    val g = r * 8f
    val drag = 1f / (1f + tt * 1.2f)
    repeat(34) { i ->
        val a = -PI.toFloat() / 2 + (hash(i + 1000) - 0.5f) * PI.toFloat() * 1.25f
        val speed = r * (4f + 5f * hash(i + 1010)) * drag
        val sway = sin(tt * 6f + i) * r * 0.25f * tt
        val p = origin + Offset(cos(a) * speed * tt + sway, sin(a) * speed * tt + 0.5f * g * tt * tt)
        val color = colors[i % colors.size]
        val alpha = 1f - progress(tt, life * 0.6f, life * 0.4f)
        when (i % 3) {
            0 -> {
                val flip = cos(tt * (8f + 6f * hash(i + 1030)) + i)
                withTransform({
                    rotate(tt * 300f * (hash(i + 1020) - 0.5f) * 2f, p)
                    scale(max(0.12f, abs(flip)), 1f, p)
                }) {
                    drawRoundRect(
                        if (flip < 0) lerp(color, Color.Black, 0.18f) else color,
                        p - Offset(r * 0.06f, r * 0.1f), Size(r * 0.12f, r * 0.2f), CornerRadius(r * 0.02f), alpha = alpha,
                    )
                }
            }

            1 -> drawCircle(color, r * 0.055f, p, alpha = alpha)
            else -> {
                val path = Path().apply {
                    moveTo(p.x, p.y)
                    for (k in 1..4) {
                        val q = p + Offset(k * r * 0.06f, sin(tt * 12f + k * 1.6f) * r * 0.06f)
                        lineTo(q.x, q.y)
                    }
                }
                drawPath(path, color, alpha = alpha, style = Stroke(r * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * The cartoon brawl: a boiling cloud of dust puffs with stars and bursts flashing out of it.
 * [grow] scales it in (0..1), [burst] blows it apart at the end (0..1).
 */
internal fun DrawScope.drawBrawlCloud(center: Offset, r: Float, t: Float, grow: Float, burst: Float) {
    val scale = easeOutBack(grow.coerceIn(0f, 1f)) * (1f + burst * 0.9f)
    val alpha = 1f - burst
    if (alpha <= 0f || scale <= 0f) return
    val ring = r * 1.45f * scale
    repeat(12) { k ->
        val a = k * TAU / 12 + t * 0.7f
        val d = ring * (0.55f + 0.18f * sin(t * 9f + k * 1.3f))
        val c = center + Offset(cos(a) * d * 1.25f, sin(a) * d * 0.75f)
        val rad = r * scale * (0.5f + 0.16f * sin(t * 13f + k * 1.7f))
        drawCircle(Color(0xFFADB5BD), rad, c + Offset(0f, rad * 0.18f), alpha = 0.55f * alpha)
    }
    repeat(12) { k ->
        val a = k * TAU / 12 + t * 0.7f
        val d = ring * (0.55f + 0.18f * sin(t * 9f + k * 1.3f))
        val c = center + Offset(cos(a) * d * 1.25f, sin(a) * d * 0.75f)
        val rad = r * scale * (0.5f + 0.16f * sin(t * 13f + k * 1.7f))
        drawCircle(Color.White, rad, c, alpha = 0.97f * alpha)
    }
    drawCircle(Color.White, ring * 0.8f, center, alpha = alpha)
    repeat(5) { k ->
        val a = -TAU / 4 + (k - 2) * 0.5f + sin(t * 3f + k) * 0.2f
        val c = center + Offset(cos(a) * ring * 0.5f, sin(a) * ring * 0.35f)
        drawCircle(Color.White, r * 0.12f * scale, c + Offset(-r * 0.05f, -r * 0.05f), alpha = 0.9f * alpha)
    }
    val slot = floor(t * 9f).toInt()
    val e = t * 9f - slot
    repeat(2) { j ->
        val id = slot * 2 + j
        val a = hash(id + 4000) * TAU
        val p = center + Offset(cos(a) * ring * 1.1f, sin(a) * ring * 0.8f)
        if (hash(id + 4100) > 0.5f) {
            drawCartoonStar(p, r * 0.22f * easeOutBack(e) * alpha, e * 180f, (1f - e) * alpha)
        } else {
            drawUnit(p, r * 0.32f * easeOutBack(e), e * 40f) {
                drawPath(UnitBurst, Color(0xFFFFD43B), alpha = (1f - e) * alpha)
                scale(0.55f, 0.55f, Offset.Zero) { drawPath(UnitBurst, Color.White, alpha = (1f - e) * alpha) }
            }
        }
    }
}

/** Comic sound effect word ("BỐP!") popping in: yellow fill with a thick dark outline. */
internal fun DrawScope.drawComicWord(word: TextLayoutResult, center: Offset, e: Float, rotation: Float, outline: Float) {
    if (e !in 0f..1f) return
    val pop = easeOutBack(progress(e, 0f, 0.35f), 3f)
    val fade = 1f - progress(e, 0.7f, 0.3f)
    withTransform({
        rotate(rotation, center)
        scale(pop, pop, center)
    }) {
        val tl = center - Offset(word.size.width / 2f, word.size.height / 2f)
        drawText(word, color = Color(0xFF2A2440), topLeft = tl, alpha = fade, drawStyle = Stroke(outline, join = StrokeJoin.Round))
        drawText(word, color = Color(0xFFFFD43B), topLeft = tl, alpha = fade)
    }
}

/** Two red exclamation marks jittering over a choking slime. */
internal fun DrawScope.drawExclaim(c: Offset, r: Float, t: Float, alpha: Float) {
    if (alpha <= 0f) return
    for (k in 0..1) {
        val base = c + Offset((k - 0.5f) * r * 0.32f + sin(t * 40f + k) * r * 0.02f, 0f)
        val tilt = (k - 0.5f) * 0.4f
        val top = base + Offset(tilt * r * 0.5f, -r * 0.5f)
        drawLine(Color(0xFFFF3B5C), top, base + Offset(0f, -r * 0.12f), r * 0.09f, StrokeCap.Round, alpha = alpha)
        drawCircle(Color(0xFFFF3B5C), r * 0.055f, base + Offset(-tilt * r * 0.05f, r * 0.02f), alpha = alpha)
    }
}

/** Liquid edge for the flood of the next scene: a circle with travelling ripples. */
internal fun Path.liquidCircle(center: Offset, radius: Float, t: Float) {
    reset()
    val n = 72
    for (i in 0..n) {
        val a = i * TAU / n
        val rr = radius * (1f + 0.05f * sin(6f * a + t * 9f) + 0.025f * sin(11f * a - t * 6f))
        val p = Offset(center.x + rr * cos(a), center.y + rr * sin(a))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/**
 * The retching jet: a wobbling, tapering stream of the next scene's colors arcing out of the
 * mouth toward [dir] with droplets flying off it. [e] runs 0..1.
 */
internal fun DrawScope.drawVomitJet(mouth: Offset, dir: Float, e: Float, r: Float, t: Float, colors: List<Color>) {
    if (e !in 0f..1f) return
    val reach = easeOutCubic(progress(e, 0f, 0.45f))
    val fade = 1f - progress(e, 0.65f, 0.35f)
    val ctrl = mouth + Offset(dir * r * 2.2f, -r * 3.4f)
    val end = mouth + Offset(dir * r * 4.6f, -r * 1.2f)
    val n = 26
    for (i in n downTo 0) {
        val f = i / n.toFloat() * reach
        val p = quadBezier(mouth, ctrl, end, f)
        val rad = r * 0.3f * (1f - f * 0.55f) * (1f + 0.18f * sin(t * 30f + f * 12f))
        val color = colors[(f * (colors.size - 1)).toInt().coerceIn(0, colors.size - 1)]
        drawCircle(color, rad, p, alpha = fade)
        drawCircle(Color.White, rad * 0.35f, p + Offset(-rad * 0.3f, -rad * 0.3f), alpha = 0.55f * fade)
    }
    repeat(14) { k ->
        val launch = hash(k + 4200) * 0.5f
        val tt = (e - launch) * 1.4f
        if (tt <= 0f || tt > 1f) return@repeat
        val f = hash(k + 4210) * reach
        val p0 = quadBezier(mouth, ctrl, end, f)
        val v = Offset(dir * r * (1f + 2f * hash(k + 4220)), -r * (1.5f + 2f * hash(k + 4230)))
        val p = p0 + v * tt + Offset(0f, r * 6f * tt * tt)
        drawCircle(colors[k % colors.size], r * (0.05f + 0.06f * hash(k + 4240)) * (1f - tt * 0.5f), p, alpha = (1f - tt) * fade)
    }
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Light spilling from the key light over everything in front of it (screen-blended). */
internal fun DrawScope.drawBloom(light: Offset, color: Color, strength: Float, radius: Float) {
    if (strength <= 0.01f) return
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = strength),
            0.35f to color.copy(alpha = strength * 0.35f),
            1f to Color.Transparent,
            center = light,
            radius = radius,
        ),
        radius,
        light,
        blendMode = BlendMode.Screen,
    )
}

/** Faint lens ghosts along the line from the light through the card center, plus a horizontal streak. */
internal fun DrawScope.drawLensFlare(light: Offset, color: Color, strength: Float) {
    if (strength <= 0.01f) return
    if (light.x !in 0f..size.width || light.y !in 0f..size.height) return
    val axis = Offset(size.width / 2f, size.height / 2f) - light
    val ghosts = floatArrayOf(0.35f, 0.05f, 0.7f, 0.11f, 1.05f, 0.03f, 1.35f, 0.16f, 1.7f, 0.06f)
    for (i in ghosts.indices step 2) {
        val p = light + axis * ghosts[i]
        val rr = size.height * ghosts[i + 1]
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.02f * strength), color.copy(alpha = 0.14f * strength), Color.Transparent),
                p,
                rr,
            ),
            rr,
            p,
            blendMode = BlendMode.Screen,
        )
    }
    drawRect(
        Brush.horizontalGradient(
            listOf(Color.Transparent, color.copy(alpha = 0.35f * strength), Color.Transparent),
            light.x - size.width * 0.35f,
            light.x + size.width * 0.35f,
        ),
        Offset(light.x - size.width * 0.35f, light.y - 1.5f),
        Size(size.width * 0.7f, 3f),
        blendMode = BlendMode.Screen,
    )
}

/** Tileable film grain: a small noise texture repeated over the card. */
internal fun filmGrain(): Brush {
    val n = 128
    val random = java.util.Random(7)
    val pixels = IntArray(n * n) {
        val v = random.nextInt(256)
        android.graphics.Color.argb(random.nextInt(60), v, v, v)
    }
    val bitmap = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
