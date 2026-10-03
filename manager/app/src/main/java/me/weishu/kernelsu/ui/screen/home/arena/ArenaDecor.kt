package me.weishu.kernelsu.ui.screen.home.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extra scenery painted over a country's panorama in the 3D diorama: the side walls and the outer
 * ends of the back wall get that country's little symbols (phone boxes, daruma, matryoshka...) as
 * glossy stickers, so every surface of the box tells you where you are. The middle of the back
 * wall is left to the scene itself and the status text.
 */
internal typealias DecorDrawer = DrawScope.(t: Float, split: WallSplit) -> Unit

// ---------------------------------------------------------------------------------------------
// Stickers: icons built once in local units (height 1, origin at the bottom center).
// ---------------------------------------------------------------------------------------------

private class Part(val path: Path, val color: Color, val rim: Float, val line: Float) {
    val shade: Brush? = if (line > 0f) null else path.getBounds().let { b ->
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.2f)), b.top + b.height * 0.35f, b.bottom)
    }
}

private class Icon {
    val parts = ArrayList<Part>()
    fun fill(path: Path, color: Color, rim: Float = 0.5f) {
        parts += Part(path, color, rim, 0f)
    }

    fun line(path: Path, color: Color, width: Float) {
        parts += Part(path, color, 0f, width)
    }
}

private fun icon(block: Icon.() -> Unit) = Icon().apply(block)

