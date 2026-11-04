package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import cam.su.kernel.ui.slime.SlimeHome
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** Show state; plain fields because the frame clock already invalidates the drawing every frame. */
internal class ArenaRuntime(var current: Int) {
    var enterStart = -1f
    var lastShowEnd = 0f
    var show: Show? = null
    var cycle = 0
    val jumpStart = FloatArray(SLIME_COUNT) { -10f }
    val poses = Array(SLIME_COUNT) { k -> SlimePose().also { it.stretch = Cast[k].stretch } }
    val peek = SlimePose()
    val lights = Array(SLIME_COUNT) { SlimeLight() }
    val peekLight = SlimeLight()
    val geos = Array(SLIME_COUNT + 2) { SlimeGeo() }
    var stage: Stage? = null
    var runs: List<GlyphRun>? = null
    var ctx: IntroCtx? = null
    /** True when the 3D stage draws the slimes: overlay anchors then follow its perspective. */
    var projected = false

    /** Where they stand between rounds, and where they will stand after this one. */
    private var homesStage: Stage? = null
    private var currentHomes: Homes? = null
    var nextHomes: Homes? = null
        private set

    /** Seconds since they last settled down (for the between-round skits). */
    fun idleTime(now: Float) = now - max(lastShowEnd, enterStart)

    fun homes(s: Stage): Homes {
        if (homesStage !== s) {
            homesStage = s
            currentHomes = s.lineHomes()
            nextHomes = null
        }
        return currentHomes!!
    }

    fun startShow(now: Float, languages: List<ArenaLanguage>) {
        if (show != null) return
        val run = runs?.get(current) ?: return
        val s = stage ?: return
        // The scene change, the feast and the rescue each rotate at their own pace, so the mix
        // of the three keeps changing from round to round.
        val style = VomitStyle.entries[cycle % VomitStyle.entries.size]
        val eat = EatStyle.entries[(cycle * 3 + 1) % EatStyle.entries.size]
        val choke = ChokeStyle.entries[cycle % ChokeStyle.entries.size]
        val seed = (now * 1000f).toInt() + cycle * 7919
        nextHomes = s.nextHomes(homes(s).formation, Random(seed.toLong() * 31 + 7))
        show = Show(now, current, (current + 1) % languages.size, introFor(languages[current]), run, s, seed, style, eat, choke)
    }

    fun tick(now: Float, languages: List<ArenaLanguage>, haptic: HapticFeedback) {
        if (enterStart < 0f) {
            enterStart = now
            lastShowEnd = now - IDLE_SECONDS + FIRST_SHOW_DELAY
        }
        val s = show
        if (s == null) {
            if (now - lastShowEnd >= IDLE_SECONDS) startShow(now, languages)
            return
        }
        val e = now - s.start
        s.buzz(e, haptic)
        if (e >= s.total) {
            current = s.next
            show = null
            lastShowEnd = now
            cycle++
            nextHomes?.let { currentHomes = it }
            nextHomes = null
        }
    }

