package me.weishu.kernelsu.ui.screen.home.arena

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How Bơ throws the next country up. The four take turns, one per round, so the change of scene
 * never plays out the same way twice in a row.
 */
internal enum class VomitStyle(val seconds: Float) {
    /** A little suitcase shoots out, lands and pops open: the new country unfolds out of it. */
    Suitcase(3.4f),

    /** A swirling glob splats on the back wall into a portal that swallows the old scene. */
    Portal(3.5f),

    /** Bơ blows a soap bubble with the next country inside; Chanh jumps up and pops it. */
    Bubble(3.4f),

    /** Goo right onto the glass; two giant wipers clear it and the new country is behind. */
    Splat(3.9f),
}

/** How much of the next scene shows, in card pixels: nothing, all of it, a band around x, or a disc. */
internal class Reveal(val kind: Kind, val x: Float = 0f, val y: Float = 0f, val size: Float = 0f) {
    enum class Kind { None, All, Band, Disc }

    companion object {
        val NONE = Reveal(Kind.None)
        val ALL = Reveal(Kind.All)
    }
}

/** Where a glyph of the next word is while it flies into place. */
internal class GlyphPose {
    var pos = Offset.Zero
    var scale = 1f
    var rotation = 0f
    var alpha = 1f
}

/**
 * The timing and the geometry of each style, shared by the slimes' poses, the overlay and the
 * walls of the 3D box so they all agree on where the suitcase, portal, bubble or wipers are.
 * Positions are in card pixels; [on] says whether the 3D stage's perspective applies.
 */
internal object Vomit {
    /** Bơ heaves until here, then whatever it is comes out. */
    const val HEAVE = 0.35f

    // Suitcase.
    private const val CASE_LAND = 0.9f
    const val CASE_OPEN = 1.2f
    private const val CASE_DEPTH = 1.1f

    // Portal.
    private const val PORTAL_OPEN = 0.9f
    private const val PORTAL_WHOOSH = 2.0f
    private const val PORTAL_FULL = 2.6f

    // Bubble.
    private const val BUBBLE_FREE = 1.3f
    const val POP = 2.0f

    // Splat.
    private const val SPLAT = 0.75f
    private const val WIPE = 1.35f
    private const val WIPE_SECONDS = 1.0f
    private const val WIPE_BACK = 2.55f

    private fun scaleAt(s: Stage, depth: Float, on: Boolean) = if (on) s.viewScale(depth) else 1f

    private fun screen(s: Stage, x: Float, y: Float, depth: Float, on: Boolean): Offset {
        val k = scaleAt(s, depth, on)
        return Offset(s.center + (x - s.center) * k, s.eyeY + (y - s.eyeY) * k)
    }

    // --- Suitcase --------------------------------------------------------------------------

    private fun caseX(s: Stage, sh: Show) = sh.spotX[CHANH] + s.radius[CHANH] * 1.2f + s.r * 1.9f

    /** Bottom center of the landed suitcase, and its size. */
    fun caseBase(s: Stage, sh: Show, on: Boolean) = screen(s, caseX(s, sh), s.groundY, CASE_DEPTH, on)
    fun caseSize(s: Stage, on: Boolean) = s.r * 1.3f * scaleAt(s, CASE_DEPTH, on)

    /** Half width of the band of new scene unfolding from the suitcase. */
    private fun caseBand(s: Stage, lt: Float) = easeOutCubic(progress(lt, CASE_OPEN + 0.05f, 0.9f)) * s.w * 1.1f

    // --- Portal ----------------------------------------------------------------------------

    fun portalCenter(s: Stage) = Offset(s.w * 0.72f, s.h * 0.4f)

    fun portalRadius(s: Stage, lt: Float): Float {
        val r0 = s.h * 0.24f
        return when {
            lt < PORTAL_OPEN -> 0f
            lt < PORTAL_OPEN + 0.4f -> r0 * easeOutBack(progress(lt, PORTAL_OPEN, 0.4f), 2f)
            lt < PORTAL_WHOOSH -> r0 * (1f + 0.05f * sin(lt * 9f))
            else -> r0 + easeInCubic(progress(lt, PORTAL_WHOOSH, PORTAL_FULL - PORTAL_WHOOSH)) * s.w * 1.1f
        }
    }

    // --- Bubble ----------------------------------------------------------------------------

    fun bubbleRadius(s: Stage, lt: Float): Float {
        val grow = easeOutCubic(progress(lt, HEAVE, BUBBLE_FREE - HEAVE))
        return s.h * 0.3f * grow * (1f + 0.04f * sin(lt * 11f) * (1f - grow * 0.5f))
    }

    private fun bubbleHigh(s: Stage) = Offset(s.center + s.w * 0.12f, s.h * 0.34f)