private fun DrawScope.sticker(icon: Icon, at: Offset, size: Float, rotation: Float = 0f, alpha: Float = 1f) {
    drawUnit(at, size, rotation) {
        withTransform({ translate(0.03f, 0.04f) }) {
            for (p in icon.parts) {
                if (p.line > 0f) {
                    drawPath(p.path, Color.Black, alpha = 0.12f * alpha, style = Stroke(p.line, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawPath(p.path, Color.Black, alpha = 0.14f * alpha)
                }
            }
        }
        for (p in icon.parts) {
            if (p.line > 0f) {
                drawPath(p.path, p.color, alpha = alpha, style = Stroke(p.line, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                drawPath(p.path, p.color, alpha = alpha)
                drawPath(p.path, p.shade!!, alpha = alpha)
                if (p.rim > 0f) drawPath(p.path, Color.White, alpha = p.rim * alpha, style = Stroke(0.022f, join = StrokeJoin.Round))
            }
        }
    }
}

private fun box(l: Float, t: Float, r: Float, b: Float, rad: Float = 0f) = Path().apply { addRoundRect(RoundRect(l, t, r, b, CornerRadius(rad))) }

private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }

private fun circle(cx: Float, cy: Float, r: Float) = oval(cx, cy, r, r)

private fun poly(vararg p: Float) = Path().apply {
    moveTo(p[0], p[1])
    var i = 2
    while (i < p.size) {
        lineTo(p[i], p[i + 1])
        i += 2
    }
    close()
}

private fun lines(vararg seg: Float) = Path().apply {
    var i = 0
    while (i + 3 < seg.size) {
        moveTo(seg[i], seg[i + 1])
        lineTo(seg[i + 2], seg[i + 3])
        i += 4
    }
}

/** A pointed petal or leaf from (x, y) of [length] and [width], turned [angle] degrees from straight up. */
private fun petal(x: Float, y: Float, length: Float, width: Float, angle: Float) = Path().apply {
    val a = Math.toRadians(angle.toDouble()).toFloat()
    val c = cos(a)
    val s = sin(a)
    fun p(px: Float, py: Float) = Offset(x + px * c - py * s, y + px * s + py * c)
    val p0 = p(0f, 0f)
    val c1 = p(width, -length * 0.3f)
    val c2 = p(width * 0.6f, -length * 0.85f)
    val tip = p(0f, -length)
    val c3 = p(-width * 0.6f, -length * 0.85f)
    val c4 = p(-width, -length * 0.3f)
    moveTo(p0.x, p0.y)
    cubicTo(c1.x, c1.y, c2.x, c2.y, tip.x, tip.y)
    cubicTo(c3.x, c3.y, c4.x, c4.y, p0.x, p0.y)
    close()
}

private val Ink = Color(0xFF263238)

// Vietnam -------------------------------------------------------------------------------------

private val Bamboo = icon {
    val stalks = listOf(Triple(-0.15f, -0.86f, 0.035f), Triple(0f, -1f, 0.04f), Triple(0.14f, -0.74f, 0.034f))
    for ((x, top, half) in stalks) fill(box(x - half, top, x + half, 0f, half), Color(0xFF7CB342), 0.35f)
    for ((x, top, half) in stalks) {
        var y = -0.16f
        while (y > top + 0.05f) {
            line(lines(x - half, y, x + half, y), Color(0xFF4E7A27), 0.018f)
            y -= 0.17f
        }
    }
    for ((i, l) in listOf(-0.15f to -0.66f, 0f to -0.84f, 0f to -0.5f, 0.14f to -0.58f, -0.15f to -0.4f).withIndex()) {
        fill(petal(l.first, l.second, 0.24f, 0.05f, if (i % 2 == 0) -62f else 58f), Color(0xFF9CCC65), 0.3f)
    }
}

private val Lotus = icon {
    fill(oval(0f, -0.05f, 0.46f, 0.07f), Color(0xFF66BB6A), 0.3f)
    for (a in listOf(-68f, 68f, -36f, 36f)) fill(petal(0f, -0.1f, 0.42f, 0.13f, a), Color(0xFFF48FB1))
    fill(petal(0f, -0.1f, 0.5f, 0.14f, 0f), Color(0xFFF8BBD0))
}

private val Phin = icon {
    fill(box(-0.18f, -0.44f, 0.18f, 0f, 0.04f), Color(0xFF5D4037), 0.6f)
    fill(box(-0.18f, -0.13f, 0.18f, 0f, 0.04f), Color(0xFFFFE0B2), 0f)
    fill(box(-0.23f, -0.5f, 0.23f, -0.44f, 0.02f), Color(0xFFB0BEC5))
    fill(box(-0.15f, -0.74f, 0.15f, -0.5f, 0.02f), Color(0xFFCFD8DC))
    fill(box(-0.1f, -0.82f, 0.1f, -0.74f, 0.04f), Color(0xFFB0BEC5))
}

private val ConicalHat = icon {
    fill(Path().apply {
        moveTo(0f, -0.62f)
        lineTo(0.55f, -0.08f)
        quadraticTo(0f, 0.04f, -0.55f, -0.08f)
        close()
    }, Color(0xFFF2D48F))
    for (k in 1..4) {
        val f = k / 5f
        line(Path().apply {
            moveTo(-0.55f * f, -0.62f + 0.54f * f)
            quadraticTo(0f, -0.58f + 0.6f * f, 0.55f * f, -0.62f + 0.54f * f)
        }, Color(0xFFC9A55A), 0.014f)
    }
}

// United Kingdom ------------------------------------------------------------------------------

private val PhoneBox = icon {
    fill(box(-0.2f, -0.9f, 0.2f, 0f, 0.03f), Color(0xFFD32F2F))
    fill(Path().apply {
        addRoundRect(RoundRect(-0.23f, -0.95f, 0.23f, -0.88f, CornerRadius(0.03f)))
        addOval(Rect(-0.17f, -1f, 0.17f, -0.91f))
    }, Color(0xFFB71C1C))
    fill(box(-0.16f, -0.86f, 0.16f, -0.81f, 0.01f), Color(0xFF212121), 0f)
    for (row in 0 until 3) for (col in 0 until 3) {
        val x = -0.14f + col * 0.1f
        val y = -0.77f + row * 0.11f
        fill(box(x, y, x + 0.08f, y + 0.09f, 0.01f), Color(0xFFE3F2FD), 0f)
    }
    fill(box(-0.23f, -0.04f, 0.23f, 0f, 0.01f), Color(0xFF8E1B1B), 0f)
}

private val Bus = icon {
    fill(box(-0.66f, -0.84f, 0.66f, -0.1f, 0.1f), Color(0xFFD32F2F))
    for (k in 0 until 5) {
        val x = -0.58f + k * 0.24f
        fill(box(x, -0.74f, x + 0.19f, -0.55f, 0.03f), Color(0xFFB3E5FC), 0f)
        fill(box(x, -0.42f, x + 0.19f, -0.25f, 0.03f), Color(0xFFB3E5FC), 0f)
    }
    fill(box(-0.66f, -0.5f, 0.66f, -0.46f), Color(0xFFFFF3E0), 0f)
    for (x in listOf(-0.4f, 0.42f)) {
        fill(circle(x, -0.1f, 0.12f), Ink, 0.2f)
        fill(circle(x, -0.1f, 0.05f), Color(0xFFB0BEC5), 0f)
    }
}

private val Teacup = icon {
    fill(oval(0f, -0.04f, 0.4f, 0.06f), Color(0xFFF5F5F5))
    line(Path().apply {
        moveTo(0.24f, -0.44f)
        cubicTo(0.44f, -0.44f, 0.44f, -0.2f, 0.18f, -0.2f)
    }, Color(0xFFF5F5F5), 0.05f)
    fill(Path().apply {
        moveTo(-0.27f, -0.5f)
        lineTo(0.27f, -0.5f)
        cubicTo(0.27f, -0.2f, 0.15f, -0.08f, 0f, -0.08f)
        cubicTo(-0.15f, -0.08f, -0.27f, -0.2f, -0.27f, -0.5f)
        close()
    }, Color(0xFFF5F5F5))
    fill(box(-0.27f, -0.5f, 0.27f, -0.45f), Color(0xFF3F51B5), 0f)
    fill(oval(0f, -0.5f, 0.25f, 0.035f), Color(0xFF8D6E63), 0f)
}

private val Umbrella = icon {
    line(lines(0f, -0.86f, 0f, -0.12f), Ink, 0.035f)
    line(Path().apply {
        moveTo(0f, -0.12f)
        quadraticTo(0f, 0f, 0.09f, -0.04f)
    }, Ink, 0.035f)
    fill(Path().apply {
        moveTo(-0.5f, -0.56f)
        cubicTo(-0.5f, -1.06f, 0.5f, -1.06f, 0.5f, -0.56f)
        for (k in 0 until 4) {
            val x0 = 0.5f - k * 0.25f
            quadraticTo(x0 - 0.125f, -0.66f, x0 - 0.25f, -0.56f)
        }
        close()
    }, Color(0xFF1A237E))
}

// Japan ---------------------------------------------------------------------------------------

private val Chochin = icon {
    line(lines(0f, -0.12f, 0f, 0f), Color(0xFFFFD54F), 0.03f)
    fill(oval(0f, -0.56f, 0.3f, 0.36f), Color(0xFFE53935))
    for (dy in listOf(-0.24f, -0.12f, 0f, 0.12f, 0.24f)) {
        val half = 0.3f * sqrt(1f - (dy / 0.36f) * (dy / 0.36f))
        line(lines(-half, -0.56f + dy, half, -0.56f + dy), Color(0xFFB71C1C), 0.012f)
    }
    line(lines(-0.08f, -0.68f, 0.08f, -0.68f, 0f, -0.75f, 0f, -0.4f, -0.1f, -0.5f, 0.1f, -0.46f), Color(0xFFFFF3E0), 0.035f)
    fill(box(-0.17f, -0.98f, 0.17f, -0.88f, 0.02f), Color(0xFF212121), 0.2f)
    fill(box(-0.17f, -0.23f, 0.17f, -0.14f, 0.02f), Color(0xFF212121), 0.2f)
}

private val Daruma = icon {
    fill(oval(0f, -0.4f, 0.34f, 0.4f), Color(0xFFD32F2F))
    fill(oval(0f, -0.5f, 0.21f, 0.17f), Color(0xFFFFF3E0), 0.2f)
    fill(circle(-0.08f, -0.52f, 0.045f), Color.Black, 0f)
    line(circle(0.08f, -0.52f, 0.04f), Color.Black, 0.012f)
    line(lines(-0.14f, -0.6f, -0.03f, -0.62f, 0.03f, -0.62f, 0.14f, -0.6f), Color.Black, 0.025f)
    line(Path().apply {
        moveTo(-0.08f, -0.4f)
        quadraticTo(0f, -0.34f, 0.08f, -0.4f)
    }, Color.Black, 0.02f)
    line(Path().apply {
        moveTo(-0.2f, -0.18f)
        quadraticTo(0f, -0.06f, 0.2f, -0.18f)
    }, Color(0xFFFFD54F), 0.035f)
}

private fun carp(body: Color, belly: Color) = icon {
    fill(Path().apply {
        moveTo(0.46f, -0.2f)
        cubicTo(0.3f, -0.44f, -0.2f, -0.42f, -0.38f, -0.3f)
        lineTo(-0.52f, -0.42f)
        lineTo(-0.44f, -0.2f)
        lineTo(-0.52f, 0f)
        lineTo(-0.38f, -0.1f)
        cubicTo(-0.2f, 0.02f, 0.3f, 0.04f, 0.46f, -0.2f)
        close()
    }, body)
    fill(oval(0.02f, -0.12f, 0.28f, 0.06f), belly, 0f)
    for (k in 0 until 3) {
        val x = 0.12f - k * 0.16f
        line(Path().apply {
            moveTo(x, -0.32f)
            quadraticTo(x - 0.08f, -0.22f, x, -0.12f)
        }, belly, 0.018f)
    }
    fill(circle(0.3f, -0.23f, 0.055f), Color.White, 0f)
    fill(circle(0.31f, -0.23f, 0.028f), Color.Black, 0f)
}

private val Carps = listOf(carp(Color(0xFF1E3A8A), Color(0xFF90CAF9)), carp(Color(0xFFE53935), Color(0xFFFFCDD2)), carp(Color(0xFFEC407A), Color(0xFFFFF59D)))

// Korea ---------------------------------------------------------------------------------------

private val Mugunghwa = icon {
    for (k in 0 until 5) fill(petal(0f, -0.3f, 0.3f, 0.16f, k * 72f), Color(0xFFF8BBD0), 0.6f)
    fill(circle(0f, -0.3f, 0.09f), Color(0xFFC2185B), 0f)
    line(lines(0f, -0.3f, 0.05f, -0.46f), Color(0xFFFFEB3B), 0.03f)
}

private val Onggi = icon {
    fill(Path().apply {
        moveTo(-0.14f, -0.68f)
        cubicTo(-0.38f, -0.6f, -0.34f, -0.08f, -0.16f, 0f)
        lineTo(0.16f, 0f)
        cubicTo(0.34f, -0.08f, 0.38f, -0.6f, 0.14f, -0.68f)
        close()
    }, Color(0xFF795548))
    fill(oval(0f, -0.7f, 0.18f, 0.05f), Color(0xFF5D4037))
    fill(circle(0f, -0.75f, 0.04f), Color(0xFF5D4037), 0.2f)
    line(Path().apply {
        moveTo(-0.22f, -0.4f)
        quadraticTo(0f, -0.34f, 0.22f, -0.4f)
    }, Color(0xFFA1887F), 0.02f)
}

private val Hanok = icon {
    fill(box(-0.56f, -0.24f, 0.56f, 0f), Color(0xFFFFF8E1), 0.2f)
    for (x in listOf(-0.5f, -0.18f, 0.18f, 0.5f)) fill(box(x - 0.03f, -0.24f, x + 0.03f, 0f), Color(0xFF8D6E63), 0f)
    fill(box(-0.56f, -0.26f, 0.56f, -0.21f), Color(0xFF6D4C41), 0f)
    fill(Path().apply {
        moveTo(-0.84f, -0.5f)
        quadraticTo(-0.6f, -0.28f, 0f, -0.34f)
        quadraticTo(0.6f, -0.28f, 0.84f, -0.5f)
        lineTo(0.72f, -0.22f)
        quadraticTo(0.5f, -0.18f, 0f, -0.2f)
        quadraticTo(-0.5f, -0.18f, -0.72f, -0.22f)
        close()
    }, Color(0xFF455A64))
    for (k in -5..5) line(lines(k * 0.12f, -0.33f, k * 0.12f * 1.1f, -0.21f), Color(0xFF37474F), 0.015f)
}

// Russia --------------------------------------------------------------------------------------

private fun matryoshka(dress: Color) = icon {
    fill(oval(0f, -0.34f, 0.27f, 0.34f), dress)
    fill(circle(0f, -0.76f, 0.2f), dress)
    fill(oval(0f, -0.74f, 0.13f, 0.12f), Color(0xFFFFF3E0), 0f)
    fill(circle(-0.065f, -0.7f, 0.025f), Color(0xFFFF8A80), 0f)
    fill(circle(0.065f, -0.7f, 0.025f), Color(0xFFFF8A80), 0f)
    fill(circle(-0.045f, -0.76f, 0.015f), Color.Black, 0f)
    fill(circle(0.045f, -0.76f, 0.015f), Color.Black, 0f)
    fill(oval(0f, -0.32f, 0.16f, 0.22f), Color(0xFFFFF8E1), 0f)
    fill(circle(0f, -0.34f, 0.06f), Color(0xFFFFC107), 0f)
    for (k in 0 until 5) {
        val a = k * 1.2566f
        fill(circle(cos(a) * 0.09f, -0.34f + sin(a) * 0.09f, 0.03f), Color(0xFFE53935), 0f)
    }
}

private val Matryoshkas = listOf(matryoshka(Color(0xFFD32F2F)), matryoshka(Color(0xFF1E88E5)), matryoshka(Color(0xFF43A047)))

private val Birch = icon {
    for ((cx, cy, r) in listOf(Triple(-0.14f, -0.72f, 0.2f), Triple(0.12f, -0.82f, 0.22f), Triple(0f, -0.95f, 0.16f), Triple(0.18f, -0.6f, 0.14f))) {
        fill(circle(cx, cy, r), Color(0xFFE3F2FD), 0.7f)
    }
    fill(box(-0.04f, -0.82f, 0.04f, 0f, 0.02f), Color(0xFFFAFAFA), 0.4f)
    for (k in 0 until 7) {
        val y = -0.1f - k * 0.1f
        val off = if (k % 2 == 0) -0.04f else 0f
        line(lines(off, y, off + 0.04f, y - 0.01f), Color(0xFF263238), 0.02f)
    }
}

private val Balalaika = icon {
    fill(box(-0.025f, -0.95f, 0.025f, -0.4f, 0.01f), Color(0xFF6D4C41), 0.2f)
    fill(box(-0.055f, -1f, 0.055f, -0.92f, 0.02f), Color(0xFF4E342E), 0.2f)
    fill(Path().apply {
        moveTo(0f, -0.48f)
        lineTo(0.28f, -0.03f)
        quadraticTo(0f, 0.04f, -0.28f, -0.03f)
        close()
    }, Color(0xFFFFB74D))
    fill(circle(0f, -0.17f, 0.05f), Color(0xFF4E342E), 0f)
    line(lines(-0.012f, -0.92f, -0.05f, -0.06f, 0f, -0.92f, 0f, -0.06f, 0.012f, -0.92f, 0.05f, -0.06f), Color(0xFFECEFF1), 0.006f)
}

// France --------------------------------------------------------------------------------------

private val Croissant = icon {
    val segs = 5
    for (k in 0 until segs) {
        val f = k / (segs - 1f)
        val a = Math.PI.toFloat() * (0.1f + 0.8f * f)
        val big = 1f - abs(f - 0.5f) * 1.2f
        fill(oval(-cos(a) * 0.36f, -0.1f - sin(a) * 0.22f, 0.1f + 0.06f * big, 0.12f + 0.06f * big), androidx.compose.ui.graphics.lerp(Color(0xFFE08A2E), Color(0xFFFFC266), big), 0.35f)
    }
}

private val Baguette = icon {
    fill(box(-0.62f, -0.2f, 0.62f, 0f, 0.1f), Color(0xFFF2B866))
    for (k in 0 until 5) {
        val x = -0.42f + k * 0.2f
        line(lines(x - 0.05f, -0.06f, x + 0.06f, -0.15f), Color(0xFFFFE9C2), 0.03f)
    }
}

private val StreetLamp = icon {
    fill(box(-0.025f, -0.8f, 0.025f, 0f), Ink, 0.2f)
    fill(box(-0.09f, -0.06f, 0.09f, 0f, 0.02f), Ink, 0.2f)
    line(Path().apply {
        moveTo(0f, -0.62f)
        cubicTo(0.12f, -0.62f, 0.12f, -0.76f, 0.04f, -0.76f)
    }, Ink, 0.02f)
    fill(poly(-0.1f, -0.97f, 0.1f, -0.97f, 0.07f, -0.8f, -0.07f, -0.8f), Ink, 0.2f)
    fill(poly(-0.07f, -0.94f, 0.07f, -0.94f, 0.05f, -0.83f, -0.05f, -0.83f), Color(0xFFFFE082), 0f)
}

private val Arc = icon {
    fill(Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(-0.44f, -0.72f, 0.44f, 0f))
        moveTo(-0.16f, 0f)
        lineTo(-0.16f, -0.32f)
        cubicTo(-0.16f, -0.52f, 0.16f, -0.52f, 0.16f, -0.32f)
        lineTo(0.16f, 0f)
        close()
    }, Color(0xFFFFF3E0))
    fill(box(-0.47f, -0.78f, 0.47f, -0.72f), Color(0xFFEAD9BE), 0.3f)
    line(lines(-0.44f, -0.6f, 0.44f, -0.6f), Color(0xFFD7C4A3), 0.02f)
}

private fun macaron(shell: Color, cream: Color) = icon {
    fill(oval(0f, -0.1f, 0.3f, 0.1f), shell)
    fill(box(-0.26f, -0.2f, 0.26f, -0.12f, 0.04f), cream, 0f)
    fill(oval(0f, -0.24f, 0.3f, 0.1f), shell)
}

private val Macarons = listOf(macaron(Color(0xFFF8A5C2), Color(0xFFFFF0F5)), macaron(Color(0xFFA8E6CF), Color(0xFFF1FFF8)), macaron(Color(0xFFC3AED6), Color(0xFFF7F0FF)))

// Binary --------------------------------------------------------------------------------------

private val Chip = icon {
    for (k in 0 until 4) {
        val f = -0.15f + k * 0.1f
        line(lines(f, -0.52f, f, -0.6f, f, -0.08f, f, 0f, -0.22f, -0.3f + f, -0.3f, -0.3f + f, 0.22f, -0.3f + f, 0.3f, -0.3f + f), Color(0xFF9E9E9E), 0.025f)
    }
    fill(box(-0.22f, -0.52f, 0.22f, -0.08f, 0.03f), Color(0xFF16261F), 0.3f)
    fill(circle(-0.14f, -0.44f, 0.025f), Color(0xFF39FF88), 0f)
    fill(box(-0.1f, -0.26f, 0.12f, -0.2f, 0.01f), Color(0xFF2E7D5B), 0f)
}

private val Terminal = icon {
    fill(box(-0.5f, -0.7f, 0.5f, 0f, 0.06f), Color(0xFF0B1F18), 0.4f)
    fill(box(-0.5f, -0.7f, 0.5f, -0.6f, 0.06f), Color(0xFF1E3A2F), 0f)
    for ((i, c) in listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).withIndex()) fill(circle(-0.42f + i * 0.07f, -0.65f, 0.022f), c, 0f)
}

