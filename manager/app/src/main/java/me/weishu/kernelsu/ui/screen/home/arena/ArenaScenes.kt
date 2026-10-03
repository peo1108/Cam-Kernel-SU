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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import me.weishu.kernelsu.ui.screen.home.arena.three.Ground
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What a scene needs to pre-build its static shapes for one card size. */
internal class SceneEnv(
    val size: Size,
    private val density: Density,
    val zero: TextLayoutResult,
    val one: TextLayoutResult,
) {
    val w get() = size.width
    val h get() = size.height
    fun dp(v: Float) = v * density.density
}

/** Draws one frame of a scene's scenery (everything above the color wash) at time [t] seconds. */
internal typealias SceneDrawer = DrawScope.(t: Float) -> Unit

/**
 * Key light of a scene: where it sits (fractions of the card), its color, the ambient fill color
 * that tints everything in its shadow, and how strongly it blooms.
 */
internal class ArenaLight(val x: Float, val y: Float, val color: Color, val ambient: Color, val bloom: Float = 0.35f)

/** One status language: its words, the country scene behind them and what the slimes wear there. */
internal class ArenaLanguage(
    val text: String,
    val label: String,
    /** Translucent color wash laid over the glass card, top to bottom. */
    val wash: List<Color>,
    val confetti: List<Color>,
    val costume: Costume,
    /** What the choking slime says as it throws this language up. */
    val retch: String,
    val light: ArenaLight,
    /** What the diorama floor is made of, and whether the sky on the ceiling has stars. */
    val ground: Ground,
    val night: Boolean,
    val scene: (SceneEnv) -> SceneDrawer,
    /** The country's symbols for the 3D diorama's side walls. */
    val decor: (SceneEnv) -> DecorDrawer,
)

internal val ArenaLanguages: List<ArenaLanguage> = listOf(
    ArenaLanguage(
        text = "Đang hoạt động",
        label = "🇻🇳  VIỆT NAM",
        wash = listOf(Color(0xFFE5383B), Color(0xFFF77F3F), Color(0xFFFFB347)),
        confetti = listOf(Color(0xFFFFD43B), Color(0xFFE03131), Color(0xFFFFF3BF)),
        costume = Costume.NonLa,
        retch = "Ọe~",
        light = ArenaLight(0.86f, 0.24f, Color(0xFFFFD98A), Color(0xFFFF8A65), 0.4f),
        ground = Ground.Water,
        night = false,
        scene = ::vietnamScene,
        decor = ::vietnamDecor,
    ),
    ArenaLanguage(
        text = "Working",
        label = "🇬🇧  UNITED KINGDOM",
        wash = listOf(Color(0xFF1D3B8F), Color(0xFF4C4FB0), Color(0xFF8E6BC4)),
        confetti = listOf(Color(0xFFC8102E), Color.White, Color(0xFF4F7BFF)),
        costume = Costume.TopHat,
        retch = "Bleh!",
        light = ArenaLight(0.84f, 0.3f, Color(0xFFFFE8B0), Color(0xFF6C7BD9), 0.3f),
        ground = Ground.Cobble,
        night = true,
        scene = ::londonScene,
        decor = ::londonDecor,
    ),
    ArenaLanguage(
        text = "動作中",
        label = "🇯🇵  日本",
        wash = listOf(Color(0xFFF25C8A), Color(0xFFFF8E8E), Color(0xFFFFB98A)),
        confetti = listOf(Color(0xFFFFC4D6), Color.White, Color(0xFFE63946)),
        costume = Costume.Hachimaki,
        retch = "オエッ",
        light = ArenaLight(0.74f, 0.4f, Color(0xFFFF9A8B), Color(0xFFFFB3C6), 0.45f),
        ground = Ground.Gravel,
        night = false,
        scene = ::japanScene,
        decor = ::japanDecor,
    ),
    ArenaLanguage(
        text = "작동 중",
        label = "🇰🇷  대한민국",
        wash = listOf(Color(0xFF2F5FD0), Color(0xFF4F86E0), Color(0xFF8EC5FF)),
        confetti = listOf(Color(0xFFCD2E3A), Color(0xFF3D7BFF), Color.White),
        costume = Costume.Gat,
        retch = "우웩",
        light = ArenaLight(0.2f, 0.05f, Color(0xFFDCE8FF), Color(0xFF4F86E0), 0.25f),
        ground = Ground.Wood,
        night = true,
        scene = ::koreaScene,
        decor = ::koreaDecor,
    ),
    ArenaLanguage(
        text = "Работает",
        label = "🇷🇺  РОССИЯ",
        wash = listOf(Color(0xFF243B8F), Color(0xFF3A5BD9), Color(0xFF8FB4FF)),
        confetti = listOf(Color.White, Color(0xFF3D7BFF), Color(0xFFD52B1E)),
        costume = Costume.Ushanka,
        retch = "Буэ!",
        light = ArenaLight(0.4f, 0.04f, Color(0xFF9BF6DA), Color(0xFF8FB4FF), 0.3f),
        ground = Ground.Snow,
        night = true,
        scene = ::russiaScene,
        decor = ::russiaDecor,
    ),
    ArenaLanguage(
        text = "En marche",
        label = "🇫🇷  FRANCE",
        wash = listOf(Color(0xFF3B4BC8), Color(0xFF9A5CC0), Color(0xFFF28CA8)),
        confetti = listOf(Color(0xFF3D7BFF), Color.White, Color(0xFFEF4135)),
        costume = Costume.Beret,
        retch = "Beurk!",
        light = ArenaLight(0.4f, 0.88f, Color(0xFFFFC09F), Color(0xFFC69BD9), 0.4f),
        ground = Ground.Cobble,
        night = false,
        scene = ::parisScene,
        decor = ::parisDecor,
    ),
    ArenaLanguage(
        text = "01001111 01001011",
        label = "💻  BINARY · OK",
        wash = listOf(Color(0xFF06261C), Color(0xFF0B3D2E), Color(0xFF0F5C45)),
        confetti = listOf(Color(0xFF39FF88), Color(0xFFCCFFDD)),
        costume = Costume.Hacker,
        retch = "0xBAD",
        light = ArenaLight(0.5f, -0.1f, Color(0xFF6BFFAA), Color(0xFF0F5C45), 0.3f),
        ground = Ground.Grid,
        night = true,
        scene = ::matrixScene,
        decor = ::matrixDecor,
    ),
    ArenaLanguage(
        text = ".-- --- .-. -.-",
        label = "📡  MORSE · WORK",
        wash = listOf(Color(0xFF0B3556), Color(0xFF0F4C75), Color(0xFF2E7DAF)),
        confetti = listOf(Color(0xFF4DF0FF), Color.White),
        costume = Costume.Radio,
        retch = "-.-.--",
        light = ArenaLight(0.5f, 1.04f, Color(0xFF7DF9FF), Color(0xFF2E7DAF), 0.35f),
        ground = Ground.Deck,
        night = true,
        scene = ::radarScene,
        decor = ::radarDecor,
    ),
    ArenaLanguage(
        text = "Meo meo meo~",
        label = "🐾  VƯƠNG QUỐC MÈO",
        wash = listOf(Color(0xFFE86A9E), Color(0xFFF58FB0), Color(0xFFF7B27A)),
        confetti = listOf(Color(0xFFFF8FB3), Color(0xFFFFD166), Color.White),
        costume = Costume.Cat,
        retch = "Hrrk~",
        light = ArenaLight(0.86f, 0.24f, Color(0xFFFFF3BF), Color(0xFFF58FB0), 0.4f),
        ground = Ground.Rug,
        night = false,
        scene = ::catScene,
        decor = ::catDecor,
    ),
)

