package me.weishu.kernelsu.ui.slime

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.cos
import kotlin.math.sin
import me.weishu.kernelsu.ui.screen.home.arena.Cast
import me.weishu.kernelsu.ui.screen.home.arena.Costume
import me.weishu.kernelsu.ui.screen.home.arena.PI_F
import me.weishu.kernelsu.ui.screen.home.arena.SLIME_COUNT
import me.weishu.kernelsu.ui.screen.home.arena.SlimeGeo
import me.weishu.kernelsu.ui.screen.home.arena.SlimeLight
import me.weishu.kernelsu.ui.screen.home.arena.drawSlime
import me.weishu.kernelsu.ui.screen.home.arena.drawSparkle
import me.weishu.kernelsu.ui.screen.home.arena.drawSpeechBubble
import me.weishu.kernelsu.ui.screen.home.arena.easeOutCubic
import me.weishu.kernelsu.ui.screen.home.arena.hash
import me.weishu.kernelsu.ui.screen.home.arena.lerp
import me.weishu.kernelsu.ui.screen.home.arena.progress
import me.weishu.kernelsu.ui.screen.home.arena.three.Roam3D
import me.weishu.kernelsu.ui.theme.AppFontFamily

/** The country costume the status card is showing; the roaming slimes dress the same. */
@Volatile
internal var roamCostume: Costume = Costume.NonLa

/**
 * The roaming slimes, over every page of the app. Wraps [content] and draws the slimes on top;
 * only touches that land on a slime are taken, everything else goes through to the app.
 * [page] identifies the page on screen (a new one re-rolls who is around); on the [home] page
 * they live in the status card instead; on a [quiet] page (flashing, installing) they keep out of
 * the way; [floorInset] keeps them above the floating bottom bar. [enabled], [maxCount] (0 for a
 * random 1 to 4 a page) and [nightNap] are the user's settings.
 */
