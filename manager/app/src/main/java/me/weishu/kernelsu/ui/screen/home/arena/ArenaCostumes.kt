package me.weishu.kernelsu.ui.screen.home.arena

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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sin
import androidx.compose.ui.graphics.lerp as lerpColor

/** What the slimes wear in each country. */
internal enum class Costume { NonLa, TopHat, Hachimaki, Gat, Ushanka, Beret, Hacker, Radio, Cat }

private val Gold = Color(0xFFFCC419)
private val GoldDeep = Color(0xFFE09A0B)

/** Parts that sit behind the body (cat ears). */
internal fun DrawScope.drawHatBack(c: Costume, g: SlimeGeo, skin: SlimeSkin) {
    if (c != Costume.Cat) return
    for (side in intArrayOf(-1, 1)) {
        val baseOut = Offset(g.cx + side * g.w * 0.42f, g.top + g.h * 0.24f)
        val baseIn = Offset(g.cx + side * g.w * 0.06f, g.top + g.h * 0.03f)
        val tip = Offset(g.cx + side * g.w * 0.36f, g.top - g.h * 0.24f)
        val ear = Path().apply {
            moveTo(baseOut.x, baseOut.y)
            quadraticTo(tip.x + side * g.w * 0.04f, tip.y + g.h * 0.12f, tip.x, tip.y)
            quadraticTo(tip.x - side * g.w * 0.06f, tip.y + g.h * 0.08f, baseIn.x, baseIn.y)
            close()
        }
        drawPath(ear, Brush.verticalGradient(listOf(lerpColor(skin.body, Color.White, 0.4f), skin.body), tip.y, baseOut.y))
        drawPath(ear, skin.deep, alpha = 0.75f, style = Stroke(g.r * 0.04f, join = StrokeJoin.Round))
        val inner = Path().apply {
            val o = lerp(baseOut, tip, 0.2f)
            val i = lerp(baseIn, tip, 0.25f)
            val t = lerp(tip, lerp(baseOut, baseIn, 0.5f), 0.22f)
            moveTo(o.x, o.y)
            quadraticTo(t.x + side * g.w * 0.02f, t.y + g.h * 0.06f, t.x, t.y)
            lineTo(i.x, i.y)
            close()
        }
        drawPath(inner, Color(0xFFFFA8C5), alpha = 0.9f)
    }
}