// ---------------------------------------------------------------------------------------------
// Frosted glass shapes: every landmark is a pane of white frost with a bright top rim.
// ---------------------------------------------------------------------------------------------

private class Frost(val path: Path, top: Float, bottom: Float, a0: Float, a1: Float, val rim: Float = 0f) {
    val brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = a0), Color.White.copy(alpha = a1)), top, bottom)
}

private fun DrawScope.frost(f: Frost, rimWidth: Float) {
    drawPath(f.path, f.brush)
    if (f.rim > 0f) drawPath(f.path, Color.White, alpha = f.rim, style = Stroke(rimWidth, join = StrokeJoin.Round))
}

/** A row of rounded humps from [baseY] up to [height]: karst peaks, hills, snow drifts. */
private fun hillsPath(w: Float, h: Float, baseY: Float, count: Int, height: Float, seed: Int, x0: Float = 0f, x1: Float = w) =
    Path().apply {
        moveTo(x0, h + 10f)
        lineTo(x0, baseY)
        val seg = (x1 - x0) / count
        for (k in 0 until count) {
            val a = x0 + k * seg
            val b = a + seg
            val top = baseY - height * (0.45f + 0.55f * hash(seed + k))
            val bulge = seg * (0.05f + 0.15f * hash(seed * 7 + k))
            cubicTo(a + bulge, top, b - bulge, top, b, baseY)
        }
        lineTo(x1, h + 10f)
        close()
    }

private fun DrawScope.twinkles(env: SceneEnv, t: Float, count: Int, seed: Int, maxY: Float) {
    repeat(count) { i ->
        val pos = Offset(hash(seed + i) * env.w, hash(seed + i * 3 + 1) * maxY)
        val pulse = ((sin(t * (1.5f + hash(seed + i * 5)) * 2f + i) + 1f) / 2f).pow(2)
        drawSparkle(pos, env.dp(1.2f + 2.2f * pulse), Color.White, 0.2f + 0.7f * pulse)
    }
}