private val Bug = icon {
    line(lines(-0.12f, -0.3f, -0.26f, -0.4f, -0.12f, -0.2f, -0.28f, -0.2f, -0.12f, -0.1f, -0.26f, 0f, 0.12f, -0.3f, 0.26f, -0.4f, 0.12f, -0.2f, 0.28f, -0.2f, 0.12f, -0.1f, 0.26f, 0f), Color(0xFF1B5E20), 0.03f)
    fill(oval(0f, -0.2f, 0.15f, 0.2f), Color(0xFF39FF88))
    fill(circle(0f, -0.43f, 0.08f), Color(0xFF1B5E20), 0.3f)
    line(lines(0f, -0.4f, 0f, 0f), Color(0xFF1B5E20), 0.02f)
    line(lines(-0.03f, -0.48f, -0.1f, -0.58f, 0.03f, -0.48f, 0.1f, -0.58f), Color(0xFF1B5E20), 0.02f)
}

private fun gearPath(teeth: Int): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val n = teeth * 4
    for (i in 0 until n) {
        val a = i / n.toFloat() * TAU
        val r = if ((i / 2) % 2 == 0) 0.5f else 0.38f
        if (i == 0) moveTo(cos(a) * r, sin(a) * r) else lineTo(cos(a) * r, sin(a) * r)
    }
    close()
    addOval(Rect(-0.16f, -0.16f, 0.16f, 0.16f))
}

