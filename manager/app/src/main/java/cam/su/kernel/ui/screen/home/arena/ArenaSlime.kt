package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.ui.graphics.lerp as lerpColor

internal enum class Mood { Normal, Angry, Happy, Dizzy, Squeeze, Surprised, Worried }

/**
 * A cast member: jelly color, its deep shade for outlines and shading, the light it glows with,
 * its size relative to the stage unit and how tall it is for its width.
 */
internal class SlimeSkin(
    val name: String,
    val body: Color,
    val deep: Color,
    val glow: Color,
    val size: Float,
    val stretch: Float,
)

/** Mochi the bossy one, Bơ the big gentle one, Soda the tall show-off, Chanh the tiny troublemaker. */
internal val Cast = listOf(
    SlimeSkin("Mochi", Color(0xFFFF9CC8), Color(0xFFD6457E), Color(0xFFFFE6F2), 1f, 1f),
    SlimeSkin("Bơ", Color(0xFFA6E07A), Color(0xFF4C9A33), Color(0xFFF1FFE0), 1.3f, 0.95f),
    SlimeSkin("Soda", Color(0xFF8FD3FF), Color(0xFF2B7FC4), Color(0xFFE8F7FF), 1f, 1.16f),
    SlimeSkin("Chanh", Color(0xFFFFE27A), Color(0xFFCC9A12), Color(0xFFFFF9DE), 0.66f, 1f),
)

/** How the current scene lights a slime: key light position and color, plus the ambient fill. */
internal class SlimeLight {
    var pos = Offset.Zero
    var color = Color.White
    var ambient = Color.Gray
}

/** Where a slime is and how it looks this frame; one instance per cast member, reused every frame. */
internal class SlimePose {
    var x = 0f
    /** World units toward the glass (+) or the back wall (-); the 2D overlay plane is 0. */
    var depth = 0f
    var lift = 0f
    var squash = 1f
    var scale = 1f
    var rotation = 0f
    var mood = Mood.Normal
    var lookX = 0f
    var lookY = 0f
    var mouthOpen = 0f
    var blink = 0f
    var dizzy = 0f
    var anger = 0f
    var sweat = 0f
    var choke = 0f
    var jiggle = 0f
    var shakeX = 0f
    var visible = true
    var costume = Costume.NonLa
    /** 0..1 progress of the puff of smoke shown while the costume changes. */
    var poof = -1f
    /** Height-for-width of this cast member; not reset per frame. */
    var stretch = 1f
    /** Arm angles in degrees from hanging straight down, positive swinging outward. */
    var armL = 18f
    var armR = 18f
    var reachL = 1f
    var reachR = 1f
    /** 0..1 arms folded across the front ("hmph"). */
    var cross = 0f
    /** 0..1 hands on hips. */
    var akimbo = 0f
    /** 0..1 rage rising like a thermometer from the chin to the top of the head (Bơ). */
    var flush = 0f
    /** 0..1 steam whistling out of the top of the head (Mochi). */
    var steam = 0f
    /** 0..1 sparkles while striking a pose (Soda). */
    var sparkle = 0f
    /** 0..1 innocent whistling with music notes (Chanh). */
    var whistle = 0f
    /** Asleep: eyes shut, z's drifting up and a nose bubble of [snore] size (negative once popped). */
    var sleep = 0f
    var snore = 0f
    /** Hat tilt in degrees, hat lifted above the head (falling costumes), and the outfit while the new hat falls. */
    var hatTilt = 0f
    var hatLift = 0f
    var outfit: Costume? = null
    var chokeTint = Color(0xFF8C8FE8)
    /** 0..1 slumped into a puddle. */
    var melt = 0f
    /** Depth squash, < 1 while pressed flat against the glass. */
    var flatZ = 1f
    /** Perspective of the 3D stage for this frame: screen scale about (pivotX, pivotY); not reset. */
    var viewScale = 1f
    var pivotX = 0f
    var pivotY = 0f

    fun reset(homeX: Float) {
        x = homeX
        depth = 0f
        lift = 0f
        squash = 1f
        scale = 1f
        rotation = 0f
        mood = Mood.Normal
        lookX = 0f
        lookY = 0f
        mouthOpen = 0f
        blink = 0f
        dizzy = 0f
        anger = 0f
        sweat = 0f
        choke = 0f
        jiggle = 0f
        shakeX = 0f
        visible = true
        poof = -1f
        armL = 18f
        armR = 18f
        reachL = 1f
        reachR = 1f
        cross = 0f
        akimbo = 0f
        flush = 0f
        steam = 0f
        sparkle = 0f
        whistle = 0f
        sleep = 0f
        snore = 0f
        hatTilt = 0f
        hatLift = 0f
        outfit = null
        chokeTint = Color(0xFF8C8FE8)
        melt = 0f
        flatZ = 1f
    }
}

