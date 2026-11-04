package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Props for the scene openings and the "lost" objects that tumble out with each scene change and
 * land in the next country, chaining the rounds together. All are flat cartoon drawings for the
 * overlay; positions are in card pixels and sizes in pixels too ([s] is roughly a slime radius).
 */

// ---------------------------------------------------------------------------------------------
// The lost props
// ---------------------------------------------------------------------------------------------

/** What arrives in each scene, in scene order: the box comes back to Hạ Long, the shuttlecock goes to London, and so on. */
internal enum class LostProp { Box, Shuttle, Bearskin, Petals, Mic, Snowball, Baguette, EnterKey, Megaphone }

/** The prop that arrives in scene [scene]. */
internal fun incomingProp(scene: Int) = LostProp.entries[scene % LostProp.entries.size]

/** Where a prop waits in its new scene (card pixels) and how big it is drawn there, given who will use it. */
internal fun LostProp.rest(s: Stage, homes: Homes, poses: Array<SlimePose>, now: Float, on: Boolean): Pair<Offset, Float> {
    fun scale(z: Float) = if (on) s.viewScale(z) else 1f
    fun floor(x: Float, z: Float) = Offset(s.center + (x - s.center) * scale(z), s.eyeY + (s.groundY - s.eyeY) * scale(z)) to s.r * scale(z)
    fun head(k: Int) = poses[k].headTop(s.radius[k], s.groundY, now) to s.r * poses[k].viewScale
    return when (this) {
        LostProp.Box -> floor(homes.x[BO], homes.z[BO] + 0.6f).let { (p, k) -> p to k * Cast[BO].size }
        LostProp.Shuttle -> head(SODA).let { (p, k) -> p - Offset(0f, s.radius[SODA] * 0.55f * poses[SODA].viewScale) to k }
        LostProp.Bearskin -> head(BO).let { (p, k) -> p to k * Cast[BO].size }
        LostProp.Petals -> head(SODA).let { (p, k) -> p + Offset(0f, s.radius[SODA] * 0.6f * poses[SODA].viewScale) to k }
        LostProp.Mic -> floor(homes.x[CHANH] + s.r * 2.6f, homes.z[CHANH] - 0.6f)
        LostProp.Snowball -> floor(homes.x[BO] - s.r * 6f, homes.z[BO] + 0.7f)
        LostProp.Baguette -> floor(homes.x[BO] + s.radius[BO] * 1.25f, homes.z[BO] + 0.8f)
        LostProp.EnterKey -> floor(homes.x[CHANH] + s.radius[CHANH] * 1.6f, homes.z[CHANH] + 0.5f)
        LostProp.Megaphone -> floor(homes.x[BO] + s.radius[BO] * 1.3f, homes.z[BO] + 0.9f)
    }
}

/**
 * The lost prop between scenes: it flies out with the new country during the scene change, lands
 * where its next user will find it, and waits there (riding on a head if it landed on one) until
 * that country's opening picks it up.
 */
internal fun DrawScope.drawLostProps(rt: ArenaRuntime, sh: Show?, phase: Phase?, lt: Float, now: Float, s: Stage) {
    val on = rt.projected
    val bo = rt.poses[BO]
    when {
        sh == null -> {
            val prop = incomingProp(rt.current)
            val (at, k) = prop.rest(s, rt.homes(s), rt.poses, now, on)
            drawLostProp(prop, at, k, now)
        }

        phase == Phase.Vomit -> {
            val prop = incomingProp(sh.next)
            val start = Vomit.wordDone(sh.style) - 0.6f
            val e = progress(lt, start, 0.8f)
            if (e <= 0f) return
            val (to, k) = prop.rest(s, rt.nextHomes ?: rt.homes(s), rt.poses, now, on)
            if (e >= 1f) {
                drawLostProp(prop, to, k, now)
            } else {
                val from = Vomit.origin(sh.style, s, sh, bo.mouth(s.radius[BO], s.groundY, now), on)
                val at = quadBezier(from, Offset(lerp(from.x, to.x, 0.5f), kotlin.math.min(from.y, to.y) - s.h * 0.45f), to, easeInOutCubic(e))
                withTransform({ rotate((1f - e) * 540f, at) }) { drawLostProp(prop, at, k * (0.5f + 0.5f * e), now) }
            }
        }

        phase == Phase.Settle || phase == Phase.Wander -> {
            val prop = incomingProp(sh.next)
            val (at, k) = prop.rest(s, rt.nextHomes ?: rt.homes(s), rt.poses, now, on)
            drawLostProp(prop, at, k, now)
        }

        else -> {}
    }
}