private val Gear = icon { fill(gearPath(9), Color(0xFF2E7D5B), 0.4f) }

// Morse ---------------------------------------------------------------------------------------

private val Lighthouse = icon {
    fill(poly(-0.15f, 0f, 0.15f, 0f, 0.09f, -0.72f, -0.09f, -0.72f), Color(0xFFFAFAFA))
    fill(poly(-0.135f, -0.14f, 0.135f, -0.14f, 0.125f, -0.27f, -0.125f, -0.27f), Color(0xFFE53935), 0f)
    fill(poly(-0.115f, -0.42f, 0.115f, -0.42f, 0.105f, -0.55f, -0.105f, -0.55f), Color(0xFFE53935), 0f)
    fill(box(-0.14f, -0.77f, 0.14f, -0.72f, 0.01f), Ink, 0.2f)
    fill(box(-0.07f, -0.89f, 0.07f, -0.77f), Color(0xFFFFF59D), 0.3f)
    fill(poly(-0.1f, -0.89f, 0.1f, -0.89f, 0f, -1f), Color(0xFFE53935))
}

private val Anchor = icon {
    val c = Color(0xFF90A4AE)
    line(circle(0f, -0.88f, 0.07f), c, 0.04f)
    line(lines(0f, -0.81f, 0f, -0.06f, -0.16f, -0.7f, 0.16f, -0.7f), c, 0.06f)
    line(Path().apply {
        moveTo(-0.34f, -0.3f)
        quadraticTo(-0.3f, -0.02f, 0f, -0.04f)
        quadraticTo(0.3f, -0.02f, 0.34f, -0.3f)
    }, c, 0.06f)
    fill(poly(-0.4f, -0.34f, -0.28f, -0.32f, -0.36f, -0.22f), c, 0f)
    fill(poly(0.4f, -0.34f, 0.28f, -0.32f, 0.36f, -0.22f), c, 0f)
}