@Composable
fun SlimeLayer(
    page: Any,
    home: Boolean,
    floorInset: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxCount: Int = 0,
    nightNap: Boolean = true,
    quiet: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (reduceMotion || !enabled) {
        // Switched off: they all stay home in the status card.
        SideEffect { SlimeHome.reset() }
        Box(modifier, content = content)
        return
    }
    val world = remember { RoamWorld() }
    // The very same 3D slimes as in the status card, in a see-through layer over the whole
    // window that never takes a touch; without Filament they are drawn flat instead.
    val roam3d = remember { Roam3D.createOrNull(context) }
    val hostView = LocalView.current
    val surface = remember { arrayOfNulls<TextureView>(1) }
    DisposableEffect(roam3d) {
        val root = hostView.rootView.findViewById<ViewGroup>(android.R.id.content)
        val tv = if (roam3d != null && root != null) {
            TextureView(hostView.context).apply {
                isOpaque = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                roam3d.attach(this)
                root.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        } else null
        surface[0] = tv
        onDispose {
            surface[0] = null
            tv?.let { root?.removeView(it) }
            roam3d?.destroy()
        }
    }
    val yaw = remember { FloatArray(SLIME_COUNT) }
    val hiddenFor = remember { floatArrayOf(0f) }
    val frame = remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val floorPx = with(density) { floorInset.toPx() }
    val radius = with(density) { 25.dp.toPx() }
    SideEffect {
        world.r = radius
        world.maxCount = maxCount
        world.nightNap = nightNap
        world.page(page, home, quiet)
    }

    // A shake of the phone throws them off whatever they stand on.
    LifecycleResumeEffect(world) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val linear = sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                var a = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                if (linear == null) a = kotlin.math.abs(a - SensorManager.GRAVITY_EARTH)
                if (a > SHAKE_ACCEL) world.shake(a / SHAKE_ACCEL)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensors != null && sensor != null) sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onPauseOrDispose { sensors?.unregisterListener(listener) }
    }

    LaunchedEffect(world) {
        var last = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { nanos ->
                val dt = if (last < 0L) 0f else ((nanos - last) / 1e9f).coerceAtMost(0.05f)
                last = nanos
                world.costume = roamCostume
                if (world.w > 0f) world.step(dt)
                if (roam3d != null && world.w > 0f) {
                    roam3d.begin(world.r)
                    for (s in world.roamers) {
                        if (s.mode == RoamMode.Away) {
                            roam3d.hide(s.k)
                            continue
                        }
                        // Turning a little toward where it is going.
                        val moving = s.mode == RoamMode.Walk || s.mode == RoamMode.Hop || s.mode == RoamMode.Fall || s.mode == RoamMode.Thrown || s.mode == RoamMode.Home
                        yaw[s.k] += ((if (moving) s.dir * 24f else 0f) - yaw[s.k]) * (dt * 8f).coerceAtMost(1f)
                        roam3d.place(s.k, s.pose, world.now, s.x, s.y, s.angle, yaw[s.k])
                    }
                    roam3d.end()
                    // Out of the way of the compositor while nobody is out.
                    hiddenFor[0] = if (roam3d.anyVisible) 0f else hiddenFor[0] + dt
                    val tv = surface[0]
                    val show = hiddenFor[0] < 0.3f
                    if (show || tv?.visibility == android.view.View.VISIBLE) roam3d.render(nanos)
                    if (tv != null) {
                        val v = if (show) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        if (tv.visibility != v) tv.visibility = v
                    }
                }
                frame.intValue++
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(world) {
                val slop = viewConfiguration.touchSlop
                val holdMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val s = world.hit(down.position)
                    if (s == null) {
                        // Not a slime: the touch belongs to the app and is only watched. A quick tap
                        // shoos nearby slimes off that spot; a finger held still becomes a laser dot
                        // they chase until it lifts, and from then on the touch is the laser's (no
                        // page swipes or clicks while you play).
                        var moved = false
                        var laser = false
                        var at = down.position
                        while (true) {
                            val waitMs = LASER_HOLD_MS - (SystemClock.uptimeMillis() - down.uptimeMillis)
                            val event = if (!laser && !moved && waitMs > 0) {
                                withTimeoutOrNull(waitMs) { awaitPointerEvent(PointerEventPass.Initial) }
                            } else if (!laser && !moved) {
                                null
                            } else {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                laser = true
                                world.laserAt(at)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            at = change.position
                            if (laser) change.consume()
                            if (!change.pressed) {
                                if (!laser && !moved && change.uptimeMillis - down.uptimeMillis < 350) world.shoo(change.position)
                                break
                            }
                            if (!laser && (change.position - down.position).getDistance() > slop) moved = true
                            if (laser) world.laserAt(change.position)
                        }
                        if (laser) world.laserAt(null)
                        return@awaitEachGesture
                    }
                    down.consume()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var held = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            if (held) {
                                val v = tracker.calculateVelocity()
                                world.release(s, Offset(v.x, v.y))
                            } else {
                                world.tap(s)
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                            break
                        }
                        val moved = (change.position - down.position).getDistance() > slop
                        if (!held && (moved || change.uptimeMillis - down.uptimeMillis > holdMs)) {
                            held = true
                            world.grab(s, change.position)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        if (held) world.drag(s, change.position)
                    }
                }
            }
    ) {
        content()
        val measurer = rememberTextMeasurer(cacheSize = 16)
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    world.w = size.width
                    world.h = size.height
                    world.topY = statusTop.toFloat()
                    world.floorY = size.height - navBottom - floorPx
                    val style = TextStyle(color = Color(0xFF2A2440), fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    val words = mapOf(
                        Reaction.Wiggle to "á!",
                        Reaction.Huff to "hứ!",
                        Reaction.Explode to "bụp!",
                        Reaction.Melt to "~",
                        Reaction.Flip to "yay!",
                        Reaction.Flee to "!!",
                    ).mapValues { measurer.measure(it.value, style) }
                    val geos = Array(SLIME_COUNT) { SlimeGeo() }
                    val light = SlimeLight().apply {
                        color = Color(0xFFFFF4E0)
                        ambient = Color(0xFFB8C4E8)
                    }
                    val said = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()
                    fun text(s: String) = said.getOrPut(s) { measurer.measure(s, style) }
                    val clapStyle = style.copy(fontSize = 17.sp, color = Color(0xFFFF7A3D))
                    val claps = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()
                    onDrawBehind {
                        frame.intValue
                        drawShards(density.density)
                        world.laser?.let { drawLaserDot(it, world.r, world.now) }
                        // What they get up to together: dust clouds, claps, the odd word.
                        for (fx in world.social.fx) {
                            val e = (world.now - fx.t0) / fx.dur
                            when (fx.kind) {
                                FxKind.Cloud -> drawScuffle(fx.at, world.r, e, world.now)
                                FxKind.Clap -> drawClap(fx.at, world.r, e, claps.getOrPut(fx.text) { measurer.measure(fx.text, clapStyle) })
                                FxKind.Say -> fx.who?.let { who ->
                                    if (who.mode != RoamMode.Away) drawSpeechBubble(headOf(world, who), text(fx.text), e, world.radius(who.k) * 0.8f)
                                }
                            }
                        }
                        for (s in world.roamers) {
                            if (s.mode == RoamMode.Away) continue
                            if (roam3d == null) drawRoamer(world, s, geos[s.k], light) else if (s.pose.visible) drawGroundShadow(world, s)
                            if (s.mode == RoamMode.React && s.reaction == Reaction.Explode) drawBurst(world, s)
                            val say = when {
                                s.mode == RoamMode.React && s.modeT < 0.9f -> s.reaction
                                s.mode == RoamMode.Thrown && s.reaction == Reaction.Flee && s.modeT < 0.6f -> Reaction.Flee
                                else -> null
                            }
                            if (say != null) {
                                drawSpeechBubble(headOf(world, s), words.getValue(say), progress(s.modeT, 0f, 0.9f), world.radius(s.k) * 0.8f)
                            }
                        }
                    }
                }
        )
    }
}