/** Draws a lost prop resting at [at] (its bottom center, or a head top for worn ones). */
internal fun DrawScope.drawLostProp(prop: LostProp, at: Offset, s: Float, t: Float, wobble: Float = 0f) {
    when (prop) {
        LostProp.Box -> drawCardboardBox(at, s * 2.6f, s * 1.0f, 0f, t)
        LostProp.Shuttle -> drawShuttlecock(at + Offset(s * 0.1f, -s * 0.1f), s * 0.36f, 160f + wobble * 20f)
        LostProp.Bearskin -> drawBearskin(at + Offset(0f, s * 0.15f), s * 0.75f, wobble * 8f)
        LostProp.Petals -> repeat(9) { k ->
            val a = k * 2.4f
            drawSakuraPetal(at + Offset(cos(a) * s * (0.3f + 0.08f * k), s * (0.1f + 0.12f * k) + sin(a) * s * 0.15f), s * 0.12f, a * 57f + wobble * 30f)
        }

        LostProp.Mic -> drawHandMic(at + Offset(0f, -s * 0.3f), s * 0.55f, -20f + wobble * 10f)
        LostProp.Snowball -> drawSnowball(at + Offset(0f, -s * 0.9f), s * 0.9f, t * 0f)
        LostProp.Baguette -> drawBaguette(at + Offset(0f, -s * 0.12f), s * 1.6f, -8f + wobble * 6f)
        LostProp.EnterKey -> drawEnterKey(at + Offset(0f, -s * 0.22f), s * 0.42f, wobble * 10f)
        LostProp.Megaphone -> drawMegaphone(at + Offset(0f, -s * 0.28f), s * 0.6f, 200f + wobble * 10f)
    }
}

// ---------------------------------------------------------------------------------------------
// Drawings
// ---------------------------------------------------------------------------------------------

private val Outline = Color(0xFF3A2A20)

/** A cardboard box seen from the front, [w] wide and [h] tall, standing on [bottom]; [flat] squashes it. */
internal fun DrawScope.drawCardboardBox(bottom: Offset, w: Float, h: Float, flat: Float, t: Float) {
    val hh = h * (1f - 0.85f * flat)
    val ww = w * (1f + 0.35f * flat)
    val top = bottom.y - hh
    drawRoundRect(Color.Black, Offset(bottom.x - ww / 2f + w * 0.03f, top + w * 0.04f), Size(ww, hh), CornerRadius(w * 0.03f), alpha = 0.18f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD9A066), Color(0xFFB07A44)), top, bottom.y), Offset(bottom.x - ww / 2f, top), Size(ww, hh), CornerRadius(w * 0.03f))
    drawRect(Color(0xFFE9D7B0), Offset(bottom.x - ww * 0.08f, top), Size(ww * 0.16f, hh), alpha = 0.7f)
    if (flat < 0.5f) {
        // Open flaps, swaying.
        val sway = sin(t * 2f) * 4f
        rotate(-35f + sway, Offset(bottom.x - ww / 2f, top)) {
            drawRect(Color(0xFFC48A52), Offset(bottom.x - ww / 2f - ww * 0.32f, top - hh * 0.06f), Size(ww * 0.32f, hh * 0.12f))
        }
        rotate(35f - sway, Offset(bottom.x + ww / 2f, top)) {
            drawRect(Color(0xFFC48A52), Offset(bottom.x + ww / 2f, top - hh * 0.06f), Size(ww * 0.32f, hh * 0.12f))
        }
    }
    drawRoundRect(Outline, Offset(bottom.x - ww / 2f, top), Size(ww, hh), CornerRadius(w * 0.03f), alpha = 0.5f, style = Stroke(w * 0.012f))
    drawLine(Outline, Offset(bottom.x - ww * 0.3f, top + hh * 0.45f), Offset(bottom.x - ww * 0.18f, top + hh * 0.55f), w * 0.01f, alpha = 0.4f)
}

/** The royal guard's bearskin: a tall black fur drum with a gold chin strap. */
internal fun DrawScope.drawBearskin(bottom: Offset, s: Float, tilt: Float) {
    rotate(tilt, bottom) {
        val w = s * 1.1f
        val h = s * 1.7f
        val path = Path().apply {
            moveTo(bottom.x - w / 2f, bottom.y)
            cubicTo(bottom.x - w * 0.62f, bottom.y - h * 0.6f, bottom.x - w * 0.45f, bottom.y - h, bottom.x, bottom.y - h)
            cubicTo(bottom.x + w * 0.45f, bottom.y - h, bottom.x + w * 0.62f, bottom.y - h * 0.6f, bottom.x + w / 2f, bottom.y)
            close()
        }
        drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0D0D0D), Color(0xFF1C1C1C)), bottom.x - w / 2f, bottom.x + w / 2f))
        repeat(14) { k ->
            val x = bottom.x + (hash(k + 80) - 0.5f) * w * 0.8f
            val y = bottom.y - hash(k + 81) * h * 0.9f
            drawLine(Color(0xFF3A3A3A), Offset(x, y), Offset(x + s * 0.04f, y - s * 0.1f), s * 0.03f, StrokeCap.Round)
        }
        drawLine(Color(0xFFE8B84A), Offset(bottom.x - w * 0.45f, bottom.y - s * 0.05f), Offset(bottom.x + w * 0.45f, bottom.y - s * 0.05f), s * 0.07f, StrokeCap.Round)
    }
}

internal fun DrawScope.drawSakuraPetal(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        val path = Path().apply {
            moveTo(c.x, c.y + s)
            cubicTo(c.x - s, c.y + s * 0.3f, c.x - s * 0.7f, c.y - s, c.x - s * 0.15f, c.y - s * 0.75f)
            lineTo(c.x, c.y - s * 0.55f)
            lineTo(c.x + s * 0.15f, c.y - s * 0.75f)
            cubicTo(c.x + s * 0.7f, c.y - s, c.x + s, c.y + s * 0.3f, c.x, c.y + s)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(Color(0xFFFFD6E3), Color(0xFFFF9EBB)), c.y - s, c.y + s))
    }
}