    /** Bubble center: on Bơ's lips while it grows, then floating up and over. */
    fun bubbleCenter(s: Stage, lt: Float, mouth: Offset): Offset {
        val rr = bubbleRadius(s, lt)
        val attached = mouth + Offset(rr * 0.72f, -rr * 0.78f)
        if (lt < BUBBLE_FREE) return attached
        val e = easeInOutCubic(progress(lt, BUBBLE_FREE, POP - BUBBLE_FREE - 0.15f))
        val high = bubbleHigh(s)
        val ctrl = Offset(lerp(attached.x, high.x, 0.3f), min(attached.y, high.y) - s.h * 0.08f)
        return quadBezier(attached, ctrl, high, e) + Offset(0f, sin(lt * 4f) * s.h * 0.012f)
    }

    /** Where Chanh has to jump to poke the floating bubble. */
    fun bubblePokeX(s: Stage) = bubbleHigh(s).x

    // --- Splat -----------------------------------------------------------------------------

    private fun gooCenter(s: Stage) = Offset(s.center, s.h * 0.5f)

    fun wiperPivots(s: Stage) = listOf(Offset(s.w * 0.27f, s.h * 1.06f), Offset(s.w * 0.73f, s.h * 1.06f))
    fun wiperLength(s: Stage) = s.w * 0.5f

    /** Wiper angle from lying flat to the right (0) up and over to the left (π), and back. */
    fun wiperAngle(lt: Float): Float = when {
        lt < WIPE_BACK -> PI.toFloat() * easeInOutCubic(progress(lt, WIPE, WIPE_SECONDS))
        else -> PI.toFloat() * (1f - easeInOutCubic(progress(lt, WIPE_BACK, 0.7f)))
    }

    /** How far the wipers have cleared, which only ever grows. */
    fun swept(lt: Float) = PI.toFloat() * easeInOutCubic(progress(lt, WIPE, WIPE_SECONDS))

    /** Angle of [p] seen from the wiper that sweeps it. */
    private fun wipeAngleOf(s: Stage, p: Offset): Float {
        val pivot = wiperPivots(s).let { if (p.x < s.center) it[0] else it[1] }
        return atan2(pivot.y - p.y, p.x - pivot.x)
    }

    // --- Shared ----------------------------------------------------------------------------

    fun reveal(style: VomitStyle, s: Stage, sh: Show, lt: Float, on: Boolean): Reveal = when (style) {
        VomitStyle.Suitcase -> {
            val half = caseBand(s, lt)
            when {
                half <= 0f -> Reveal.NONE
                half >= s.w * 1.05f -> Reveal.ALL
                else -> Reveal(Reveal.Kind.Band, caseBase(s, sh, on).x, s.h * 0.5f, half)
            }
        }

        VomitStyle.Portal -> when {
            lt < PORTAL_OPEN -> Reveal.NONE
            lt >= PORTAL_FULL -> Reveal.ALL
            else -> portalCenter(s).let { Reveal(Reveal.Kind.Disc, it.x, it.y, portalRadius(s, lt) * 0.97f) }
        }

        VomitStyle.Bubble -> if (lt >= POP) Reveal.ALL else Reveal.NONE
        VomitStyle.Splat -> if (lt >= SPLAT + 0.25f) Reveal.ALL else Reveal.NONE
    }

    /** When the lights, the floor and the sky switch to the new country (0 or 1). */
    fun mix(style: VomitStyle, lt: Float): Float = when (style) {
        VomitStyle.Suitcase -> if (lt >= CASE_OPEN + 0.4f) 1f else 0f
        VomitStyle.Portal -> if (lt >= PORTAL_WHOOSH + 0.3f) 1f else 0f
        VomitStyle.Bubble -> if (lt >= POP) 1f else 0f
        VomitStyle.Splat -> if (lt >= SPLAT + 0.25f) 1f else 0f
    }

    /** Camera shake, in stage radii. */
    fun shake(style: VomitStyle, lt: Float): Float = when (style) {
        VomitStyle.Suitcase -> 0.08f * bump(lt, CASE_LAND, 0.3f) + 0.04f * bump(lt, CASE_OPEN, 0.25f)
        VomitStyle.Portal -> 0.05f * bump(lt, PORTAL_OPEN, 0.3f) + if (lt in PORTAL_WHOOSH..PORTAL_FULL) 0.07f else 0f
        VomitStyle.Bubble -> 0.06f * bump(lt, POP, 0.3f)
        VomitStyle.Splat -> 0.16f * bump(lt, SPLAT, 0.4f)
    }

    private fun bump(lt: Float, at: Float, length: Float) = if (lt in at..at + length) 1f - (lt - at) / length else 0f

