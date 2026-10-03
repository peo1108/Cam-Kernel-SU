package me.weishu.kernelsu.ui.slime

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import me.weishu.kernelsu.ui.screen.home.arena.PI_F
import me.weishu.kernelsu.ui.screen.home.arena.SLIME_COUNT
import me.weishu.kernelsu.ui.screen.home.arena.drawSparkle
import me.weishu.kernelsu.ui.screen.home.arena.hash
import me.weishu.kernelsu.ui.screen.home.arena.progress
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The status card is the slimes' home. On the home page all four are inside the glass box; leave
 * it and they smash the glass and burst out onto the other pages; come back and they file in
 * through a little door in the glass, which then mends itself. This is the state both sides share:
 * the 3D box (who is inside, who just walked in) and the roaming layer (where the box and its door
 * are, where each slime was standing when it broke out).
 */
internal object SlimeHome {
    /** A clock both sides agree on, in seconds. */
    fun clock() = SystemClock.uptimeMillis() / 1000f

    val inside = BooleanArray(SLIME_COUNT) { true }
    /** When each one came back in through the door (it walks in from the door side). */
    val cameIn = FloatArray(SLIME_COUNT) { -100f }
    /** Where each one is on screen while inside, so it bursts out from the right spot. */
    val boxPos = Array(SLIME_COUNT) { Offset.Unspecified }
    /** How big each one looks in the box (screen pixels per world unit), so it bursts out that size. */
    val boxUnit = FloatArray(SLIME_COUNT)
    /** The status card on screen (root pixels). */
    var cardRect: Rect? = null

    /** The glass: smashed at [brokeAt], left broken until everyone is back, then mended from [mendAt]. */
    var broken = false
    var brokeAt = -100f
    var mendAt = -100f
    /** 0 shut .. 1 wide open. */
    var doorOpen = 0f
    /** True once they have ever left: the box then skips its first-time drop-in. */
    var hasLeft = false
    /**
     * The main pager's position (page + offset fraction), NaN when it is not on screen. They
     * smash out as soon as a swipe starts carrying the home page away, not once it has settled.
     */
    @Volatile
    var pagerPos = Float.NaN

    fun allInside() = inside.all { it }

    /** Everyone back in an unbroken box, at once (the roaming slimes were switched off). */
    fun reset() {
        inside.fill(true)
        cameIn.fill(-100f)
        broken = false
        mendAt = -100f
        doorOpen = 0f
    }

    /** Busy while anyone is out or the glass is still being mended: the box's show waits. */
    fun busy() = !allInside() || broken

    /** The box's slime radius in screen pixels: the door is made for slimes that size. */
    var doorR = 0f

    /** The threshold of the door, low on the right of the glass. */
    fun door(): Offset? {
        val r = doorR
        if (r <= 0f) return null
        return cardRect?.let { Offset(it.right - r * DOOR_IN, it.bottom - r * 0.35f) }
    }

    /** How far in from the card's right edge the door stands, in box slime radii. */
    const val DOOR_IN = 2.6f

    const val MEND_SECONDS = 1.3f
}

/**
 * The broken glass, the door and the mending, drawn over the status card (card-local pixels;
 * [r] is the box's slime radius, [px] one dp).
 */