/** A sparkly handheld microphone. */
internal fun DrawScope.drawHandMic(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        drawRoundRect(Color(0xFF1E1E24), Offset(c.x - s * 0.13f, c.y), Size(s * 0.26f, s * 1.1f), CornerRadius(s * 0.1f))
        drawRoundRect(Color(0xFFFF8FB3), Offset(c.x - s * 0.15f, c.y + s * 0.05f), Size(s * 0.3f, s * 0.1f), CornerRadius(s * 0.04f))
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFB0BEC5), Color(0xFF607D8B)), c + Offset(-s * 0.1f, -s * 0.2f), s * 0.4f), s * 0.3f, c + Offset(0f, -s * 0.15f))
        for (k in -2..2) drawLine(Color(0xFF546E7A), c + Offset(k * s * 0.1f, -s * 0.42f), c + Offset(k * s * 0.1f, s * 0.12f), s * 0.02f, alpha = 0.5f)
        drawSparkle(c + Offset(s * 0.2f, -s * 0.35f), s * 0.12f, Color.White, 0.9f)
    }
}

internal fun DrawScope.drawSnowball(c: Offset, radius: Float, roll: Float) {
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFE3ECF7), Color(0xFFB9CBE3)), c - Offset(radius * 0.35f, radius * 0.35f), radius * 1.4f), radius, c)
    rotate(roll, c) {
        repeat(6) { k ->
            val a = k * 1.1f
            drawCircle(Color(0xFFCFDCEE), radius * (0.08f + 0.05f * hash(k + 5)), c + Offset(cos(a), sin(a)) * (radius * 0.55f), alpha = 0.8f)
        }
    }
}

internal fun DrawScope.drawBaguette(c: Offset, length: Float, rotation: Float) {
    rotate(rotation, c) {
        val h = length * 0.16f
        drawRoundRect(Color.Black, Offset(c.x - length / 2f + h * 0.15f, c.y - h / 2f + h * 0.2f), Size(length, h), CornerRadius(h / 2f), alpha = 0.15f)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF6C27A), Color(0xFFC77D2E)), c.y - h / 2f, c.y + h / 2f), Offset(c.x - length / 2f, c.y - h / 2f), Size(length, h), CornerRadius(h / 2f))
        for (k in 0 until 5) {
            val x = c.x - length * 0.34f + k * length * 0.17f
            drawLine(Color(0xFFFFE9C2), Offset(x - h * 0.25f, c.y + h * 0.15f), Offset(x + h * 0.3f, c.y - h * 0.3f), h * 0.18f, StrokeCap.Round)
        }
    }
}

/** A keyboard's Enter keycap with its bent arrow. */
internal fun DrawScope.drawEnterKey(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        drawRoundRect(Color(0xFF8A939E), Offset(c.x - s, c.y - s * 0.55f), Size(s * 2f, s * 1.3f), CornerRadius(s * 0.25f))
        drawRoundRect(Color(0xFFEDF1F5), Offset(c.x - s * 0.88f, c.y - s * 0.68f), Size(s * 1.76f, s * 1.1f), CornerRadius(s * 0.22f))
        val arrow = Path().apply {
            moveTo(c.x + s * 0.45f, c.y - s * 0.4f)
            lineTo(c.x + s * 0.45f, c.y - s * 0.05f)
            lineTo(c.x - s * 0.4f, c.y - s * 0.05f)
        }
        drawPath(arrow, Color(0xFF263238), style = Stroke(s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(Path().apply {
            moveTo(c.x - s * 0.55f, c.y - s * 0.05f)
            lineTo(c.x - s * 0.3f, c.y - s * 0.22f)
            lineTo(c.x - s * 0.3f, c.y + s * 0.12f)
            close()
        }, Color(0xFF263238))
    }
}

/** A captain's megaphone, mouth toward [rotation] degrees (0 = right). */
internal fun DrawScope.drawMegaphone(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        val cone = Path().apply {
            moveTo(c.x - s * 0.5f, c.y - s * 0.12f)
            lineTo(c.x + s * 0.6f, c.y - s * 0.45f)
            lineTo(c.x + s * 0.6f, c.y + s * 0.45f)
            lineTo(c.x - s * 0.5f, c.y + s * 0.12f)
            close()
        }
        drawPath(cone, Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD0D7DE)), c.y - s * 0.45f, c.y + s * 0.45f))
        drawOval(Color(0xFFE63946), Offset(c.x + s * 0.5f, c.y - s * 0.47f), Size(s * 0.2f, s * 0.94f))
        drawRoundRect(Color(0xFF263238), Offset(c.x - s * 0.62f, c.y - s * 0.15f), Size(s * 0.18f, s * 0.3f), CornerRadius(s * 0.05f))
        drawRoundRect(Color(0xFF263238), Offset(c.x - s * 0.35f, c.y + s * 0.1f), Size(s * 0.12f, s * 0.35f), CornerRadius(s * 0.04f))
    }
}

