package cam.su.kernel.ui.bunker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import cam.su.kernel.ui.intro.IntroArt
import cam.su.kernel.ui.intro.IntroGeometry
import cam.su.kernel.ui.intro.IntroGeometry.SIZE
import cam.su.kernel.ui.intro.IntroGeometry.aim
import cam.su.kernel.ui.intro.IntroGeometry.centroid
import cam.su.kernel.ui.intro.IntroPaths
import cam.su.kernel.ui.intro.IntroText
import cam.su.kernel.ui.intro.ColdShot
import cam.su.kernel.ui.intro.IntroTimeline
import cam.su.kernel.ui.intro.WhiteFlash
import cam.su.kernel.ui.intro.drawBullet
import cam.su.kernel.ui.intro.drawEmbedded
import cam.su.kernel.ui.intro.drawSparks
import cam.su.kernel.ui.screen.home.arena.drawGlow
import cam.su.kernel.ui.screen.home.arena.easeInOutCubic
import cam.su.kernel.ui.screen.home.arena.easeOutCubic
import cam.su.kernel.ui.screen.home.arena.hash
import cam.su.kernel.ui.screen.home.arena.lerp
import cam.su.kernel.ui.screen.home.arena.progress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The command bunker: the Inner Veterans logo as a living page background. Everything inside the
// logo transform is in logo pixels (IntroGeometry); see BunkerMotion for the moves.

/** Everything one frame of the bunker needs; refilled every frame by [BunkerRun]. */
internal class BunkerFrame {
    var t = 0f

    /** Parallax: how far the device is tilted from its resting pose, -1..1 each way. */
    var tiltX = 0f
    var tiltY = 0f

    var pull = 0f
    var dischargeAge = 100f
    var lockAge = 100f

    /** The random event playing ([BunkerMotion.SHOT]), or -1. */
    var event = -1
    var eventAge = 0f
    var eventSeed = 0

}

internal val HudText = Color(0xFFA9DCD6)
private val LockText = Color(0xFFFF5A2A)

/** How far each layer slides with a full tilt, in logo pixels at depth 1. */
private const val SHIFT = 26f

/** Parallax depth of each ring of plates (outer frame, inner frame, core, reticle); the HUD is 1, the lettering 0.8. */
private val RING_DEPTH = floatArrayOf(0.6f, 0.4f, 0.2f, 0.1f)
private const val RETICLE = 21

/** Where the logo goes on a [w] x [h] page: 92% of the shorter side, centered. */
internal fun bunkerFit(w: Float, h: Float): IntroGeometry.Fit {
    val side = 0.92f * min(w, h)
    return IntroGeometry.Fit(side / SIZE, (w - side) / 2f, (h - side) / 2f)
}

internal fun DrawScope.drawBunker(art: IntroArt, f: BunkerFrame, paths: IntroPaths, light: BunkerPaths, text: IntroText) {
    drawRect(Color.Black)
    val fit = bunkerFit(size.width, size.height)
    val shake = shakeAt(f) * fit.scale
    withTransform({
        translate(fit.left + shake.x, fit.top + shake.y)
        scale(fit.scale, fit.scale, Offset.Zero)
    }) {
        drawHud(art, f, paths, text)
        withShift(f, 0.8f) { clipRect(0f, IntroGeometry.TEXT_TOP, SIZE, SIZE) { drawImage(art.logo) } }
        drawPlates(art, f)
        drawReticle(art, f)
        drawSheen(art, f, light)
        withTriangle(f) { drawRim(f, light) }
        drawDischarge(f)
        drawEvent(f, paths)
    }
    // A pull that went through: the whole screen kicks.
    if (f.dischargeAge < 0.3f) drawRect(ColdBody, alpha = 0.22f * (1f - f.dischargeAge / 0.3f))
}

private inline fun DrawScope.withShift(f: BunkerFrame, depth: Float, block: DrawScope.() -> Unit) {
    withTransform({ translate(f.tiltX * SHIFT * depth, f.tiltY * SHIFT * depth) }, block)
}