/** Little "v" birds gliding across. */
private fun DrawScope.birds(env: SceneEnv, t: Float, seed: Int, count: Int, y: Float) {
    repeat(count) { i ->
        val x = wrap(t * env.dp(14f + 6f * hash(seed + i)) + hash(seed + i * 5) * env.w * 1.2f, env.w * 1.2f) - env.w * 0.1f
        val yy = y + hash(seed + i * 9) * env.h * 0.15f + sin(t * 1.3f + i) * env.dp(3f)
        val flap = sin(t * 9f + i * 2f) * env.dp(2.5f)
        val s = env.dp(5f + 2f * hash(seed + i * 13))
        val path = Path().apply {
            moveTo(x - s, yy - flap)
            quadraticTo(x - s * 0.4f, yy - s * 0.15f, x, yy + s * 0.15f)
            quadraticTo(x + s * 0.4f, yy - s * 0.15f, x + s, yy - flap)
        }
        drawPath(path, Color.White, alpha = 0.6f, style = Stroke(env.dp(1.2f), cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------------------------------------
// Scenes
// ---------------------------------------------------------------------------------------------

/** Ha Long Bay at dusk: the gold star, frosted karst peaks, a sampan, Hoi An lanterns drifting up. */
private fun vietnamScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val star = Offset(w * 0.86f, h * 0.24f)
    val far = Frost(hillsPath(w, h, h * 0.74f, 8, h * 0.36f, 3), h * 0.4f, h * 0.8f, 0.16f, 0.04f)
    val mid = Frost(hillsPath(w, h, h * 0.86f, 6, h * 0.26f, 11), h * 0.55f, h * 0.9f, 0.24f, 0.08f, rim = 0.3f)
    val water = Frost(Path().apply { addRect(Rect(0f, h * 0.9f, w, h)) }, h * 0.9f, h, 0.18f, 0.06f, rim = 0.35f)
    val boatX = w * 0.3f
    val boatY = h * 0.885f
    val s = env.dp(13f)
    val hull = Path().apply {
        moveTo(boatX - s * 1.6f, boatY - s * 0.35f)
        quadraticTo(boatX, boatY + s * 0.35f, boatX + s * 1.7f, boatY - s * 0.45f)
        lineTo(boatX + s * 1.2f, boatY - s * 0.05f)
        quadraticTo(boatX, boatY + s * 0.15f, boatX - s * 1.2f, boatY - s * 0.05f)
        close()
    }
    val sail = Path().apply {
        moveTo(boatX - s * 0.1f, boatY - s * 0.3f)
        lineTo(boatX - s * 0.1f, boatY - s * 2.3f)
        quadraticTo(boatX + s * 1.3f, boatY - s * 1.6f, boatX + s * 1.1f, boatY - s * 0.45f)
        close()
    }
    return { t ->
        drawGodRays(star, h * 1.1f, t, Color(0xFFFFE8A3), 0.1f)
        drawGlow(star, h * 0.38f, Color(0xFFFFE066), 0.55f)
        drawUnit(star, h * 0.09f * (1f + 0.04f * sin(t * 2f)), 0f) {
            drawPath(UnitStar, Color(0xFFFFE066))
            drawPath(UnitStar, Color(0xFFFFF3BF), style = Stroke(0.06f, join = StrokeJoin.Round))
        }
        frost(far, env.dp(1f))
        repeat(6) { i ->
            val speed = h * (0.05f + 0.04f * hash(i))
            val y = h * 0.95f - wrap(t * speed + hash(i + 7) * h * 1.2f, h * 1.2f)
            val x = (0.08f + 0.84f * hash(i + 3)) * w + sin(t * 0.7f + i) * env.dp(6f)
            lantern(Offset(x, y), env.dp(5f + 2f * hash(i + 30)), t + i)
        }
        frost(mid, env.dp(1f))
        frost(water, env.dp(1f))
        repeat(9) { i ->
            val x = wrap(hash(i + 50) * w + t * env.dp(8f) * (0.5f + hash(i + 51)), w)
            val y = h * (0.92f + 0.07f * hash(i + 52))
            val len = env.dp(10f + 14f * hash(i + 53))
            drawLine(Color.White, Offset(x, y), Offset(x + len, y), env.dp(1f), StrokeCap.Round, alpha = 0.25f + 0.2f * sin(t * 2f + i))
        }
        withTransform({ translate(sin(t * 0.25f) * w * 0.02f, sin(t * 1.6f) * env.dp(1.5f)) }) {
            drawPath(sail, Brush.verticalGradient(listOf(Color(0xCCFF8A65), Color(0x99E8590C)), boatY - s * 2.3f, boatY), alpha = 0.85f)
            for (k in 1..3) {
                val y = boatY - s * (0.3f + 0.5f * k)
                drawLine(Color.White, Offset(boatX - s * 0.1f, y), Offset(boatX + s * (1.05f - 0.12f * k), y + s * 0.1f), env.dp(0.8f), alpha = 0.5f)
            }
            drawPath(hull, Color.White, alpha = 0.55f)
            drawPath(hull, Color.White, alpha = 0.8f, style = Stroke(env.dp(1f)))
        }
    }
}

/** Hoi An silk lantern: ribbed glowing body, caps and a swaying tassel. */
internal fun DrawScope.lantern(c: Offset, s: Float, t: Float) {
    drawGlow(c, s * 4f, Color(0xFFFFA94D), 0.55f)
    val body = Size(s * 1.7f, s * 2f)
    drawOval(
        Brush.radialGradient(listOf(Color(0xFFFFD08A), Color(0xFFFF6B3D), Color(0xFFD9480F)), c - Offset(s * 0.2f, s * 0.3f), s * 1.6f),
        c - Offset(body.width / 2, body.height / 2),
        body,
    )
    for (k in -1..1) {
        drawArc(
            Color(0xFFB8360B), -90f, 180f, false,
            Offset(c.x - s * 0.45f * abs(k) - s * 0.05f + k * s * 0.25f, c.y - s), Size(s * 0.4f + s * 0.25f * (1 - abs(k)), s * 2f),
            alpha = 0.35f, style = Stroke(s * 0.08f),
        )
    }
    drawRect(Color(0xFF7A1F0B), Offset(c.x - s * 0.45f, c.y - s * 1.12f), Size(s * 0.9f, s * 0.24f))
    drawRect(Color(0xFF7A1F0B), Offset(c.x - s * 0.45f, c.y + s * 0.88f), Size(s * 0.9f, s * 0.24f))
    val sway = sin(t * 2.2f) * s * 0.3f
    drawLine(Color(0xFFFFD43B), Offset(c.x, c.y + s * 1.1f), Offset(c.x + sway, c.y + s * 1.9f), s * 0.12f, StrokeCap.Round)
}

/** Dusk over the Thames: a frosted skyline, the London Eye turning, Big Ben, drizzle and puddle ripples. */
private fun londonScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val ground = h * 0.93f
    val skyline = Path().apply {
        var x = 0f
        var i = 0
        while (x < w) {
            val bw = env.dp(14f + 22f * hash(i + 200))
            val bh = h * (0.12f + 0.2f * hash(i + 210))
            addRect(Rect(x, ground - bh, x + bw - env.dp(2f), h))
            x += bw
            i++
        }
    }
    val far = Frost(skyline, h * 0.6f, h, 0.14f, 0.05f)
    val towerX = w * 0.84f
    val tw = max(env.dp(18f), w * 0.04f)
    val towerTop = h * 0.36f
    val ben = Path().apply {
        addRect(Rect(towerX - tw / 2, towerTop, towerX + tw / 2, h))
        addRect(Rect(towerX - tw * 0.62f, towerTop - tw * 0.95f, towerX + tw * 0.62f, towerTop + tw * 0.2f))
        moveTo(towerX - tw * 0.62f, towerTop - tw * 0.95f)
        lineTo(towerX, towerTop - tw * 3.3f)
        lineTo(towerX + tw * 0.62f, towerTop - tw * 0.95f)
        close()
    }
    val benFrost = Frost(ben, towerTop - tw * 3.3f, h, 0.36f, 0.12f, rim = 0.45f)
    val clock = Offset(towerX, towerTop - tw * 0.38f)
    val eye = Offset(w * 0.16f, h * 0.56f)
    val eyeR = h * 0.3f
    val lamps = listOf(w * 0.42f, w * 0.64f)
    return { t ->
        frost(far, env.dp(1f))
        drawLine(Color.White, eye, Offset(eye.x - eyeR * 0.5f, h), env.dp(2.5f), alpha = 0.3f)
        drawLine(Color.White, eye, Offset(eye.x + eyeR * 0.5f, h), env.dp(2.5f), alpha = 0.3f)
        drawCircle(Color.White, eyeR, eye, alpha = 0.42f, style = Stroke(env.dp(2f)))
        drawCircle(Color.White, eyeR * 0.94f, eye, alpha = 0.18f, style = Stroke(env.dp(1f)))
        repeat(18) { k ->
            val a = t * 0.12f + k * TAU / 18
            val rim = Offset(eye.x + eyeR * cos(a), eye.y + eyeR * sin(a))
            drawLine(Color.White, eye, rim, env.dp(0.7f), alpha = 0.14f)
            drawRoundRect(Color(0xFFFFF4D6), rim - Offset(env.dp(2.5f), env.dp(1.5f)), Size(env.dp(5f), env.dp(4f)), CornerRadius(env.dp(1.5f)), alpha = 0.85f)
        }
        drawCircle(Color.White, env.dp(4f), eye, alpha = 0.5f)
        lamps.forEach { x ->
            drawLine(Color.White, Offset(x, ground), Offset(x, ground - h * 0.18f), env.dp(1.5f), alpha = 0.45f)
            drawGlow(Offset(x, ground - h * 0.19f), env.dp(20f), Color(0xFFFFD27A), 0.6f)
            drawCircle(Color(0xFFFFF0C2), env.dp(2.5f), Offset(x, ground - h * 0.19f))
        }
        frost(benFrost, env.dp(1f))
        drawGlow(clock, tw * 1.2f, Color(0xFFFFF0C2), 0.5f)
        drawCircle(Color(0xFFFFFBEB), tw * 0.42f, clock)
        repeat(12) { k ->
            val a = k * TAU / 12
            drawLine(
                Color(0xFF1D3B8F),
                clock + Offset(sin(a), -cos(a)) * (tw * 0.33f),
                clock + Offset(sin(a), -cos(a)) * (tw * 0.38f),
                env.dp(0.8f),
            )
        }
        val minute = t * 0.6f
        drawLine(Color(0xFF1D3B8F), clock, clock + Offset(sin(minute), -cos(minute)) * (tw * 0.3f), env.dp(1.2f), StrokeCap.Round)
        drawLine(Color(0xFF1D3B8F), clock, clock + Offset(sin(minute / 12f), -cos(minute / 12f)) * (tw * 0.2f), env.dp(1.6f), StrokeCap.Round)
        repeat(22) { i ->
            val x = hash(i) * w * 1.1f
            val y = wrap(t * h * 1.4f + hash(i + 40) * h, h * 1.2f) - h * 0.1f
            drawLine(Color.White, Offset(x, y), Offset(x - env.dp(3f), y + env.dp(9f)), env.dp(1f), StrokeCap.Round, alpha = 0.28f)
        }
        repeat(5) { i ->
            val e = wrap(t * 0.9f + hash(i + 60), 1f)
            val c = Offset(hash(i + 61) * w, ground + (h - ground) * (0.3f + 0.5f * hash(i + 62)))
            drawOval(Color.White, c - Offset(env.dp(14f) * e, env.dp(3f) * e), Size(env.dp(28f) * e, env.dp(6f) * e), alpha = 0.4f * (1f - e), style = Stroke(env.dp(1f)))
        }
    }
}

/** Mount Fuji under a setting sun with drifting cloud bands, a vermilion torii and tumbling sakura. */
private fun japanScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val peakL = Offset(w * 0.6f, h * 0.36f)
    val peakR = Offset(w * 0.68f, h * 0.36f)
    val baseL = Offset(w * 0.22f, h * 0.98f)
    val baseR = Offset(w * 1.06f, h * 0.98f)
    val fujiPath = Path().apply {
        moveTo(baseL.x, h + 10f)
        lineTo(baseL.x, baseL.y)
        quadraticTo(w * 0.46f, h * 0.6f, peakL.x, peakL.y)
        quadraticTo((peakL.x + peakR.x) / 2, peakL.y - env.dp(3f), peakR.x, peakR.y)
        quadraticTo(w * 0.82f, h * 0.6f, baseR.x, baseR.y)
        lineTo(baseR.x, h + 10f)
        close()
    }
    val fuji = Frost(fujiPath, peakL.y, h, 0.34f, 0.1f, rim = 0.4f)
    val l = lerp(peakL, baseL, 0.24f)
    val r = lerp(peakR, baseR, 0.24f)
    val snow = Path().apply {
        moveTo(peakL.x, peakL.y)
        quadraticTo((peakL.x + peakR.x) / 2, peakL.y - env.dp(3f), peakR.x, peakR.y)
        lineTo(r.x, r.y)
        listOf(0.16f to 7f, 0.3f to 1f, 0.46f to 9f, 0.62f to 2f, 0.8f to 6f).forEach { (f, dy) ->
            val p = lerp(r, l, f)
            quadraticTo(p.x + env.dp(4f), p.y + env.dp(dy) + env.dp(4f), p.x, p.y + env.dp(dy))
        }
        lineTo(l.x, l.y)
        close()
    }
    val sun = Offset(w * 0.74f, h * 0.4f)
    val sunR = h * 0.17f
    val toriiX = w * 0.12f
    val tg = env.dp(1f)
    val ground = h * 0.95f
    return { t ->
        drawGlow(sun, sunR * 2.6f, Color(0xFFFFC9A3), 0.5f)
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFFF8787), Color(0xFFFF5C5C)), sun.y - sunR, sun.y + sunR), sunR, sun)
        repeat(3) { k ->
            val y = sun.y - sunR * 0.4f + k * sunR * 0.55f
            val x = wrap(t * env.dp(6f) * (1f + k * 0.4f) + k * w * 0.3f, w * 1.4f) - w * 0.2f
            drawRoundRect(Color.White, Offset(x, y), Size(w * 0.22f, env.dp(5f)), CornerRadius(env.dp(3f)), alpha = 0.35f)
        }
        frost(fuji, env.dp(1f))
        drawPath(snow, Color.White, alpha = 0.88f)
        birds(env, t, 500, 3, h * 0.12f)
        val vermilion = Color(0xFFE8590C)
        drawRect(vermilion, Offset(toriiX - 24 * tg, ground - 54 * tg), Size(4 * tg, 54 * tg), alpha = 0.85f)
        drawRect(vermilion, Offset(toriiX + 20 * tg, ground - 54 * tg), Size(4 * tg, 54 * tg), alpha = 0.85f)
        drawRect(vermilion, Offset(toriiX - 28 * tg, ground - 46 * tg), Size(56 * tg, 3.5f * tg), alpha = 0.85f)
        val kasagi = Path().apply {
            moveTo(toriiX - 34 * tg, ground - 60 * tg)
            quadraticTo(toriiX, ground - 54 * tg, toriiX + 34 * tg, ground - 60 * tg)
            lineTo(toriiX + 32 * tg, ground - 55 * tg)
            quadraticTo(toriiX, ground - 50 * tg, toriiX - 32 * tg, ground - 55 * tg)
            close()
        }
        drawPath(kasagi, Color(0xFF3B1F1A), alpha = 0.8f)
        repeat(20) { i ->
            val y = wrap(t * h * (0.1f + 0.07f * hash(i)) + hash(i + 9) * h * 1.2f, h * 1.2f) - h * 0.1f
            val x = wrap(hash(i) * w * 1.2f + t * w * 0.035f + sin(t * 1.3f + i) * env.dp(12f), w * 1.1f) - w * 0.05f
            val flip = cos(t * (2f + 2f * hash(i + 3)) + i)
            withTransform({
                rotate(t * 70f * (0.5f + hash(i + 2)) + i * 40f, Offset(x, y))
                scale(max(0.2f, abs(flip)), 1f, Offset(x, y))
            }) {
                drawUnit(Offset(x, y), env.dp(4.5f)) {
                    drawPath(UnitHeart, Brush.verticalGradient(listOf(Color(0xFFFFE3EC), Color(0xFFFFA8C5)), -1f, 1f), alpha = 0.95f)
                }
            }
        }
    }
}