/** A teacup on its saucer, tipped by [tilt] degrees; [tea] 0..1 how full. */
internal fun DrawScope.drawTeacup(c: Offset, s: Float, tilt: Float = 0f, tea: Float = 1f) {
    rotate(tilt, c) {
        drawOval(Color(0xFFF5F5F5), Offset(c.x - s * 0.55f, c.y - s * 0.06f), Size(s * 1.1f, s * 0.16f))
        val cup = Path().apply {
            moveTo(c.x - s * 0.38f, c.y - s * 0.55f)
            lineTo(c.x + s * 0.38f, c.y - s * 0.55f)
            cubicTo(c.x + s * 0.38f, c.y - s * 0.15f, c.x + s * 0.2f, c.y, c.x, c.y)
            cubicTo(c.x - s * 0.2f, c.y, c.x - s * 0.38f, c.y - s * 0.15f, c.x - s * 0.38f, c.y - s * 0.55f)
            close()
        }
        drawPath(cup, Brush.verticalGradient(listOf(Color.White, Color(0xFFE0E6EE)), c.y - s * 0.55f, c.y))
        drawArc(Color(0xFFF5F5F5), -70f, 160f, false, Offset(c.x + s * 0.25f, c.y - s * 0.48f), Size(s * 0.3f, s * 0.3f), style = Stroke(s * 0.07f))
        drawRect(Color(0xFF3F51B5), Offset(c.x - s * 0.38f, c.y - s * 0.55f), Size(s * 0.76f, s * 0.06f))
        if (tea > 0f) drawOval(Color(0xFF9C6B3E), Offset(c.x - s * 0.34f, c.y - s * 0.6f), Size(s * 0.68f, s * 0.1f), alpha = tea)
    }
}

/** A newspaper held open, with [headline] printed big. */
internal fun DrawScope.drawNewspaper(c: Offset, s: Float, headline: TextLayoutResult?, rotation: Float = -6f) {
    rotate(rotation, c) {
        val w = s * 2.4f
        val h = s * 1.6f
        drawRect(Color.Black, Offset(c.x - w / 2f + s * 0.05f, c.y - h / 2f + s * 0.06f), Size(w, h), alpha = 0.15f)
        drawRect(Color(0xFFF4F1E8), Offset(c.x - w / 2f, c.y - h / 2f), Size(w, h))
        drawRect(Color(0xFF2B2B2B), Offset(c.x - w * 0.45f, c.y - h * 0.45f), Size(w * 0.9f, h * 0.08f))
        if (headline != null) {
            val k = min(w * 0.9f / headline.size.width, h * 0.32f / headline.size.height)
            withTransform({ scale(k, k, Offset(c.x, c.y - h * 0.12f)) }) {
                drawText(headline, topLeft = Offset(c.x - headline.size.width / 2f, c.y - h * 0.12f - headline.size.height / 2f))
            }
        }
        for (i in 0 until 4) drawLine(Color(0xFF9E9E9E), Offset(c.x - w * 0.42f, c.y + h * (0.12f + i * 0.08f)), Offset(c.x + w * (0.1f + 0.3f * hash(i + 20)), c.y + h * (0.12f + i * 0.08f)), s * 0.04f)
        drawRect(Color(0xFFB0BEC5), Offset(c.x + w * 0.18f, c.y + h * 0.1f), Size(w * 0.24f, h * 0.28f))
    }
}

internal fun DrawScope.drawMagnifier(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        drawLine(Color(0xFF6D4C41), c + Offset(s * 0.4f, s * 0.4f), c + Offset(s * 1.0f, s * 1.0f), s * 0.18f, StrokeCap.Round)
        drawCircle(Color(0xFFDDEEFF), s * 0.48f, c, alpha = 0.4f)
        drawCircle(Color(0xFFC9A227), s * 0.5f, c, style = Stroke(s * 0.1f))
        drawArc(Color.White, 200f, 60f, false, c - Offset(s * 0.34f, s * 0.34f), Size(s * 0.68f, s * 0.68f), alpha = 0.8f, style = Stroke(s * 0.06f, cap = StrokeCap.Round))
    }
}

/** Tiny wet footprints from [from] to [to]. */
internal fun DrawScope.drawFootprints(from: Offset, to: Offset, n: Int, s: Float, shown: Float, alpha: Float = 1f) {
    for (i in 0 until n) {
        val u = i / max(1f, n - 1f)
        if (u > shown) break
        val p = lerp(from, to, u) + Offset(0f, (if (i % 2 == 0) -1f else 1f) * s * 0.2f)
        drawOval(Color(0xFF8D6E63), p - Offset(s * 0.18f, s * 0.1f), Size(s * 0.36f, s * 0.2f), alpha = 0.55f * alpha)
    }
}

/** The sumo ring: a straw ellipse on the floor around [c]. */
internal fun DrawScope.drawDohyo(c: Offset, rx: Float, ry: Float) {
    drawOval(Color(0xFFE9D9A6), c - Offset(rx * 1.08f, ry * 1.08f), Size(rx * 2.16f, ry * 2.16f), alpha = 0.5f)
    drawOval(Color(0xFFB8955A), c - Offset(rx, ry), Size(rx * 2f, ry * 2f), style = Stroke(ry * 0.14f))
    drawOval(Color(0xFFFFF3D6), c - Offset(rx, ry), Size(rx * 2f, ry * 2f), alpha = 0.5f, style = Stroke(ry * 0.04f))
}

