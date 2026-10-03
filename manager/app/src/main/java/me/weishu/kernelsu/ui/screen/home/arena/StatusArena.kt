package me.weishu.kernelsu.ui.screen.home.arena

import android.os.Build
import android.provider.Settings
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassDefaults
import me.weishu.kernelsu.ui.component.glass.glassMaterial
import me.weishu.kernelsu.ui.screen.home.arena.three.Arena3D
import me.weishu.kernelsu.ui.screen.home.arena.three.PanoramaMap
import me.weishu.kernelsu.ui.slime.SlimeHome
import me.weishu.kernelsu.ui.slime.drawHomeGlass
import me.weishu.kernelsu.ui.slime.roamCostume
import me.weishu.kernelsu.ui.slime.slimeSurface
import me.weishu.kernelsu.ui.theme.AppFontFamily
import top.yukonga.miuix.kmp.basic.Text

/**
 * The "working" status card as a small cartoon. Four jelly slimes of different sizes and tempers
 * (big sleepy Bơ, bossy Mochi, show-off Soda, tiny troublemaker Chanh) act out a scene-specific
 * squabble that ends in a rolling dust-cloud brawl; then everybody eats the status word like a
 * buffet (you can see the letters inside them), Bơ chokes, gets domino back-slaps and a belly ram
 * from Chanh, and throws up a rainbow carrying the word in the next language while the next
 * country unrolls like a carpet and costumes drop onto everyone's heads.
 *
 * Tap a slime to make it flip, tap the word to start a round now, tap anywhere else for [onClick].
 * Animation only runs while [active]; with system animations off the card stays still.
 */