private val Wheel = icon {
    val wood = Color(0xFF8D6E63)
    for (k in 0 until 8) {
        val a = k * TAU / 8
        line(lines(cos(a) * 0.08f, sin(a) * 0.08f, cos(a) * 0.48f, sin(a) * 0.48f), wood, 0.05f)
        fill(circle(cos(a) * 0.5f, sin(a) * 0.5f, 0.05f), wood, 0f)
    }
    line(circle(0f, 0f, 0.36f), wood, 0.06f)
    fill(circle(0f, 0f, 0.1f), Color(0xFF6D4C41))
}

private val Mast = icon {
    val c = Color(0xFFB0BEC5)
    line(lines(-0.18f, 0f, 0f, -0.92f, 0.18f, 0f, 0f, -0.92f), c, 0.03f)
    for (k in 1..5) {
        val y = -k * 0.15f
        val half = 0.18f * (1f + y / 0.92f)
        line(lines(-half, y, half, y + 0.075f, half, y, -half, y + 0.075f), c, 0.015f)
    }
}

// Cats ----------------------------------------------------------------------------------------

private fun yarn(color: Color) = icon {
    fill(circle(0f, -0.3f, 0.3f), color)
    for (k in 0 until 4) {
        line(Path().apply {
            moveTo(-0.26f, -0.42f + k * 0.08f)
            quadraticTo(0f, -0.62f + k * 0.16f, 0.26f, -0.2f - k * 0.06f)
        }, Color.White.copy(alpha = 0.55f), 0.02f)
    }
    line(Path().apply {
        moveTo(0.22f, -0.1f)
        cubicTo(0.4f, 0f, 0.5f, -0.05f, 0.62f, 0f)
    }, color, 0.025f)
}

private val Yarns = listOf(yarn(Color(0xFFFF8FB3)), yarn(Color(0xFF81D4FA)), yarn(Color(0xFFFFD166)))

private val FishBone = icon {
    val c = Color(0xFFFFFDE7)
    line(lines(-0.4f, -0.2f, 0.3f, -0.2f), c, 0.04f)
    for (k in 0 until 4) {
        val x = -0.22f + k * 0.13f
        line(lines(x, -0.36f, x + 0.04f, -0.2f, x, -0.04f, x + 0.04f, -0.2f), c, 0.03f)
    }
    fill(poly(0.28f, -0.32f, 0.5f, -0.2f, 0.28f, -0.08f), c, 0f)
    fill(poly(-0.38f, -0.2f, -0.52f, -0.34f, -0.52f, -0.06f), c, 0f)
    fill(circle(0.36f, -0.22f, 0.025f), Color(0xFF6D4C41), 0f)
}

private val Paw = icon {
    val c = Color(0xFFFFFFFF)
    fill(oval(0f, -0.2f, 0.2f, 0.17f), c, 0f)
    for ((x, y) in listOf(-0.22f to -0.42f, -0.08f to -0.52f, 0.08f to -0.52f, 0.22f to -0.42f)) fill(oval(x, y, 0.07f, 0.09f), c, 0f)
}

private fun catFace(fur: Color) = icon {
    fill(poly(-0.36f, -0.42f, -0.3f, -0.8f, -0.08f, -0.56f), fur)
    fill(poly(0.36f, -0.42f, 0.3f, -0.8f, 0.08f, -0.56f), fur)
    fill(oval(0f, -0.32f, 0.4f, 0.32f), fur)
    fill(poly(-0.3f, -0.68f, -0.26f, -0.58f, -0.16f, -0.56f), Color(0xFFFFB3C6), 0f)
    fill(poly(0.3f, -0.68f, 0.26f, -0.58f, 0.16f, -0.56f), Color(0xFFFFB3C6), 0f)
    fill(oval(-0.14f, -0.36f, 0.05f, 0.07f), Color(0xFF263238), 0f)
    fill(oval(0.14f, -0.36f, 0.05f, 0.07f), Color(0xFF263238), 0f)
    fill(poly(-0.04f, -0.26f, 0.04f, -0.26f, 0f, -0.21f), Color(0xFFFF8FB3), 0f)
    line(lines(-0.2f, -0.22f, -0.46f, -0.26f, -0.2f, -0.18f, -0.46f, -0.14f, 0.2f, -0.22f, 0.46f, -0.26f, 0.2f, -0.18f, 0.46f, -0.14f), Color.White, 0.015f)
}

private val CatFaces = listOf(catFace(Color(0xFFFFB74D)), catFace(Color(0xFFB0BEC5)), catFace(Color(0xFFFFF3E0)))

private val CatTree = icon {
    val post = Color(0xFFE6C9A8)
    fill(box(-0.32f, -0.62f, -0.22f, 0f), post, 0.3f)
    fill(box(0.16f, -0.95f, 0.26f, 0f), post, 0.3f)
    for (k in 0 until 8) {
        line(lines(-0.32f, -0.05f - k * 0.075f, -0.22f, -0.08f - k * 0.075f), Color(0xFFBF9B76), 0.012f)
        line(lines(0.16f, -0.05f - k * 0.11f, 0.26f, -0.08f - k * 0.11f), Color(0xFFBF9B76), 0.012f)
    }
    fill(box(-0.5f, -0.06f, 0.44f, 0f, 0.03f), Color(0xFFCE93D8), 0.3f)
    fill(box(-0.46f, -0.68f, -0.06f, -0.6f, 0.04f), Color(0xFFF48FB1))
    fill(box(0.0f, -1f, 0.42f, -0.92f, 0.04f), Color(0xFFF48FB1))
    line(lines(-0.12f, -0.6f, -0.12f, -0.42f), Color(0xFF8D6E63), 0.012f)
    fill(circle(-0.12f, -0.38f, 0.045f), Color(0xFFFFD166), 0.4f)
}

// ---------------------------------------------------------------------------------------------
// Placement
// ---------------------------------------------------------------------------------------------

/** Positions along the walls, in panorama pixels. f runs 0..1 along each stretch. */
private class Walls(val w: Float, val h: Float, s: WallSplit) {
    private val side = s.cornerFrom * w
    private val backFrom = s.cornerTo * w
    private val backTo = (1f - s.cornerTo) * w

    /** Along the left wall from the glass (0) to its corner (1). */
    fun left(f: Float) = f * side