/** The HUD round the triangle, one piece now and then flickering, with its live readouts. */
private fun DrawScope.drawHud(art: IntroArt, f: BunkerFrame, paths: IntroPaths, text: IntroText) {
    withShift(f, 1f) {
        clipPath(paths.hud) {
            drawImage(art.logo)
            val k = BunkerMotion.hudFlicker(f.t, IntroGeometry.hud.size)
            if (k >= 0) drawRect(Color.Black, IntroGeometry.hud[k].topLeft, IntroGeometry.hud[k].size)
        }
        val r = IntroGeometry.rng
        drawRect(Color.Black, r.topLeft, r.size)
        val canvas = drawContext.canvas.nativeCanvas
        text.hud.color = HudText.toArgb()
        val base = IntroGeometry.rngBaseline
        val range = BunkerMotion.range(f.t)
        var div = 1000
        for (i in 0..3) {
            canvas.drawText(text.chars, range / div % 10, 1, base.x + i * IntroGeometry.RNG_PITCH, base.y, text.hud)
            div /= 10
        }
        canvas.drawText(text.chars, 10, 1, base.x + 4 * IntroGeometry.RNG_PITCH, base.y, text.hud)
        if (f.lockAge < LOCK_LEN && floor(f.lockAge * 8f).toInt() % 2 == 0) {
            text.lock.color = LockText.toArgb()
            text.lock.alpha = 255
            val l = IntroGeometry.lockBaseline
            canvas.drawText(text.lockChars, 0, text.lockChars.size, l.x, l.y, text.lock)
        }
    }
}

private const val LOCK_LEN = 2.4f

/** The plates, each ring at its own depth. */
private fun DrawScope.drawPlates(art: IntroArt, f: BunkerFrame) {
    eachPlate(f) { i -> drawImage(art.plates[i], art.plateAt[i]) }
}

/** Runs [block] for each plate but the reticle, at its ring's depth. */
private inline fun DrawScope.eachPlate(f: BunkerFrame, block: DrawScope.(Int) -> Unit) {
    val plates = IntroTimeline.plates
    for (i in plates.indices) {
        val p = plates[i]
        if (p.ring == 3) continue
        val depth = RING_DEPTH[p.ring]
        withTransform({ translate(f.tiltX * SHIFT * depth, f.tiltY * SHIFT * depth) }) { block(i) }
    }
}

/** Runs [block] at the outer frame's depth. */
private inline fun DrawScope.withTriangle(f: BunkerFrame, block: DrawScope.() -> Unit) {
    val d = RING_DEPTH[0]
    withTransform({
        translate(f.tiltX * SHIFT * d, f.tiltY * SHIFT * d)
    }, block)
}


/** The reticle: turning slowly, whipped round and lit up teal on a lock. */
private fun DrawScope.drawReticle(art: IntroArt, f: BunkerFrame) {
    val d = RING_DEPTH[3]
    val shift = Offset(f.tiltX * SHIFT * d, f.tiltY * SHIFT * d)
    // a lock whips it round a full turn
    val whip = if (f.lockAge < 0.6f) 360f * easeOutCubic(f.lockAge / 0.6f) else 0f
    withTransform({
        translate(shift.x, shift.y)
        rotate(f.t * 9f + whip, aim)
    }) {
        drawImage(art.plates[RETICLE], art.plateAt[RETICLE])
        val flash = 1f - progress(f.lockAge, 0f, 0.35f)
        if (flash > 0f) drawImage(art.plates[RETICLE], art.plateAt[RETICLE], alpha = 0.7f * flash, colorFilter = ColorFilter.tint(ColdBody))
    }
    ring(aim + shift, f.lockAge, 0.6f, ColdBody)
}

private fun DrawScope.ring(center: Offset, age: Float, len: Float, color: Color) {
    if (age < 0f || age >= len) return
    val e = age / len
    drawCircle(color, IntroGeometry.AIM_RADIUS * (1f + 1.6f * easeOutCubic(e)), center, alpha = 0.85f * (1f - e), style = Stroke(4f * (1f - e) + 1f))
}

/** Reused paths for the bunker's light, made once. */
internal class BunkerPaths {
    /** The triangle's outer edge, where the chrome rim catches the light. */
    val rim = Path().apply {
        val c = IntroGeometry.corners
        moveTo(c[0].x, c[0].y)
        lineTo(c[1].x, c[1].y)
        lineTo(c[2].x, c[2].y)
        close()
    }

    /** Part of the rim, for the edge heating up as a list is pulled. */
    val heat = Path()


    /** Adds its layer onto the picture under it. */
    val plus = Paint().apply { blendMode = BlendMode.Plus }
}