@Composable
fun StatusArena(
    version: String,
    mode: String?,
    tags: List<String>,
    active: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 160)
    val languages = ArenaLanguages
    val localized = stringResource(R.string.home_working)
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val runtime = remember {
        ArenaRuntime(languages.indexOfFirst { it.text == localized }.coerceAtLeast(0))
    }
    var clock by remember { mutableFloatStateOf(0f) }
    val latestOnClick by rememberUpdatedState(onClick)
    val grain = remember { filmGrain() }
    val arena3d = remember { Arena3D.createOrNull() }
    DisposableEffect(arena3d) {
        onDispose { arena3d?.destroy() }
    }
    val fontResolver = LocalFontFamilyResolver.current
    val backdrop = remember { BackdropPainter(TextMeasurer(fontResolver, Density(1f), LayoutDirection.Ltr, 4)) }

    LaunchedEffect(active, reduceMotion, arena3d) {
        if (!active) return@LaunchedEffect
        if (reduceMotion && arena3d == null) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { nanos ->
                roamCostume = languages[runtime.current].costume
                if (!reduceMotion) {
                    if (last >= 0L) clock += ((nanos - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    last = nanos
                    if (SlimeHome.busy()) runtime.hold(clock) else runtime.tick(clock, languages, haptic)
                }
                val s = runtime.stage
                if (arena3d != null && s != null) {
                    runtime.projected = true
                    runtime.updatePoses(clock, s, languages)
                    val v = runtime.sceneView(clock, s)
                    val t = clock
                    val split = arena3d.split
                    val map = arena3d.map
                    arena3d.update(s, runtime.poses, t, if (v.mix > 0.5f) v.next else v.cur, languages, runtime.shakeAt(t, s), runtime.rollAt(t)) {
                        backdrop.paint(this, v, languages, t, split, map)
                    }
                    arena3d.render(nanos)
                }
            }
        }
    }

    val shape = RoundedCornerShape(GlassDefaults.cardCorner)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .slimeSurface()
            .onGloballyPositioned { coords ->
                val p = coords.positionInRoot()
                SlimeHome.cardRect = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height)
            }
            .clip(shape)
            .glassMaterial(shape, GlassDefaults.cardTint())
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.15f))), shape)
            .semantics {
                contentDescription = "$localized. $version"
                if (onClick != null) {
                    onClick { latestOnClick?.invoke(); true }
                }
            }
    ) {
        if (arena3d != null) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { arena3d.attach(it) } },
                modifier = Modifier.matchParentSize(),
            )
        }
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val s = runtime.stage ?: return@detectTapGestures
                        val hit = if (runtime.show == null) {
                            (0 until SLIME_COUNT).firstOrNull { runtime.poses[it].contains(pos, s.radius[it], s.groundY, clock) }
                        } else {
                            null
                        }
                        when {
                            hit != null -> {
                                runtime.jumpStart[hit] = clock
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }

                            abs(pos.y - s.textY) < s.h * 0.15f -> {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                if (reduceMotion) {
                                    runtime.current = (runtime.current + 1) % languages.size
                                    clock += 0.001f
                                } else {
                                    runtime.startShow(clock, languages)
                                }
                            }

                            else -> latestOnClick?.invoke()
                        }
                    }
                }
                .drawWithCache {
                    val px = density
                    val stage = Stage(size.width, size.height, px)
                    runtime.stage = stage
                    val digitStyle = TextStyle(color = Color(0xFF39FF88), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    val env = SceneEnv(size, this, measurer.measure("0", digitStyle, density = this), measurer.measure("1", digitStyle, density = this))
                    val washes = languages.map { lang ->
                        Brush.verticalGradient(lang.wash.mapIndexed { i, c -> c.copy(alpha = 0.82f - 0.1f * i) })
                    }
                    val scenes = languages.map { it.scene(env) }
                    val runs = languages.map { measureRun(measurer, it, size.width * 0.8f, this) }
                    runtime.runs = runs
                    val bubbleStyle = TextStyle(color = Color(0xFF2A2440), fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    val comicStyle = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    val said = HashMap<String, TextLayoutResult>()
                    val shouted = HashMap<String, TextLayoutResult>()
                    val say: (String) -> TextLayoutResult = { text -> said.getOrPut(text) { measurer.measure(text, bubbleStyle, density = this) } }
                    val shout: (String) -> TextLayoutResult = { text -> shouted.getOrPut(text) { measurer.measure(text, comicStyle, density = this) } }
                    runtime.ctx = IntroCtx(stage, runtime.poses).also {
                        it.say = say
                        it.shout = shout
                        it.zero = env.zero
                        it.one = env.one
                    }
                    val words = listOf("BỤP!", "BỐP!", "ÁI!", "HÍC!", "BÙ!!").map(shout)
                    val glyphPose = GlyphPose()
                    val vignette = Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.22f),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = hypot(size.width, size.height) / 2f,
                    )
                    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), 0f, size.height * 0.35f)
                    // The scene is recorded once per frame and replayed: as the softly defocused
                    // background, and magnified inside every slime as what you see through the jelly.
                    val sceneLayer = obtainGraphicsLayer().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            renderEffect = BlurEffect(2.2f * px, 2.2f * px, TileMode.Clamp)
                        }
                    }
                    val refract: DrawScope.(SlimeGeo) -> Unit = { geo ->
                        val pivot = Offset(geo.cx, geo.by - geo.h * 0.5f)
                        withTransform({
                            rotate(-geo.rotation, pivot)
                            scale(1.28f, 1.28f, pivot)
                            translate(0f, geo.h * 0.05f)
                        }) { drawLayer(sceneLayer) }
                    }

                    fun DrawScope.scene(i: Int, t: Float) {
                        drawRect(washes[i])
                        scenes[i](this, t)
                        drawBokeh(t, 9000 + i * 50, 7, Color.White, px)
                    }

                    fun textOrigin(run: GlyphRun) = Offset((size.width - run.width) / 2f, stage.textY - run.height / 2f)

                    fun DrawScope.labelPill(run: GlyphRun, alpha: Float) {
                        if (alpha <= 0.01f) return
                        val l = run.label
                        val padX = 10f * px
                        val padY = 3f * px
                        val pill = Size(l.size.width + padX * 2, l.size.height + padY * 2)
                        val tl = Offset((size.width - pill.width) / 2f, stage.textY + run.height * 0.42f)
                        drawRoundRect(Color.White, tl, pill, CornerRadius(pill.height / 2), alpha = 0.18f * alpha)
                        drawRoundRect(Color.White, tl, pill, CornerRadius(pill.height / 2), alpha = 0.45f * alpha, style = Stroke(px))
                        drawText(l, topLeft = tl + Offset(padX, padY), alpha = alpha)
                    }

                    fun DrawScope.staticRun(run: GlyphRun, t: Float, hop: (Int) -> Float) {
                        val o = textOrigin(run)
                        run.glyphs.forEachIndexed { i, g ->
                            val bob = sin(t * 2.2f + i * 0.55f) * 1.6f * px
                            drawText(g, topLeft = Offset(o.x + run.offsets[i], o.y + bob - hop(i)))
                        }
                    }

                    /** Letters a slime has swallowed, tumbling inside the jelly. */
                    fun DrawScope.belly(geo: SlimeGeo, run: GlyphRun, glyphs: List<Int>, t: Float, alpha: Float, swirl: Float) {
                        if (alpha <= 0.01f) return
                        glyphs.forEachIndexed { n, gi ->
                            val g = run.glyphs[gi]
                            val a = hash(gi * 17 + 3) * TAU + t * (0.5f + hash(gi + 40)) * swirl
                            val c = Offset(geo.cx + cos(a) * geo.w * 0.2f, geo.by - geo.h * 0.25f + sin(a) * geo.h * 0.1f)
                            drawGlyph(g, c, geo.h * 0.2f / g.size.height, t * 50f * (hash(gi + 60) - 0.5f) * swirl + n * 30f, alpha * 0.9f)
                        }
                    }

                    onDrawBehind {
                        val now = clock
                        val rt = runtime
                        val sh = rt.show
                        val elapsed = if (sh != null) now - sh.start else 0f
                        val phase = sh?.phaseAt(elapsed)
                        val p = if (sh != null && phase != null) sh.progressOf(phase, elapsed) else 0f
                        val lt = if (sh != null && phase != null) sh.local(phase, elapsed) else 0f
                        if (arena3d == null) rt.updatePoses(now, stage, languages)
                        val r = stage.r
                        val cur = sh?.from ?: rt.current
                        val nxt = sh?.next ?: rt.current
                        val curRun = runs[cur]
                        val nextRun = runs[nxt]
                        val reveal = if (sh != null && phase == Phase.Vomit) Vomit.reveal(sh.style, stage, sh, lt, rt.projected) else Reveal.NONE
                        val sceneMix = when (phase) {
                            Phase.Vomit -> Vomit.mix(sh.style, lt)
                            Phase.Settle, Phase.Wander -> 1f
                            else -> 0f
                        }
                        for (k in 0 until SLIME_COUNT) {
                            val useNext = sh != null && (phase == Phase.Settle || phase == Phase.Wander || (phase == Phase.Vomit && sh.dropAt[k] >= 0f))
                            val l = (if (useNext) languages[nxt] else languages[cur]).light
                            rt.lights[k].pos = Offset(l.x * size.width, l.y * size.height)
                            rt.lights[k].color = l.color
                            rt.lights[k].ambient = l.ambient
                        }

                        val shake = rt.shakeAt(now, stage)
                        val sx = shake.x
                        val sy = shake.y

                        if (arena3d == null) sceneLayer.record {
                            when (phase) {
                                Phase.Vomit -> {
                                    if (reveal.kind != Reveal.Kind.All) scene(cur, now)
                                    revealed(reveal, 1f, { it }) { scene(nxt, now) }
                                }

                                Phase.Settle, Phase.Wander -> scene(nxt, now)
                                else -> scene(cur, now)
                            }
                        }

                        val roll = rt.rollAt(now)
                        withTransform({
                            translate(sx, sy)
                            rotate(roll, Offset(size.width / 2f, size.height / 2f))
                        }) {
                            if (arena3d == null) drawLayer(sceneLayer)
                            val ctx = rt.ctx
                            if (phase == Phase.Intro && ctx != null) with(sh.intro) { back(ctx) }

                            // Who has eaten what, for the letters seen inside each slime.
                            val eaten = Array(SLIME_COUNT) { ArrayList<Int>() }
                            if (sh != null && (phase == Phase.Eat || phase == Phase.Choke || phase == Phase.Vomit)) {
                                for (b in sh.bites) {
                                    if (phase == Phase.Eat && b.arrive > lt) continue
                                    for (gi in b.glyphs) if (!(b.eater == BO && phase != Phase.Eat && gi in sh.chokeOn)) eaten[b.eater] += gi
                                }
                            }
                            val bellyAlpha = if (phase == Phase.Vomit) 1f - progress(lt, 0f, 0.5f) else 1f
                            val swirl = when (phase) {
                                Phase.Choke -> 1f + 2f * progress(lt, 0.3f, 0.6f)
                                Phase.Vomit -> 5f
                                else -> 1f
                            }

                            if (arena3d == null) for (k in Lineup) {
                                if (phase == Phase.Brawl && k == CHANH) continue
                                val inside: (DrawScope.(SlimeGeo) -> Unit)? = if (eaten[k].isNotEmpty() || (k == BO && phase == Phase.Choke)) {
                                    { geo ->
                                        belly(geo, curRun, eaten[k], now, bellyAlpha, swirl)
                                        if (k == BO && phase == Phase.Choke && lt < RAM_AT + 0.15f) {
                                            val g = curRun.glyphs[sh.chokeOn[0]]
                                            val throat = Offset(geo.cx + sin(now * 50f) * r * 0.04f, geo.mouthY + geo.h * 0.12f)
                                            val up = progress(lt, RAM_AT, 0.15f)
                                            drawGlyph(g, lerp(throat, Offset(geo.cx, geo.mouthY - geo.h * 0.1f), up), geo.h * 0.3f / g.size.height, sin(now * 30f) * 12f, 1f - up)
                                        }
                                    }
                                } else null
                                drawSlime(rt.poses[k], Cast[k], stage.radius[k], stage.groundY, now, rt.geos[k], 300 + k * 37, rt.lights[k], refract, inside)
                            }

                            // The lost prop on its way to (or waiting in) its next scene, and the idle skits.
                            drawLostProps(rt, sh, phase, lt, now, stage)
                            if (sh == null) drawIdleSkit(rt.poses, rt.homes(stage), stage, rt.idleTime(now), now)
                            if (sh != null && phase != null) {
                                if (phase == Phase.Intro) {
                                    if (ctx != null) with(sh.intro) { front(ctx) }
                                } else {
                                    drawSignatureEffects(rt, sh, phase, p, lt, now, stage, languages, words, say, refract, { i -> scene(i, now) }, flatSlimes = arena3d == null)
                                }
                            }

                            if (arena3d == null) {
                                val lc = languages[cur].light
                                val ln = languages[nxt].light
                                drawBloom(Offset(lc.x * size.width, lc.y * size.height), lc.color, lc.bloom * (1f - sceneMix), size.height * 1.3f)
                                if (sceneMix > 0f) drawBloom(Offset(ln.x * size.width, ln.y * size.height), ln.color, ln.bloom * sceneMix, size.height * 1.3f)
                            }

                            // The word.
                            when (phase) {
                                null, Phase.Intro, Phase.Brawl, Phase.Recover, Phase.Gather -> {
                                    staticRun(curRun, now) { 0f }
                                    labelPill(curRun, 1f)
                                }

                                Phase.Scatter -> {
                                    val o = textOrigin(curRun)
                                    staticRun(curRun, now) { i ->
                                        val d = abs(o.x + curRun.offsets[i] - stage.center) / size.width
                                        sin(PI_F * progress(lt, d * 0.4f, 0.35f)) * r * 0.3f
                                    }
                                    labelPill(curRun, 1f)
                                }

                                Phase.Eat -> {
                                    val show = sh
                                    val o = textOrigin(curRun)
                                    val biteOf = arrayOfNulls<Bite>(curRun.glyphs.size)
                                    for (b in show.bites) for (gi in b.glyphs) biteOf[gi] = b
                                    curRun.glyphs.forEachIndexed { i, g ->
                                        val half = Offset(g.size.width / 2f, g.size.height / 2f)
                                        val start = Offset(o.x + curRun.offsets[i], o.y) + half
                                        val b = biteOf[i]
                                        if (b == null) {
                                            drawText(g, topLeft = start - half, alpha = 1f - progress(lt, show.bites.last().launch, 0.3f))
                                            return@forEachIndexed
                                        }
                                        val f = progress(lt, b.launch, FALL_SECONDS)
                                        if (f <= 0f) {
                                            val tremble = if (lt > b.launch - 0.25f) sin(now * 60f + i) * 1.5f * px else 0f
                                            drawText(g, topLeft = start - half + Offset(tremble, sin(now * 2.2f + i * 0.55f) * 1.6f * px))
                                        } else if (f < 1f) {
                                            val m = rt.poses[b.eater].mouth(stage.radius[b.eater], stage.groundY, now)
                                            val boMouth = rt.poses[BO].mouth(stage.radius[BO], stage.groundY, now)
                                            val ctrl = eatControl(show.eatStyle, start, m, boMouth, b.eater == BO, size.height)
                                            val e = easeInOutCubic(f)
                                            drawGlyph(g, quadBezier(start, ctrl, m, e), 1f - 0.6f * e, sign(m.x - start.x) * 160f * e, 1f)
                                        }
                                    }
                                    labelPill(curRun, 1f - progress(lt, 0f, 0.4f))
                                }

                                Phase.Choke -> {}

                                Phase.Vomit -> {
                                    val show = sh
                                    val m = rt.poses[BO].mouth(stage.radius[BO], stage.groundY, now)
                                    val o = textOrigin(nextRun)
                                    val n = nextRun.glyphs.size
                                    nextRun.glyphs.forEachIndexed { i, g ->
                                        val target = Offset(o.x + nextRun.offsets[i] + g.size.width / 2f, o.y + g.size.height / 2f)
                                        if (!Vomit.glyph(show.style, stage, show, i, n, lt, target, m, rt.projected, glyphPose)) return@forEachIndexed
                                        drawGlyph(g, glyphPose.pos, glyphPose.scale, glyphPose.rotation, glyphPose.alpha)
                                    }
                                    labelPill(nextRun, progress(lt, Vomit.wordDone(show.style), 0.4f))
                                }

                                Phase.Settle, Phase.Wander -> {
                                    staticRun(nextRun, now) { 0f }
                                    labelPill(nextRun, 1f)
                                }
                            }
                        }

                        drawLensFlare(Offset(languages[cur].light.x * size.width, languages[cur].light.y * size.height), languages[cur].light.color, 1f - sceneMix)
                        if (sceneMix > 0f) drawLensFlare(Offset(languages[nxt].light.x * size.width, languages[nxt].light.y * size.height), languages[nxt].light.color, sceneMix)
                        drawRect(vignette)
                        if (arena3d != null) drawGlassFront(now, px) else drawRect(sheen)
                        // Their home: where each one stands (to burst out from), the broken glass, the door.
                        SlimeHome.cardRect?.let { card ->
                            for (k in 0 until SLIME_COUNT) {
                                SlimeHome.boxPos[k] = card.topLeft + rt.poses[k].base(stage.groundY)
                                SlimeHome.boxUnit[k] = stage.r * rt.poses[k].viewScale
                            }
                        }
                        SlimeHome.doorR = stage.r
                        drawHomeGlass(stage.r, px)
                        val grainShift = floor(now * 24f).toInt()
                        withTransform({ translate(-hash(grainShift) * 128f, -hash(grainShift + 31) * 128f) }) {
                            drawRect(grain, Offset.Zero, Size(size.width + 128f, size.height + 128f), alpha = 0.22f)
                        }
                    }
                }
        )

        Chip(version, Modifier.align(Alignment.TopStart).padding(12.dp))
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            mode?.let { Chip(it) }
            tags.forEach { Chip(it) }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(50))
                .border(0.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Runs [block] clipped to the part of the card [reveal] uncovers. [scale] and [offset] map card
 * pixels to the surface being painted (identity for the 2D card, the back wall for the 3D panorama).
 */
private inline fun DrawScope.revealed(reveal: Reveal, scale: Float, offset: (Offset) -> Offset, block: DrawScope.() -> Unit) {
    when (reveal.kind) {
        Reveal.Kind.None -> {}
        Reveal.Kind.All -> block()
        Reveal.Kind.Band -> {
            val c = offset(Offset(reveal.x, reveal.y)).x
            val half = reveal.size * scale
            clipRect(left = c - half, right = c + half) { block() }
        }

        Reveal.Kind.Disc -> {
            val c = offset(Offset(reveal.x, reveal.y))
            clipPath(Path().apply { addOval(Rect(c, reveal.size * scale)) }) { block() }
        }
    }
}

/** How far the whole box is rolled (degrees), when a scene tilts the ship. */
private fun ArenaRuntime.rollAt(now: Float): Float {
    val sh = show ?: return 0f
    return if (sh.phaseAt(now - sh.start) == Phase.Intro) ctx?.roll ?: 0f else 0f
}

/** How far the whole picture is jolted at this moment of the show, in card pixels. */
private fun ArenaRuntime.shakeAt(now: Float, s: Stage): Offset {
    val sh = show ?: return Offset.Zero
    val elapsed = now - sh.start
    val phase = sh.phaseAt(elapsed) ?: return Offset.Zero
    val p = sh.progressOf(phase, elapsed)
    val lt = sh.local(phase, elapsed)
    val r = s.r
    val amount = when (phase) {
        Phase.Intro -> ctx?.shake ?: 0f
        Phase.Brawl -> r * 0.045f
        Phase.Scatter -> r * 0.14f * (1f - progress(p, 0f, 0.35f))
        Phase.Choke -> if (lt > RAM_AT) r * 0.22f * exp(-(lt - RAM_AT) * 6f) else 0f
        Phase.Vomit -> r * Vomit.shake(sh.style, lt)
        else -> 0f
    }
    if (amount <= 0.1f) return Offset.Zero
    val frame = floor(now * 40f).toInt()
    return Offset((hash(frame) - 0.5f) * 2f * amount, (hash(frame + 999) - 0.5f) * 2f * amount)
}

/** Which country is showing, how much of the next one shows over it, and which one lights the room. */
private class SceneView(val cur: Int, val next: Int, val reveal: Reveal, val mix: Float)

private fun ArenaRuntime.sceneView(now: Float, s: Stage): SceneView {
    val sh = show ?: return SceneView(current, current, Reveal.NONE, 0f)
    val elapsed = now - sh.start
    return when (val phase = sh.phaseAt(elapsed)) {
        Phase.Vomit -> {
            val lt = sh.local(phase, elapsed)
            SceneView(sh.from, sh.next, Vomit.reveal(sh.style, s, sh, lt, projected), Vomit.mix(sh.style, lt))
        }

        Phase.Settle, Phase.Wander, null -> SceneView(sh.next, sh.next, Reveal.NONE, 1f)
        else -> SceneView(sh.from, sh.next, Reveal.NONE, 0f)
    }
}

/** Paints the country scenes into the 3D backdrop, rebuilding the scene shapes for its size. */
private class BackdropPainter(private val measurer: TextMeasurer) {
    private var w = -1f
    private var h = -1f
    private var scenes: List<SceneDrawer> = emptyList()
    private var decors: List<DecorDrawer> = emptyList()
    private var washes: List<Brush> = emptyList()

    fun paint(scope: DrawScope, v: SceneView, languages: List<ArenaLanguage>, t: Float, split: WallSplit, map: PanoramaMap?) = with(scope) {
        if (size.width != w || size.height != h) {
            w = size.width
            h = size.height
            val digit = TextStyle(color = Color(0xFF39FF88), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            val env = SceneEnv(size, this, measurer.measure("0", digit, density = this), measurer.measure("1", digit, density = this))
            scenes = languages.map { it.scene(env) }
            decors = languages.map { it.decor(env) }
            washes = languages.map { lang -> Brush.verticalGradient(lang.wash) }
        }
        fun one(i: Int) {
            drawRect(washes[i])
            scenes[i](this, t)
            decors[i](this, t, split)
            drawBokeh(t, 9000 + i * 50, 7, Color.White, density)
        }
        if (v.reveal.kind != Reveal.Kind.All || v.next == v.cur) one(v.cur)
        if (v.next != v.cur && map != null) revealed(v.reveal, map.scale, map::toPanorama) { one(v.next) }
        shadeWalls(split)
    }

    /**
     * Soft occlusion baked into the walls: the rounded back corners and the foot of the walls are
     * a touch darker, so the side walls read as walls rather than one flat picture.
     */
    private fun DrawScope.shadeWalls(split: WallSplit) {
        val shade = Color.Black
        for (side in 0..1) {
            val from = split.cornerFrom * size.width
            val to = split.cornerTo * size.width
            val (a, b) = if (side == 0) from to to else size.width - to to size.width - from
            val mid = (a + b) / 2f
            val reach = (b - a) * 1.6f
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, shade.copy(alpha = 0.16f), Color.Transparent), mid - reach, mid + reach),
                Offset(mid - reach, 0f),
                Size(reach * 2f, size.height),
            )
            // The side walls sit a little out of the key light.
            val (w0, w1) = if (side == 0) 0f to from else size.width - from to size.width
            drawRect(shade.copy(alpha = 0.07f), Offset(w0, 0f), Size(w1 - w0, size.height))
        }
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, shade.copy(alpha = 0.2f)), size.height * 0.82f, size.height),
            Offset(0f, size.height * 0.82f),
            Size(size.width, size.height * 0.18f),
        )
    }
}