/** Maps a point on the 2D action plane to where this slime's depth puts it on screen. */
internal fun SlimePose.project(p: Offset): Offset =
    if (viewScale == 1f) p else Offset(pivotX + (p.x - pivotX) * viewScale, pivotY + (p.y - pivotY) * viewScale)

/** Where the hand of an upright slime ends up for arm [side] (-1 left, 1 right). */
internal fun SlimePose.hand(side: Int, r: Float, groundY: Float, t: Float): Offset {
    val (w, h) = size(r, t)
    val b = logical(groundY)
    val shoulder = Offset(b.x + side * w * 0.4f, b.y - h * 0.42f)
    val a = Math.toRadians((if (side < 0) armL else armR).toDouble()).toFloat()
    val len = r * 0.55f * (if (side < 0) reachL else reachR)
    return project(shoulder + Offset(side * sin(a), cos(a)) * len)
}

/** Arm angle (degrees, for [hand]) that points arm [side] of a slime at [target]. */
internal fun SlimePose.aimAt(side: Int, target: Offset, r: Float, groundY: Float, t: Float): Float {
    val (w, h) = size(r, t)
    val b = base(groundY)
    val shoulder = Offset(b.x + side * w * 0.4f * viewScale, b.y - h * 0.42f * viewScale)
    val d = target - shoulder
    return Math.toDegrees(kotlin.math.atan2((side * d.x).toDouble(), d.y.toDouble())).toFloat()
}

/** Geometry of one slime for one frame, shared with costume and belly drawing. */
internal class SlimeGeo {
    var cx = 0f
    var by = 0f
    var w = 0f
    var h = 0f
    var r = 0f
    var s = 1f
    var t = 0f
    var faceX = 0f
    var eyeY = 0f
    var eyeDx = 0f
    var eyeW = 0f
    var eyeH = 0f
    var mouthY = 0f
    var rotation = 0f
    val top get() = by - h
    val body = Path()
}

private val Ink = Color(0xFF2A2440)

private fun SlimePose.size(r: Float, t: Float): Pair<Float, Float> {
    val sy = squash
    var sx = if (sy < 1f) 1f + (1f - sy) * 0.9f else 1f - (sy - 1f) * 0.5f
    var syj = sy
    if (jiggle > 0f) {
        val j = sin(t * 24f) * jiggle * 0.07f
        sx *= 1f + j
        syj *= 1f - j
    }
    return 1.96f * r * scale * sx / kotlin.math.sqrt(stretch) to 1.72f * r * scale * syj * stretch
}

/** Feet on the 2D action plane, before perspective. */
internal fun SlimePose.logical(groundY: Float) = Offset(x + shakeX, groundY - lift)

/** Feet on screen. */
internal fun SlimePose.base(groundY: Float) = project(logical(groundY))

/** Mouth of an upright slime: where letters go in and come back out. */
internal fun SlimePose.mouth(r: Float, groundY: Float, t: Float): Offset {
    val (_, h) = size(r, t)
    val b = logical(groundY)
    val slump = 1f - melt * 0.44f
    return project(Offset(b.x + lookX * 1.96f * r * scale * 0.08f, b.y - (h * 0.47f - r * 0.26f * scale * stretch) * slump))
}

internal fun SlimePose.headTop(r: Float, groundY: Float, t: Float): Offset {
    val (_, h) = size(r, t)
    val b = logical(groundY)
    return project(Offset(b.x, b.y - h * (1f - melt * 0.44f)))
}

internal fun SlimePose.bodyHeight(r: Float, t: Float) = size(r, t).second

internal fun SlimePose.contains(point: Offset, r: Float, groundY: Float, t: Float): Boolean {
    val (w0, h0) = size(r, t)
    val w = w0 * viewScale
    val h = h0 * viewScale
    val b = base(groundY)
    return point.x in (b.x - w * 0.6f)..(b.x + w * 0.6f) && point.y in (b.y - h * 1.3f)..(b.y + r * 0.2f)
}

private fun Path.mochi(cx: Float, by: Float, w: Float, h: Float) {
    reset()
    moveTo(cx, by)
    cubicTo(cx + w * 0.34f, by, cx + w * 0.5f, by - h * 0.1f, cx + w * 0.5f, by - h * 0.45f)
    cubicTo(cx + w * 0.5f, by - h * 0.82f, cx + w * 0.27f, by - h, cx, by - h)
    cubicTo(cx - w * 0.27f, by - h, cx - w * 0.5f, by - h * 0.82f, cx - w * 0.5f, by - h * 0.45f)
    cubicTo(cx - w * 0.5f, by - h * 0.1f, cx - w * 0.34f, by, cx, by)
    close()
}

/**
 * Draws a jelly slime lit by the scene: a long soft shadow cast away from the key light with a
 * tight contact shadow and a caustic (light focused through the jelly onto the ground); inside the
 * body, [backdrop] shows the scene refracted through it, under a Fresnel tint that is clear in the
 * middle and dense at the rim, subsurface glow on the side away from the light, drifting bubbles,
 * the costume and [inside] content (e.g. swallowed letters); then a light-colored rim, specular
 * highlights facing the light, the face, the hat and the overlays (sweat, anger, dizzy stars,
 * costume-change puff).
 */