/** Clothes on the lower body; called inside the body clip so they wrap the jelly. */
internal fun DrawScope.drawOutfit(c: Costume, g: SlimeGeo, skin: SlimeSkin) {
    val r = g.r
    val y0 = g.by - g.h * 0.2f
    val left = g.cx - g.w * 0.6f
    val width = g.w * 1.2f
    when (c) {
        Costume.NonLa -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFF03E3E), Color(0xFFA61E4D)), y0, g.by), Offset(left, y0), Size(width, g.by - y0), alpha = 0.92f)
            repeat(5) { i ->
                val p = Offset(g.cx + (hash(i + 3100) - 0.5f) * g.w * 0.6f, y0 + g.h * (0.07f + 0.09f * hash(i + 3110)))
                repeat(5) { k ->
                    val a = k * TAU / 5
                    drawCircle(Gold, r * 0.028f, p + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * (r * 0.045f), alpha = 0.9f)
                }
                drawCircle(Color(0xFFFFF3BF), r * 0.02f, p)
            }
            val closure = Path().apply {
                moveTo(g.cx + g.w * 0.03f, y0)
                quadraticTo(g.cx + g.w * 0.08f, y0 + g.h * 0.1f, g.cx + g.w * 0.3f, y0 + g.h * 0.12f)
            }
            drawPath(closure, Gold, style = Stroke(r * 0.025f, cap = StrokeCap.Round))
            drawRoundRect(Gold, Offset(g.cx - g.w * 0.11f, y0 - g.h * 0.012f), Size(g.w * 0.22f, g.h * 0.045f), CornerRadius(g.h * 0.02f))
            drawRoundRect(GoldDeep, Offset(g.cx - g.w * 0.11f, y0 - g.h * 0.012f), Size(g.w * 0.22f, g.h * 0.045f), CornerRadius(g.h * 0.02f), style = Stroke(r * 0.015f))
        }

        Costume.TopHat -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF364FC7), Color(0xFF1C2A6B)), y0, g.by), Offset(left, y0), Size(width, g.by - y0), alpha = 0.9f)
            val shirt = Path().apply {
                moveTo(g.cx - g.w * 0.16f, y0)
                lineTo(g.cx, y0 + g.h * 0.2f)
                lineTo(g.cx + g.w * 0.16f, y0)
                close()
            }
            drawPath(shirt, Color.White, alpha = 0.95f)
            drawArc(Gold, 20f, 120f, false, Offset(g.cx - g.w * 0.05f, y0 + g.h * 0.04f), Size(g.w * 0.4f, g.h * 0.16f), style = Stroke(r * 0.02f))
            drawCircle(Gold, r * 0.04f, Offset(g.cx + g.w * 0.33f, y0 + g.h * 0.13f))
            for (sgn in intArrayOf(-1, 1)) {
                val wing = Path().apply {
                    moveTo(g.cx, y0 + g.h * 0.02f)
                    lineTo(g.cx + sgn * g.w * 0.13f, y0 - g.h * 0.04f)
                    quadraticTo(g.cx + sgn * g.w * 0.15f, y0 + g.h * 0.02f, g.cx + sgn * g.w * 0.13f, y0 + g.h * 0.08f)
                    close()
                }
                drawPath(wing, Color(0xFFE03131))
                drawPath(wing, Color(0xFF9B1C1C), style = Stroke(r * 0.015f, join = StrokeJoin.Round))
            }
            drawCircle(Color(0xFFB02525), r * 0.035f, Offset(g.cx, y0 + g.h * 0.02f))
        }

        Costume.Hachimaki -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF4263EB), Color(0xFF253A9E)), y0, g.by), Offset(left, y0), Size(width, g.by - y0), alpha = 0.92f)
            repeat(4) { col ->
                repeat(2) { row ->
                    val c0 = Offset(left + (col + 0.5f * row) * g.w * 0.32f, y0 + g.h * (0.14f + 0.08f * row))
                    for (k in 1..2) {
                        drawArc(Color.White, 180f, 180f, false, c0 - Offset(r * 0.05f * k, r * 0.05f * k), Size(r * 0.1f * k, r * 0.1f * k), alpha = 0.3f, style = Stroke(r * 0.012f))
                    }
                }
            }
            drawLine(Color.White, Offset(g.cx + g.w * 0.28f, y0), Offset(g.cx - g.w * 0.02f, y0 + g.h * 0.12f), r * 0.07f, StrokeCap.Round)
            drawLine(Color(0xFFDEE2E6), Offset(g.cx - g.w * 0.28f, y0), Offset(g.cx + g.w * 0.02f, y0 + g.h * 0.12f), r * 0.07f, StrokeCap.Round)
            drawRect(Color(0xFFE03131), Offset(left, g.by - g.h * 0.07f), Size(width, g.h * 0.06f))
            drawLine(Gold, Offset(left, g.by - g.h * 0.04f), Offset(left + width, g.by - g.h * 0.04f), r * 0.012f)
            hachimakiBand(g)
        }

        Costume.Gat -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFFFD6E7), Color(0xFFFFA8C5)), y0, g.by), Offset(left, y0), Size(width, g.by - y0), alpha = 0.92f)
            drawLine(Color.White, Offset(g.cx - g.w * 0.3f, y0), Offset(g.cx + g.w * 0.04f, y0 + g.h * 0.16f), r * 0.06f, StrokeCap.Round)
            drawLine(Color.White, Offset(g.cx + g.w * 0.3f, y0), Offset(g.cx - g.w * 0.04f, y0 + g.h * 0.16f), r * 0.06f, StrokeCap.Round)
            val knot = Offset(g.cx + g.w * 0.08f, y0 + g.h * 0.12f)
            val ribbon = Color(0xFF9C36B5)
            rotate(-25f, knot) { drawOval(ribbon, knot - Offset(r * 0.22f, r * 0.06f), Size(r * 0.22f, r * 0.12f)) }
            rotate(25f, knot) { drawOval(ribbon, knot - Offset(0f, r * 0.06f), Size(r * 0.22f, r * 0.12f)) }
            drawLine(ribbon, knot, knot + Offset(r * 0.02f, g.h * 0.2f), r * 0.05f, StrokeCap.Round)
            drawLine(ribbon, knot, knot + Offset(r * 0.12f, g.h * 0.16f), r * 0.05f, StrokeCap.Round)
            drawCircle(lerpColor(ribbon, Color.Black, 0.2f), r * 0.04f, knot)
        }

        Costume.Ushanka -> {
            val band = Path().apply {
                moveTo(left, g.by - g.h * 0.2f)
                quadraticTo(g.cx, g.by - g.h * 0.15f, left + width, g.by - g.h * 0.2f)
                lineTo(left + width, g.by - g.h * 0.08f)
                quadraticTo(g.cx, g.by - g.h * 0.03f, left, g.by - g.h * 0.08f)
                close()
            }
            drawPath(band, Brush.verticalGradient(listOf(Color(0xFFFA5252), Color(0xFFC92A2A)), g.by - g.h * 0.2f, g.by - g.h * 0.03f))
            repeat(9) { i ->
                val x = left + (i + 0.5f) * width / 9f
                val y = g.by - g.h * 0.13f + (x - g.cx) * (x - g.cx) / (g.w * g.w) * g.h * 0.08f
                drawLine(Color.White, Offset(x - r * 0.03f, y - r * 0.03f), Offset(x, y + r * 0.01f), r * 0.015f, alpha = 0.45f)
                drawLine(Color.White, Offset(x + r * 0.03f, y - r * 0.03f), Offset(x, y + r * 0.01f), r * 0.015f, alpha = 0.45f)
            }
            drawRect(Color(0xFFE03131), Offset(g.cx + g.w * 0.14f, g.by - g.h * 0.12f), Size(g.w * 0.14f, g.h * 0.12f))
            repeat(4) { i ->
                val x = g.cx + g.w * 0.15f + i * g.w * 0.04f
                drawLine(Color(0xFFFFC9C9), Offset(x, g.by - g.h * 0.03f), Offset(x, g.by), r * 0.015f)
            }
        }

        Costume.Beret -> {
            drawRect(Color.White, Offset(left, y0), Size(width, g.by - y0), alpha = 0.95f)
            var y = y0 + g.h * 0.03f
            while (y < g.by) {
                drawRect(Color(0xFF1C2A6B), Offset(left, y), Size(width, g.h * 0.035f), alpha = 0.95f)
                y += g.h * 0.075f
            }
            val scarf = Path().apply {
                moveTo(g.cx - g.w * 0.15f, y0 - g.h * 0.01f)
                lineTo(g.cx + g.w * 0.15f, y0 - g.h * 0.01f)
                lineTo(g.cx + g.w * 0.02f, y0 + g.h * 0.13f)
                close()
            }
            drawPath(scarf, Color(0xFFE03131))
            drawCircle(Color(0xFFB02525), r * 0.04f, Offset(g.cx, y0 + g.h * 0.01f))
        }

        Costume.Hacker -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF343A40), Color(0xFF1B1D2E)), y0, g.by), Offset(left, y0), Size(width, g.by - y0), alpha = 0.95f)
            drawRoundRect(Color.White, Offset(g.cx - g.w * 0.2f, g.by - g.h * 0.14f), Size(g.w * 0.4f, g.h * 0.1f), CornerRadius(r * 0.04f), alpha = 0.18f, style = Stroke(r * 0.02f))
            for (sgn in intArrayOf(-1, 1)) {
                val x = g.cx + sgn * g.w * 0.07f
                drawLine(Color(0xFFDEE2E6), Offset(x, y0), Offset(x + sgn * r * 0.02f, y0 + g.h * 0.13f), r * 0.02f, StrokeCap.Round)
                drawCircle(Color(0xFF39FF88), r * 0.022f, Offset(x + sgn * r * 0.02f, y0 + g.h * 0.14f))
            }
            drawPath(g.body, Color(0xFF2B2D42), style = Stroke(r * 0.36f))
            drawRect(Color(0xFF2B2D42), Offset(left, g.top - r), Size(width, g.h * 0.2f + r))
            drawPath(g.body, Color(0xFF39FF88), alpha = 0.25f, style = Stroke(r * 0.03f))
        }

        Costume.Radio -> {
            val collar = Path().apply {
                moveTo(left, y0 - g.h * 0.04f)
                lineTo(g.cx, y0 + g.h * 0.12f)
                lineTo(left + width, y0 - g.h * 0.04f)
                lineTo(left + width, g.by)
                lineTo(left, g.by)
                close()
            }
            drawRect(Color.White, Offset(left, y0), Size(width, g.by - y0), alpha = 0.9f)
            drawPath(collar, Color(0xFF1C3D7A), alpha = 0.95f)
            for (k in 1..2) {
                val d = g.h * 0.035f * k
                val stripe = Path().apply {
                    moveTo(left, y0 - g.h * 0.04f + d)
                    lineTo(g.cx, y0 + g.h * 0.12f + d)
                    lineTo(left + width, y0 - g.h * 0.04f + d)
                }
                drawPath(stripe, Color.White, alpha = 0.85f, style = Stroke(r * 0.015f))
            }
            val tie = Path().apply {
                moveTo(g.cx - g.w * 0.07f, y0 + g.h * 0.1f)
                lineTo(g.cx + g.w * 0.07f, y0 + g.h * 0.1f)
                lineTo(g.cx, y0 + g.h * 0.19f)
                close()
            }
            drawPath(tie, Color(0xFFE03131))
        }

        Costume.Cat -> {
            val collarY = g.by - g.h * 0.16f
            val band = Path().apply {
                moveTo(left, collarY - g.h * 0.04f)
                quadraticTo(g.cx, collarY + g.h * 0.03f, left + width, collarY - g.h * 0.04f)
                lineTo(left + width, collarY + g.h * 0.03f)
                quadraticTo(g.cx, collarY + g.h * 0.1f, left, collarY + g.h * 0.03f)
                close()
            }
            drawPath(band, Color(0xFFE03131))
            val bell = Offset(g.cx, collarY + g.h * 0.1f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3BF), Gold, GoldDeep), bell - Offset(r * 0.03f, r * 0.03f), r * 0.1f), r * 0.08f, bell)
            drawLine(Color(0xFF8A5A00), bell + Offset(-r * 0.04f, r * 0.02f), bell + Offset(r * 0.04f, r * 0.02f), r * 0.015f, StrokeCap.Round)
            drawCircle(Color(0xFF8A5A00), r * 0.015f, bell + Offset(0f, r * 0.04f))
        }
    }
}