/** Seoul at night: a faint taegeuk, frosted ridgelines and the N Seoul Tower blinking. */
private fun koreaScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val c = Offset(w * 0.2f, h * 0.44f)
    val big = h * 0.28f
    val far = Frost(hillsPath(w, h, h * 0.76f, 5, h * 0.3f, 21), h * 0.4f, h, 0.14f, 0.04f)
    val mid = Frost(hillsPath(w, h, h * 0.86f, 4, h * 0.24f, 33), h * 0.55f, h, 0.22f, 0.06f, rim = 0.3f)
    val towerX = w * 0.8f
    val shaftTop = h * 0.28f
    val towerBase = h * 0.72f
    val tower = Path().apply {
        addRect(Rect(towerX - env.dp(3.5f), shaftTop, towerX + env.dp(3.5f), towerBase))
        addOval(Rect(towerX - env.dp(15f), shaftTop + env.dp(3f), towerX + env.dp(15f), shaftTop + env.dp(15f)))
        addRect(Rect(towerX - env.dp(1.2f), shaftTop - h * 0.14f, towerX + env.dp(1.2f), shaftTop))
        addOval(Rect(towerX - h * 0.12f, towerBase - h * 0.04f, towerX + h * 0.12f, towerBase + h * 0.14f))
    }
    val towerFrost = Frost(tower, shaftTop - h * 0.14f, towerBase, 0.5f, 0.2f, rim = 0.45f)
    return { t ->
        twinkles(env, t, 16, 70, h * 0.55f)
        drawArc(Color(0xFFFF6B6B), 180f, 180f, true, c - Offset(big, big), Size(big * 2, big * 2), alpha = 0.3f)
        drawArc(Color(0xFF4F86FF), 0f, 180f, true, c - Offset(big, big), Size(big * 2, big * 2), alpha = 0.3f)
        drawCircle(Color(0xFFFF6B6B), big / 2, c - Offset(big / 2, 0f), alpha = 0.3f)
        drawCircle(Color(0xFF4F86FF), big / 2, c + Offset(big / 2, 0f), alpha = 0.3f)
        drawCircle(Color.White, big, c, alpha = 0.25f, style = Stroke(env.dp(1f)))
        frost(far, env.dp(1f))
        frost(towerFrost, env.dp(1f))
        repeat(5) { k ->
            drawCircle(Color(0xFFFFF0C2), env.dp(1.2f), Offset(towerX - env.dp(11f) + k * env.dp(5.5f), shaftTop + env.dp(9f)), alpha = 0.8f)
        }
        val blink = ((sin(t * 3f) + 1f) / 2f).pow(3)
        drawGlow(Offset(towerX, shaftTop - h * 0.14f), env.dp(12f), Color(0xFFFF4D4D), blink)
        drawCircle(Color(0xFFFF6B6B), env.dp(2.2f), Offset(towerX, shaftTop - h * 0.14f))
        frost(mid, env.dp(1f))
    }
}