internal fun DrawScope.drawSlime(
    pose: SlimePose,
    skin: SlimeSkin,
    r: Float,
    groundY: Float,
    t: Float,
    geo: SlimeGeo,
    seed: Int,
    light: SlimeLight,
    backdrop: (DrawScope.(SlimeGeo) -> Unit)? = null,
    inside: (DrawScope.(SlimeGeo) -> Unit)? = null,
    /** False for a slime in the air or clinging to a wall: no shadow on a floor that is not there. */
    shadow: Boolean = true,
) {
    if (!pose.visible) return
    val (w, h) = pose.size(r, t)
    val base = pose.base(groundY)
    val center = Offset(base.x, base.y - h * 0.5f)
    val toLight = light.pos - center
    val len = kotlin.math.hypot(toLight.x, toLight.y).coerceAtLeast(1f)
    val lx = toLight.x / len
    val ly = toLight.y / len

    val bodyColor = if (pose.choke > 0f) lerpColor(skin.body, pose.chokeTint, pose.choke * 0.7f) else skin.body
    val deep = if (pose.choke > 0f) lerpColor(skin.deep, Color(0xFF4B3FA8), pose.choke * 0.7f) else skin.deep

    // Ground: long soft shadow away from the light, contact shadow, caustic.
    val k = if (shadow) (1f - pose.lift / (r * 3f)).coerceIn(0.25f, 1f) else 0f
    val elevation = (-ly).coerceIn(0.12f, 1f)
    if (k > 0f) {
        val throwX = -lx * w * (0.25f + 0.55f * (1f - elevation))
        val shadowC = Offset(pose.x + throwX * 0.6f, groundY)
        val shadowW = (w * 0.62f + kotlin.math.abs(throwX)) * k
        withTransform({ scale(1f, 0.16f, shadowC) }) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.32f * k),
                    0.6f to Color.Black.copy(alpha = 0.14f * k),
                    1f to Color.Transparent,
                    center = shadowC,
                    radius = shadowW,
                ),
                shadowW,
                shadowC,
            )
        }
        val contact = Offset(pose.x, groundY)
        withTransform({ scale(1f, 0.12f, contact) }) {
            drawCircle(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f * k * k), Color.Transparent), contact, w * 0.42f * k),
                w * 0.42f * k,
                contact,
            )
        }
        val caustic = Offset(pose.x - lx * w * 0.28f, groundY + r * 0.02f)
        val shimmer = 0.8f + 0.2f * sin(t * 3f + seed)
        withTransform({ scale(1f, 0.22f, caustic) }) {
            drawCircle(
                Brush.radialGradient(
                    listOf(lerpColor(bodyColor, Color.White, 0.6f).copy(alpha = 0.75f * k * shimmer), bodyColor.copy(alpha = 0.25f * k), Color.Transparent),
                    caustic,
                    w * 0.3f * k,
                ),
                w * 0.3f * k,
                caustic,
            )
        }

    }

    rotate(pose.rotation, center) {
        geo.cx = base.x
        geo.by = base.y
        geo.w = w
        geo.h = h
        geo.r = r
        geo.s = pose.scale
        geo.t = t
        geo.rotation = pose.rotation
        geo.faceX = base.x + pose.lookX * w * 0.08f
        geo.eyeY = base.y - h * 0.47f + pose.lookY * h * 0.04f
        geo.eyeDx = w * 0.19f
        geo.eyeW = r * 0.19f * pose.scale
        geo.eyeH = r * 0.26f * pose.scale
        geo.mouthY = geo.eyeY + r * 0.26f * pose.scale * pose.stretch
        geo.body.mochi(base.x, base.y, w, h)

        val sideArms = 1f - max(pose.cross, pose.akimbo)
        if (sideArms > 0.01f) {
            drawArm(geo, -1, pose.armL, pose.reachL, bodyColor, deep, sideArms)
            drawArm(geo, 1, pose.armR, pose.reachR, bodyColor, deep, sideArms)
        }
        if (pose.akimbo > 0.01f) drawAkimbo(geo, bodyColor, deep, pose.akimbo)

        withHat(pose, geo) { drawHatBack(pose.costume, geo, skin) }

        clipPath(geo.body) {
            backdrop?.invoke(this, geo)
            // Fresnel: almost clear through the middle, dense toward the rim.
            drawRect(
                Brush.radialGradient(
                    0f to lerpColor(bodyColor, Color.White, 0.15f).copy(alpha = 0.5f),
                    0.55f to bodyColor.copy(alpha = 0.66f),
                    0.82f to lerpColor(bodyColor, deep, 0.25f).copy(alpha = 0.82f),
                    1f to deep.copy(alpha = 0.95f),
                    center = Offset(base.x + lx * w * 0.06f, base.y - h * 0.5f),
                    radius = max(w, h) * 0.62f,
                ),
                Offset(base.x - w, base.y - h * 1.5f),
                Size(w * 2f, h * 2f),
            )
            drawRect(light.ambient.copy(alpha = 0.1f), Offset(base.x - w, base.y - h * 1.5f), Size(w * 2f, h * 2f))
            // Self-shadow on the side turned away from the light.
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, deep.copy(alpha = 0.32f)),
                    center + Offset(lx * w * 0.2f, ly * h * 0.2f),
                    center - Offset(lx * w * 0.55f, ly * h * 0.55f),
                ),
                Offset(base.x - w, base.y - h * 1.5f),
                Size(w * 2f, h * 2f),
            )
            // Subsurface scattering: light enters on the lit side and glows out low on the far side.
            val sss = Offset(base.x - lx * w * 0.22f, base.y - h * 0.24f)
            drawCircle(
                Brush.radialGradient(
                    listOf(lerpColor(skin.glow, light.color, 0.35f).copy(alpha = 0.7f), Color.Transparent),
                    sss,
                    w * 0.45f,
                ),
                w * 0.45f,
                sss,
            )
            repeat(4) { i ->
                val phase = wrap(t * (0.1f + 0.06f * hash(seed + i)) + hash(seed * 7 + i), 1f)
                val bx = base.x + (hash(seed * 3 + i) - 0.5f) * w * 0.6f + sin(t + i) * w * 0.03f
                val byy = base.y - h * (0.1f + 0.75f * phase)
                val br = r * (0.03f + 0.035f * hash(seed * 5 + i))
                val ba = sin(PI.toFloat() * phase) * 0.6f
                drawCircle(Color.White, br, Offset(bx, byy), alpha = ba * 0.3f)
                drawCircle(Color.White, br, Offset(bx, byy), alpha = ba, style = Stroke(r * 0.012f))
                drawCircle(Color.White, br * 0.3f, Offset(bx + lx * br * 0.35f, byy + ly * br * 0.35f), alpha = ba)
            }
            drawOutfit(pose.outfit ?: pose.costume, geo, skin)
            if (pose.flush > 0.01f) {
                val level = base.y - h * (pose.flush * 1.08f)
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFFFF4D4D).copy(alpha = 0.5f), Color(0xFFE03131).copy(alpha = 0.62f)),
                        level - h * 0.12f,
                        base.y,
                    ),
                    Offset(base.x - w, level - h * 0.12f),
                    Size(w * 2f, base.y - level + h * 0.12f),
                )
            }
            inside?.invoke(this, geo)
            drawOval(
                Brush.verticalGradient(listOf(Color.Transparent, lerpColor(skin.glow, light.ambient, 0.3f).copy(alpha = 0.5f)), base.y - h * 0.2f, base.y),
                Offset(base.x - w * 0.34f, base.y - h * 0.2f),
                Size(w * 0.68f, h * 0.22f),
            )
            drawPath(geo.body, deep.copy(alpha = 0.2f), style = Stroke(r * 0.24f))
            // Rim light from the key light.
            drawPath(
                geo.body,
                Brush.linearGradient(
                    listOf(light.color.copy(alpha = 0.95f), light.color.copy(alpha = 0f)),
                    center + Offset(lx * w * 0.55f, ly * h * 0.55f),
                    center,
                ),
                style = Stroke(r * 0.16f),
            )
        }
        drawPath(geo.body, deep.copy(alpha = 0.6f), style = Stroke(r * 0.035f, join = StrokeJoin.Round))

        // Specular highlights facing the light.
        val spec = Offset(base.x + lx * w * 0.2f - w * 0.06f, base.y - h * 0.78f + ly.coerceAtLeast(-0.2f) * h * 0.1f)
        val specAngle = Math.toDegrees(kotlin.math.atan2(ly, lx).toDouble()).toFloat() + 90f
        rotate(specAngle * 0.35f - 20f, spec) {
            drawOval(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.35f), Color.Transparent),
                    spec,
                    w * 0.16f,
                ),
                spec - Offset(w * 0.14f, h * 0.075f),
                Size(w * 0.28f, h * 0.15f),
            )
        }
        drawCircle(Color.White, r * 0.05f, spec + Offset(w * 0.15f, -h * 0.06f), alpha = 0.95f)
        drawCircle(Color.White, r * 0.022f, spec + Offset(w * 0.21f, -h * 0.02f), alpha = 0.8f)
        drawArc(
            Color.White, if (lx < 0f) 200f else -62f, 34f, false,
            Offset(base.x - w * 0.44f, base.y - h * 0.96f), Size(w * 0.88f, h * 1.5f),
            alpha = 0.6f, style = Stroke(r * 0.03f, cap = StrokeCap.Round),
        )

        drawFace(pose, geo, skin, t)
        if (pose.cross > 0.01f) drawCrossedArms(geo, bodyColor, deep, pose.cross)
        withHat(pose, geo) { drawHatFront(pose.costume, geo, skin, pose) }
    }

    drawSignatures(pose, geo, t, seed)

    if (pose.sweat > 0.01f) {
        val drop = Offset(base.x + w * 0.4f, base.y - h * (0.78f - 0.12f * pose.sweat))
        drawUnit(drop, r * 0.11f * (0.6f + 0.4f * pose.sweat)) {
            drawPath(UnitDrop, Brush.verticalGradient(listOf(Color(0xFFE7F5FF), Color(0xFF74C0FC)), -1f, 1f), alpha = pose.sweat)
            drawPath(UnitDrop, Color(0xFF339AF0), alpha = pose.sweat * 0.7f, style = Stroke(0.12f))
            drawCircle(Color.White, 0.18f, Offset(-0.22f, 0.25f), alpha = pose.sweat)
        }
    }
    if (pose.anger > 0.01f) {
        val pop = easeOutBack(pose.anger.coerceIn(0f, 1f), 2.6f) * (1f + 0.08f * sin(t * 18f))
        drawAngerMark(Offset(base.x + w * 0.34f, base.y - h * 0.98f), r * 0.17f * pop)
    }
    if (pose.dizzy > 0.01f) {
        val head = Offset(base.x, base.y - h - r * 0.15f)
        repeat(3) { k2 ->
            val a = t * 4.5f + k2 * TAU / 3
            val depth = (sin(a) + 1f) / 2f
            val p = head + Offset(cos(a) * w * 0.38f, sin(a) * r * 0.16f)
            drawCartoonStar(p, r * (0.09f + 0.05f * depth), t * 200f + k2 * 60f, pose.dizzy * (0.6f + 0.4f * depth))
        }
    }
    if (pose.poof in 0f..1f) drawPoof(Offset(base.x, base.y - h * 0.75f), pose.poof, r)
}