/**
 * The diorama's front pane: a thick bevelled edge, two soft reflections drifting across, a faint
 * fingerprint and a few dust specks catching the light.
 */
private fun DrawScope.drawGlassFront(t: Float, px: Float) {
    val w = size.width
    val h = size.height
    // Bevel: bright outer lip, darker inner step, a glint along the top edge.
    drawRect(Color.White.copy(alpha = 0.55f), style = Stroke(2.2f * px))
    drawRect(Color.Black.copy(alpha = 0.18f), Offset(3f * px, 3f * px), Size(w - 6f * px, h - 6f * px), style = Stroke(2.5f * px))
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent), w * 0.1f, w * 0.6f),
        Offset(0f, 0f),
        Size(w, 2f * px),
    )
    // Reflections sliding slowly across the pane.
    repeat(2) { k ->
        val x = wrap(t * w * 0.025f + k * w * 0.55f, w * 1.6f) - w * 0.3f
        val band = if (k == 0) w * 0.09f else w * 0.035f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(x, 0f)
            lineTo(x + band, 0f)
            lineTo(x + band - h * 0.55f, h)
            lineTo(x - h * 0.55f, h)
            close()
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = if (k == 0) 0.1f else 0.16f), Color.Transparent),
                x - h * 0.55f,
                x + band,
            ),
        )
    }
    // A fingerprint smudge and dust.
    val print = Offset(w * 0.83f, h * 0.68f)
    repeat(7) { k ->
        drawOval(
            Color.White,
            print - Offset(px * (16f - k * 2f), px * (21f - k * 2.6f)),
            Size(px * (32f - k * 4f), px * (42f - k * 5.2f)),
            alpha = 0.035f,
            style = Stroke(px * 1.1f),
        )
    }
    repeat(16) { k ->
        val p = Offset(hash(k + 5100) * w, hash(k + 5200) * h)
        val twinkle = 0.25f + 0.35f * ((sin(t * 1.3f + k) + 1f) / 2f)
        drawCircle(Color.White, px * (0.6f + hash(k + 5300) * 0.9f), p, alpha = twinkle * 0.5f)
    }
}