/** Matcha bowl with the whisk. */
internal fun DrawScope.drawTeaBowl(c: Offset, s: Float, splash: Float = 0f) {
    drawArc(Brush.verticalGradient(listOf(Color(0xFF8D6E63), Color(0xFF4E342E)), c.y - s * 0.3f, c.y + s * 0.3f), 0f, 180f, true, Offset(c.x - s * 0.5f, c.y - s * 0.35f), Size(s, s * 0.7f))
    drawOval(Color(0xFF7CB342), Offset(c.x - s * 0.44f, c.y - s * 0.1f), Size(s * 0.88f, s * 0.2f))
    if (splash > 0f && splash < 1f) repeat(7) { k ->
        val a = -PI_F * (0.15f + 0.7f * k / 6f)
        drawCircle(Color(0xFF8BC34A), s * 0.08f * (1f - splash), c + Offset(cos(a), sin(a)) * (s * (0.3f + 1.2f * splash)), alpha = 1f - splash)
    }
}

internal fun DrawScope.drawSmokePuff(c: Offset, r: Float, e: Float) {
    if (e <= 0f || e >= 1f) return
    repeat(7) { k ->
        val a = k * TAU / 7 + 0.3f
        val d = r * (0.3f + 0.9f * easeOutCubic(e))
        drawCircle(Color(0xFFECEFF1), r * (0.55f - 0.25f * e), c + Offset(cos(a), sin(a)) * d, alpha = 0.9f * (1f - e))
    }
}

/** An open book; [flap] lifts a page. */
internal fun DrawScope.drawBook(c: Offset, s: Float, flap: Float = 0f) {
    drawRoundRect(Color(0xFF6A1B1A), Offset(c.x - s * 0.62f, c.y - s * 0.42f), Size(s * 1.24f, s * 0.84f), CornerRadius(s * 0.05f))
    drawRect(Color(0xFFFFF8E1), Offset(c.x - s * 0.56f, c.y - s * 0.38f), Size(s * 0.54f, s * 0.74f))
    drawRect(Color(0xFFFFF3D6), Offset(c.x + s * 0.02f, c.y - s * 0.38f), Size(s * 0.54f, s * 0.74f))
    for (i in 0 until 5) {
        val y = c.y - s * 0.28f + i * s * 0.12f
        drawLine(Color(0xFFBCAAA4), Offset(c.x - s * 0.5f, y), Offset(c.x - s * 0.08f, y), s * 0.025f)
        drawLine(Color(0xFFBCAAA4), Offset(c.x + s * 0.08f, y), Offset(c.x + s * 0.5f, y), s * 0.025f)
    }
    if (flap > 0f) {
        withTransform({ scale(1f - flap, 1f, Offset(c.x, c.y)) }) {
            drawRect(Color.White, Offset(c.x + s * 0.02f, c.y - s * 0.38f), Size(s * 0.54f, s * 0.74f))
        }
    }
}

/** A loose page fluttering up. */
internal fun DrawScope.drawPage(c: Offset, s: Float, rotation: Float, alpha: Float) {
    rotate(rotation, c) {
        drawRect(Color(0xFFFFF8E1), Offset(c.x - s * 0.3f, c.y - s * 0.4f), Size(s * 0.6f, s * 0.8f), alpha = alpha)
        for (i in 0 until 4) drawLine(Color(0xFFBCAAA4), Offset(c.x - s * 0.22f, c.y - s * 0.25f + i * s * 0.14f), Offset(c.x + s * 0.2f, c.y - s * 0.25f + i * s * 0.14f), s * 0.03f, alpha = alpha)
    }
}

/** A stage spotlight cone from [from] down onto [to]. */
internal fun DrawScope.drawSpotlight(from: Offset, to: Offset, width: Float, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    val dir = to - from
    val len = dir.getDistance().coerceAtLeast(1f)
    val n = Offset(-dir.y / len, dir.x / len)
    val path = Path().apply {
        moveTo(from.x + n.x * width * 0.1f, from.y + n.y * width * 0.1f)
        lineTo(to.x + n.x * width, to.y + n.y * width)
        lineTo(to.x - n.x * width, to.y - n.y * width)
        lineTo(from.x - n.x * width * 0.1f, from.y - n.y * width * 0.1f)
        close()
    }
    drawPath(path, Brush.linearGradient(listOf(color.copy(alpha = 0.55f * alpha), color.copy(alpha = 0.08f * alpha)), from, to))
    drawOval(color, to - Offset(width * 1.1f, width * 0.3f), Size(width * 2.2f, width * 0.6f), alpha = 0.3f * alpha)
}