/** The metal's own brightness turned cold blue-white and pushed up: what a sheen lights up. */
private val ColdSheen = ColorFilter.colorMatrix(
    ColorMatrix(
        FloatArray(20).also { m ->
            val gain = floatArrayOf(1.45f, 1.62f, 1.75f)
            for (r in 0..2) {
                m[r * 5] = 0.299f * gain[r]
                m[r * 5 + 1] = 0.587f * gain[r]
                m[r * 5 + 2] = 0.114f * gain[r]
            }
            m[18] = 1f
        },
    ),
)

/** Across the triangle, top left to bottom right: the direction light sweeps the metal. */
private val SWEEP_DIR = Offset(0.83f, 0.56f)
private const val SWEEP_REACH = 640f

/**
 * Cold light on the metal: a sharp sheen sweeping across the plates now and then, a reflection
 * that slides over them as the device tilts, and a fast flash across for a refresh. Drawn as the
 * plates again, cold and bright, masked by the light's band and added on top: it lights only the
 * metal, never the black around it.
 */
private fun DrawScope.drawSheen(art: IntroArt, f: BunkerFrame, paths: BunkerPaths) {
    val sweep = BunkerMotion.sweepAt(f.t)
    val tiltLen = hypot(f.tiltX, f.tiltY)
    val tilt = min(1f, tiltLen * 1.6f)
    val flash = if (f.dischargeAge < FLASH_LEN) f.dischargeAge / FLASH_LEN else -1f
    if (sweep < 0f && tilt < 0.03f && flash < 0f) return
    val sweepAt = if (sweep >= 0f) -0.2f + 1.4f * easeInOutCubic(sweep) else -9f
    val sweepA = if (sweep >= 0f) sin(PI.toFloat() * sweep) else 0f
    // the reflection slides the way the device leans
    val lean = if (tiltLen > 0.001f) (f.tiltX * SWEEP_DIR.x + f.tiltY * SWEEP_DIR.y) / tiltLen else 0f
    val tiltAt = (0.5f + 0.45f * lean * tilt).coerceIn(0f, 1f)
    val flashAt = if (flash >= 0f) -0.1f + 1.2f * easeOutCubic(flash) else -9f
    val flashA = if (flash >= 0f) 1f - flash * flash else 0f

    fun alpha(s: Float) = (
        sweepA * (0.35f * band(s, sweepAt, 0.11f) + 0.95f * band(s, sweepAt, 0.022f)) +
            tilt * 0.75f * (0.4f * band(s, tiltAt, 0.1f) + band(s, tiltAt, 0.03f)) +
            flashA * (0.5f * band(s, flashAt, 0.16f) + band(s, flashAt, 0.04f))
        ).coerceIn(0f, 1f)

    // Stops: evenly over the axis, and closely round each band so the sharp ones keep their edge.
    val points = ArrayList<Float>(96)
    for (k in 0..24) points += k / 24f
    for (c in floatArrayOf(sweepAt, tiltAt, flashAt)) for (k in -8..8) points += c + k * 0.006f
    points.retainAll { it in 0f..1f }
    points.sort()
    val stops = Array(points.size) { i -> points[i] to Color.White.copy(alpha = alpha(points[i])) }
    val a = centroid - SWEEP_DIR * SWEEP_REACH
    val b = centroid + SWEEP_DIR * SWEEP_REACH
    val bounds = Rect(centroid - Offset(720f, 720f), Size(1440f, 1440f))
    drawIntoCanvas { it.saveLayer(bounds, paths.plus) }
    eachPlate(f) { i -> drawImage(art.plates[i], art.plateAt[i], colorFilter = ColdSheen) }
    drawRect(Brush.linearGradient(*stops, start = a, end = b), bounds.topLeft, bounds.size, blendMode = BlendMode.DstIn)
    drawIntoCanvas { it.restore() }
}

private const val FLASH_LEN = 0.45f

private fun band(s: Float, center: Float, width: Float): Float {
    val x = (s - center) / width
    return exp(-x * x)
}

/**
 * The chrome rim: a hairline of cold light round the triangle's edge, two brighter stretches of it
 * drifting slowly round, swelling now and then; heating up from the apex as a list is pulled.
 */