    /** Along the right wall from the glass (0) to its corner (1). */
    fun right(f: Float) = w - f * side

    /** Along the back wall from left (0) to right (1). */
    fun back(f: Float) = backFrom + f * (backTo - backFrom)

    val floor get() = h * 0.985f
}

/** A string sagging from [a] to [b] with [count] things hung along it by [hang]. */
private inline fun DrawScope.garland(a: Offset, b: Offset, sag: Float, count: Int, width: Float, hang: DrawScope.(Offset, Int, Float) -> Unit) {
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f + sag * 2f)
    val path = Path().apply {
        moveTo(a.x, a.y)
        quadraticTo(mid.x, mid.y, b.x, b.y)
    }
    drawPath(path, Color.White, alpha = 0.55f, style = Stroke(width))
    for (i in 0 until count) {
        val u = (i + 0.5f) / count
        hang(quadBezier(a, mid, b, u), i, u)
    }
}

/** Triangular bunting in [colors] along a sagging string. */
private fun DrawScope.bunting(a: Offset, b: Offset, sag: Float, count: Int, size: Float, colors: List<Color>, t: Float) {
    garland(a, b, sag, count, size * 0.05f) { p, i, _ ->
        val sway = sin(t * 1.8f + i * 0.7f) * 6f
        drawUnit(p, size, sway) {
            val flag = poly(-0.32f, 0f, 0.32f, 0f, 0f, 0.75f)
            drawPath(flag, colors[i % colors.size])
            drawPath(flag, Color.White, alpha = 0.5f, style = Stroke(0.04f))
        }
    }
}

private fun DrawScope.snowflake(c: Offset, r: Float, rotation: Float, alpha: Float) {
    drawUnit(c, r, rotation) {
        for (k in 0 until 6) {
            val a = k * TAU / 6
            val dx = cos(a)
            val dy = sin(a)
            drawLine(Color.White, Offset.Zero, Offset(dx, dy), 0.09f, StrokeCap.Round, alpha = alpha)
            for (f in listOf(0.45f, 0.72f)) {
                val p = Offset(dx * f, dy * f)
                val l = 0.24f * (1.1f - f)
                drawLine(Color.White, p, p + Offset(cos(a + 0.8f), sin(a + 0.8f)) * l, 0.07f, StrokeCap.Round, alpha = alpha)
                drawLine(Color.White, p, p + Offset(cos(a - 0.8f), sin(a - 0.8f)) * l, 0.07f, StrokeCap.Round, alpha = alpha)
            }
        }
    }
}

private fun DrawScope.floating(icon: Icon, base: Offset, size: Float, t: Float, seed: Int, alpha: Float = 1f) {
    val bob = sin(t * (0.8f + hash(seed) * 0.6f) + seed) * size * 0.06f
    sticker(icon, base + Offset(0f, bob), size, sin(t * 0.6f + seed) * 6f, alpha)
}

internal fun vietnamDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    // Lantern strings under the eaves of both side walls.
    for (side in 0..1) {
        val x0 = if (side == 0) g.left(0.05f) else g.right(0.05f)
        val x1 = if (side == 0) g.left(1f) else g.right(1f)
        garland(Offset(x0, h * 0.34f), Offset(x1, h * 0.3f), h * 0.05f, 4, env.dp(0.8f)) { p, i, _ ->
            val s = h * 0.045f
            drawLine(Color(0xFF7A1F0B), p, p + Offset(0f, s * 0.8f), env.dp(0.8f))
            lantern(p + Offset(sin(t * 1.6f + i) * s * 0.2f, s * 1.9f), s, t + i)
        }
    }
    sticker(Bamboo, Offset(g.left(0.3f), g.floor), h * 0.62f)
    sticker(Bamboo, Offset(g.left(0.72f), g.floor), h * 0.5f, 0f, 0.9f)
    sticker(Lotus, Offset(g.left(0.52f), g.floor), h * 0.26f)
    sticker(Lotus, Offset(g.right(0.45f), g.floor), h * 0.3f)
    sticker(Phin, Offset(g.right(0.75f), g.floor), h * 0.3f)
    sticker(Bamboo, Offset(g.right(0.2f), g.floor), h * 0.6f)
    floating(ConicalHat, Offset(g.back(0.06f), h * 0.62f), h * 0.18f, t, 1)
    sticker(Lotus, Offset(g.back(0.95f), g.floor), h * 0.2f)
    // A coffee drip from the phin.
    val drip = wrap(t * 0.9f, 1f)
    drawCircle(Color(0xFF4E342E), h * 0.008f, Offset(g.right(0.75f), g.floor - h * 0.3f * 0.5f + drip * h * 0.06f), alpha = 1f - drip)
}

internal fun londonDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    val jack = listOf(Color(0xFFC8102E), Color.White, Color(0xFF012169))
    bunting(Offset(g.left(0f), h * 0.32f), Offset(g.left(1f), h * 0.3f), h * 0.04f, 7, h * 0.07f, jack, t)
    bunting(Offset(g.right(1f), h * 0.3f), Offset(g.right(0f), h * 0.32f), h * 0.04f, 7, h * 0.07f, jack, t)
    bunting(Offset(g.back(0f), h * 0.3f), Offset(g.back(0.22f), h * 0.32f), h * 0.03f, 6, h * 0.05f, jack, t)
    bunting(Offset(g.back(0.78f), h * 0.32f), Offset(g.back(1f), h * 0.3f), h * 0.03f, 6, h * 0.05f, jack, t)
    sticker(PhoneBox, Offset(g.left(0.35f), g.floor), h * 0.6f)
    sticker(Bus, Offset(g.right(0.45f), g.floor), h * 0.42f)
    floating(Umbrella, Offset(g.left(0.75f), h * 0.72f), h * 0.26f, t, 2)
    sticker(Teacup, Offset(g.back(0.05f), g.floor), h * 0.22f)
    repeat(3) { k ->
        val e = wrap(t * 0.5f + k / 3f, 1f)
        val x = g.back(0.05f) + sin(e * 9f + k) * h * 0.02f
        drawCircle(Color.White, h * 0.012f * (1f - e), Offset(x, g.floor - h * 0.2f - e * h * 0.16f), alpha = 0.6f * (1f - e))
    }
    sticker(PhoneBox, Offset(g.right(0.85f), g.floor), h * 0.45f, 0f, 0.85f)
}