/** White headband with the rising sun; drawn inside the body clip so it hugs the head. */
private fun DrawScope.hachimakiBand(g: SlimeGeo) {
    val b1 = g.top + g.h * 0.15f
    val b2 = g.top + g.h * 0.26f
    val band = Path().apply {
        moveTo(g.cx - g.w, b1 + g.h * 0.06f)
        quadraticTo(g.cx, b1 - g.h * 0.04f, g.cx + g.w, b1 + g.h * 0.06f)
        lineTo(g.cx + g.w, b2 + g.h * 0.06f)
        quadraticTo(g.cx, b2 - g.h * 0.04f, g.cx - g.w, b2 + g.h * 0.06f)
        close()
    }
    drawPath(band, Brush.verticalGradient(listOf(Color.White, Color(0xFFE9ECEF)), b1, b2))
    drawPath(band, Color(0x22000000), style = Stroke(g.r * 0.015f))
    drawCircle(Color(0xFFE03131), g.h * 0.035f, Offset(g.cx, (b1 + b2) / 2 - g.h * 0.005f))
}

/** Hats and face accessories, drawn over the face. */
internal fun DrawScope.drawHatFront(c: Costume, g: SlimeGeo, skin: SlimeSkin, pose: SlimePose) {
    val r = g.r
    val t = g.t
    when (c) {
        Costume.NonLa -> {
            val hw = g.w * 0.68f
            val hh = g.h * 0.06f
            val baseY = g.top + g.h * 0.16f
            val apex = Offset(g.cx, g.top - g.h * 0.38f)
            clipPath(g.body) {
                drawOval(Color.Black, Offset(g.cx - g.w * 0.5f, baseY - g.h * 0.02f), Size(g.w, g.h * 0.16f), alpha = 0.12f)
            }
            for (sgn in intArrayOf(-1, 1)) {
                val strap = Path().apply {
                    moveTo(g.cx + sgn * hw * 0.62f, baseY + hh * 0.6f)
                    quadraticTo(g.cx + sgn * g.w * 0.5f, g.by - g.h * 0.45f, g.cx + sgn * g.w * 0.46f, g.by - g.h * 0.3f)
                }
                drawPath(strap, Color(0xFFF06595), alpha = 0.8f, style = Stroke(r * 0.025f, cap = StrokeCap.Round))
            }
            val cone = Path().apply {
                moveTo(g.cx - hw, baseY)
                quadraticTo(g.cx - hw * 0.32f, apex.y + (baseY - apex.y) * 0.35f, apex.x, apex.y)
                quadraticTo(g.cx + hw * 0.32f, apex.y + (baseY - apex.y) * 0.35f, g.cx + hw, baseY)
                quadraticTo(g.cx, baseY + hh * 2.4f, g.cx - hw, baseY)
                close()
            }
            drawPath(cone, Brush.linearGradient(listOf(Color(0xFFFFF4D2), Color(0xFFF1D08C), Color(0xFFD4A253)), Offset(g.cx - hw, apex.y), Offset(g.cx + hw, baseY)))
            clipPath(cone) {
                for (k in 1..5) {
                    val f = k / 6f
                    val a = lerp(apex, Offset(g.cx - hw, baseY), f)
                    val b = lerp(apex, Offset(g.cx + hw, baseY), f)
                    val ring = Path().apply {
                        moveTo(a.x, a.y)
                        quadraticTo(g.cx, a.y + hh * 2.4f * f, b.x, b.y)
                    }
                    drawPath(ring, Color(0xFFB07A2E), alpha = 0.4f, style = Stroke(r * 0.016f))
                }
                for (k in -6..6) {
                    drawLine(Color(0xFFB07A2E), apex, Offset(g.cx + k * hw / 6f, baseY + hh * 2f), r * 0.008f, alpha = 0.18f)
                }
            }
            drawPath(cone, Color(0xFFA8732A), alpha = 0.85f, style = Stroke(r * 0.025f, join = StrokeJoin.Round))
            drawLine(Color.White, lerp(apex, Offset(g.cx - hw, baseY), 0.1f), lerp(apex, Offset(g.cx - hw, baseY), 0.7f), r * 0.025f, StrokeCap.Round, alpha = 0.6f)
        }

        Costume.TopHat -> {
            val pivot = Offset(g.cx + g.w * 0.04f, g.top + g.h * 0.07f)
            rotate(-9f, pivot) {
                val crownL = pivot.x - g.w * 0.25f
                val crownR = pivot.x + g.w * 0.25f
                val crownTop = pivot.y - g.h * 0.56f
                val crown = Path().apply {
                    moveTo(crownL, pivot.y)
                    lineTo(crownL - g.w * 0.025f, crownTop)
                    lineTo(crownR + g.w * 0.025f, crownTop)
                    lineTo(crownR, pivot.y)
                    close()
                }
                drawOval(Color(0xFF16181D), pivot - Offset(g.w * 0.42f, g.h * 0.07f), Size(g.w * 0.84f, g.h * 0.14f))
                drawPath(crown, Brush.horizontalGradient(listOf(Color(0xFF343A40), Color(0xFF16181D), Color(0xFF212529)), crownL, crownR))
                drawRect(Color(0xFFC92A2A), Offset(crownL, pivot.y - g.h * 0.13f), Size(crownR - crownL, g.h * 0.09f))
                drawOval(Color(0xFF495057), Offset(crownL - g.w * 0.025f, crownTop - g.h * 0.03f), Size(crownR - crownL + g.w * 0.05f, g.h * 0.06f))
                drawLine(Color.White, Offset(crownL + g.w * 0.05f, crownTop + g.h * 0.06f), Offset(crownL + g.w * 0.05f, pivot.y - g.h * 0.16f), r * 0.03f, StrokeCap.Round, alpha = 0.3f)
            }
            if (pose.mood != Mood.Dizzy && pose.mood != Mood.Squeeze) {
                val eye = Offset(g.faceX + g.eyeDx, g.eyeY)
                drawCircle(Color.White, g.eyeW * 1.0f, eye, alpha = 0.18f)
                drawCircle(Gold, g.eyeW * 1.0f, eye, style = Stroke(r * 0.03f))
                drawArc(Color.White, 200f, 60f, false, eye - Offset(g.eyeW * 0.75f, g.eyeW * 0.75f), Size(g.eyeW * 1.5f, g.eyeW * 1.5f), alpha = 0.8f, style = Stroke(r * 0.02f, cap = StrokeCap.Round))
                val chain = Path().apply {
                    moveTo(eye.x + g.eyeW * 0.7f, eye.y + g.eyeW * 0.7f)
                    quadraticTo(eye.x + g.w * 0.22f, g.by - g.h * 0.2f, g.cx + g.w * 0.42f, g.by - g.h * 0.3f)
                }
                drawPath(chain, Gold, style = Stroke(r * 0.015f, cap = StrokeCap.Round))
            }
        }

        Costume.Hachimaki -> {
            val knot = Offset(g.cx + g.w * 0.46f, g.top + g.h * 0.22f)
            for (k in 0..1) {
                val tail = Path().apply {
                    moveTo(knot.x, knot.y)
                    for (i in 1..6) {
                        val f = i / 6f
                        val x = knot.x + f * g.w * (0.32f + 0.08f * k)
                        val y = knot.y + f * g.h * (0.08f + 0.12f * k) + sin(t * 9f + i * 0.9f + k) * g.h * 0.035f * f
                        lineTo(x, y)
                    }
                }
                drawPath(tail, Color(0x33000000), style = Stroke(r * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(tail, Color.White, style = Stroke(r * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(Color.White, r * 0.06f, knot)
            drawCircle(Color(0x33000000), r * 0.06f, knot, style = Stroke(r * 0.012f))
        }

        Costume.Gat -> {
            val brimC = Offset(g.cx, g.top + g.h * 0.08f)
            for (sgn in intArrayOf(-1, 1)) {
                val beads = Path().apply {
                    moveTo(g.cx + sgn * g.w * 0.44f, brimC.y + g.h * 0.03f)
                    quadraticTo(g.cx + sgn * g.w * 0.56f, g.by - g.h * 0.32f, g.cx + sgn * g.w * 0.1f, g.by - g.h * 0.16f)
                }
                drawPath(beads, Color(0xFF8A5A00), alpha = 0.5f, style = Stroke(r * 0.008f))
                for (i in 0..9) {
                    val f = i / 9f
                    val a = Offset(g.cx + sgn * g.w * 0.44f, brimC.y + g.h * 0.03f)
                    val ctrl = Offset(g.cx + sgn * g.w * 0.56f, g.by - g.h * 0.32f)
                    val b = Offset(g.cx + sgn * g.w * 0.1f, g.by - g.h * 0.16f)
                    drawCircle(Color(0xFFFFB84D), r * 0.028f, quadBezier(a, ctrl, b, f))
                }
            }
            drawOval(Color(0xFF16181D), brimC - Offset(g.w * 0.74f, g.h * 0.075f), Size(g.w * 1.48f, g.h * 0.15f), alpha = 0.5f)
            for (k in 1..3) {
                drawOval(Color.White, brimC - Offset(g.w * 0.74f * k / 4f, g.h * 0.075f * k / 4f), Size(g.w * 1.48f * k / 4f, g.h * 0.15f * k / 4f), alpha = 0.1f, style = Stroke(r * 0.01f))
            }
            drawOval(Color(0xFF16181D), brimC - Offset(g.w * 0.74f, g.h * 0.075f), Size(g.w * 1.48f, g.h * 0.15f), alpha = 0.8f, style = Stroke(r * 0.02f))
            val crown = Path().apply {
                moveTo(g.cx - g.w * 0.17f, brimC.y)
                lineTo(g.cx - g.w * 0.15f, brimC.y - g.h * 0.4f)
                quadraticTo(g.cx, brimC.y - g.h * 0.46f, g.cx + g.w * 0.15f, brimC.y - g.h * 0.4f)
                lineTo(g.cx + g.w * 0.17f, brimC.y)
                close()
            }
            drawPath(crown, Color(0xFF16181D), alpha = 0.72f)
            drawLine(Color.White, Offset(g.cx - g.w * 0.1f, brimC.y - g.h * 0.36f), Offset(g.cx - g.w * 0.11f, brimC.y - g.h * 0.05f), r * 0.02f, StrokeCap.Round, alpha = 0.3f)
        }

        Costume.Ushanka -> {
            val fur = Color(0xFF8B5E3C)
            val light = Color(0xFFE6C9A0)
            for (sgn in intArrayOf(-1, 1)) {
                val flap = Rect(g.cx + sgn * g.w * 0.5f - g.w * 0.09f, g.top + g.h * 0.14f, g.cx + sgn * g.w * 0.5f + g.w * 0.09f, g.top + g.h * 0.55f)
                drawRoundRect(light, flap.topLeft, flap.size, CornerRadius(g.w * 0.09f))
                drawRoundRect(lerpColor(light, fur, 0.4f), flap.topLeft, flap.size, CornerRadius(g.w * 0.09f), style = Stroke(r * 0.02f))
                drawLine(Color(0xFF6B4226), Offset(flap.center.x, flap.bottom), Offset(flap.center.x + sin(t * 3f + sgn) * r * 0.05f, flap.bottom + g.h * 0.12f), r * 0.02f, StrokeCap.Round)
            }
            val domeTop = g.top - g.h * 0.2f
            val dome = Path().apply {
                moveTo(g.cx - g.w * 0.48f, g.top + g.h * 0.16f)
                cubicTo(g.cx - g.w * 0.5f, domeTop, g.cx + g.w * 0.5f, domeTop, g.cx + g.w * 0.48f, g.top + g.h * 0.16f)
                close()
            }
            drawPath(dome, Brush.verticalGradient(listOf(lerpColor(fur, Color.White, 0.15f), fur), domeTop, g.top + g.h * 0.16f))
            repeat(9) { i ->
                val f = i / 8f
                val p = Offset(g.cx - g.w * 0.42f + f * g.w * 0.84f, domeTop + g.h * 0.12f + (f - 0.5f) * (f - 0.5f) * g.h * 0.5f)
                drawCircle(fur, r * 0.07f, p)
            }
            val fold = Rect(g.cx - g.w * 0.5f, g.top + g.h * 0.04f, g.cx + g.w * 0.5f, g.top + g.h * 0.24f)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF3E1C4), light), fold.top, fold.bottom), fold.topLeft, fold.size, CornerRadius(g.h * 0.08f))
            repeat(10) { i ->
                val x = fold.left + (i + 0.5f) * fold.width / 10f
                drawCircle(Color(0xFFF3E1C4), r * 0.045f, Offset(x, fold.top + r * 0.01f))
                drawCircle(light, r * 0.045f, Offset(x, fold.bottom - r * 0.01f))
            }
            drawCartoonStar(Offset(g.cx, fold.center.y), g.h * 0.06f, 0f, 1f)
            drawUnit(Offset(g.cx, fold.center.y), g.h * 0.06f) { drawPath(UnitStar, Color(0xFFE03131)) }
        }

        Costume.Beret -> {
            val c0 = Offset(g.cx + g.w * 0.1f, g.top + g.h * 0.02f)
            rotate(-12f, c0) {
                drawOval(Brush.verticalGradient(listOf(Color(0xFF4C5AA8), Color(0xFF232B5C)), c0.y - g.h * 0.14f, c0.y + g.h * 0.1f), c0 - Offset(g.w * 0.42f, g.h * 0.13f), Size(g.w * 0.84f, g.h * 0.24f))
                drawOval(Color(0xFF1A2048), c0 - Offset(g.w * 0.38f, -g.h * 0.03f), Size(g.w * 0.76f, g.h * 0.07f), alpha = 0.6f)
                drawLine(Color(0xFF232B5C), c0 - Offset(0f, g.h * 0.12f), c0 - Offset(-g.w * 0.02f, g.h * 0.2f), r * 0.04f, StrokeCap.Round)
                drawArc(Color.White, 200f, 50f, false, c0 - Offset(g.w * 0.36f, g.h * 0.11f), Size(g.w * 0.72f, g.h * 0.2f), alpha = 0.35f, style = Stroke(r * 0.025f, cap = StrokeCap.Round))
            }
            val m = Offset(g.faceX, g.mouthY - g.eyeH * 0.42f)
            for (sgn in intArrayOf(-1, 1)) {
                val stache = Path().apply {
                    moveTo(m.x, m.y)
                    cubicTo(m.x + sgn * g.w * 0.06f, m.y - g.h * 0.04f, m.x + sgn * g.w * 0.15f, m.y + g.h * 0.01f, m.x + sgn * g.w * 0.19f, m.y - g.h * 0.04f)
                    quadraticTo(m.x + sgn * g.w * 0.21f, m.y - g.h * 0.08f, m.x + sgn * g.w * 0.17f, m.y - g.h * 0.08f)
                }
                drawPath(stache, Color(0xFF3B2A1A), style = Stroke(r * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        Costume.Hacker -> {
            val y = g.eyeY - g.eyeH * 0.15f
            val px = g.eyeW * 0.28f
            val lensW = g.eyeW * 2.1f
            val lensH = g.eyeH * 0.95f
            drawRect(Color(0xFF0B0B0F), Offset(g.faceX - g.eyeDx - lensW / 2, y - lensH / 2), Size(g.eyeDx * 2 + lensW, px))
            for (sgn in intArrayOf(-1, 1)) {
                val cx = g.faceX + sgn * g.eyeDx
                drawRect(Color(0xFF0B0B0F), Offset(cx - lensW / 2, y - lensH / 2), Size(lensW, lensH - px))
                drawRect(Color(0xFF0B0B0F), Offset(cx - lensW / 2 + px, y + lensH / 2 - px), Size(lensW - px * 2, px))
                drawRect(Color.White, Offset(cx - lensW / 2 + px, y - lensH / 2 + px), Size(px, px), alpha = 0.9f)
                drawRect(Color.White, Offset(cx - lensW / 2 + px * 2, y - lensH / 2 + px * 2), Size(px, px), alpha = 0.6f)
            }
        }

        Costume.Radio -> {
            drawArc(
                Color(0xFF343A40), 200f, 140f, false,
                Offset(g.cx - g.w * 0.53f, g.top - g.h * 0.14f), Size(g.w * 1.06f, g.h * 1.1f),
                style = Stroke(r * 0.09f, cap = StrokeCap.Round),
            )
            drawArc(
                Color.White, 215f, 40f, false,
                Offset(g.cx - g.w * 0.53f, g.top - g.h * 0.14f), Size(g.w * 1.06f, g.h * 1.1f),
                alpha = 0.35f, style = Stroke(r * 0.02f, cap = StrokeCap.Round),
            )
            for (sgn in intArrayOf(-1, 1)) {
                val cup = Rect(g.cx + sgn * g.w * 0.5f - g.w * 0.08f, g.top + g.h * 0.32f, g.cx + sgn * g.w * 0.5f + g.w * 0.08f, g.top + g.h * 0.6f)
                drawRoundRect(Color(0xFF495057), cup.topLeft, cup.size, CornerRadius(g.w * 0.06f))
                drawRoundRect(Color(0xFF212529), cup.topLeft + Offset(-sgn * g.w * 0.03f, g.h * 0.03f), Size(cup.width * 0.5f, cup.height - g.h * 0.06f), CornerRadius(g.w * 0.04f))
            }
            val micStart = Offset(g.cx - g.w * 0.5f, g.top + g.h * 0.55f)
            val mic = Offset(g.faceX - g.w * 0.16f, g.mouthY + g.h * 0.03f)
            val boom = Path().apply {
                moveTo(micStart.x, micStart.y)
                quadraticTo(g.cx - g.w * 0.45f, g.mouthY + g.h * 0.08f, mic.x, mic.y)
            }
            drawPath(boom, Color(0xFF343A40), style = Stroke(r * 0.03f, cap = StrokeCap.Round))
            drawCircle(Color(0xFF212529), r * 0.055f, mic)
            drawCircle(Color(0xFFFF4D4D), r * 0.018f, Offset(g.cx + g.w * 0.5f, g.top + g.h * 0.38f), alpha = 0.4f + 0.6f * ((sin(t * 6f) + 1f) / 2f))
        }

        Costume.Cat -> {
            val nose = Offset(g.faceX, g.mouthY - g.eyeH * 0.4f)
            val n = Path().apply {
                moveTo(nose.x - r * 0.045f, nose.y - r * 0.025f)
                lineTo(nose.x + r * 0.045f, nose.y - r * 0.025f)
                lineTo(nose.x, nose.y + r * 0.03f)
                close()
            }
            drawPath(n, Color(0xFFFF6B9A))
            for (sgn in intArrayOf(-1, 1)) {
                for (k in -1..1) {
                    drawLine(
                        Color(0xFF2A2440),
                        Offset(g.faceX + sgn * g.w * 0.22f, g.mouthY - g.h * 0.02f + k * g.h * 0.03f),
                        Offset(g.faceX + sgn * g.w * 0.56f, g.mouthY - g.h * 0.05f + k * g.h * 0.07f),
                        r * 0.016f, StrokeCap.Round, alpha = 0.55f,
                    )
                }
            }
        }
    }
}