/** A snowman standing on [bottom]: [head] scales its head (the boss keeps changing her mind). */
internal fun DrawScope.drawSnowman(bottom: Offset, s: Float, head: Float = 1f, built: Float = 1f) {
    val b = s * 0.9f * min(1f, built * 2f)
    if (b <= 0f) return
    drawSnowball(bottom - Offset(0f, b), b, 0f)
    if (built > 0.5f) {
        val m = s * 0.62f * min(1f, (built - 0.5f) * 4f)
        drawSnowball(bottom - Offset(0f, b * 2f + m * 0.85f), m, 0f)
        if (built > 0.75f) {
            val hr = s * 0.42f * head
            val hc = bottom - Offset(0f, b * 2f + m * 1.7f + hr * 0.85f)
            drawSnowball(hc, hr, 0f)
            drawCircle(Color(0xFF263238), hr * 0.1f, hc + Offset(-hr * 0.32f, -hr * 0.15f))
            drawCircle(Color(0xFF263238), hr * 0.1f, hc + Offset(hr * 0.32f, -hr * 0.15f))
            drawPath(Path().apply {
                moveTo(hc.x, hc.y)
                lineTo(hc.x + hr * 0.8f, hc.y + hr * 0.12f)
                lineTo(hc.x, hc.y + hr * 0.22f)
                close()
            }, Color(0xFFFF8F3D))
            for (k in 0 until 3) drawCircle(Color(0xFF263238), m * 0.08f, bottom - Offset(0f, b * 2f + m * (0.4f + 0.4f * k)))
        }
    }
}

/** A matryoshka doll in [dress] colors, standing on [bottom]. */
internal fun DrawScope.drawMatryoshka(bottom: Offset, s: Float, dress: Color, rotation: Float = 0f) {
    rotate(rotation, bottom) {
        val body = Path().apply {
            moveTo(bottom.x - s * 0.45f, bottom.y)
            cubicTo(bottom.x - s * 0.6f, bottom.y - s * 0.7f, bottom.x - s * 0.42f, bottom.y - s * 1.5f, bottom.x, bottom.y - s * 1.55f)
            cubicTo(bottom.x + s * 0.42f, bottom.y - s * 1.5f, bottom.x + s * 0.6f, bottom.y - s * 0.7f, bottom.x + s * 0.45f, bottom.y)
            close()
        }
        drawPath(body, dress)
        drawOval(Color(0xFFFFF3E0), Offset(bottom.x - s * 0.25f, bottom.y - s * 1.3f), Size(s * 0.5f, s * 0.42f))
        drawCircle(Color(0xFFFF8A80), s * 0.06f, Offset(bottom.x - s * 0.13f, bottom.y - s * 1.05f))
        drawCircle(Color(0xFFFF8A80), s * 0.06f, Offset(bottom.x + s * 0.13f, bottom.y - s * 1.05f))
        drawCircle(Color.Black, s * 0.035f, Offset(bottom.x - s * 0.09f, bottom.y - s * 1.14f))
        drawCircle(Color.Black, s * 0.035f, Offset(bottom.x + s * 0.09f, bottom.y - s * 1.14f))
        drawOval(Color(0xFFFFF8E1), Offset(bottom.x - s * 0.28f, bottom.y - s * 0.82f), Size(s * 0.56f, s * 0.7f))
        drawCircle(Color(0xFFFFC107), s * 0.12f, Offset(bottom.x, bottom.y - s * 0.48f))
    }
}

/** Snow caked over a face, cracking as [crack] rises, falling off at 1. */
internal fun DrawScope.drawSnowMask(c: Offset, r: Float, crack: Float) {
    val fall = progress(crack, 0.75f, 0.25f)
    val drop = fall * r * 1.5f
    drawOval(Color.White, Offset(c.x - r, c.y - r * 0.7f + drop), Size(r * 2f, r * 1.4f), alpha = 1f - fall)
    if (crack > 0.2f) {
        val n = (crack * 6).toInt()
        for (i in 0 until n) {
            val a = hash(i + 300) * TAU
            drawLine(Color(0xFF90A4AE), c + Offset(0f, drop), c + Offset(cos(a) * r * 0.9f, sin(a) * r * 0.6f + drop), r * 0.04f, alpha = 1f - fall)
        }
    }
}

/** The painter's easel with its canvas: [snowman], [tower] (0..1 painted) and [lean] of the tower. */
internal fun DrawScope.drawEasel(bottom: Offset, s: Float, snowman: Float, tower: Float, lean: Float) {
    val legs = Color(0xFF8D6E63)
    drawLine(legs, bottom + Offset(-s * 0.6f, 0f), bottom + Offset(0f, -s * 2.4f), s * 0.08f, StrokeCap.Round)
    drawLine(legs, bottom + Offset(s * 0.6f, 0f), bottom + Offset(0f, -s * 2.4f), s * 0.08f, StrokeCap.Round)
    drawLine(legs, bottom + Offset(0f, -s * 2.2f), bottom + Offset(0f, s * 0.02f), s * 0.07f, StrokeCap.Round)
    val tl = bottom + Offset(-s * 0.75f, -s * 2.2f)
    drawRect(Color.Black, tl + Offset(s * 0.05f, s * 0.06f), Size(s * 1.5f, s * 1.1f), alpha = 0.15f)
    drawRect(Color(0xFFFFFDF5), tl, Size(s * 1.5f, s * 1.1f))
    drawRect(Color(0xFF6D4C41), tl, Size(s * 1.5f, s * 1.1f), style = Stroke(s * 0.05f))
    val base = tl + Offset(s * 0.75f, s * 1.0f)
    if (tower > 0f) {
        rotate(lean * 14f, base) {
            val h = s * 0.85f * tower
            drawLine(Color(0xFF5D4037), base + Offset(-s * 0.28f, 0f), base + Offset(0f, -h), s * 0.05f)
            drawLine(Color(0xFF5D4037), base + Offset(s * 0.28f, 0f), base + Offset(0f, -h), s * 0.05f)
            drawLine(Color(0xFF5D4037), base + Offset(-s * 0.16f, -h * 0.35f), base + Offset(s * 0.16f, -h * 0.35f), s * 0.04f)
            drawLine(Color(0xFF5D4037), base + Offset(-s * 0.08f, -h * 0.65f), base + Offset(s * 0.08f, -h * 0.65f), s * 0.03f)
        }
    }
    if (lean > 0f) drawLine(Color(0xFFE63946), tl + Offset(s * 0.2f, s * 0.2f), tl + Offset(s * 1.3f, s * 0.9f), s * 0.06f * lean, StrokeCap.Round, alpha = 0.8f)
    if (snowman > 0f) {
        val c = tl + Offset(s * 0.28f, s * 0.82f)
        drawCircle(Color(0xFFDCE6F2), s * 0.13f * snowman, c)
        if (snowman > 0.5f) drawCircle(Color(0xFFDCE6F2), s * 0.09f, c - Offset(0f, s * 0.2f))
    }
}