internal fun DrawScope.drawHomeGlass(r: Float, px: Float) {
    val now = SlimeHome.clock()
    val w = size.width
    val h = size.height
    // Cracks spreading from where they hit the glass, and the hole they made.
    val mend = if (SlimeHome.mendAt > 0f) progress(now, SlimeHome.mendAt, SlimeHome.MEND_SECONDS) else 0f
    if (SlimeHome.broken) {
        val alpha = 1f - mend
        val hit = Offset(w * 0.62f, h * 0.55f)
        val hole = Path().apply {
            val n = 11
            for (i in 0..n) {
                val a = i / n.toFloat() * 2f * PI_F
                val rr = h * (0.13f + 0.07f * hash(i + 31))
                val p = hit + Offset(cos(a) * rr * 1.5f, sin(a) * rr)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(hole, Color.Black, alpha = 0.12f * alpha)
        drawPath(hole, Color.White, alpha = 0.85f * alpha, style = Stroke(1.6f * px))
        repeat(14) { i ->
            val a = hash(i + 50) * 2f * PI_F
            val from = hit + Offset(cos(a), sin(a) * 0.66f) * (h * 0.18f)
            val len = h * (0.25f + 0.55f * hash(i + 60))
            val mid = from + Offset(cos(a + 0.25f), sin(a + 0.25f) * 0.66f) * (len * 0.5f)
            val end = mid + Offset(cos(a - 0.2f), sin(a - 0.2f) * 0.66f) * (len * 0.5f)
            drawLine(Color.White, from, mid, 1.2f * px, StrokeCap.Round, alpha = 0.75f * alpha)
            drawLine(Color.White, mid, end, 0.9f * px, StrokeCap.Round, alpha = 0.6f * alpha)
            // Ring cracks between the spokes.
            if (i % 3 == 0) drawArc(Color.White, Math.toDegrees(a.toDouble()).toFloat(), 30f, false, hit - Offset(h * 0.45f, h * 0.3f), Size(h * 0.9f, h * 0.6f), alpha = 0.4f * alpha, style = Stroke(0.8f * px))
        }
        if (mend > 0f) {
            // A shimmer sweeping across the pane as it heals.
            val x = w * (mend * 1.4f - 0.2f)
            repeat(10) { i ->
                val y = h * hash(i + 90)
                drawSparkle(Offset(x + (hash(i + 91) - 0.5f) * w * 0.08f, y), 4f * px, Color.White, 1f - mend)
            }
        }
    }
    // The door, low on the right of the glass, swinging open on its left hinge.
    val open = SlimeHome.doorOpen
    if (open > 0.01f || !SlimeHome.allInside()) {
        val dw = r * 1.9f
        val dh = r * 2.3f
        val right = w - r * SlimeHome.DOOR_IN + dw / 2f
        val left = right - dw
        val top = h - r * 0.35f - dh
        drawRoundRect(Color.Black, Offset(left, top), Size(dw, dh), androidx.compose.ui.geometry.CornerRadius(dw * 0.5f, dw * 0.5f), alpha = 0.35f * open)
        val leaf = dw * (1f - 0.85f * open)
        drawRoundRect(Color.White, Offset(left, top), Size(leaf, dh), androidx.compose.ui.geometry.CornerRadius(min(leaf, dw) * 0.5f, dw * 0.5f), alpha = 0.18f)
        drawRoundRect(Color.White, Offset(left, top), Size(leaf, dh), androidx.compose.ui.geometry.CornerRadius(min(leaf, dw) * 0.5f, dw * 0.5f), alpha = 0.8f, style = Stroke(1.5f * px))
        drawCircle(Color(0xFFFFD166), 2.2f * px, Offset(left + leaf * 0.8f, top + dh * 0.55f))
    }
}

/** Glass shards flying off the card for a moment after the break-out (root pixels). */
internal fun DrawScope.drawShards(px: Float) {
    val rect = SlimeHome.cardRect ?: return
    val e = SlimeHome.clock() - SlimeHome.brokeAt
    if (e < 0f || e > 1.1f) return
    val hit = Offset(rect.left + rect.width * 0.62f, rect.top + rect.height * 0.55f)
    repeat(18) { i ->
        val a = hash(i + 200) * 2f * PI_F
        val v = 600f * px * (0.4f + hash(i + 201))
        val p = hit + Offset(cos(a) * v * e * 0.5f + v * e * 0.3f, sin(a) * v * e * 0.4f + 900f * px * e * e)
        val s = 6f * px * (0.5f + hash(i + 202))
        val tri = Path().apply {
            moveTo(p.x, p.y - s)
            lineTo(p.x + s * 0.8f, p.y + s * 0.6f)
            lineTo(p.x - s * 0.7f, p.y + s * 0.4f)
            close()
        }
        drawPath(tri, Color(0xFFE3F2FD), alpha = 0.8f * (1f - e / 1.1f))
        drawPath(tri, Color.White, alpha = 1f - e / 1.1f, style = Stroke(0.8f * px))
    }
}