/** One slime, standing on its surface (rotated onto the walls), lit from the top of the screen. */
private fun DrawScope.drawRoamer(world: RoamWorld, s: Roamer, geo: SlimeGeo, light: SlimeLight) {
    val a = Math.toRadians(-s.angle.toDouble())
    val lx = size.width * 0.3f - s.x
    val ly = -size.height * 0.3f - s.y
    light.pos = Offset((lx * cos(a) - ly * sin(a)).toFloat(), (lx * sin(a) + ly * cos(a)).toFloat())
    val grounded = s.mode == RoamMode.Walk || s.mode == RoamMode.Idle || s.mode == RoamMode.React
    withTransform({
        translate(s.x, s.y)
        rotate(s.angle, Offset.Zero)
    }) {
        drawSlime(s.pose, Cast[s.k], world.radius(s.k), 0f, world.now, geo, 300 + s.k * 37, light, shadow = grounded && s.angle == 0f)
    }
}

/** A soft shadow under a 3D slime standing or walking on something level (the 3D layer has no floor). */
private fun DrawScope.drawGroundShadow(world: RoamWorld, s: Roamer) {
    if (s.angle != 0f) return
    if (s.mode != RoamMode.Walk && s.mode != RoamMode.Idle && s.mode != RoamMode.React && s.mode != RoamMode.Home && s.mode != RoamMode.Social) return
    if (s.mode == RoamMode.Social && s.duet?.kind == Duo.Ride && s === s.duet?.a) return
    val rad = world.radius(s.k) * s.pose.scale
    val k = (1f - s.pose.lift / (rad * 3f)).coerceIn(0.3f, 1f)
    val w = rad * 1.9f * k
    val h = rad * 0.34f * k
    drawOval(
        androidx.compose.ui.graphics.Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.28f * k),
            1f to Color.Transparent,
            center = Offset(s.x, s.y),
            radius = w / 2f,
        ),
        topLeft = Offset(s.x - w / 2f, s.y - h / 2f),
        size = androidx.compose.ui.geometry.Size(w, h),
    )
}

/** The laser dot under a held finger: a red point with a glow that flickers a little. */
private fun DrawScope.drawLaserDot(at: Offset, r: Float, now: Float) {
    val flicker = 0.85f + 0.15f * sin(now * 37f)
    drawCircle(
        androidx.compose.ui.graphics.Brush.radialGradient(
            0f to Color(0xFFFF2D2D).copy(alpha = 0.55f * flicker),
            1f to Color.Transparent,
            center = at,
            radius = r * 1.1f,
        ),
        r * 1.1f,
        at,
    )
    drawCircle(Color(0xFFFF3B3B), r * 0.16f, at)
    drawCircle(Color.White, r * 0.07f, at, alpha = 0.9f)
}

/** Just above a slime's head, where its speech bubble hangs (clear of the 3D body). */
private fun headOf(world: RoamWorld, s: Roamer): Offset {
    val c = world.center(s)
    return c + Offset(0f, -world.radius(s.k) * 1.05f * s.pose.scale)
}