/** Red Square in winter: aurora ribbons, candy-striped onion domes and falling snow. */
private fun russiaScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val baseTop = h * 0.66f
    val ground = h * 0.9f
    class Dome(val x: Float, val top: Float, val width: Float, val color: Color)
    val domes = listOf(
        Dome(0.58f, 0.38f, 0.065f, Color(0xFF2A9D8F)),
        Dome(0.86f, 0.4f, 0.065f, Color(0xFFF4A261)),
        Dome(0.65f, 0.28f, 0.075f, Color(0xFF4F86FF)),
        Dome(0.79f, 0.27f, 0.075f, Color(0xFFFFD43B)),
        Dome(0.72f, 0.12f, 0.09f, Color(0xFFFF6B6B)),
    )
    val towers = Path().apply { addRect(Rect(w * 0.54f, baseTop, w * 0.9f, h)) }
    val bulbs = domes.map { d ->
        val cx = w * d.x
        val dw = w * d.width
        val dh = dw * 1.25f
        val y0 = h * d.top + dh
        towers.addRect(Rect(cx - dw * 0.3f, y0, cx + dw * 0.3f, baseTop))
        val bulb = Path().apply {
            moveTo(cx - dw * 0.35f, y0)
            cubicTo(cx - dw * 0.78f, y0 - dh * 0.45f, cx - dw * 0.05f, y0 - dh * 0.72f, cx, y0 - dh)
            cubicTo(cx + dw * 0.05f, y0 - dh * 0.72f, cx + dw * 0.78f, y0 - dh * 0.45f, cx + dw * 0.35f, y0)
            close()
        }
        Triple(bulb, d, Rect(cx - dw * 0.6f, y0 - dh, cx + dw * 0.6f, y0))
    }
    val towerFrost = Frost(towers, h * 0.3f, h, 0.3f, 0.12f, rim = 0.4f)
    val snowGround = Frost(hillsPath(w, h, ground, 7, h * 0.06f, 51), ground - h * 0.06f, h, 0.55f, 0.3f, rim = 0.6f)
    return { t ->
        repeat(2) { k ->
            val path = Path().apply {
                val y0 = h * (0.16f + 0.1f * k)
                moveTo(0f, y0)
                var x = 0f
                while (x <= w) {
                    lineTo(x, y0 + sin(x / w * 7f + t * (0.6f + 0.3f * k) + k) * h * 0.06f)
                    x += w / 24f
                }
                lineTo(w, y0 + h * 0.2f)
                lineTo(0f, y0 + h * 0.2f)
                close()
            }
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(if (k == 0) Color(0x5574F2CE) else Color(0x44B197FC), Color.Transparent),
                    h * (0.12f + 0.1f * k),
                    h * (0.4f + 0.1f * k),
                ),
            )
        }
        frost(towerFrost, env.dp(1f))
        bulbs.forEach { (bulb, d, box) ->
            drawPath(bulb, Brush.horizontalGradient(listOf(lerp(d.color, Color.White, 0.35f), d.color), box.left, box.right))
            clipPath(bulb) {
                for (k in -3..3) {
                    val x = box.center.x + k * box.width * 0.28f
                    drawLine(Color.White, Offset(x - box.width * 0.4f, box.bottom), Offset(x + box.width * 0.4f, box.top), box.width * 0.08f, alpha = 0.35f)
                }
            }
            drawPath(bulb, Color.White, alpha = 0.6f, style = Stroke(env.dp(1f)))
            drawLine(Color(0xFFFFE08A), Offset(box.center.x, box.top), Offset(box.center.x, box.top - env.dp(9f)), env.dp(1.4f))
            drawLine(Color(0xFFFFE08A), Offset(box.center.x - env.dp(3f), box.top - env.dp(6f)), Offset(box.center.x + env.dp(3f), box.top - env.dp(6f)), env.dp(1.4f))
        }
        frost(snowGround, env.dp(1f))
        repeat(34) { i ->
            val y = wrap(t * h * (0.07f + 0.08f * hash(i)) + hash(i + 5) * h * 1.2f, h * 1.2f) - h * 0.1f
            val x = hash(i) * w + sin(t * 0.8f + i) * env.dp(10f)
            val big = hash(i + 11) > 0.82f
            if (big) {
                val s = env.dp(4f)
                rotate(t * 40f + i * 20f, Offset(x, y)) {
                    repeat(3) { k ->
                        val a = k * TAU / 6
                        drawLine(Color.White, Offset(x - cos(a) * s, y - sin(a) * s), Offset(x + cos(a) * s, y + sin(a) * s), env.dp(1f), StrokeCap.Round, alpha = 0.9f)
                    }
                }
            } else {
                drawCircle(Color.White, env.dp(1.2f + 1.8f * hash(i + 12)), Offset(x, y), alpha = 0.85f)
            }
        }
    }
}