private fun DrawScope.drawRim(f: BunkerFrame, paths: BunkerPaths) {
    var strength = 0.55f + 0.45f * BunkerMotion.surge(f.t)
    if (f.dischargeAge < 0.6f) strength = maxOf(strength, 1.4f * (1f - f.dischargeAge / 0.6f))
    val p1 = f.t * 0.5f
    val p2 = 2.2f - f.t * 0.33f
    val stops = Array(25) { k ->
        val th = k / 24f * 2f * PI.toFloat()
        val glow = 0.16f + 0.84f * (maxOf(0f, cos(th - p1)).pow(10) + maxOf(0f, cos(th - p2)).pow(14))
        k / 24f to Color.White.copy(alpha = glow.coerceAtMost(1f))
    }
    val brush = Brush.sweepGradient(*stops, center = centroid)
    drawPath(paths.rim, brush, alpha = (0.3f * strength).coerceAtMost(1f), style = Stroke(10f, join = StrokeJoin.Miter), blendMode = BlendMode.Plus)
    drawPath(paths.rim, brush, alpha = strength.coerceAtMost(1f), style = Stroke(2.6f, join = StrokeJoin.Miter), blendMode = BlendMode.Plus)

    if (f.pull > 0.02f) {
        // the edge heats up white from the apex down both sides
        val reach = f.pull * IntroGeometry.perimeter / 2f
        val heat = paths.heat
        heat.rewind()
        trace(heat, 0f, reach)
        trace(heat, IntroGeometry.perimeter - reach, IntroGeometry.perimeter)
        drawPath(heat, ColdBody, alpha = 0.35f * f.pull, style = Stroke(16f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        drawPath(heat, Color.White, alpha = f.pull, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    }
}

/** Adds the outline from [from] to [to] (distances round it from the apex) to [path]. */
private fun trace(path: Path, from: Float, to: Float) {
    val l0 = IntroGeometry.edgeLength(0)
    val l1 = l0 + IntroGeometry.edgeLength(1)
    val start = IntroGeometry.perimeterPoint(from)
    path.moveTo(start.x, start.y)
    for (corner in floatArrayOf(l0, l1)) {
        if (corner > from && corner < to) {
            val c = IntroGeometry.perimeterPoint(corner)
            path.lineTo(c.x, c.y)
        }
    }
    val end = IntroGeometry.perimeterPoint(to)
    path.lineTo(end.x, end.y)
}

/** The pull and the refresh: light gathering in the reticle, then a burst of it. */
private fun DrawScope.drawDischarge(f: BunkerFrame) {
    if (f.pull > 0.02f) drawGlow(aim, 60f + 90f * f.pull, ColdBody, 0.6f * f.pull)
    if (f.dischargeAge < 0.5f) drawGlow(aim, 240f, ColdBody, 1f - f.dischargeAge / 0.5f)
}

/** The random event playing: a shot nailing the frame. */
private fun DrawScope.drawEvent(f: BunkerFrame, paths: IntroPaths) {
    if (f.event < 0) return
    withTriangle(f) {
        val age = f.eventAge
        if (f.event == BunkerMotion.SHOT) {
            val edge = (hash(f.eventSeed) * 3f).toInt().coerceIn(0, 2)
            val along = 0.15f + 0.7f * hash(f.eventSeed + 1)
            val target = IntroGeometry.bandPoint(edge, along, IntroGeometry.RIVET_BAND)
            val n = IntroGeometry.edgeNormal(edge)
            val turn = (hash(f.eventSeed + 2) - 0.5f) * 1.4f
            val from = Offset(n.x * cos(turn) - n.y * sin(turn), n.x * sin(turn) + n.y * cos(turn))
            if (age < SHOT_FLIGHT) {
                val e = IntroTimeline.bulletEase(age / SHOT_FLIGHT)
                drawBullet(paths, lerp(target + from * 1500f, target, e), -from, 420f, f.t, ColdShot)
            } else {
                val hit = age - SHOT_FLIGHT
                drawEmbedded(target, hit, f.eventSeed, heat = 1f - progress(hit, BunkerMotion.SHOT_LEN - 1.2f, 1f), colors = ColdShot)
                drawSparks(target, from, hit, f.eventSeed, 14, ColdShot)
            }
        }
    }
}

private const val SHOT_FLIGHT = 0.1f


/** How far the bunker's knocks jolt the logo, in logo pixels. */
private fun shakeAt(f: BunkerFrame): Offset {
    var amount = 0f
    if (f.event == BunkerMotion.SHOT && f.eventAge >= SHOT_FLIGHT) amount += 7f * exp(-(f.eventAge - SHOT_FLIGHT) * 25f)
    amount += 10f * exp(-f.dischargeAge * 10f)
    amount += 3f * exp(-f.lockAge * 20f)
    if (amount < 0.3f) return Offset.Zero
    val frame = floor(f.t * 60f).toInt()
    return Offset((hash(frame) - 0.5f) * 2f * amount, (hash(frame + 999) - 0.5f) * 2f * amount)
}