    fun updatePoses(now: Float, s: Stage, languages: List<ArenaLanguage>) {
        val sh = show
        val baseCostume = languages[sh?.from ?: current].costume
        val h = homes(s)
        for (k in 0 until SLIME_COUNT) {
            val p = poses[k]
            p.reset(h.x[k])
            p.depth = h.z[k]
            p.costume = baseCostume
            p.lookX = sign(s.center - h.x[k]) * 0.5f
            p.squash = 1f + 0.03f * sin(now * (2.6f + k * 0.37f) + k * 1.3f)
            val period = 3.4f + k * 0.37f
            val blinkT = wrap(now + k * 1.7f, period)
            p.blink = if (blinkT < 0.14f) sin(blinkT / 0.14f * PI_F) else 0f
            if (enterStart >= 0f && !SlimeHome.hasLeft) {
                val e = progress(now - enterStart, k * 0.14f, 0.9f)
                p.lift += (1f - easeOutBounce(e)) * s.h * 1.1f
                if (e > 0.6f && e < 1f) p.jiggle = (1f - e) / 0.4f
            }
        }
        if (sh == null) {
            val idleT = now - max(lastShowEnd, enterStart)
            if (!poses.idleSkit(h, s, idleT, now)) for (k in 0 until SLIME_COUNT) idleAct(poses[k], k, now, s, h)
        }
        for (k in 0 until SLIME_COUNT) {
            val p = poses[k]
            val j = (now - jumpStart[k]) / 0.75f
            if (j in 0f..1f) {
                p.lift += sin(PI_F * j) * s.r * 1.3f
                p.squash = 1f + 0.18f * sin(PI_F * j)
                p.rotation = -sign(s.center - h.x[k] + 0.01f) * 360f * easeInOutCubic(j)
                p.mood = Mood.Happy
                p.armL = 120f
                p.armR = 120f
                if (j > 0.85f) p.jiggle = (1f - j) / 0.15f
            }
        }
        if (sh != null) {
            val elapsed = now - sh.start
            val phase = sh.phaseAt(elapsed)
            if (phase != null) {
                val p = sh.progressOf(phase, elapsed)
                val lt = sh.local(phase, elapsed)
                when (phase) {
                    Phase.Intro -> ctx?.let { c ->
                        c.homes = h
                        c.projected = projected
                        c.t = lt
                        c.now = now
                        c.shake = 0f
                        c.roll = 0f
                        sh.intro.pose(c)
                    }

                    Phase.Brawl -> brawlPoses(sh, s, lt, now)
                    Phase.Scatter -> scatterPoses(sh, s, p, now)
                    Phase.Recover -> recoverPoses(sh, s, lt, now)
                    Phase.Gather -> gatherPoses(sh, s, lt, now)
                    Phase.Eat -> eatPoses(sh, s, lt, now)
                    Phase.Choke -> chokePoses(sh, s, lt, now)
                    Phase.Vomit -> vomitPoses(sh, s, lt, now, languages)
                    Phase.Settle -> settlePoses(sh, s, lt, now, languages)
                    Phase.Wander -> wanderPoses(sh, s, lt, now, languages)
                }
            }
        }
        // Whoever is out roaming the other pages is not in the box; whoever just came back in
        // through the door walks from it to their place.
        val homeNow = SlimeHome.clock()
        for (k in 0 until SLIME_COUNT) {
            val p = poses[k]
            if (!SlimeHome.inside[k]) {
                p.visible = false
                continue
            }
            val e = (homeNow - SlimeHome.cameIn[k]) / 1.1f
            if (e in 0f..1f) {
                // From just inside the door (where it stands on screen) to its place.
                val z = Box.GLASS - 1.0f
                val doorX = s.center + (s.w - s.r * SlimeHome.DOOR_IN - s.center) / s.viewScale(z)
                p.travel(k, doorX, z, h.x[k], h.z[k], e, s)
            }
        }
        for (p in poses) perspective(p, s)
    }

    /** The box's own show waits while they are out (or the glass is still mending). */
    fun hold(now: Float) {
        show = null
        lastShowEnd = now
        if (enterStart < 0f) enterStart = now
    }

    /** Points overlay anchors at where the 3D camera shows this slime. */
    fun perspective(p: SlimePose, s: Stage) {
        p.viewScale = if (projected) s.viewScale(p.depth) else 1f
        p.pivotX = s.center
        p.pivotY = s.eyeY
    }

    private fun brawlPoses(sh: Show, s: Stage, lt: Float, now: Float) {
        for (k in 0 until SLIME_COUNT) poses[k].visible = false
        // Chanh sneaks out, dusts off his hands, whistles... and is dragged back in.
        if (lt in 0.9f..1.75f) {
            val c = sh.cloud(s, lt)
            val edge = c.x + s.r * 1.6f
            val ch = poses[CHANH]
            ch.visible = true
            ch.depth = 0f
            ch.x = when {
                lt < 1.15f -> lerp(c.x + s.r * 0.8f, edge + s.radius[CHANH] * 1.6f, easeOutCubic(progress(lt, 0.9f, 0.25f)))
                lt < 1.6f -> edge + s.radius[CHANH] * 1.6f
                else -> lerp(edge + s.radius[CHANH] * 1.6f, c.x, easeInCubic(progress(lt, 1.6f, 0.15f)))
            }
            ch.squash = if (lt < 1.15f) 0.82f else 1f
            when {
                lt < 1.15f -> ch.lookX = 1f
                lt < 1.45f -> {
                    ch.mood = Mood.Happy
                    ch.armL = 95f + 35f * sin(now * 28f)
                    ch.armR = 95f - 35f * sin(now * 28f)
                    ch.whistle = 1f
                    ch.lookY = -1f
                }

                else -> {
                    ch.mood = Mood.Surprised
                    ch.lookX = -1f
                    ch.armL = 120f
                    ch.armR = 120f
                    if (lt > 1.6f) ch.rotation = -360f * progress(lt, 1.6f, 0.15f)
                }
            }
        }
    }