private fun DrawScope.drawAngerMark(c: Offset, size: Float) {
    if (size <= 0f) return
    val red = Color(0xFFFF3B5C)
    for (q in 0 until 4) {
        val sx = if (q % 2 == 0) -1f else 1f
        val sy = if (q < 2) -1f else 1f
        val center = c + Offset(sx * size * 0.62f, sy * size * 0.62f)
        val start = when (q) {
            0 -> 0f
            1 -> 90f
            2 -> 270f
            else -> 180f
        }
        drawArc(
            red, start, 90f, false,
            center - Offset(size * 0.5f, size * 0.5f), Size(size, size),
            style = Stroke(size * 0.3f, cap = StrokeCap.Round),
        )
    }
}

/** Puff of cartoon smoke while a costume swaps in. */
internal fun DrawScope.drawPoof(c: Offset, e: Float, r: Float) {
    val fade = 1f - e
    repeat(7) { k ->
        val a = k * TAU / 7 + 0.3f
        val d = r * (0.3f + 0.6f * easeOutCubic(e))
        val p = c + Offset(cos(a) * d, sin(a) * d * 0.7f)
        val rad = r * (0.22f + 0.12f * hash(k + 1700)) * (0.5f + 0.7f * easeOutCubic(e))
        drawCircle(Color.White, rad, p, alpha = 0.9f * fade)
        drawCircle(Color(0x33000000), rad, p, alpha = fade, style = Stroke(r * 0.02f))
    }
    repeat(4) { k ->
        val a = k * TAU / 4 + e * 2f
        drawSparkle(c + Offset(cos(a), sin(a)) * (r * (0.6f + 0.6f * e)), r * 0.08f, Color.White, fade)
    }
}