/** Paris at golden hour: the Eiffel Tower with its lattice and hourly sparkle, birds over the Seine. */
private fun parisScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val cx = w * 0.78f
    val baseY = h * 0.94f
    val topY = h * 0.06f
    val tall = baseY - topY
    val bw = h * 0.24f
    val towerPath = Path().apply {
        moveTo(cx - bw, baseY)
        quadraticTo(cx - bw * 0.35f, baseY - tall * 0.35f, cx - bw * 0.12f, baseY - tall * 0.62f)
        lineTo(cx - env.dp(1.5f), topY + env.dp(6f))
        lineTo(cx, topY)
        lineTo(cx + env.dp(1.5f), topY + env.dp(6f))
        lineTo(cx + bw * 0.12f, baseY - tall * 0.62f)
        quadraticTo(cx + bw * 0.35f, baseY - tall * 0.35f, cx + bw, baseY)
        lineTo(cx + bw * 0.58f, baseY)
        quadraticTo(cx, baseY - tall * 0.26f, cx - bw * 0.58f, baseY)
        close()
    }
    val tower = Frost(towerPath, topY, baseY, 0.42f, 0.16f, rim = 0.5f)
    val lights = List(26) { i ->
        val f = 0.06f + 0.86f * hash(i + 90)
        val half = lerp(bw * 0.75f, env.dp(2f), f.pow(0.55f))
        Offset(cx + (hash(i + 120) * 2f - 1f) * half * 0.8f, baseY - tall * f)
    }
    val horizon = Offset(w * 0.4f, h * 0.9f)
    return { t ->
        drawGlow(horizon, w * 0.5f, Color(0xFFFFC9A3), 0.4f)
        twinkles(env, t, 10, 300, h * 0.4f)
        birds(env, t, 520, 4, h * 0.14f)
        frost(tower, env.dp(1f))
        clipPath(towerPath) {
            var k = -20f
            while (k < 20f) {
                val x = cx + k * env.dp(7f)
                drawLine(Color.White, Offset(x - tall * 0.3f, baseY), Offset(x + tall * 0.3f, topY), env.dp(0.6f), alpha = 0.22f)
                drawLine(Color.White, Offset(x + tall * 0.3f, baseY), Offset(x - tall * 0.3f, topY), env.dp(0.6f), alpha = 0.22f)
                k += 1f
            }
        }
        drawRect(Color.White, Offset(cx - bw * 0.42f, baseY - tall * 0.37f), Size(bw * 0.84f, env.dp(3f)), alpha = 0.65f)
        drawRect(Color.White, Offset(cx - bw * 0.2f, baseY - tall * 0.63f), Size(bw * 0.4f, env.dp(2.5f)), alpha = 0.65f)
        drawGlow(Offset(cx, topY + env.dp(4f)), env.dp(14f), Color(0xFFFFF4C2), 0.5f + 0.5f * sin(t * 2.5f))
        val sparkleOn = wrap(t, 12f) < 4f
        lights.forEachIndexed { i, p ->
            val a = if (sparkleOn) max(0f, sin(t * 7f + i * 1.7f)).pow(8) else 0.15f
            drawSparkle(p, env.dp(2.4f), Color(0xFFFFF4C2), a)
        }
        repeat(8) { i ->
            val x = wrap(hash(i + 130) * w + t * env.dp(6f), w)
            val y = h * (0.95f + 0.04f * hash(i + 131))
            drawLine(Color.White, Offset(x, y), Offset(x + env.dp(12f + 10f * hash(i + 132)), y), env.dp(1f), StrokeCap.Round, alpha = 0.35f)
        }
    }
}