internal fun DrawScope.drawPaintSplat(c: Offset, r: Float, color: Color, seed: Int, alpha: Float = 1f) {
    drawCircle(color, r, c, alpha = alpha)
    repeat(6) { k ->
        val a = hash(seed * 7 + k) * TAU
        val d = r * (1.1f + 0.5f * hash(seed * 7 + k + 3))
        drawCircle(color, r * (0.18f + 0.15f * hash(seed + k)), c + Offset(cos(a), sin(a)) * d, alpha = alpha)
    }
}

/** A telegraph key made of the Enter keycap on a little wooden base. */
internal fun DrawScope.drawTelegraphKey(c: Offset, s: Float, pressed: Boolean) {
    drawRoundRect(Color(0xFF6D4C41), Offset(c.x - s * 1.2f, c.y - s * 0.12f), Size(s * 2.4f, s * 0.3f), CornerRadius(s * 0.08f))
    drawEnterKey(c + Offset(0f, -s * (if (pressed) 0.35f else 0.55f)), s * 0.5f, 0f)
}

/** A round hatch in the deck, opened to [open]. */
internal fun DrawScope.drawHatch(c: Offset, rx: Float, open: Float) {
    drawOval(Color(0xFF263238), c - Offset(rx, rx * 0.3f), Size(rx * 2f, rx * 0.6f))
    drawOval(Color(0xFF1565C0), c - Offset(rx * 0.85f, rx * 0.24f), Size(rx * 1.7f, rx * 0.48f), alpha = 0.8f)
    rotate(-110f * open, c + Offset(-rx, 0f)) {
        drawOval(Color(0xFF78909C), c - Offset(rx, rx * 0.3f), Size(rx * 2f, rx * 0.6f))
        drawOval(Color(0xFF546E7A), c - Offset(rx, rx * 0.3f), Size(rx * 2f, rx * 0.6f), style = Stroke(rx * 0.06f))
    }
}

internal fun DrawScope.drawSplash(c: Offset, r: Float, e: Float) {
    if (e <= 0f || e >= 1f) return
    repeat(12) { k ->
        val a = -PI_F * (0.1f + 0.8f * hash(k + 600))
        val d = r * (0.4f + 1.4f * e)
        val p = c + Offset(cos(a) * d, sin(a) * d + e * e * r * 1.5f)
        drawCircle(Color(0xFF90CAF9), r * 0.1f * (1f - e), p, alpha = 0.9f * (1f - e))
    }
}

/** A ball of yarn of [color], unwinding toward [tail] if given. */
internal fun DrawScope.drawYarn(c: Offset, r: Float, color: Color, rotation: Float) {
    drawCircle(color, r, c)
    rotate(rotation, c) {
        for (k in 0 until 4) {
            drawArc(Color.White, 200f + k * 20f, 120f, false, c - Offset(r * (0.95f - k * 0.18f), r * (0.6f - k * 0.1f)), Size(r * (1.9f - k * 0.36f), r * (1.2f - k * 0.2f)), alpha = 0.45f, style = Stroke(r * 0.07f))
        }
    }
}

internal fun DrawScope.drawLaserDot(c: Offset, r: Float, t: Float) {
    drawGlow(c, r * 4f, Color(0xFFFF1744), 0.7f)
    drawCircle(Color(0xFFFF1744), r * (1f + 0.1f * sin(t * 30f)), c)
    drawCircle(Color.White, r * 0.4f, c, alpha = 0.8f)
}

internal fun DrawScope.drawLaserPointer(c: Offset, s: Float, rotation: Float) {
    rotate(rotation, c) {
        drawRoundRect(Color(0xFF37474F), Offset(c.x - s * 0.6f, c.y - s * 0.1f), Size(s * 1.2f, s * 0.2f), CornerRadius(s * 0.1f))
        drawCircle(Color(0xFFFF1744), s * 0.06f, Offset(c.x + s * 0.6f, c.y))
    }
}