    /** Whether slime [k], standing at [x], gets its new costume dropped on it by now. */
    fun dropDue(style: VomitStyle, s: Stage, sh: Show, k: Int, x: Float, lt: Float, on: Boolean): Boolean = when (style) {
        VomitStyle.Suitcase -> lt > CASE_OPEN && caseBand(s, lt) >= abs(x - caseBase(s, sh, on).x)
        VomitStyle.Portal -> lt >= PORTAL_WHOOSH && portalRadius(s, lt) >= hypot(x - portalCenter(s).x, s.h * 0.6f - portalCenter(s).y)
        VomitStyle.Bubble -> lt >= POP + 0.1f + k * 0.08f
        VomitStyle.Splat -> lt > WIPE && swept(lt) >= wipeAngleOf(s, Offset(x, s.h * 0.55f))
    }

    /**
     * Pose of glyph [i] of [n] of the next word, whose resting center is [target]; false while it
     * is not out yet. [mouth] is Bơ's mouth.
     */
    fun glyph(style: VomitStyle, s: Stage, sh: Show, i: Int, n: Int, lt: Float, target: Offset, mouth: Offset, on: Boolean, out: GlyphPose): Boolean {
        val f = i / max(1f, n - 1f)
        when (style) {
            VomitStyle.Suitcase -> {
                val from = caseBase(s, sh, on) - Offset(0f, caseSize(s, on) * 0.75f)
                val e = progress(lt, CASE_OPEN + 0.1f + f * 0.6f, 0.5f)
                if (e <= 0f) return false
                val ctrl = Offset(lerp(from.x, target.x, 0.5f), min(from.y, target.y) - s.h * 0.25f)
                out.pos = quadBezier(from, ctrl, target, easeOutCubic(e))
                out.scale = 0.3f + 0.7f * easeOutBack(e)
                out.rotation = (1f - e) * 360f * (if (i % 2 == 0) 1f else -1f)
                out.alpha = min(1f, e * 5f)
            }

            VomitStyle.Portal -> {
                val from = portalCenter(s)
                val e = progress(lt, PORTAL_OPEN + 0.55f + f * 0.5f, 0.45f)
                if (e <= 0f) return false
                out.pos = lerp(from, target, easeOutBack(e, 1.3f))
                out.scale = 0.2f + 0.8f * easeOutCubic(e)
                out.rotation = (1f - e) * -200f
                out.alpha = min(1f, e * 4f)
            }

            VomitStyle.Bubble -> {
                val from = bubbleHigh(s)
                if (lt < POP) return false
                val burst = easeOutCubic(progress(lt, POP, 0.25f))
                val a = hash(i * 31 + 7) * TAU
                val flung = from + Offset(cos(a), sin(a)) * (s.h * (0.15f + 0.15f * hash(i + 3)) * burst)
                val settle = easeInOutCubic(progress(lt, POP + 0.25f + f * 0.25f, 0.45f))
                out.pos = lerp(flung, target, settle)
                out.scale = 0.5f + 0.5f * settle
                out.rotation = (1f - settle) * (hash(i + 9) - 0.5f) * 540f
                out.alpha = 1f
            }

            VomitStyle.Splat -> {
                val past = swept(lt) - wipeAngleOf(s, target)
                if (lt <= WIPE || past <= 0f) return false
                val e = (past / 0.45f).coerceIn(0f, 1f)
                out.pos = target
                out.scale = easeOutBack(e, 2.2f)
                out.rotation = (1f - e) * 25f
                out.alpha = min(1f, e * 3f)
            }
        }
        return true
    }

    /** Where things come out of in this style: the open suitcase, the portal, the burst bubble, Bơ's mouth. */
    fun origin(style: VomitStyle, s: Stage, sh: Show, mouth: Offset, on: Boolean): Offset = when (style) {
        VomitStyle.Suitcase -> caseBase(s, sh, on) - Offset(0f, caseSize(s, on) * 0.75f)
        VomitStyle.Portal -> portalCenter(s)
        VomitStyle.Bubble -> bubbleHigh(s)
        VomitStyle.Splat -> mouth
    }

    /** When the new word has fully arrived, for its country label. */
    fun wordDone(style: VomitStyle): Float = when (style) {
        VomitStyle.Suitcase -> CASE_OPEN + 1.3f
        VomitStyle.Portal -> PORTAL_OPEN + 1.5f
        VomitStyle.Bubble -> POP + 0.95f
        VomitStyle.Splat -> WIPE + WIPE_SECONDS + 0.4f
    }