internal fun japanDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    for (side in 0..1) {
        val x0 = if (side == 0) g.left(0.08f) else g.right(0.08f)
        val x1 = if (side == 0) g.left(0.95f) else g.right(0.95f)
        garland(Offset(x0, h * 0.33f), Offset(x1, h * 0.3f), h * 0.04f, 3, env.dp(0.8f)) { p, i, _ ->
            sticker(Chochin, p + Offset(0f, h * 0.2f), h * 0.2f, sin(t * 1.4f + i * 1.3f) * 5f)
        }
    }
    // Carp streamers on a pole, swimming in the wind.
    val pole = g.right(0.55f)
    drawLine(Color(0xFF6D4C41), Offset(pole, g.floor), Offset(pole, h * 0.36f), h * 0.012f, StrokeCap.Round)
    drawCircle(Color(0xFFFFD54F), h * 0.018f, Offset(pole, h * 0.35f))
    Carps.forEachIndexed { i, c ->
        val y = h * (0.42f + i * 0.1f)
        val sway = sin(t * 2.4f + i * 0.9f)
        sticker(c, Offset(pole - h * 0.14f, y + h * 0.04f + sway * h * 0.01f), h * (0.24f - i * 0.03f), 180f + sway * 6f)
    }
    sticker(Daruma, Offset(g.left(0.4f), g.floor), h * 0.32f)
    sticker(Daruma, Offset(g.left(0.62f), g.floor), h * 0.22f, 0f, 0.95f)
    sticker(Daruma, Offset(g.back(0.94f), g.floor), h * 0.18f)
    // A sakura branch reaching in from the back corner.
    val root = Offset(g.back(0f), h * 0.28f)
    val branch = Path().apply {
        moveTo(root.x, root.y)
        cubicTo(root.x + h * 0.15f, root.y + h * 0.05f, root.x + h * 0.25f, root.y - h * 0.02f, root.x + h * 0.42f, root.y + h * 0.08f)
    }
    drawPath(branch, Color(0xFF5D4037), style = Stroke(h * 0.016f, cap = StrokeCap.Round))
    repeat(7) { k ->
        val p = Offset(root.x + h * 0.06f * (k + 0.5f), root.y + h * (0.03f + 0.03f * sin(k * 1.7f)))
        for (j in 0 until 5) {
            val a = j * TAU / 5 + k
            drawCircle(Color(0xFFFFB7C5), h * 0.016f, p + Offset(cos(a), sin(a)) * (h * 0.014f))
        }
        drawCircle(Color(0xFFE91E63), h * 0.007f, p)
    }
}

internal fun koreaDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    sticker(Hanok, Offset(g.left(0.5f), h * 0.62f), h * 0.42f)
    sticker(Hanok, Offset(g.right(0.5f), h * 0.62f), h * 0.42f)
    // A crowd of light sticks waving along the bottom of the side walls.
    val sticks = listOf(Color(0xFFFF8FB3), Color(0xFF81D4FA), Color(0xFFCE93D8))
    for (side in 0..1) for (k in 0 until 6) {
        val f = 0.1f + k * 0.16f
        val x = if (side == 0) g.left(f) else g.right(f)
        val wave = sin(t * 4f + k * 0.8f + side)
        val base = Offset(x, g.floor)
        val tip = base + Offset(wave * h * 0.04f, -h * 0.22f)
        drawLine(Color(0xFFECEFF1), base, tip, h * 0.014f, StrokeCap.Round)
        val c = sticks[(k + side) % sticks.size]
        drawGlow(tip, h * 0.1f, c, 0.5f)
        drawUnit(tip, h * 0.04f, wave * 12f) { drawPath(UnitHeart, c) }
    }
    sticker(Onggi, Offset(g.back(0.03f), g.floor), h * 0.26f)
    sticker(Onggi, Offset(g.back(0.08f), g.floor), h * 0.18f)
    floating(Mugunghwa, Offset(g.left(0.2f), h * 0.4f), h * 0.16f, t, 3)
    floating(Mugunghwa, Offset(g.right(0.85f), h * 0.36f), h * 0.14f, t, 4)
    floating(Mugunghwa, Offset(g.back(0.96f), h * 0.5f), h * 0.12f, t, 5)
}

internal fun russiaDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    Matryoshkas.forEachIndexed { i, m ->
        sticker(m, Offset(g.left(0.3f + i * 0.17f), g.floor), h * (0.44f - i * 0.1f))
    }
    sticker(Birch, Offset(g.right(0.3f), g.floor), h * 0.66f)
    sticker(Birch, Offset(g.right(0.62f), g.floor), h * 0.52f, 0f, 0.9f)
    sticker(Birch, Offset(g.back(0.03f), g.floor), h * 0.4f, 0f, 0.85f)
    floating(Balalaika, Offset(g.right(0.85f), h * 0.86f), h * 0.36f, t, 6)
    for ((i, p) in listOf(g.left(0.15f) to 0.42f, g.left(0.75f) to 0.36f, g.right(0.15f) to 0.4f, g.right(0.9f) to 0.38f, g.back(0.96f) to 0.42f).withIndex()) {
        snowflake(Offset(p.first, h * p.second), h * (0.06f + 0.02f * hash(i + 70)), t * 20f + i * 30f, 0.85f)
    }
}

internal fun parisDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    val tri = listOf(Color(0xFF0055A4), Color.White, Color(0xFFEF4135))
    bunting(Offset(g.left(0f), h * 0.32f), Offset(g.left(1f), h * 0.3f), h * 0.04f, 7, h * 0.07f, tri, t)
    bunting(Offset(g.right(1f), h * 0.3f), Offset(g.right(0f), h * 0.32f), h * 0.04f, 7, h * 0.07f, tri, t)
    for (f in listOf(0.25f, 0.8f)) {
        val x = g.left(f)
        drawGlow(Offset(x, g.floor - h * 0.56f), h * 0.18f, Color(0xFFFFE082), 0.5f)
        sticker(StreetLamp, Offset(x, g.floor), h * 0.62f)
    }
    sticker(Arc, Offset(g.right(0.6f), g.floor), h * 0.42f, 0f, 0.95f)
    floating(Croissant, Offset(g.right(0.2f), h * 0.62f), h * 0.22f, t, 7)
    floating(Baguette, Offset(g.right(0.9f), h * 0.56f), h * 0.2f, t, 8)
    Macarons.forEachIndexed { i, m -> sticker(m, Offset(g.back(0.05f), g.floor - i * h * 0.075f), h * 0.2f) }
    floating(Croissant, Offset(g.back(0.95f), h * 0.6f), h * 0.14f, t, 9)
}