internal fun DrawScope.drawFace(pose: SlimePose, g: SlimeGeo, skin: SlimeSkin, t: Float) {
    val r = g.r
    val eyeW = g.eyeW
    val eyeH = g.eyeH
    val stroke = r * 0.06f
    for (side in intArrayOf(-1, 1)) {
        val ec = Offset(g.faceX + side * g.eyeDx, g.eyeY)
        when (pose.mood) {
            Mood.Happy -> drawArc(
                Ink, 200f, 140f, false,
                ec - Offset(eyeW * 0.75f, eyeH * 0.25f), Size(eyeW * 1.5f, eyeH * 0.9f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )

            Mood.Squeeze -> {
                val dir = side.toFloat()
                val p = Path().apply {
                    moveTo(ec.x - dir * eyeW * 0.55f, ec.y - eyeH * 0.4f)
                    lineTo(ec.x + dir * eyeW * 0.45f, ec.y)
                    lineTo(ec.x - dir * eyeW * 0.55f, ec.y + eyeH * 0.4f)
                }
                drawPath(p, Ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            Mood.Dizzy -> drawUnit(ec, eyeW * 0.75f, t * 420f * side) {
                drawPath(UnitSpiral, Ink, style = Stroke(0.16f, cap = StrokeCap.Round))
            }

            else -> {
                val bulge = if (pose.mood == Mood.Surprised) 1.25f else 1f
                val ew = eyeW * bulge
                val shut = max(pose.blink, pose.sleep)
                val eh = max(eyeH * bulge * (1f - shut), r * 0.035f)
                if (shut > 0.7f) {
                    drawArc(
                        Ink, 20f, 140f, false, ec - Offset(ew * 0.6f, eyeH * 0.3f), Size(ew * 1.2f, eyeH * 0.5f),
                        style = Stroke(stroke * 0.9f, cap = StrokeCap.Round),
                    )
                } else if (pose.mood == Mood.Surprised) {
                    drawOval(Color.White, ec - Offset(ew / 2, eh / 2), Size(ew, eh))
                    drawOval(Ink, ec - Offset(ew / 2, eh / 2), Size(ew, eh), style = Stroke(r * 0.03f))
                    drawCircle(Ink, ew * 0.22f, ec + Offset(sin(t * 30f) * ew * 0.06f, 0f))
                } else {
                    drawOval(
                        Brush.verticalGradient(listOf(Ink, lerpColor(Ink, skin.deep, 0.45f)), ec.y - eh / 2, ec.y + eh / 2),
                        ec - Offset(ew / 2, eh / 2),
                        Size(ew, eh),
                    )
                    if (eh > eyeH * 0.5f) {
                        drawOval(
                            lerpColor(skin.body, Color.White, 0.35f),
                            Offset(ec.x - ew * 0.24f, ec.y + eh * 0.14f), Size(ew * 0.48f, eh * 0.22f), alpha = 0.6f,
                        )
                        drawCircle(Color.White, ew * 0.3f, ec + Offset(ew * 0.14f, -eh * 0.2f))
                        drawCircle(Color.White, ew * 0.13f, ec + Offset(-ew * 0.2f, eh * 0.16f), alpha = 0.9f)
                    }
                }
            }
        }
        when (pose.mood) {
            Mood.Angry -> drawLine(
                Ink,
                Offset(ec.x + side * eyeW * 0.85f, ec.y - eyeH * 0.95f),
                Offset(ec.x - side * eyeW * 0.55f, ec.y - eyeH * 0.5f),
                r * 0.07f, StrokeCap.Round,
            )

            Mood.Worried, Mood.Surprised -> drawLine(
                Ink,
                Offset(ec.x + side * eyeW * 0.7f, ec.y - eyeH * 0.75f),
                Offset(ec.x - side * eyeW * 0.5f, ec.y - eyeH * 1.0f),
                r * 0.045f, StrokeCap.Round,
            )

            else -> {}
        }

        val blushC = Offset(g.faceX + side * g.w * 0.3f, g.eyeY + eyeH * 0.68f)
        val puff = 1f + pose.choke * 0.25f
        val bw = r * 0.3f * puff
        val bh = r * 0.15f * puff
        withTransform({ scale(1f, bh / bw, blushC) }) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFF6E9C).copy(alpha = 0.6f), Color.Transparent), blushC, bw / 2),
                bw / 2,
                blushC,
            )
        }
        for (i in -1..1) {
            val x = blushC.x + i * bw * 0.18f
            drawLine(Color.White, Offset(x + bw * 0.05f, blushC.y - bh * 0.25f), Offset(x - bw * 0.05f, blushC.y + bh * 0.25f), r * 0.016f, StrokeCap.Round, alpha = 0.7f)
        }
    }

    val m = Offset(g.faceX, g.mouthY)
    if (pose.mouthOpen > 0.05f) {
        val o = pose.mouthOpen
        val mw = r * (0.16f + 0.24f * o) * g.s
        val mh = r * (0.1f + 0.32f * o) * g.s
        val mouth = Path().apply {
            moveTo(m.x - mw / 2, m.y - mh * 0.3f)
            quadraticTo(m.x, m.y - mh * 0.45f, m.x + mw / 2, m.y - mh * 0.3f)
            cubicTo(m.x + mw * 0.55f, m.y + mh * 0.4f, m.x + mw * 0.25f, m.y + mh * 0.62f, m.x, m.y + mh * 0.62f)
            cubicTo(m.x - mw * 0.25f, m.y + mh * 0.62f, m.x - mw * 0.55f, m.y + mh * 0.4f, m.x - mw / 2, m.y - mh * 0.3f)
            close()
        }
        drawPath(mouth, Color(0xFF5A1F3B))
        clipPath(mouth) {
            drawOval(Color(0xFFFF7A9A), Offset(m.x - mw * 0.32f, m.y + mh * 0.15f), Size(mw * 0.64f, mh * 0.6f))
        }
        drawPath(mouth, Ink, style = Stroke(r * 0.025f, join = StrokeJoin.Round))
    } else {
        when (pose.mood) {
            Mood.Angry, Mood.Worried -> drawArc(
                Ink, 205f, 130f, false, m - Offset(r * 0.11f, r * 0.0f), Size(r * 0.22f, r * 0.14f),
                style = Stroke(r * 0.05f, cap = StrokeCap.Round),
            )

            Mood.Dizzy -> {
                val p = Path().apply {
                    moveTo(m.x - r * 0.13f, m.y)
                    for (i in 1..6) lineTo(m.x - r * 0.13f + i * r * 0.044f, m.y + if (i % 2 == 0) 0f else r * 0.04f)
                }
                drawPath(p, Ink, style = Stroke(r * 0.04f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            Mood.Surprised -> drawCircle(Ink, r * 0.05f, m, style = Stroke(r * 0.035f))

            else -> {
                val wide = if (pose.mood == Mood.Happy) 1.25f else 1f
                for (sgn in intArrayOf(-1, 1)) {
                    drawArc(
                        Ink, 0f, 180f, false,
                        Offset(m.x + sgn * r * 0.06f * wide - r * 0.06f * wide, m.y - r * 0.05f), Size(r * 0.12f * wide, r * 0.1f),
                        style = Stroke(r * 0.045f, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/** Runs [block] with the hat moved by [SlimePose.hatLift] and tilted by [SlimePose.hatTilt]. */
private inline fun DrawScope.withHat(pose: SlimePose, g: SlimeGeo, block: DrawScope.() -> Unit) {
    if (pose.hatLift == 0f && pose.hatTilt == 0f) {
        block()
        return
    }
    withTransform({
        translate(0f, -pose.hatLift)
        rotate(pose.hatTilt, Offset(g.cx, g.top + g.h * 0.12f))
    }, block)
}

/** A stubby jelly arm from the side of the body with a round hand; drawn behind the body. */
private fun DrawScope.drawArm(g: SlimeGeo, side: Int, angle: Float, reach: Float, body: Color, deep: Color, alpha: Float) {
    val shoulder = Offset(g.cx + side * g.w * 0.4f, g.by - g.h * 0.42f)
    val a = Math.toRadians(angle.toDouble()).toFloat()
    val hand = shoulder + Offset(side * sin(a), cos(a)) * (g.r * 0.55f * reach)
    drawLine(deep, shoulder, hand, g.r * 0.25f, StrokeCap.Round, alpha = 0.65f * alpha)
    drawLine(lerpColor(body, Color.White, 0.2f), shoulder, hand, g.r * 0.19f, StrokeCap.Round, alpha = 0.92f * alpha)
    drawCircle(deep, g.r * 0.135f, hand, alpha = 0.6f * alpha)
    drawCircle(lerpColor(body, Color.White, 0.32f), g.r * 0.11f, hand, alpha = alpha)
    drawCircle(Color.White, g.r * 0.035f, hand + Offset(-g.r * 0.03f, -g.r * 0.04f), alpha = 0.8f * alpha)
}

/** Hands planted on the hips: elbows out, fists back against the body. */
private fun DrawScope.drawAkimbo(g: SlimeGeo, body: Color, deep: Color, alpha: Float) {
    for (side in intArrayOf(-1, 1)) {
        val shoulder = Offset(g.cx + side * g.w * 0.4f, g.by - g.h * 0.5f)
        val elbow = Offset(g.cx + side * g.w * 0.66f, g.by - g.h * 0.36f)
        val fist = Offset(g.cx + side * g.w * 0.46f, g.by - g.h * 0.22f)
        val path = Path().apply {
            moveTo(shoulder.x, shoulder.y)
            lineTo(elbow.x, elbow.y)
            lineTo(fist.x, fist.y)
        }
        drawPath(path, deep, alpha = 0.65f * alpha, style = Stroke(g.r * 0.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, lerpColor(body, Color.White, 0.2f), alpha = alpha, style = Stroke(g.r * 0.19f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lerpColor(body, Color.White, 0.32f), g.r * 0.11f, fist, alpha = alpha)
    }
}

/** Arms folded across the front, below the mouth. */
private fun DrawScope.drawCrossedArms(g: SlimeGeo, body: Color, deep: Color, alpha: Float) {
    val y = g.mouthY + g.h * 0.14f
    for (side in intArrayOf(-1, 1)) {
        val from = Offset(g.cx + side * g.w * 0.46f, y - g.h * 0.03f)
        val to = Offset(g.cx - side * g.w * 0.16f, y + side * g.h * 0.025f)
        drawLine(deep, from, to, g.r * 0.25f, StrokeCap.Round, alpha = 0.7f * alpha)
        drawLine(lerpColor(body, Color.White, 0.22f), from, to, g.r * 0.19f, StrokeCap.Round, alpha = alpha)
        drawCircle(lerpColor(body, Color.White, 0.32f), g.r * 0.11f, to, alpha = alpha)
    }
}

/** Musical note: filled head, stem and flag. */
private fun DrawScope.drawNote(c: Offset, s: Float, color: Color, alpha: Float) {
    rotate(-20f, c) { drawOval(color, c - Offset(s * 0.5f, s * 0.35f), Size(s, s * 0.7f), alpha = alpha) }
    val top = c + Offset(s * 0.42f, -s * 2f)
    drawLine(color, c + Offset(s * 0.42f, -s * 0.1f), top, s * 0.16f, alpha = alpha)
    val flag = Path().apply {
        moveTo(top.x, top.y)
        quadraticTo(top.x + s * 0.7f, top.y + s * 0.4f, top.x + s * 0.45f, top.y + s * 1.1f)
    }
    drawPath(flag, color, alpha = alpha, style = Stroke(s * 0.16f, cap = StrokeCap.Round))
}

/** Hand-drawn "Z". */
private fun DrawScope.drawZ(c: Offset, s: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(c.x - s / 2, c.y - s / 2)
        lineTo(c.x + s / 2, c.y - s / 2)
        lineTo(c.x - s / 2, c.y + s / 2)
        lineTo(c.x + s / 2, c.y + s / 2)
    }
    drawPath(p, Color(0xFF3B3561), alpha = alpha, style = Stroke(s * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(p, Color.White, alpha = alpha * 0.9f, style = Stroke(s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Each character's tell: Bơ's ear smoke, Mochi's kettle steam, Soda's sparkles, Chanh's whistle, sleep. */
private fun DrawScope.drawSignatures(pose: SlimePose, g: SlimeGeo, t: Float, seed: Int) {
    val r = g.r
    if (pose.flush > 0.8f) {
        val a = (pose.flush - 0.8f) / 0.2f
        for (side in intArrayOf(-1, 1)) {
            repeat(4) { k ->
                val f = wrap(t * 1.6f + k / 4f + side * 0.13f, 1f)
                val c = Offset(g.cx + side * (g.w * 0.4f + f * r * 0.7f), g.top + g.h * 0.15f - f * r * 1.1f)
                drawCircle(Color(0xFFE9ECEF), r * (0.08f + 0.16f * f), c, alpha = a * (1f - f) * 0.9f)
            }
        }
    }
    if (pose.steam > 0.01f) {
        repeat(5) { k ->
            val f = wrap(t * 2.2f + k / 5f, 1f)
            val c = Offset(g.cx + sin(f * 7f + k) * r * 0.18f, g.top - f * r * 1.2f)
            drawCircle(Color.White, r * (0.06f + 0.14f * f), c, alpha = pose.steam * (1f - f) * 0.85f)
        }
        drawLine(Color.White, Offset(g.cx - r * 0.12f, g.top - r * 0.05f), Offset(g.cx + r * 0.12f, g.top - r * 0.05f), r * 0.03f, StrokeCap.Round, alpha = pose.steam * (0.5f + 0.5f * sin(t * 40f)))
    }
    if (pose.sparkle > 0.01f) {
        repeat(6) { k ->
            val a = k * TAU / 6 + t * 0.8f
            val pulse = ((sin(t * 6f + k * 1.7f) + 1f) / 2f)
            val c = Offset(g.cx + cos(a) * g.w * 0.65f, g.by - g.h * 0.55f + sin(a) * g.h * 0.6f)
            drawSparkle(c, r * (0.06f + 0.08f * pulse), Color(0xFFFFF3BF), pose.sparkle * (0.4f + 0.6f * pulse))
        }
    }
    if (pose.whistle > 0.01f) {
        repeat(3) { k ->
            val f = wrap(t * 0.9f + k / 3f, 1f)
            val c = Offset(g.cx + g.w * 0.3f + f * r * 0.9f + sin(f * 9f + k) * r * 0.08f, g.mouthY - f * r * 1.3f)
            drawNote(c, r * 0.13f, Color(0xFF3B3561), pose.whistle * sin(PI.toFloat() * f))
        }
    }
    if (pose.sleep > 0.01f) {
        repeat(3) { k ->
            val f = wrap(t * 0.45f + k / 3f, 1f)
            val c = Offset(g.cx + g.w * 0.25f + f * r * 0.8f, g.top - f * r * 1.1f + r * 0.2f)
            drawZ(c, r * (0.12f + 0.12f * f), pose.sleep * sin(PI.toFloat() * f))
        }
        if (pose.snore > 0f) {
            val c = Offset(g.faceX + g.w * 0.08f, g.mouthY - g.eyeH * 0.2f)
            val rad = r * 0.28f * pose.snore
            drawCircle(Brush.radialGradient(listOf(Color(0x33A5D8FF), Color(0x88A5D8FF)), c, rad), rad, c)
            drawCircle(Color.White, rad, c, alpha = 0.8f, style = Stroke(r * 0.02f))
            drawCircle(Color.White, rad * 0.22f, c + Offset(-rad * 0.35f, -rad * 0.35f), alpha = 0.9f)
        }
    }
}