/** A scuffle: a rolling dust cloud with stars, flailing arms and the odd foot sticking out. */
private fun DrawScope.drawScuffle(at: Offset, r: Float, e: Float, now: Float) {
    if (e <= 0f || e >= 1f) return
    val grow = progress(e, 0f, 0.12f)
    val fade = 1f - progress(e, 0.85f, 0.15f)
    val wob = r * 0.12f
    val puffs = 9
    val body = androidx.compose.ui.graphics.Path()
    repeat(puffs) { i ->
        val a = i / puffs.toFloat() * 2f * PI_F + now * 2.2f
        val d = r * 1.15f * grow
        val c = at + Offset(cos(a) * d * 1.35f, sin(a) * d * 0.75f) + Offset(sin(now * 13f + i) * wob, cos(now * 11f + i * 2) * wob)
        val pr = r * (0.75f + 0.25f * hash(i + 400)) * grow
        body.addOval(androidx.compose.ui.geometry.Rect(c.x - pr, c.y - pr, c.x + pr, c.y + pr))
    }
    body.addOval(androidx.compose.ui.geometry.Rect(at.x - r * 1.3f * grow, at.y - r * 0.9f * grow, at.x + r * 1.3f * grow, at.y + r * 0.9f * grow))
    drawPath(body, Color(0xFF8C8C9A), alpha = 0.35f * fade, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.22f))
    drawPath(body, Color(0xFFF4F1EC), alpha = 0.97f * fade)
    // Arms and feet poking out in the slimes' colors.
    repeat(5) { i ->
        val k = i % Cast.size
        val a = hash(i * 7 + (now * 6f).toInt() * 13) * 2f * PI_F
        val from = at + Offset(cos(a) * r * 1.2f, sin(a) * r * 0.7f)
        val to = from + Offset(cos(a) * r * 0.55f, sin(a) * r * 0.4f)
        drawLine(Cast[k].body, from, to, r * 0.22f, androidx.compose.ui.graphics.StrokeCap.Round, alpha = fade)
    }
    // Stars and swirls.
    repeat(4) { i ->
        val a = now * 5f + i * 1.7f
        val p = at + Offset(cos(a) * r * 1.6f, -r * 0.9f + sin(a * 1.3f) * r * 0.4f)
        drawSparkle(p, r * 0.22f, Color(0xFFFFD166), fade)
    }
}

/** The high-five: a burst of lines and stars where the hands met, and the sound of it. */
private fun DrawScope.drawClap(at: Offset, r: Float, e: Float, label: androidx.compose.ui.text.TextLayoutResult) {
    if (e <= 0f || e >= 1f) return
    val burst = easeOutCubic(progress(e, 0f, 0.35f))
    val fade = 1f - progress(e, 0.55f, 0.45f)
    repeat(8) { i ->
        val a = i / 8f * 2f * PI_F
        val from = at + Offset(cos(a), sin(a)) * (r * (0.35f + 0.5f * burst))
        val to = at + Offset(cos(a), sin(a)) * (r * (0.6f + 0.9f * burst))
        drawLine(Color(0xFFFFC23D), from, to, r * 0.09f, androidx.compose.ui.graphics.StrokeCap.Round, alpha = fade)
    }
    drawSparkle(at, r * 0.35f * (0.6f + 0.4f * burst), Color.White, fade)
    val pop = easeOutCubic(progress(e, 0f, 0.25f))
    val tl = at + Offset(-label.size.width / 2f, -r * 1.2f - label.size.height - r * 0.4f * e)
    withTransform({ scale(0.6f + 0.4f * pop, 0.6f + 0.4f * pop, at) }) {
        drawText(label, topLeft = tl, alpha = fade)
    }
}

/** The droplets of a slime that went pop, flying out and finding their way back together. */
private fun DrawScope.drawBurst(world: RoamWorld, s: Roamer) {
    val e = s.modeT
    if (e < 0.12f || e > 1.4f) return
    val c = world.center(s)
    val rad = world.radius(s.k)
    val skin = Cast[s.k]
    repeat(9) { i ->
        val a = -PI_F / 2f + (hash(s.seed + i) - 0.5f) * PI_F * 1.8f
        val dist = rad * (1.6f + 1.4f * hash(s.seed + i + 20))
        val out = easeOutCubic(progress(e, 0.12f, 0.45f))
        val back = progress(e, 0.8f, 0.55f)
        val fall = progress(e, 0.12f, 0.68f)
        var p = c + Offset(cos(a) * dist * out, sin(a) * dist * out + fall * fall * rad * 1.2f)
        p = lerp(p, c, back * back)
        val dr = rad * (0.16f + 0.12f * hash(s.seed + i + 40)) * (1f - 0.3f * back)
        drawCircle(skin.deep, dr * 1.12f, p, alpha = 0.5f)
        drawCircle(skin.body, dr, p)
        drawCircle(Color.White, dr * 0.32f, p + Offset(-dr * 0.35f, -dr * 0.35f), alpha = 0.85f)
    }
    if (e < 0.4f) {
        val ring = progress(e, 0.12f, 0.28f)
        drawCircle(Color.White, rad * (0.6f + 1.6f * ring), c, alpha = 0.6f * (1f - ring), style = androidx.compose.ui.graphics.drawscope.Stroke(rad * 0.08f))
    }
}

/** How long a finger must rest before it turns into the laser dot. */
private const val LASER_HOLD_MS = 450L

/** Linear acceleration (m/s²) that counts as a shake. */
private const val SHAKE_ACCEL = 15f