    /** Haptics, as (seconds into the phase, 0 tap / 1 thump / 2 confirm). */
    fun buzz(style: VomitStyle): List<Pair<Float, Int>> = when (style) {
        VomitStyle.Suitcase -> listOf(0.4f to 0, CASE_LAND to 1, CASE_OPEN to 2)
        VomitStyle.Portal -> listOf(0.4f to 0, PORTAL_OPEN to 1, PORTAL_WHOOSH to 1, PORTAL_FULL to 2)
        VomitStyle.Bubble -> listOf(0.4f to 0, BUBBLE_FREE to 0, POP to 2)
        VomitStyle.Splat -> listOf(0.4f to 0, SPLAT to 1, WIPE to 0, WIPE + 0.5f to 0, WIPE + WIPE_SECONDS to 0, WIPE_BACK + 0.7f to 2)
    }

    // --- Drawing ---------------------------------------------------------------------------

    /**
     * The overlay for the current style: what flies out of Bơ, the suitcase, the portal ring, the
     * bubble with the next country inside, or the goo on the glass and the wipers.
     */
    fun DrawScope.draw(
        rt: ArenaRuntime,
        sh: Show,
        lt: Float,
        now: Float,
        s: Stage,
        languages: List<ArenaLanguage>,
        pop: TextLayoutResult,
        miniScene: DrawScope.(Int) -> Unit,
    ) {
        val on = rt.projected
        val bo = rt.poses[BO]
        val mouth = bo.mouth(s.radius[BO], s.groundY, now)
        val colors = languages[sh.next].wash
        val r = s.r
        // "Ọe~" in the next country's language as it comes up.
        rt.runs?.get(sh.next)?.retch?.let { retch ->
            if (lt < 0.75f) drawSpeechBubble(mouth + Offset(-s.radius[BO] * 0.5f, 0f), retch, progress(lt, 0f, 0.75f), s.radius[BO])
        }
        when (sh.style) {
            VomitStyle.Suitcase -> {
                val base = caseBase(s, sh, on)
                val size = caseSize(s, on)
                val fly = progress(lt, HEAVE + 0.05f, CASE_LAND - HEAVE - 0.05f)
                if (fly <= 0f) return
                val gone = progress(lt, 2.85f, 0.4f)
                if (fly < 1f) {
                    val ctrl = Offset(lerp(mouth.x, base.x, 0.5f), min(mouth.y, base.y) - s.h * 0.45f)
                    val at = quadBezier(mouth, ctrl, base, fly)
                    drawSuitcase(at, size * (0.35f + 0.65f * fly), 0f, (1f - fly) * 540f, 1f, colors, 0f, now)
                } else {
                    val land = progress(lt, CASE_LAND, 0.25f)
                    val squash = 1f - 0.25f * sin(PI.toFloat() * land) * (1f - land)
                    val lid = easeOutBack(progress(lt, CASE_OPEN, 0.3f), 2.4f) * (1f - easeInCubic(progress(lt, 2.6f, 0.25f)))
                    val glow = progress(lt, CASE_OPEN, 0.2f) * (1f - progress(lt, 2.3f, 0.5f))
                    if (land < 1f) drawDust(base, land, size)
                    drawSuitcase(base, size * (1f - gone), lid, 0f, squash, colors, glow, now)
                    if (gone > 0f && gone < 1f) drawDust(base, gone, size * 0.8f)
                    // The edges of the new country unfolding like a pop-up book.
                    val half = caseBand(s, lt)
                    if (half > 0f && half < s.w) {
                        for (edge in listOf(base.x - half, base.x + half)) {
                            if (edge < s.w * 0.18f || edge > s.w * 0.82f) continue
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent), edge - r * 0.25f, edge + r * 0.25f),
                                Offset(edge - r * 0.25f, s.h * 0.08f), Size(r * 0.5f, s.h * 0.55f),
                            )
                            repeat(3) { k -> drawSparkle(Offset(edge, s.h * (0.15f + 0.2f * k) + sin(now * 5f + k) * r * 0.1f), r * 0.07f, Color.White, 0.9f) }
                        }
                    }
                }
            }

            VomitStyle.Portal -> {
                val c = portalCenter(s)
                val fly = progress(lt, HEAVE + 0.05f, PORTAL_OPEN - HEAVE - 0.05f)
                if (fly in 0.001f..0.999f) {
                    val ctrl = Offset(lerp(mouth.x, c.x, 0.4f), min(mouth.y, c.y) - s.h * 0.3f)
                    val at = quadBezier(mouth, ctrl, c, easeInCubic(fly))
                    repeat(5) { k ->
                        val back = quadBezier(mouth, ctrl, c, easeInCubic(max(0f, fly - k * 0.05f)))
                        drawCircle(colors[k % colors.size], r * (0.16f - k * 0.025f), back, alpha = 0.8f - k * 0.14f)
                    }
                    drawGlob(at, r * 0.3f, now, colors)
                }
                val rr = portalRadius(s, lt)
                if (rr > 0f) {
                    val fade = 1f - progress(lt, PORTAL_WHOOSH + 0.3f, PORTAL_FULL - PORTAL_WHOOSH - 0.3f)
                    if (lt < PORTAL_OPEN + 0.3f) drawImpact(c, progress(lt, PORTAL_OPEN, 0.3f), r * 0.8f)
                    if (fade > 0f) drawPortal(c, rr, now, colors, fade)
                }
            }

            VomitStyle.Bubble -> {
                if (lt < POP) {
                    val rr = bubbleRadius(s, lt)
                    if (rr > r * 0.05f) {
                        val c = bubbleCenter(s, lt, mouth)
                        drawBubble(c, rr, now) { miniScene(sh.next) }
                        // The next country's name tag floating inside, so you can tell where it goes.
                        rt.runs?.get(sh.next)?.label?.let { tag ->
                            val k = rr * 1.3f / tag.size.width
                            withTransform({ scale(k, k, c) }) {
                                drawText(tag, topLeft = c - Offset(tag.size.width / 2f, tag.size.height / 2f), alpha = 0.9f)
                            }
                        }
                    }
                } else {
                    val e = progress(lt, POP, 0.45f)
                    val c = bubbleHigh(s)
                    val rr = s.h * 0.3f
                    if (e < 1f) {
                        drawRect(Color.White, alpha = 0.55f * (1f - progress(lt, POP, 0.22f)))
                        repeat(18) { k ->
                            val a = k * TAU / 18 + hash(k + 40) * 0.3f
                            val d = rr * (1f + 0.9f * easeOutCubic(e))
                            val p = c + Offset(cos(a) * d, sin(a) * d + e * e * rr * 0.6f)
                            drawCircle(Color.White, r * 0.05f * (1f - e), p, alpha = 0.8f * (1f - e))
                            drawCircle(colors[k % colors.size], r * 0.03f * (1f - e), p, alpha = 0.8f * (1f - e))
                        }
                        drawCircle(Color.White, rr * (1f + 0.3f * e), c, alpha = 0.5f * (1f - e), style = Stroke(r * 0.04f * (1f - e)))
                    }
                    drawComicWord(pop, c + Offset(rr * 0.9f, -rr * 0.5f), progress(lt, POP, 0.6f), -12f, 4f * s.px)
                }
            }

            VomitStyle.Splat -> drawSplat(s, lt, now, mouth, colors)
        }
    }

    private fun DrawScope.drawGlob(c: Offset, radius: Float, t: Float, colors: List<Color>) {
        val path = Path().apply { liquidCircle(c, radius, t) }
        drawPath(path, Brush.radialGradient(listOf(colors.last(), colors.first()), c - Offset(radius * 0.3f, radius * 0.3f), radius * 1.3f))
        drawCircle(Color.White, radius * 0.25f, c - Offset(radius * 0.35f, radius * 0.35f), alpha = 0.7f)
    }

    /** A travel suitcase with stickers in the next country's colors; [lid] 0 shut .. 1 flung open. */
    private fun DrawScope.drawSuitcase(base: Offset, size: Float, lid: Float, rotation: Float, squash: Float, colors: List<Color>, glow: Float, t: Float) {
        if (size <= 0f) return
        val w = size * 1.5f
        val h = size
        val hinge = Offset(base.x - w / 2f, base.y - h * 0.74f)
        // Light pouring out of the open case.
        if (glow > 0f) {
            val mouth = Offset(base.x, base.y - h * 0.74f)
            drawGodRays(mouth, size * 4f, t, colors.last(), 0.35f * glow)
            drawGlow(mouth, size * 2.4f, colors.last(), 0.7f * glow)
            repeat(7) { k ->
                val e = wrap(t * 0.9f + k / 7f, 1f)
                val p = mouth + Offset((hash(k + 60) - 0.5f) * w * 1.4f * e, -e * size * 2.4f)
                drawSparkle(p, size * 0.1f * (1f - e), Color.White, glow * (1f - e))
            }
        }
        withTransform({
            rotate(rotation, Offset(base.x, base.y - h / 2f))
            scale(1f + (1f - squash) * 0.6f, squash, base)
        }) {
            val leather = Color(0xFFC97B3A)
            val dark = Color(0xFF7A4A21)
            // Body.
            val bodyTop = base.y - h * 0.74f
            drawRoundRect(Color.Black, Offset(base.x - w / 2f + size * 0.04f, bodyTop + size * 0.06f), Size(w, h * 0.74f), CornerRadius(size * 0.12f), alpha = 0.18f)
            drawRoundRect(
                Brush.verticalGradient(listOf(leather, androidx.compose.ui.graphics.lerp(leather, Color.Black, 0.25f)), bodyTop, base.y),
                Offset(base.x - w / 2f, bodyTop), Size(w, h * 0.74f), CornerRadius(size * 0.12f),
            )
            for (sx in listOf(-0.28f, 0.28f)) {
                drawRect(dark, Offset(base.x + sx * w - size * 0.06f, bodyTop), Size(size * 0.12f, h * 0.74f))
                drawRoundRect(Color(0xFFFFD166), Offset(base.x + sx * w - size * 0.08f, bodyTop + h * 0.08f), Size(size * 0.16f, size * 0.1f), CornerRadius(size * 0.02f))
            }
            // Stickers from the next country.
            drawCircle(colors.first(), size * 0.15f, Offset(base.x - w * 0.06f, bodyTop + h * 0.4f))
            drawCircle(Color.White, size * 0.15f, Offset(base.x - w * 0.06f, bodyTop + h * 0.4f), style = Stroke(size * 0.025f))
            drawRoundRect(colors.last(), Offset(base.x + w * 0.06f, bodyTop + h * 0.22f), Size(size * 0.28f, size * 0.18f), CornerRadius(size * 0.04f))
            drawUnit(Offset(base.x - w * 0.38f, bodyTop + h * 0.52f), size * 0.08f, 12f) { drawPath(UnitStar, Color(0xFFFFE066)) }
            for (cx in listOf(-1f, 1f)) drawCircle(Color(0xFFB0BEC5), size * 0.06f, Offset(base.x + cx * (w / 2f - size * 0.08f), base.y - size * 0.06f))
            // Inside of the case, seen once the lid is up.
            if (lid > 0.05f) {
                drawRoundRect(Color(0xFF3E2410), Offset(base.x - w / 2f + size * 0.06f, bodyTop - size * 0.02f), Size(w - size * 0.12f, size * 0.1f * min(1f, lid * 2f)), CornerRadius(size * 0.04f))
            }
            // The lid swings up round its hinge on the left.
            withTransform({ rotate(-110f * lid, hinge) }) {
                val lidTop = base.y - h
                drawRoundRect(Color(0xFFB86A2C), Offset(base.x - w / 2f, lidTop), Size(w, h * 0.27f), CornerRadius(size * 0.12f))
                // Its lining shows as it swings past upright.
                if (lid > 0.5f) drawRoundRect(colors.first(), Offset(base.x - w / 2f + size * 0.06f, lidTop + h * 0.05f), Size(w - size * 0.12f, h * 0.17f), CornerRadius(size * 0.06f), alpha = progress(lid, 0.5f, 0.3f))
                drawRoundRect(Color.White, Offset(base.x - w / 2f, lidTop), Size(w, h * 0.27f), CornerRadius(size * 0.12f), alpha = 0.25f, style = Stroke(size * 0.02f))
                for (sx in listOf(-0.28f, 0.28f)) drawRect(dark, Offset(base.x + sx * w - size * 0.06f, lidTop), Size(size * 0.12f, h * 0.27f))
                // Handle.
                drawArc(dark, 180f, 180f, false, Offset(base.x - size * 0.22f, lidTop - size * 0.16f), Size(size * 0.44f, size * 0.32f), style = Stroke(size * 0.07f, cap = StrokeCap.Round))
            }
        }
    }

    /** A spinning ring of the next country's colors, open onto it. */
    private fun DrawScope.drawPortal(c: Offset, radius: Float, t: Float, colors: List<Color>, alpha: Float) {
        drawGlow(c, radius * 1.8f, colors.first(), 0.55f * alpha)
        // A dark throat just inside the rim, so the new country looks set back through a hole.
        drawCircle(
            Brush.radialGradient(0.78f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f), center = c, radius = radius),
            radius, c, alpha = alpha,
        )
        val ring = Brush.sweepGradient(colors + Color.White + colors.first(), c)
        withTransform({ rotate(t * 140f, c) }) {
            drawCircle(ring, radius * 1.06f, c, alpha = alpha, style = Stroke(radius * 0.15f))
            // Spiral arms whipping round the rim.
            repeat(4) { k ->
                drawArc(
                    Color.White, k * 90f, 50f, false,
                    c - Offset(radius * 1.06f, radius * 1.06f), Size(radius * 2.12f, radius * 2.12f),
                    alpha = 0.7f * alpha, style = Stroke(radius * 0.05f, cap = StrokeCap.Round),
                )
            }
        }
        drawCircle(Color.White, radius * 0.99f, c, alpha = 0.8f * alpha, style = Stroke(radius * 0.02f))
        withTransform({ rotate(-t * 90f, c) }) {
            drawCircle(ring, radius * 1.15f, c, alpha = 0.45f * alpha, style = Stroke(radius * 0.035f))
        }
        repeat(12) { k ->
            val a = k * 30f + t * 220f
            drawArc(
                Color.White, a, 14f, false,
                c - Offset(radius * 1.1f, radius * 1.1f), Size(radius * 2.2f, radius * 2.2f),
                alpha = 0.55f * alpha, style = Stroke(radius * 0.025f, cap = StrokeCap.Round),
            )
        }
        repeat(6) { k ->
            val a = t * 3f + k * TAU / 6
            drawSparkle(c + Offset(cos(a), sin(a)) * (radius * 1.25f), radius * 0.07f, Color.White, alpha * 0.9f)
        }
    }

    /** A wobbling soap bubble with [inside] seen through it. */
    private inline fun DrawScope.drawBubble(c: Offset, radius: Float, t: Float, inside: DrawScope.() -> Unit) {
        val wob = 0.045f * sin(t * 7f)
        withTransform({ scale(1f + wob, 1f - wob, c) }) {
            val disc = Path().apply { addOval(Rect(c, radius)) }
            clipPath(disc) {
                val k = radius * 2.1f / size.height
                drawContext.canvas.saveLayer(Rect(c, radius), Paint().apply { alpha = 0.93f })
                withTransform({
                    translate(c.x - size.width / 2f, c.y - size.height / 2f)
                    scale(k, k, Offset(size.width / 2f, size.height / 2f))
                }) { inside() }
                drawContext.canvas.restore()
                drawCircle(
                    Brush.radialGradient(0.6f to Color.Transparent, 1f to Color.White.copy(alpha = 0.35f), center = c, radius = radius),
                    radius, c,
                )
            }
            drawCircle(
                Brush.sweepGradient(listOf(Color(0xFF8FF3FF), Color(0xFFFFA6E7), Color(0xFFFFF59D), Color(0xFF8FF3FF)), c),
                radius, c, alpha = 0.55f, style = Stroke(radius * 0.045f),
            )
            drawArc(Color.White, 200f, 50f, false, c - Offset(radius * 0.78f, radius * 0.78f), Size(radius * 1.56f, radius * 1.56f), alpha = 0.8f, style = Stroke(radius * 0.06f, cap = StrokeCap.Round))
            drawCircle(Color.White, radius * 0.06f, c + Offset(radius * 0.42f, -radius * 0.5f), alpha = 0.85f)
        }
    }

    /** Goo hitting the glass, two wipers clearing it, and the smears they leave. */
    private fun DrawScope.drawSplat(s: Stage, lt: Float, t: Float, mouth: Offset, colors: List<Color>) {
        val r = s.r
        val g = gooCenter(s)
        if (lt < SPLAT) {
            val e = progress(lt, HEAVE + 0.05f, SPLAT - HEAVE - 0.05f)
            if (e > 0f) drawGlob(lerp(mouth, g, easeInCubic(e)), r * (0.2f + 1.6f * e * e), t, colors)
            return
        }
        val pivots = wiperPivots(s)
        val length = wiperLength(s)
        val swept = swept(lt)
        val grow = easeOutCubic(progress(lt, SPLAT, 0.2f))
        val gooR = lerp(r * 1.8f, s.w * 0.62f, grow)
        // The goo, minus what the wipers have already cleared.
        fun sector(p: Offset) = Path().apply {
            moveTo(p.x, p.y)
            arcTo(Rect(p, length * 1.05f), 0f, -Math.toDegrees(swept.toDouble()).toFloat(), false)
            close()
        }
        clipPath(sector(pivots[0]), ClipOp.Difference) {
            clipPath(sector(pivots[1]), ClipOp.Difference) {
                val goo = Path().apply { liquidCircle(g, gooR, t * 0.5f) }
                drawPath(goo, Brush.radialGradient(listOf(colors.last(), colors[colors.size / 2], colors.first()), g, gooR), alpha = 0.95f)
                // Thicker and thinner patches of jelly.
                repeat(7) { k ->
                    val p = Offset(hash(k + 700) * s.w, hash(k + 710) * s.h)
                    val rr = r * (1.2f + 2.2f * hash(k + 720))
                    val tone = if (k % 2 == 0) Color.Black else Color.White
                    drawPath(Path().apply { liquidCircle(p, rr, t * 0.3f + k) }, Brush.radialGradient(listOf(tone.copy(alpha = 0.16f), Color.Transparent), p, rr))
                }
                // Long glossy streaks from the light catching the wet surface.
                repeat(6) { k ->
                    val p = Offset(hash(k + 500) * s.w, s.h * (0.1f + 0.6f * hash(k + 510)))
                    val len = r * (1.2f + 2f * hash(k + 520))
                    drawLine(Color.White, p, p + Offset(len, -len * 0.18f), r * (0.08f + 0.06f * hash(k + 530)), StrokeCap.Round, alpha = 0.32f)
                    drawLine(Color.White, p + Offset(len * 1.15f, -len * 0.21f), p + Offset(len * 1.3f, -len * 0.24f), r * 0.07f, StrokeCap.Round, alpha = 0.32f)
                }
                // Plump drips sliding down the pane.
                val drip = androidx.compose.ui.graphics.lerp(colors.last(), Color.White, 0.25f)
                repeat(14) { k ->
                    val x = hash(k + 300) * s.w
                    val y0 = s.h * (0.02f + 0.55f * hash(k + 310))
                    val run = easeOutCubic(progress(lt, SPLAT + 0.1f + hash(k + 320) * 0.3f, 1.1f)) * s.h * (0.15f + 0.3f * hash(k + 330))
                    val fat = r * (0.1f + 0.08f * hash(k + 340))
                    drawLine(drip, Offset(x, y0), Offset(x, y0 + run), fat, StrokeCap.Round, alpha = 0.85f)
                    drawCircle(drip, fat * 1.25f, Offset(x, y0 + run))
                    drawCircle(Color.White, fat * 0.35f, Offset(x - fat * 0.4f, y0 + run - fat * 0.4f), alpha = 0.8f)
                    drawLine(Color.White, Offset(x - fat * 0.25f, y0), Offset(x - fat * 0.25f, y0 + run * 0.8f), fat * 0.18f, StrokeCap.Round, alpha = 0.45f)
                }
                // Trapped air bubbles.
                repeat(10) { k ->
                    val p = Offset(hash(k + 400) * s.w, hash(k + 410) * s.h)
                    val rr = r * (0.08f + 0.2f * hash(k + 420))
                    drawCircle(Color.White, rr, p, alpha = 0.12f)
                    drawCircle(Color.White, rr, p, alpha = 0.4f, style = Stroke(r * 0.025f))
                    drawCircle(Color.White, rr * 0.28f, p - Offset(rr * 0.38f, rr * 0.38f), alpha = 0.85f)
                }
            }
        }
        // Smears left behind by the rubber.
        val smear = 1f - progress(lt, WIPE + WIPE_SECONDS, 1.4f)
        if (swept > 0f && smear > 0f) {
            for ((i, p) in pivots.withIndex()) repeat(4) { k ->
                val rr = length * (0.35f + 0.6f * hash(i * 10 + k + 600))
                drawArc(
                    colors.first(), 0f, -Math.toDegrees(swept.toDouble()).toFloat(), false,
                    p - Offset(rr, rr), Size(rr * 2f, rr * 2f), alpha = 0.22f * smear, style = Stroke(r * 0.05f),
                )
            }
        }
        // The wipers themselves, parked flat along the bottom until they start.
        val angle = wiperAngle(lt)
        val show = progress(lt, SPLAT + 0.3f, 0.3f) * (1f - progress(lt, WIPE_BACK + 0.7f, 0.3f))
        if (show > 0f) for (p in pivots) drawWiper(p, angle, length, r, show)
    }

    private fun DrawScope.drawWiper(pivot: Offset, angle: Float, length: Float, r: Float, alpha: Float) {
        val dir = Offset(cos(angle), -sin(angle))
        val tip = pivot + dir * length
        val bladeFrom = pivot + dir * (length * 0.22f)
        val side = Offset(-dir.y, dir.x)
        drawLine(Color.Black, pivot + Offset(r * 0.06f, r * 0.08f), tip + Offset(r * 0.06f, r * 0.08f), r * 0.26f, StrokeCap.Round, alpha = 0.22f * alpha)
        // Arm, with a highlight down its spine.
        drawLine(Color(0xFF2B2F36), pivot, pivot + dir * (length * 0.62f), r * 0.2f, StrokeCap.Round, alpha = alpha)
        drawLine(Color(0xFF6B7380), pivot + side * (r * 0.05f), pivot + dir * (length * 0.62f) + side * (r * 0.05f), r * 0.05f, StrokeCap.Round, alpha = alpha)
        // Blade frame and rubber.
        drawLine(Color(0xFF1C1F24), bladeFrom, tip, r * 0.3f, StrokeCap.Round, alpha = alpha)
        drawLine(Color(0xFF3A4049), bladeFrom + side * (r * 0.07f), tip + side * (r * 0.07f), r * 0.08f, StrokeCap.Round, alpha = alpha)
        drawLine(Color(0xFF0B0C0E), bladeFrom - side * (r * 0.12f), tip - side * (r * 0.12f), r * 0.07f, StrokeCap.Round, alpha = alpha)
        drawCircle(Color(0xFF2B2F36), r * 0.32f, pivot, alpha = alpha)
        drawCircle(Color(0xFF8A939E), r * 0.13f, pivot, alpha = alpha)
        drawCircle(Color.White, r * 0.05f, pivot - Offset(r * 0.04f, r * 0.04f), alpha = 0.6f * alpha)
    }
}