/** Matrix rain of 0s and 1s over a faint grid, with a scanline sweeping down. */
private fun matrixScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val colW = max(env.dp(16f), w / 30f)
    val cols = (w / colW).toInt() + 1
    val glyphH = env.zero.size.height.toFloat()
    val grid = env.dp(26f)
    return { t ->
        var gx = 0f
        while (gx < w) {
            drawLine(Color(0xFF39FF88), Offset(gx, 0f), Offset(gx, h), env.dp(0.5f), alpha = 0.07f)
            gx += grid
        }
        var gy = 0f
        while (gy < h) {
            drawLine(Color(0xFF39FF88), Offset(0f, gy), Offset(w, gy), env.dp(0.5f), alpha = 0.07f)
            gy += grid
        }
        for (c in 0 until cols) {
            val speed = h * (0.3f + 0.4f * hash(c))
            val head = wrap(t * speed + hash(c + 3) * h * 2f, h * 1.6f) - h * 0.2f
            for (k in 0 until 7) {
                val y = head - k * glyphH
                if (y < -glyphH || y > h) continue
                val bit = hash(c * 31 + k + floor(t * 4f + c).toInt()) > 0.5f
                val layout = if (bit) env.one else env.zero
                drawText(
                    layout,
                    color = if (k == 0) Color(0xFFE6FFEF) else Color(0xFF39FF88),
                    topLeft = Offset(c * colW + (colW - layout.size.width) / 2f, y),
                    alpha = (1f - k / 7f) * 0.7f,
                )
            }
        }
        val scan = wrap(t * h * 0.35f, h * 1.4f) - h * 0.2f
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color(0x3339FF88), Color.Transparent), scan - env.dp(18f), scan + env.dp(18f)),
            Offset(0f, scan - env.dp(18f)),
            Size(w, env.dp(36f)),
        )
    }
}

/** Radar sweep with range rings, bearing ticks and blips that light up as the beam passes. */
private fun radarScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val center = Offset(w * 0.5f, h * 1.04f)
    val maxR = hypot(w * 0.5f, h)
    val cyan = Color(0xFF7DF9FF)
    val blips = List(8) { i ->
        val a = TAU / 2 + (0.06f + 0.88f * hash(i + 400)) * TAU / 2
        val d = maxR * (0.22f + 0.66f * hash(i + 410))
        a to Offset(center.x + d * cos(a), center.y + d * sin(a))
    }
    return { t ->
        for (k in 1..6) drawCircle(cyan, maxR * k / 6f, center, alpha = 0.16f, style = Stroke(env.dp(1f)))
        repeat(72) { k ->
            val a = TAU / 2 + k * TAU / 144
            val len = if (k % 6 == 0) env.dp(8f) else env.dp(4f)
            val rr = min(maxR * 0.66f, h * 0.98f)
            drawLine(cyan, center + Offset(cos(a), sin(a)) * rr, center + Offset(cos(a), sin(a)) * (rr - len), env.dp(0.8f), alpha = 0.3f)
        }
        drawLine(cyan, Offset(0f, center.y - env.dp(1f)), Offset(w, center.y - env.dp(1f)), env.dp(1f), alpha = 0.12f)
        drawLine(cyan, center, Offset(center.x, 0f), env.dp(1f), alpha = 0.12f)
        val sweep = wrap(t * 1.2f, TAU)
        repeat(26) { k ->
            val a = sweep - k * 0.022f
            drawLine(cyan, center, Offset(center.x + maxR * cos(a), center.y + maxR * sin(a)), env.dp(2.2f), alpha = 0.4f * (1f - k / 26f))
        }
        blips.forEach { (a, p) ->
            val since = wrap(sweep - a, TAU)
            val alpha = max(0f, 1f - since / 2.6f)
            drawGlow(p, env.dp(10f), cyan, 0.6f * alpha)
            drawCircle(cyan, env.dp(3f), p, alpha = alpha)
            drawCircle(cyan, env.dp(3f) + since * env.dp(5f), p, alpha = alpha * 0.4f, style = Stroke(env.dp(1f)))
        }
    }
}