    /** Height of [k]'s body on the 2D stage, for stacking one slime on another. */
    private fun bodyTop(s: Stage, k: Int) = 1.72f * s.radius[k] * Cast[k].stretch * 0.92f

    private fun scatterPoses(sh: Show, s: Stage, p: Float, now: Float) {
        val from = sh.cloud(s, sh.durations[Phase.Brawl.ordinal])
        val fly = progress(p, 0f, 0.85f)
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            val landed = fly >= 1f
            x = lerp(from.x + (k - 1.5f) * s.r * 0.3f, sh.landX[k], easeOutCubic(fly))
            depth = lerp(0f, sh.landZ[k], easeOutCubic(fly))
            val endLift = when (sh.landing[k]) {
                Landing.Glass -> s.r * 1.3f
                Landing.Stack -> bodyTop(s, sh.stackBase)
                else -> 0f
            }
            lift = lerp(s.groundY - from.y, endLift, fly) + sin(PI_F * fly) * s.r * 1.9f
            mood = Mood.Dizzy
            dizzy = progress(p, 0.6f, 0.3f)
            armL = 130f
            armR = 130f
            rotation = when (sh.landing[k]) {
                Landing.Upside -> 540f * easeOutCubic(fly) * sign(sh.landX[k] - from.x + 0.01f) + if (landed) 0f else 0f
                Landing.Glass -> 0f
                else -> 360f * easeOutCubic(fly) * sign(sh.landX[k] - from.x + 0.01f)
            }
            if (sh.landing[k] == Landing.Glass && fly > 0.8f) {
                // Splat: pressed flat against the inside of the glass.
                flatZ = lerp(1f, 0.32f, progress(fly, 0.8f, 0.2f))
                mood = Mood.Squeeze
                armL = 150f
                armR = 150f
            }
            if (landed) {
                jiggle = 1f - progress(p, 0.85f, 0.15f)
                if (sh.landing[k] == Landing.Plop) {
                    squash = 0.75f
                    melt = 0.4f
                }
            }
        }
    }

    private fun recoverPoses(sh: Show, s: Stage, lt: Float, now: Float) {
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            x = sh.landX[k]
            depth = sh.landZ[k]
            when (sh.landing[k]) {
                Landing.Plop -> {
                    // Melted into a dazed puddle, oozes back up, shakes it off.
                    melt = when {
                        lt < 1.0f -> 0.75f
                        lt < 1.6f -> 0.75f * (1f - easeInOutCubic(progress(lt, 1.0f, 0.6f)))
                        else -> 0f
                    }
                    dizzy = 1f - progress(lt, 0.8f, 0.6f)
                    mood = if (lt < 1.4f) Mood.Dizzy else Mood.Normal
                    if (lt in 1.6f..2.4f) {
                        rotation = sin((lt - 1.6f) * 28f) * 12f * (1f - progress(lt, 1.6f, 0.8f))
                        jiggle = 1f - progress(lt, 1.6f, 0.8f)
                    }
                    if (lt > 2.4f) lookX = sin((lt - 2.4f) * 6f)
                }

                Landing.Glass -> {
                    // Stuck to the glass, slides down it, peels off and backs away.
                    val slide = progress(lt, 1.0f, 1.0f)
                    val peel = progress(lt, 2.0f, 0.45f)
                    lift = s.r * 1.3f * (1f - easeInCubic(slide))
                    flatZ = lerp(0.32f, 1f, easeOutBack(peel))
                    depth = lerp(GLASS_DEPTH, sh.recZ[k], easeOutCubic(peel))
                    mood = if (peel < 0.5f) Mood.Squeeze else Mood.Dizzy
                    dizzy = progress(lt, 2.2f, 0.3f) * (1f - progress(lt, 2.6f, 0.4f))
                    armL = if (peel < 1f) 150f - 60f * slide else 18f
                    armR = armL
                    if (peel in 0.01f..0.99f) jiggle = 1f
                }

                Landing.Stack -> {
                    val base = sh.stackBase
                    val off = progress(lt, 1.6f, 0.6f)
                    x = lerp(sh.landX[k], sh.recX[k], easeInOutCubic(off))
                    lift = bodyTop(s, base) * (1f - easeInCubic(off)) + sin(PI_F * off) * s.r * 0.5f
                    mood = if (off < 1f) Mood.Happy else Mood.Surprised
                    lookY = 0.6f
                    armL = 110f + 20f * sin(now * 6f)
                    armR = 110f - 20f * sin(now * 6f)
                    if (off >= 1f) jiggle = 1f - progress(lt, 2.2f, 0.4f)
                }

                Landing.Upside -> {
                    val flip = progress(lt, 1.5f, 0.5f)
                    rotation = if (flip <= 0f) 180f + sin(now * 14f) * 14f else lerp(180f, 360f, easeInOutCubic(flip))
                    lift = sin(PI_F * flip) * s.r * 0.8f
                    mood = if (flip < 1f) Mood.Surprised else Mood.Dizzy
                    sweat = 1f - flip
                    armL = 90f + 50f * sin(now * 20f)
                    armR = 90f - 50f * sin(now * 20f)
                    dizzy = progress(lt, 2.0f, 0.2f) * (1f - progress(lt, 2.4f, 0.5f))
                }
            }
        }
        // The one at the bottom of the stack is not amused.
        if (sh.stackBase >= 0) poses[sh.stackBase].apply {
            if (lt < 1.6f) {
                mood = Mood.Angry
                lookY = -1f
                steam = progress(lt, 0.3f, 0.3f)
                squash = 0.9f
            } else if (lt < 2.2f) {
                rotation = sin((lt - 1.6f) * 30f) * 10f
            }
        }
    }

    private fun gatherPoses(sh: Show, s: Stage, lt: Float, now: Float) {
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            val e = progress(lt, k * 0.12f, 1.45f)
            travel(k, sh.recX[k], sh.recZ[k], sh.spotX[k], sh.spotZ[k], e, s)
            if (e >= 1f) {
                mood = Mood.Happy
                lookY = -0.9f
            }
        }
    }

    private fun eatPoses(sh: Show, s: Stage, lt: Float, now: Float) {
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            x = sh.spotX[k]
            depth = sh.spotZ[k]
            mood = Mood.Happy
            lookY = -0.8f
            lookX = sign(s.center - sh.spotX[k]) * 0.4f
            var eaten = 0
            var last = -9f
            var next = Float.MAX_VALUE
            for (b in sh.bites) {
                if (b.eater != k) continue
                if (b.arrive <= lt) {
                    eaten += b.glyphs.size
                    last = b.arrive
                } else if (b.arrive < next) {
                    next = b.arrive
                }
            }
            val since = lt - last
            val toNext = next - lt
            mouthOpen = when {
                since < 0.08f -> 0.04f
                toNext < 0.28f -> 0.45f + 0.55f * (1f - toNext / 0.28f)
                eaten > 0 -> 0.1f + 0.1f * abs(sin(now * (if (k == CHANH) 22f else 12f)))
                else -> 0f
            }
            if (toNext < 0.35f) {
                lift += sin(PI_F * (1f - toNext / 0.35f)) * s.r * (if (k == BO) 0.15f else 0.35f)
                armL = 120f
                armR = 120f
            }
            if (since < 0.25f) {
                squash = 1f - (if (k == BO) 0.16f else 0.1f) * exp(-since * 14f)
                mood = Mood.Squeeze
            }
            scale = 1f + min(0.4f, eaten * (if (k == BO) 0.06f else 0.035f))
            if (k == SODA && toNext < 0.6f) {
                armR = 135f
                sparkle = 0.6f
            }
        }
        poses.eatStyled(sh, s, lt, now)
    }

    private fun chokePoses(sh: Show, s: Stage, lt: Float, now: Float) {
        val bo = poses[BO]
        bo.apply {
            x = sh.spotX[BO]
            depth = sh.spotZ[BO]
            chokeTint = Color(0xFF7FD8C8)
            scale = 1f + min(0.4f, sh.bites.filter { it.eater == BO }.sumOf { it.glyphs.size } * 0.06f)
            lookX = 0.6f
            if (lt < 0.3f) {
                squash = 1f + 0.14f * sin(PI_F * lt / 0.3f)
                mood = Mood.Surprised
            } else {
                choke = easeOutCubic(progress(lt, 0.15f, 0.3f))
                mood = Mood.Surprised
                shakeX = sin(now * 60f) * s.r * 0.06f * choke
                sweat = choke
                jiggle = 0.4f * choke
                armL = 140f + 20f * sin(now * 20f)
                armR = 140f - 20f * sin(now * 20f)
            }
            if (sh.chokeStyle == ChokeStyle.Domino) DOMINO.last().let { at ->
                if (lt > at && lt < at + 0.2f) squash = 1f + 0.12f * sin(PI_F * (lt - at) / 0.2f)
            }
        }
        if (sh.chokeStyle != ChokeStyle.Domino) {
            poses.chokeStyled(sh, s, lt, now)
            release(bo, s, lt, pushed = false)
            return
        }
        release(bo, s, lt, pushed = true)
        // Queue up behind Bơ, then knock into each other like dominoes.
        val queue = intArrayOf(MOCHI, SODA, CHANH)
        for ((i, k) in queue.withIndex()) poses[k].apply {
            val go = progress(lt, 0.3f + i * 0.08f, 0.45f)
            travel(k, sh.spotX[k], sh.spotZ[k], sh.queueX(s, k), sh.spotZ[BO], go, s)
            mood = Mood.Worried
            sweat = progress(lt, 0.2f, 0.3f)
            if (go >= 1f) lookX = -1f
            val hit = DOMINO[DOMINO.size - 1 - i]
            if (lt > hit - 0.08f && lt < hit + 0.25f) {
                rotation = -22f * sin(PI_F * progress(lt, hit - 0.08f, 0.33f))
                x -= s.r * 0.25f * sin(PI_F * progress(lt, hit - 0.08f, 0.33f))
                if (k == MOCHI) {
                    armL = 95f
                    reachL = 1.5f
                }
            }
        }
        // Mochi and Soda leap aside while Chanh takes a run-up from the back of the box and rams Bơ's belly.
        for (k in intArrayOf(MOCHI, SODA)) poses[k].apply {
            if (lt > 1.3f && lt < 2.0f) {
                lift += sin(PI_F * progress(lt, 1.35f, 0.6f)) * s.r * (if (k == MOCHI) 1.6f else 1.2f)
                mood = Mood.Surprised
                armL = 150f
                armR = 150f
            }
        }
        poses[CHANH].apply {
            val runFrom = s.w - s.radius[CHANH] * 1.4f
            val hitX = sh.spotX[BO] + (s.radius[BO] + s.radius[CHANH]) * 0.9f
            when {
                lt in 1.3f..1.55f -> {
                    x = lerp(sh.queueX(s, CHANH), runFrom, easeOutCubic(progress(lt, 1.3f, 0.25f)))
                    depth = lerp(sh.spotZ[BO], -4.2f, easeOutCubic(progress(lt, 1.3f, 0.25f)))
                }

                lt in 1.55f..RAM_AT -> {
                    x = lerp(runFrom, hitX, easeInCubic(progress(lt, 1.55f, RAM_AT - 1.55f)))
                    depth = lerp(-4.2f, sh.spotZ[BO] + 0.3f, easeInCubic(progress(lt, 1.55f, RAM_AT - 1.55f)))
                    mood = Mood.Angry
                    lookX = -1f
                    squash = 0.9f
                    rotation = -12f
                }

                lt > RAM_AT -> {
                    val b = progress(lt, RAM_AT, 0.45f)
                    x = lerp(hitX, sh.rammedX(s), easeOutCubic(b))
                    depth = sh.spotZ[BO]
                    lift = sin(PI_F * b) * s.r * 1f
                    rotation = 360f * easeOutCubic(b)
                    mood = Mood.Dizzy
                    dizzy = b
                }
            }
        }
    }

    /** The moment the stuck letter comes loose: Bơ bulges and his mouth flies open. */
    private fun release(bo: SlimePose, s: Stage, lt: Float, pushed: Boolean) {
        if (lt <= RAM_AT) return
        val b = progress(lt, RAM_AT, 0.5f)
        bo.squash = 1f + 0.25f * sin(PI_F * min(1f, b * 2f))
        if (pushed) bo.x -= s.r * 0.35f * sin(PI_F * min(1f, b * 2f))
        bo.mouthOpen = easeOutCubic(b)
        bo.mood = Mood.Squeeze
    }

    private fun vomitPoses(sh: Show, s: Stage, lt: Float, now: Float, languages: List<ArenaLanguage>) {
        val next = languages[sh.next].costume
        val style = sh.style
        val heave = lt < Vomit.HEAVE
        poses[BO].apply {
            x = sh.spotX[BO]
            depth = sh.spotZ[BO]
            choke = 1f - progress(lt, 0f, 0.4f)
            chokeTint = Color(0xFF7FD8C8)
            lookX = 1f
            scale = 1.3f
            if (heave) {
                // Cheeks puffed, shaking, mouth clamped shut.
                squash = 0.86f + 0.05f * sin(now * 40f)
                scale = 1.3f + 0.06f * progress(lt, 0f, Vomit.HEAVE)
                shakeX = sin(now * 50f) * s.r * 0.04f
                mood = Mood.Squeeze
            } else {
                mood = Mood.Happy
                when (style) {
                    VomitStyle.Suitcase -> {
                        val out = progress(lt, Vomit.HEAVE, 0.3f)
                        if (out < 1f) {
                            mouthOpen = 1f
                            squash = 1f + 0.18f * sin(PI_F * out)
                            rotation = -10f * sin(PI_F * out)
                            mood = Mood.Squeeze
                        }
                        scale = lerp(1.3f, 1f, easeOutBack(progress(lt, Vomit.HEAVE, 0.6f)))
                        lookY = 0.3f
                    }

                    VomitStyle.Portal -> {
                        val out = progress(lt, Vomit.HEAVE, 0.5f)
                        if (out < 1f) {
                            mouthOpen = 1f
                            squash = 1.12f + 0.05f * sin(now * 30f)
                            armL = 120f + 30f * sin(now * 22f)
                            armR = 120f - 30f * sin(now * 22f)
                            mood = Mood.Squeeze
                        }
                        scale = lerp(1.3f, 1f, easeOutBack(progress(lt, Vomit.HEAVE, 0.7f)))
                        lookY = -0.7f
                    }

                    VomitStyle.Bubble -> {
                        if (lt < 1.3f) {
                            // Blowing: lips pursed, cheeks pumping.
                            mouthOpen = 0.2f
                            mood = Mood.Normal
                            squash = 1f + 0.06f * sin(now * 7f)
                            lookX = 0.6f
                            lookY = -0.6f
                        } else {
                            scale = lerp(1.3f, 1f, easeOutBack(progress(lt, 1.3f, 0.5f)))
                            lookY = -1f
                            if (lt in Vomit.POP..Vomit.POP + 0.35f) mood = Mood.Surprised
                        }
                    }

                    VomitStyle.Splat -> {
                        val out = progress(lt, Vomit.HEAVE, 0.4f)
                        if (out < 1f) {
                            mouthOpen = 1f
                            squash = 1f + 0.15f * sin(PI_F * out)
                            mood = Mood.Squeeze
                        }
                        scale = lerp(1.3f, 1f, easeOutBack(progress(lt, Vomit.HEAVE, 0.6f)))
                        lookX = 0f
                        lookY = 0.1f
                    }
                }
            }
        }
        // Everyone else drifts back to their buffet places, then reacts.
        val on = projected
        for (k in intArrayOf(MOCHI, SODA, CHANH)) poses[k].apply {
            val (fx, fz) = sh.afterChoke(s, k)
            travel(k, fx, fz, sh.spotX[k], sh.spotZ[k], progress(lt, 0.1f + k * 0.06f, 1.0f), s)
            if (k == CHANH) {
                dizzy = 1f - progress(lt, 0f, 0.8f)
                if (dizzy > 0.3f) mood = Mood.Dizzy
            }
            if (lt < 1.1f) return@apply
            when (style) {
                VomitStyle.Suitcase -> {
                    lookX = sign(Vomit.caseBase(s, sh, on).x - x)
                    if (lt < Vomit.CASE_OPEN) {
                        mood = Mood.Surprised
                    } else {
                        mood = Mood.Happy
                        val cheer = sin(PI_F * progress(lt, Vomit.CASE_OPEN + k * 0.08f, 0.9f))
                        armL = 18f + 130f * cheer
                        armR = armL
                        lift += cheer * s.r * 0.2f
                        if (k == SODA) sparkle = cheer
                    }
                }

                VomitStyle.Portal -> {
                    val c = Vomit.portalCenter(s)
                    val side = sign(c.x - x)
                    lookX = side
                    lookY = -0.7f
                    if (lt < 2.0f) {
                        // The pull of the portal.
                        mood = Mood.Worried
                        sweat = 1f
                        rotation = side * (6f + 3f * sin(now * 16f + k))
                        if (k == CHANH) hatTilt = 18f * sin(now * 14f)
                    } else if (lt < 2.6f) {
                        mood = Mood.Surprised
                        jiggle = 1f
                        squash = 1f - 0.1f * sin(now * 30f)
                    } else {
                        mood = Mood.Happy
                    }
                }

                VomitStyle.Bubble -> {
                    lookY = -1f
                    lookX = sign(Vomit.bubblePokeX(s) - x) * 0.6f
                    mood = if (lt in Vomit.POP..Vomit.POP + 0.35f) Mood.Surprised else Mood.Happy
                    if (k == SODA && lt < Vomit.POP) sparkle = 0.7f
                    if (k == CHANH) {
                        // Chanh can't resist: jumps up and pokes it.
                        val up = progress(lt, Vomit.POP - 0.45f, 0.45f)
                        val down = progress(lt, Vomit.POP, 0.5f)
                        if (up > 0f) {
                            x = lerp(sh.spotX[CHANH], Vomit.bubblePokeX(s), easeOutCubic(up) * (1f - easeInOutCubic(down)))
                            lift += sin(PI_F * (up * 0.5f + down * 0.5f)) * s.r * 1.1f
                            armR = 165f
                            reachR = 1.4f
                            mood = if (down > 0f) Mood.Happy else Mood.Angry
                            if (down >= 1f) squash = 1f - 0.2f * (1f - progress(lt, Vomit.POP + 0.5f, 0.2f))
                        }
                    }
                }

                VomitStyle.Splat -> {
                    val swept = Vomit.swept(lt)
                    if (lt < 1.35f) {
                        mood = Mood.Surprised
                        lookX = 0f
                        lookY = 0f
                        sweat = 1f
                    } else if (swept < PI_F * 0.99f) {
                        // Eyes following the blades.
                        lookX = -cos(Vomit.wiperAngle(lt))
                        lookY = -0.4f
                        mood = Mood.Surprised
                    } else {
                        mood = Mood.Happy
                        val cheer = sin(PI_F * progress(lt, 2.4f + k * 0.08f, 0.8f))
                        armL = 18f + 130f * cheer
                        armR = armL
                    }
                }
            }
        }
        val drop = 0.65f
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            if (sh.dropAt[k] < 0f && Vomit.dropDue(style, s, sh, k, x, lt, on)) sh.dropAt[k] = now
            val at = sh.dropAt[k]
            if (at >= 0f) {
                val e = progress(now - at, 0f, drop)
                costume = next
                hatLift = (1f - easeOutBounce(e)) * s.h * 0.9f
                if (e < 0.55f) {
                    outfit = languages[sh.from].costume
                    lookY = -1f
                } else {
                    poof = (now - at - drop * 0.55f) / 0.5f
                    if (k != BO || !heave) mood = Mood.Happy
                }
                if (k == CHANH && e >= 1f) {
                    // Chanh's always lands crooked; a quick spin puts it straight.
                    val fix = progress(now - at - drop, 0.35f, 0.4f)
                    hatTilt = 28f * (1f - fix)
                    if (fix > 0f && fix < 1f) rotation = 360f * easeInOutCubic(fix)
                }
            } else {
                costume = languages[sh.from].costume
            }
        }
    }

    private fun settlePoses(sh: Show, s: Stage, lt: Float, now: Float, languages: List<ArenaLanguage>) {
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            x = sh.spotX[k]
            depth = sh.spotZ[k]
            costume = languages[sh.next].costume
            val hop = progress(lt, 0.1f + k * 0.12f, 0.4f)
            lift = sin(PI_F * hop) * s.r * 0.45f
            mood = Mood.Happy
            armL = lerp(18f, 150f, sin(PI_F * progress(lt, 0.05f + k * 0.1f, 0.9f)))
            armR = armL
            if (k == SODA) sparkle = 1f - progress(lt, 0.8f, 0.6f)
        }
        poses[BO].lookX = 1f
        poses[CHANH].lookX = -1f
    }

    private fun wanderPoses(sh: Show, s: Stage, lt: Float, now: Float, languages: List<ArenaLanguage>) {
        for (k in 0 until SLIME_COUNT) poses[k].apply {
            costume = languages[sh.next].costume
            val e = progress(lt, k * 0.25f, 1.7f)
            val to = nextHomes ?: homes(s)
            travel(k, sh.spotX[k], sh.spotZ[k], to.x[k], to.z[k], e, s)
            if (e >= 1f) lookX = sign(s.center - to.x[k]) * 0.5f
        }
    }

    /** Small things the slimes do on their own between rounds. */
    private fun idleAct(p: SlimePose, k: Int, now: Float, s: Stage, h: Homes) {
        val window = floor(now / 2.6f).toInt()
        val actor = (hash(window * 7 + 1) * SLIME_COUNT).toInt().coerceIn(0, SLIME_COUNT - 1)
        if (actor != k) return
        val e = now - window * 2.6f - 0.6f
        if (e !in 0f..1f) return
        val bell = sin(PI_F * e)
        when ((hash(window * 7 + 2) * 7).toInt()) {
            0 -> {
                p.lift += bell * s.r * 0.45f
                p.squash = 1f + 0.15f * bell
                p.mood = Mood.Happy
                p.armL = 18f + 120f * bell
                p.armR = p.armL
            }

            1 -> {
                p.lookX = if (k == Lineup.first() || k == MOCHI) 1f else -1f
                p.lookY = 0f
            }

            2 -> {
                p.mouthOpen = bell * 0.8f
                p.blink = bell
                p.squash = 1f + 0.08f * bell
                p.armL = 18f + 140f * bell
                p.armR = p.armL
            }

            3 -> {
                p.rotation = sin(e * PI_F * 4f) * 8f * (1f - e)
                p.jiggle = 1f - e
            }

            4 -> when (k) {
                SODA -> {
                    p.armR = 18f + 130f * bell
                    p.sparkle = bell
                    p.rotation = 8f * bell
                }

                CHANH -> {
                    p.whistle = bell
                    p.lookY = -bell
                }

                MOCHI -> {
                    p.cross = bell
                    p.lookX = -1f
                }

                else -> {
                    p.sleep = bell
                    p.snore = 0.5f * bell
                    p.melt = 0.35f * bell
                }
            }

            5 -> {
                // A lazy melt and ooze back up.
                p.melt = bell * 0.6f
                p.mood = Mood.Happy
            }

            else -> {
                p.lookY = -bell
                p.lookX = sign(s.center - h.x[k]) * 0.6f
            }
        }
    }
}