internal fun matrixDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    val green = Color(0xFF39FF88)
    // Circuit traces with pulses running along them on both side walls.
    for (side in 0..1) for (k in 0 until 4) {
        val f0 = 0.05f + k * 0.22f
        fun x(f: Float) = if (side == 0) g.left(f) else g.right(f)
        val y0 = h * (0.4f + 0.12f * k)
        val path = Path().apply {
            moveTo(x(f0), h * 0.32f)
            lineTo(x(f0), y0)
            lineTo(x(f0 + 0.1f), y0 + h * 0.06f)
            lineTo(x(f0 + 0.1f), g.floor)
        }
        drawPath(path, green, alpha = 0.35f, style = Stroke(h * 0.008f))
        drawCircle(green, h * 0.014f, Offset(x(f0), y0), alpha = 0.6f)
        val e = wrap(t * 0.5f + k * 0.27f + side * 0.5f, 1f)
        val pulse = if (e < 0.5f) {
            Offset(x(f0), lerp(h * 0.32f, y0, e * 2f))
        } else {
            Offset(x(f0 + 0.1f), lerp(y0 + h * 0.06f, g.floor, (e - 0.5f) * 2f))
        }
        drawGlow(pulse, h * 0.05f, green, 0.6f)
    }
    sticker(Chip, Offset(g.left(0.4f), h * 0.72f), h * 0.24f)
    sticker(Terminal, Offset(g.right(0.5f), h * 0.74f), h * 0.32f)
    // The terminal's text, and a blinking cursor.
    val term = Offset(g.right(0.5f), h * 0.74f)
    val tw = h * 0.32f
    repeat(3) { k ->
        val len = tw * (0.3f + 0.4f * hash(k + 90))
        val y = term.y - tw * (0.48f - k * 0.11f)
        drawLine(green, Offset(term.x - tw * 0.4f, y), Offset(term.x - tw * 0.4f + len, y), tw * 0.025f, alpha = 0.8f)
    }
    if (wrap(t, 1f) < 0.5f) drawRect(green, Offset(term.x - tw * 0.4f, term.y - tw * 0.18f), androidx.compose.ui.geometry.Size(tw * 0.05f, tw * 0.08f))
    sticker(Gear, Offset(g.left(0.8f), h * 0.5f), h * 0.18f, t * 30f)
    sticker(Gear, Offset(g.left(0.8f) + h * 0.14f, h * 0.6f), h * 0.12f, -t * 45f + 20f)
    // The bug of the day, wandering up the wall.
    val bugE = wrap(t * 0.08f, 1f)
    sticker(Bug, Offset(g.back(0.95f) + sin(bugE * 20f) * h * 0.03f, lerp(g.floor, h * 0.35f, bugE)), h * 0.12f, sin(bugE * 20f) * 25f)
    sticker(Chip, Offset(g.back(0.04f), g.floor), h * 0.18f)
}

internal fun radarDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    // Lighthouse with its turning beam.
    val lx = g.left(0.4f)
    val lamp = Offset(lx, g.floor - h * 0.6f * 0.83f)
    val beam = sin(t * 1.2f)
    drawPath(
        Path().apply {
            moveTo(lamp.x, lamp.y)
            lineTo(lamp.x + h * 0.9f * beam, lamp.y - h * 0.1f)
            lineTo(lamp.x + h * 0.9f * beam, lamp.y + h * 0.1f)
            close()
        },
        Brush.horizontalGradient(listOf(Color(0x99FFF59D), Color.Transparent), lamp.x, lamp.x + h * 0.9f * beam),
    )
    drawGlow(lamp, h * 0.1f, Color(0xFFFFF59D), 0.7f)
    sticker(Lighthouse, Offset(lx, g.floor), h * 0.6f)
    // Radio mast sending out rings.
    val mx = g.right(0.4f)
    sticker(Mast, Offset(mx, g.floor), h * 0.6f)
    val top = Offset(mx, g.floor - h * 0.56f)
    if (wrap(t, 1.2f) < 0.6f) drawGlow(top, h * 0.05f, Color(0xFFFF5252), 0.9f)
    drawCircle(Color(0xFFFF5252), h * 0.012f, top)
    repeat(3) { k ->
        val e = wrap(t * 0.6f + k / 3f, 1f)
        drawCircle(Color(0xFF7DF9FF), h * (0.05f + 0.25f * e), top, alpha = 0.5f * (1f - e), style = Stroke(h * 0.008f))
    }
    sticker(Anchor, Offset(g.right(0.85f), h * 0.9f), h * 0.3f)
    sticker(Wheel, Offset(g.left(0.8f), h * 0.62f), h * 0.26f, t * 12f)
    val signal = listOf(Color(0xFFFFD54F), Color(0xFF1565C0), Color(0xFFE53935), Color.White)
    bunting(Offset(g.back(0f), h * 0.3f), Offset(g.back(0.22f), h * 0.33f), h * 0.03f, 6, h * 0.05f, signal, t)
    bunting(Offset(g.back(0.78f), h * 0.33f), Offset(g.back(1f), h * 0.3f), h * 0.03f, 6, h * 0.05f, signal, t)
    sticker(Anchor, Offset(g.back(0.04f), g.floor), h * 0.2f)
}

internal fun catDecor(env: SceneEnv): DecorDrawer = { t, split ->
    val g = Walls(env.w, env.h, split)
    val h = env.h
    sticker(CatTree, Offset(g.left(0.45f), g.floor), h * 0.64f)
    Yarns.forEachIndexed { i, y ->
        val roll = wrap(t * 0.05f + i * 0.33f, 1f)
        sticker(y, Offset(g.right(0.15f + 0.7f * roll), g.floor), h * (0.16f + 0.03f * i), roll * 720f)
    }
    // Paw prints walking up the walls.
    for (side in 0..1) repeat(6) { k ->
        val f = 0.1f + k * 0.14f
        val x = (if (side == 0) g.left(f) else g.right(f)) + (if (k % 2 == 0) -1f else 1f) * h * 0.02f
        val y = h * (0.88f - k * 0.08f)
        val lit = wrap(t * 0.8f - k * 0.18f - side * 0.5f, 1f)
        sticker(Paw, Offset(x, y), h * 0.07f, if (side == 0) 20f else -20f, 0.25f + 0.5f * (1f - lit))
    }
    CatFaces.forEachIndexed { i, c ->
        val peek = (sin(t * 0.9f + i * 2.1f) * 0.5f + 0.5f)
        val x = when (i) {
            0 -> g.left(0.85f)
            1 -> g.right(0.6f)
            else -> g.back(0.95f)
        }
        sticker(c, Offset(x, h * 0.5f + (1f - peek) * h * 0.06f), h * 0.2f, sin(t + i) * 8f)
    }
    floating(FishBone, Offset(g.left(0.15f), h * 0.45f), h * 0.18f, t, 10)
    floating(FishBone, Offset(g.back(0.05f), h * 0.5f), h * 0.14f, t, 11)
}