/** Pastel kingdom of cats: a sleepy moon, paw prints walking past, fish bones and a rolling yarn ball. */
private fun catScene(env: SceneEnv): SceneDrawer {
    val w = env.w
    val h = env.h
    val paws = List(12) { i ->
        val p = Offset(w * (0.04f + 0.92f * i / 11f), h * (0.62f + if (i % 2 == 0) 0f else 0.1f) - h * 0.38f * sin(i / 11f * 3.14f))
        Triple(p, -20f + 40f * (i / 11f), env.dp(6f + 3f * hash(i + 620)))
    }
    val moon = Offset(w * 0.86f, h * 0.24f)
    val moonR = h * 0.12f
    return { t ->
        drawGlow(moon, moonR * 2.6f, Color(0xFFFFF3BF), 0.5f)
        drawCircle(Color(0xFFFFF7D6), moonR, moon, alpha = 0.95f)
        drawArc(Color(0xFFB07A2A), 20f, 140f, false, moon + Offset(-moonR * 0.55f, -moonR * 0.25f), Size(moonR * 0.4f, moonR * 0.25f), style = Stroke(env.dp(1.5f), cap = StrokeCap.Round))
        drawArc(Color(0xFFB07A2A), 20f, 140f, false, moon + Offset(moonR * 0.15f, -moonR * 0.25f), Size(moonR * 0.4f, moonR * 0.25f), style = Stroke(env.dp(1.5f), cap = StrokeCap.Round))
        drawCircle(Color(0xFFFFB3C7), moonR * 0.13f, moon + Offset(-moonR * 0.45f, moonR * 0.22f), alpha = 0.8f)
        drawCircle(Color(0xFFFFB3C7), moonR * 0.13f, moon + Offset(moonR * 0.45f, moonR * 0.22f), alpha = 0.8f)
        paws.forEachIndexed { i, (p, rot, s) ->
            val step = wrap(t * 0.6f - i / 12f, 1f)
            val alpha = 0.12f + 0.4f * (1f - step).pow(2)
            rotate(rot, p) {
                drawOval(Color.White, Offset(p.x - s * 0.6f, p.y - s * 0.1f), Size(s * 1.2f, s), alpha = alpha)
                listOf(-0.75f to -0.45f, -0.27f to -0.85f, 0.27f to -0.85f, 0.75f to -0.45f).forEach { (dx, dy) ->
                    drawCircle(Color.White, s * 0.3f, Offset(p.x + dx * s, p.y + dy * s), alpha = alpha)
                }
            }
        }
        repeat(4) { i ->
            val x = wrap(t * w * 0.04f * (1f + hash(i + 700)) + hash(i + 710) * w * 1.3f, w * 1.3f) - w * 0.15f
            val y = h * (0.1f + 0.5f * hash(i + 720)) + sin(t + i) * env.dp(6f)
            val len = env.dp(22f)
            val bone = Color.White
            rotate(sin(t * 1.5f + i) * 10f, Offset(x, y)) {
                drawLine(bone, Offset(x - len / 2, y), Offset(x + len / 2, y), env.dp(1.6f), StrokeCap.Round, alpha = 0.75f)
                drawCircle(bone, env.dp(4.2f), Offset(x + len / 2 + env.dp(3f), y), alpha = 0.75f)
                drawCircle(Color(0xFFE86A9E), env.dp(1f), Offset(x + len / 2 + env.dp(4.5f), y - env.dp(1f)), alpha = 0.8f)
                for (k in 0..2) {
                    val rx = x - len * 0.25f + k * len * 0.22f
                    drawLine(bone, Offset(rx, y - env.dp(4.5f)), Offset(rx, y + env.dp(4.5f)), env.dp(1.4f), StrokeCap.Round, alpha = 0.75f)
                }
                drawLine(bone, Offset(x - len / 2, y), Offset(x - len / 2 - env.dp(5f), y - env.dp(4f)), env.dp(1.4f), StrokeCap.Round, alpha = 0.75f)
                drawLine(bone, Offset(x - len / 2, y), Offset(x - len / 2 - env.dp(5f), y + env.dp(4f)), env.dp(1.4f), StrokeCap.Round, alpha = 0.75f)
            }
        }
        val yarnR = env.dp(11f)
        val yarnX = wrap(t * env.dp(18f), w + yarnR * 8f) - yarnR * 4f
        val yarn = Offset(yarnX, h * 0.96f - yarnR)
        rotate(t * 160f, yarn) {
            drawCircle(Color(0xFFB197FC), yarnR, yarn, alpha = 0.9f)
            repeat(4) { k ->
                drawArc(Color.White, k * 45f, 160f, false, yarn - Offset(yarnR * 0.8f, yarnR * 0.8f), Size(yarnR * 1.6f, yarnR * 1.6f), alpha = 0.5f, style = Stroke(env.dp(1f)))
            }
        }
        drawLine(Color(0xFFB197FC), yarn + Offset(-yarnR, yarnR * 0.6f), yarn + Offset(-yarnR * 4f, yarnR), env.dp(1.2f), StrokeCap.Round, alpha = 0.8f)
    }
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