/** A propeller spinning at [c] (Bơ's pretend plane). */
internal fun DrawScope.drawPropeller(c: Offset, s: Float, t: Float) {
    val a = t * 40f
    for (k in 0 until 2) {
        val ang = a + k * PI_F
        drawLine(Color(0xFF795548), c, c + Offset(cos(ang) * s, sin(ang) * s * 0.3f), s * 0.16f, StrokeCap.Round, alpha = 0.75f)
    }
    drawCircle(Color(0xFFE63946), s * 0.14f, c)
}

/** The radar's sweeping beam from [origin] at [angle] radians. */
internal fun DrawScope.drawRadarBeam(origin: Offset, angle: Float, length: Float, alpha: Float) {
    val spread = 0.12f
    val path = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(origin.x + cos(angle - spread) * length, origin.y + sin(angle - spread) * length)
        lineTo(origin.x + cos(angle + spread) * length, origin.y + sin(angle + spread) * length)
        close()
    }
    drawPath(path, Brush.radialGradient(listOf(Color(0xAA7DF9FF), Color.Transparent), origin, length), alpha = alpha)
}

/** Glitch bars and colour fringes across the card. */
internal fun DrawScope.drawGlitch(t: Float, strength: Float) {
    if (strength <= 0f) return
    val slot = (t * 24f).toInt()
    repeat(6) { k ->
        val y = hash(slot * 7 + k) * size.height
        val h = size.height * (0.01f + 0.04f * hash(slot * 7 + k + 3))
        val dx = (hash(slot * 7 + k + 5) - 0.5f) * size.width * 0.06f * strength
        drawRect(Color(0xFFFF1744), Offset(dx, y), Size(size.width, h), alpha = 0.18f * strength)
        drawRect(Color(0xFF00E5FF), Offset(-dx, y + h * 0.4f), Size(size.width, h), alpha = 0.18f * strength)
    }
}

/** A string of 0s and 1s flying like a dart from [from] to [to]. */
internal fun DrawScope.drawDigitDart(from: Offset, to: Offset, e: Float, zero: TextLayoutResult, one: TextLayoutResult) {
    if (e <= 0f || e >= 1f) return
    for (i in 0 until 5) {
        val u = (e - i * 0.05f).coerceIn(0f, 1f)
        val p = lerp(from, to, u)
        val g = if ((i + (e * 10).toInt()) % 2 == 0) zero else one
        drawText(g, topLeft = p - Offset(g.size.width / 2f, g.size.height / 2f), alpha = 1f - i * 0.15f)
    }
}

/** A slime turned to coloured pixels: squares spread by [scatter] (0 whole, 1 blown apart). */
internal fun DrawScope.drawPixels(c: Offset, w: Float, h: Float, color: Color, scatter: Float, seed: Int) {
    val n = 7
    val cell = w / n
    for (i in 0 until n) for (j in 0 until n) {
        val u = (i + 0.5f) / n - 0.5f
        val v = (j + 0.5f) / n
        if (u * u * 4f + (v - 0.55f) * (v - 0.55f) * 3.2f > 1f) continue
        val id = seed + i * 31 + j * 7
        val off = Offset((hash(id) - 0.5f) * w * 1.6f, (hash(id + 1) - 0.8f) * h * 1.2f) * scatter
        val p = Offset(c.x + u * w, c.y - h + v * h) + off
        drawRect(color, p - Offset(cell / 2f, cell / 2f), Size(cell * 0.9f, cell * 0.9f), alpha = 0.9f)
    }
}

/** A dust cloud with [n] small squares (pixel cloud) or round puffs. */
internal fun DrawScope.drawCloudFlavor(scene: Int, center: Offset, r: Float, t: Float, alpha: Float) {
    when (scene) {
        // Paris: blobs of paint flying out of the scrap.
        5 -> repeat(8) { k ->
            val a = k * TAU / 8 + t * 1.5f
            drawPaintSplat(center + Offset(cos(a) * r * 1.6f, sin(a) * r * 0.9f), r * 0.12f, listOf(Color(0xFFE63946), Color(0xFFFFC300), Color(0xFF2A9D8F), Color(0xFF8E44AD))[k % 4], k, alpha)
        }
        // Binary: square pixels.
        6 -> repeat(14) { k ->
            val a = hash(k + 900) * TAU + t
            val d = r * (1.2f + 0.5f * hash(k + 901))
            drawRect(Color(0xFF39FF88), center + Offset(cos(a) * d, sin(a) * d * 0.6f), Size(r * 0.14f, r * 0.14f), alpha = alpha * 0.8f)
        }
        // Russia: snow flying off it.
        4 -> repeat(12) { k ->
            val a = hash(k + 910) * TAU + t * 2f
            drawCircle(Color.White, r * 0.06f, center + Offset(cos(a) * r * 1.5f, sin(a) * r), alpha = alpha)
        }
        // Cats: tufts of fur.
        8 -> repeat(10) { k ->
            val a = hash(k + 920) * TAU + t * 1.2f
            val p = center + Offset(cos(a) * r * 1.5f, sin(a) * r)
            for (j in -1..1) drawLine(Color(0xFFF4A261), p, p + Offset(j * r * 0.06f, -r * 0.16f), r * 0.03f, StrokeCap.Round, alpha = alpha)
        }
        else -> {}
    }
}
