package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextLayoutResult
import cam.su.kernel.ui.slime.SlimeHome
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Beats of one round: the scene's own intro, then the shared signature sequence. */
internal enum class Phase { Intro, Brawl, Scatter, Recover, Gather, Eat, Choke, Vomit, Settle, Wander }

/** How a slime comes down when the brawl cloud bursts. */
internal enum class Landing { Plop, Glass, Stack, Upside }

/** Each cast member's own way of getting about. */
internal enum class Gait { Hop, Slide, Glide, Spring }

/** Mochi hops evenly, Bơ inches along like a caterpillar, Soda glides in a twirl, Chanh boings. */
private val Gaits = arrayOf(Gait.Hop, Gait.Slide, Gait.Glide, Gait.Spring)

internal const val FALL_SECONDS = 0.3f
private const val IDLE_SECONDS = 6f
private const val FIRST_SHOW_DELAY = 2.2f
/** Choke beats (seconds into the phase). */
private val DOMINO = floatArrayOf(0.95f, 1.07f, 1.19f)
internal const val RAM_AT = 1.9f
/** Depth of a slime splatted on the inside of the glass. */
private const val GLASS_DEPTH = Box.GLASS - 0.55f

/** One mouthful in the buffet: who eats which letters, and when they leave the word. */
internal class Bite(val eater: Int, val glyphs: IntArray, val launch: Float) {
    val arrive get() = launch + FALL_SECONDS
}

/** Haptic kinds, in the order [Vomit.buzz] numbers them. */
private enum class Buzz { Tap, Thump, Confirm }

/** One round, fixed when it starts (including where everyone lands when the brawl bursts). */
internal class Show(
    val start: Float,
    val from: Int,
    val next: Int,
    val intro: SceneIntro,
    run: GlyphRun,
    s: Stage,
    seed: Int,
    /** How Bơ throws the next country up this round. */
    val style: VomitStyle,
    /** How they line up for the letters, and how they get Bơ to cough up the one he chokes on. */
    val eatStyle: EatStyle,
    val chokeStyle: ChokeStyle,
) {
    val bites: List<Bite>
    /** Letters that stick in Bơ's throat (his last, too-big mouthful). */
    val chokeOn: IntArray
    val durations: FloatArray
    val starts: FloatArray
    val total: Float
    /** When each slime's new costume started falling (-1 until the carpet reaches it). */
    val dropAt = FloatArray(SLIME_COUNT) { -1f }

    val landing = Array(SLIME_COUNT) { Landing.Plop }
    val landX = FloatArray(SLIME_COUNT)
    val landZ = FloatArray(SLIME_COUNT)
    /** Who a [Landing.Stack] slime landed on. */
    var stackBase = -1
    /** Where each one is once it has picked itself up. */
    val recX = FloatArray(SLIME_COUNT)
    val recZ = FloatArray(SLIME_COUNT)
    /** Places at the "buffet" under the word, Bơ leftmost so the back-slap queue forms to his right. */
    val spotX = FloatArray(SLIME_COUNT)
    val spotZ = FloatArray(SLIME_COUNT) { 0.15f }

    private val events: List<Pair<Float, Buzz>>
    private var fired = 0

    init {
        val list = run.eatList
        val chunk = min(3, max(1, list.size / 4))
        chokeOn = list.copyOfRange(list.size - chunk, list.size)
        val rest = list.copyOfRange(0, list.size - chunk)
        val order = intArrayOf(MOCHI, CHANH, SODA, BO)
        val out = ArrayList<Bite>()
        var i = 0
        var turn = 0
        var time = 0.75f
        while (i < rest.size) {
            val eater = order[turn % order.size]
            val take = if (eater == BO) min(2, rest.size - i) else 1
            out += Bite(eater, rest.copyOfRange(i, i + take), time)
            time += 0.28f
            i += take
            turn++
        }
        out += Bite(BO, chokeOn, time + 0.15f)
        bites = out
        durations = floatArrayOf(intro.seconds, 2.4f, 1.0f, 3.0f, 1.9f, out.last().arrive + 0.4f, 2.4f, style.seconds, 1.5f, 2.6f)
        starts = FloatArray(durations.size)
        var acc = 0f
        for (k in durations.indices) {
            starts[k] = acc
            acc += durations[k]
        }
        total = acc

        val rnd = Random(seed.toLong())
        planLandings(s, rnd)
        seatDiners(eatStyle, s, rnd, spotX, spotZ)

        val ev = ArrayList<Pair<Float, Buzz>>()
        intro.taps.forEach { ev += it to Buzz.Tap }
        intro.thumps.forEach { ev += it to Buzz.Thump }
        val brawl = starts[Phase.Brawl.ordinal]
        for (k in 0 until 10) ev += brawl + k * 0.22f to Buzz.Tap
        ev += starts[Phase.Scatter.ordinal] to Buzz.Thump
        ev += starts[Phase.Recover.ordinal] to Buzz.Thump
        bites.forEach { ev += starts[Phase.Eat.ordinal] + it.arrive to Buzz.Tap }
        chokeTaps(chokeStyle).forEach { ev += starts[Phase.Choke.ordinal] + it to Buzz.Tap }
        ev += starts[Phase.Choke.ordinal] + RAM_AT to Buzz.Thump
        for ((at, kind) in Vomit.buzz(style)) ev += starts[Phase.Vomit.ordinal] + at to Buzz.entries[kind]
        events = ev.sortedBy { it.first }
    }

    /** Random landing spots and stunts, kept apart so nobody lands inside somebody else. */
    private fun planLandings(s: Stage, rnd: Random) {
        val ids = (0 until SLIME_COUNT).shuffled(rnd)
        if (rnd.nextFloat() < 0.85f) landing[ids[0]] = Landing.Glass
        val base = if (landing[BO] != Landing.Glass) BO else ids.first { it != BO && landing[it] != Landing.Glass }
        val stacker = listOf(CHANH, MOCHI, SODA).firstOrNull { it != base && landing[it] == Landing.Plop && Cast[it].size < Cast[base].size }
        if (stacker != null && rnd.nextFloat() < 0.7f) {
            landing[stacker] = Landing.Stack
            stackBase = base
        }
        ids.firstOrNull { landing[it] == Landing.Plop && it != base && rnd.nextFloat() < 0.65f }?.let { landing[it] = Landing.Upside }

        val placed = ArrayList<Int>()
        fun free(k: Int, x: Float, z: Float) = placed.all { j ->
            abs(x - landX[j]) > (s.radius[k] + s.radius[j]) * 1.15f || abs(z - landZ[j]) > 1.4f
        }
        val order = (0 until SLIME_COUNT).sortedBy { if (landing[it] == Landing.Stack) 1 else 0 }
        for (k in order) {
            when (landing[k]) {
                Landing.Stack -> {
                    landX[k] = landX[stackBase]
                    landZ[k] = landZ[stackBase] + 0.02f
                }

                Landing.Glass -> {
                    landX[k] = s.w * (0.18f + 0.64f * rnd.nextFloat())
                    landZ[k] = GLASS_DEPTH
                }

                else -> {
                    var tries = 0
                    do {
                        landX[k] = s.w * (0.1f + 0.8f * rnd.nextFloat())
                        landZ[k] = -3.2f + 4.0f * rnd.nextFloat()
                        tries++
                    } while (!free(k, landX[k], landZ[k]) && tries < 24)
                }
            }
            placed += k
        }
        for (k in 0 until SLIME_COUNT) {
            recX[k] = landX[k]
            recZ[k] = landZ[k]
            when (landing[k]) {
                Landing.Glass -> recZ[k] = Box.GLASS - 1.6f
                Landing.Stack -> {
                    val side = if (landX[stackBase] < s.center) 1f else -1f
                    recX[k] = landX[stackBase] + side * (s.radius[stackBase] + s.radius[k]) * 1.05f
                }

                else -> {}
            }
        }
    }

    fun phaseAt(elapsed: Float): Phase? {
        if (elapsed >= total) return null
        for (k in durations.indices.reversed()) if (elapsed >= starts[k]) return Phase.entries[k]
        return Phase.Intro
    }

    fun progressOf(phase: Phase, elapsed: Float) = ((elapsed - starts[phase.ordinal]) / durations[phase.ordinal]).coerceIn(0f, 1f)
    fun local(phase: Phase, elapsed: Float) = elapsed - starts[phase.ordinal]

    fun buzz(elapsed: Float, haptic: HapticFeedback) {
        while (fired < events.size && events[fired].first <= elapsed) {
            haptic.performHapticFeedback(
                when (events[fired].second) {
                    Buzz.Tap -> HapticFeedbackType.SegmentTick
                    Buzz.Thump -> HapticFeedbackType.LongPress
                    Buzz.Confirm -> HapticFeedbackType.Confirm
                }
            )
            fired++
        }
    }

    /** Where the rolling brawl cloud is, [lt] seconds into the brawl. */
    fun cloud(s: Stage, lt: Float) = Offset(
        s.center + sin(lt * 2.1f) * s.w * 0.14f,
        s.groundY - s.r * 1.15f - abs(sin(lt * 7f)) * s.r * 0.12f,
    )

    /** Where Bơ's teammates queue up behind him (to his right) to slap his back. */
    fun queueX(s: Stage, k: Int): Float {
        var x = spotX[BO]
        var prev = BO
        for (q in intArrayOf(MOCHI, SODA, CHANH)) {
            x += (s.radius[prev] + s.radius[q]) * 0.92f
            if (q == k) return x
            prev = q
        }
        return x
    }

    /** Where Chanh ends up after bouncing off Bơ's belly. */
    fun rammedX(s: Stage) = spotX[BO] + (s.radius[BO] + s.radius[CHANH]) * 0.9f + s.r * 2f
}

/**
 * Moves a slime from (fromX, fromZ) to (toX, toZ) over [e] (0..1) in its own [Gait]: hops, an
 * inchworm slide, a twirling glide or springy bounces, with squash, stretch and facing to match.
 */
internal fun SlimePose.travel(k: Int, fromX: Float, fromZ: Float, toX: Float, toZ: Float, e: Float, s: Stage) {
    val t = e.coerceIn(0f, 1f)
    val dist = hypot(toX - fromX, (toZ - fromZ) * s.r)
    val dir = sign(toX - fromX + 0.001f)
    fun place(f: Float) {
        x = lerp(fromX, toX, f)
        depth = lerp(fromZ, toZ, f)
    }
    if (t <= 0f) {
        place(0f)
        return
    }
    if (t >= 1f) {
        place(1f)
        return
    }
    lookX = dir
    when (Gaits[k]) {
        Gait.Hop -> {
            val hops = max(1, (dist / (s.r * 1.4f)).roundToInt())
            val ph = t * hops
            val n = floor(ph)
            val local = ph - n
            place((n + easeInOutCubic(local)) / hops)
            lift = sin(PI_F * local) * s.r * 0.45f
            squash = 1f + 0.18f * sin(PI_F * local) - if (local < 0.1f || local > 0.9f) 0.14f else 0f
        }

        Gait.Slide -> {
            val waves = max(2, (dist / (s.r * 0.9f)).roundToInt())
            place(easeInOutCubic(t))
            squash = 1f + 0.13f * sin(t * waves * 2f * PI_F)
            rotation = dir * 5f
            jiggle = 0.3f
            mood = Mood.Normal
        }

        Gait.Glide -> {
            place(easeInOutCubic(t))
            lift = sin(PI_F * t) * s.r * 0.35f
            rotation = dir * 360f * easeInOutCubic(t)
            sparkle = sin(PI_F * t)
            armL = 100f
            armR = 100f
            mood = Mood.Happy
        }

        Gait.Spring -> {
            val hops = max(1, (dist / (s.r * 2.2f)).roundToInt())
            val ph = t * hops
            val n = floor(ph)
            val local = ph - n
            place((n + local) / hops)
            lift = sin(PI_F * local) * s.r * 1.0f
            squash = when {
                local < 0.12f -> 0.72f + local / 0.12f * 0.5f
                local > 0.88f -> 1.22f - (local - 0.88f) / 0.12f * 0.5f
                else -> 1.22f
            }
            mood = Mood.Happy
        }
    }
}

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

/** The shared middle of every round, drawn over the slimes. */
internal fun DrawScope.drawSignatureEffects(
    rt: ArenaRuntime,
    sh: Show,
    phase: Phase,
    p: Float,
    lt: Float,
    now: Float,
    stage: Stage,
    languages: List<ArenaLanguage>,
    words: List<TextLayoutResult>,
    say: (String) -> TextLayoutResult,
    refract: DrawScope.(SlimeGeo) -> Unit,
    /** Draws a country's 2D scene full size, for the miniature inside the bubble. */
    miniScene: DrawScope.(Int) -> Unit,
    flatSlimes: Boolean = true,
) {
    val r = stage.r
    val px = stage.px
    fun head(k: Int) = rt.poses[k].headTop(stage.radius[k], stage.groundY, now)
    fun mouth(k: Int) = rt.poses[k].mouth(stage.radius[k], stage.groundY, now)
    fun feet(k: Int) = rt.poses[k].base(stage.groundY)
    fun light(lang: ArenaLanguage, out: SlimeLight) {
        out.pos = Offset(lang.light.x * size.width, lang.light.y * size.height)
        out.color = lang.light.color
        out.ambient = lang.light.ambient
    }
    drawGlassSmudges(sh, phase, lt, stage)
    when (phase) {
        Phase.Intro -> {}

        Phase.Brawl -> {
            val c = sh.cloud(stage, lt)
            // Faces, hands and hats poking out of the cloud.
            val slot = floor(lt * 8f).toInt()
            val e = lt * 8f - slot
            val pop = easeOutBack(min(1f, e * 3f), 2f) * (1f - progress(e, 0.7f, 0.3f))
            repeat(2) { j ->
                val id = slot * 2 + j
                val k = (hash(id * 13 + 5) * SLIME_COUNT).toInt().coerceIn(0, SLIME_COUNT - 1)
                val a = -PI_F / 2 + (hash(id * 13 + 6) - 0.5f) * PI_F * 1.6f
                val at = c + Offset(cos(a) * r * 1.55f, sin(a) * r * 1.0f)
                when ((hash(id * 13 + 7) * 3).toInt()) {
                    0 -> if (flatSlimes) {
                        rt.peek.apply {
                            reset(at.x)
                            stretch = Cast[k].stretch
                            lift = stage.groundY - at.y - r * 0.6f
                            scale = 0.7f * pop
                            rotation = Math.toDegrees((a + PI_F / 2).toDouble()).toFloat()
                            mood = if (slot % 2 == 0) Mood.Angry else Mood.Dizzy
                            costume = languages[sh.from].costume
                            visible = pop > 0.02f
                        }
                        light(languages[sh.from], rt.peekLight)
                        drawSlime(rt.peek, Cast[k], stage.radius[k], stage.groundY, now, rt.geos[SLIME_COUNT], 777, rt.peekLight, refract)
                    }

                    1 -> {
                        val tip = at + Offset(cos(a), sin(a)) * (r * 0.7f * pop)
                        drawLine(Cast[k].deep, at, tip, r * 0.26f, StrokeCap.Round, alpha = 0.7f)
                        drawLine(Cast[k].body, at, tip, r * 0.2f, StrokeCap.Round)
                        drawCircle(Cast[k].body, r * 0.14f, tip)
                        drawCircle(Color.White, r * 0.04f, tip + Offset(-r * 0.03f, -r * 0.04f), alpha = 0.8f)
                    }

                    else -> if (j == 0 && slot % 2 == 0) {
                        drawLostProp(incomingProp(sh.next), at, r * 0.45f * pop, now, sin(now * 10f))
                    } else {
                        drawCartoonStar(at, r * 0.2f * pop, e * 200f, 1f - e)
                    }
                }
            }
            // In the Seoul practice room the mirrors multiply the scrap into six.
            if (sh.from == 3) for (m in 1..5) {
                val off = Offset((m - 3f) * r * 4.2f, -r * 0.2f * (m % 2))
                if (m != 3) drawBrawlCloud(c + off, r * 0.7f, now + m, 1f, 0f)
            }
            drawBrawlCloud(c, r, now, 1f, 0f)
            drawCloudFlavor(sh.from, c, r, now, 1f)
            val wslot = floor(lt * 3.4f).toInt()
            val we = lt * 3.4f - wslot
            val word = words[(hash(wslot + 4300) * words.size).toInt().coerceIn(0, words.size - 1)]
            val wa = (hash(wslot + 4310) - 0.5f) * PI_F
            drawComicWord(word, c + Offset(sin(wa) * r * 1.9f, -r * (1.2f + 0.4f * hash(wslot + 4320))), we, (hash(wslot + 4330) - 0.5f) * 30f, 4f * px)
            // Chanh's escape attempt is drawn in front of the cloud, with the hand that drags him back.
            if (lt in 0.9f..1.75f) {
                val ch = rt.poses[CHANH]
                light(languages[sh.from], rt.lights[CHANH])
                if (flatSlimes) drawSlime(ch, Cast[CHANH], stage.radius[CHANH], stage.groundY, now, rt.geos[CHANH], 300 + CHANH * 37, rt.lights[CHANH], refract)
                if (lt in 1.4f..1.65f) {
                    val from = c + Offset(r * 1.1f, 0f)
                    val grab = lerp(from, rt.poses[CHANH].hand(-1, stage.radius[CHANH], stage.groundY, now), sin(PI_F * progress(lt, 1.4f, 0.25f)))
                    drawLine(Cast[BO].deep, from, grab, r * 0.26f, StrokeCap.Round, alpha = 0.6f)
                    drawLine(Cast[BO].body, from, grab, r * 0.2f, StrokeCap.Round)
                    drawCircle(Cast[BO].body, r * 0.15f, grab)
                }
            }
        }

        Phase.Scatter -> {
            val c = sh.cloud(stage, sh.durations[Phase.Brawl.ordinal])
            drawBrawlCloud(c, r, now, 1f, progress(p, 0f, 0.35f))
            drawImpact(c, progress(p, 0f, 0.6f), r * 1.3f)
            val land = progress(p, 0.8f, 0.3f)
            if (land > 0f) for (k in 0 until SLIME_COUNT) {
                when (sh.landing[k]) {
                    Landing.Glass -> drawImpact(feet(k) + Offset(0f, -stage.radius[k] * 0.9f * rt.poses[k].viewScale), land, r * 0.8f)
                    Landing.Stack -> drawCartoonStar(head(sh.stackBase), r * 0.25f * (1f - land), land * 300f, 1f - land)
                    else -> drawDust(feet(k), land, stage.radius[k] * rt.poses[k].viewScale)
                }
            }
        }

        Phase.Recover -> {
            for (k in 0 until SLIME_COUNT) {
                val pose = rt.poses[k]
                when (sh.landing[k]) {
                    // Little stars circling the dazed ones.
                    Landing.Plop -> if (lt < 1.4f) drawDizzyStars(head(k), stage.radius[k] * pose.viewScale, now, 1f - progress(lt, 1.0f, 0.4f))
                    Landing.Glass -> if (lt in 2.0f..2.5f) drawDust(feet(k), progress(lt, 2.0f, 0.5f), stage.radius[k] * pose.viewScale * 0.7f)
                    Landing.Stack -> if (lt in 2.2f..2.6f) drawDust(feet(k), progress(lt, 2.2f, 0.4f), stage.radius[k] * pose.viewScale)
                    Landing.Upside -> if (lt in 2.0f..2.4f) drawDust(feet(k), progress(lt, 2.0f, 0.4f), stage.radius[k] * pose.viewScale)
                }
            }
            if (sh.stackBase >= 0 && lt in 0.2f..1.6f) {
                drawExclaim(head(sh.stackBase) + Offset(r * 0.7f, -r * 0.1f), r * 0.8f, now, progress(lt, 0.2f, 0.15f) * (1f - progress(lt, 1.4f, 0.2f)))
            }
        }

        Phase.Gather -> {
            // A puff under every hop and boing as they make their way to the buffet.
            for (k in 0 until SLIME_COUNT) {
                val pose = rt.poses[k]
                if (pose.lift < stage.r * 0.03f && pose.squash < 0.95f) drawDust(feet(k), 0.4f, stage.radius[k] * pose.viewScale * 0.6f)
            }
        }

        Phase.Eat -> {
            drawEatStyled(sh, rt.poses, stage, lt, now, say)
            // Crumbs at every bite, and Soda's fork.
            for (b in sh.bites) {
                val since = lt - b.arrive
                if (since in 0f..0.3f) {
                    val e = since / 0.3f
                    val m = mouth(b.eater)
                    repeat(3) { j ->
                        val a = -PI_F / 2 + (j - 1) * 0.9f + hash(b.glyphs[0] * 7 + j)
                        drawCircle(Color.White, r * 0.04f * (1f - e), m + Offset(cos(a), sin(a)) * (r * 0.5f * e), alpha = 1f - e)
                    }
                }
            }
            val soda = rt.poses[SODA]
            if (soda.armR > 100f) drawFork(soda.hand(1, stage.radius[SODA], stage.groundY, now), r * 0.35f * soda.viewScale)
        }

        Phase.Choke -> {
            drawExclaim(head(BO) + Offset(-r * 0.6f, -r * 0.2f), r, now, progress(lt, 0.3f, 0.1f) * (1f - progress(lt, RAM_AT, 0.1f)))
            if (sh.chokeStyle != ChokeStyle.Domino) {
                drawChokeStyled(sh, rt.poses, stage, lt, now, say, words)
                if (lt > RAM_AT) drawImpact(mouth(BO), progress(lt, RAM_AT, 0.4f), r * 0.7f)
                return
            }
            for (i in DOMINO.indices) {
                val at = DOMINO[i]
                if (lt > at && lt < at + 0.3f) {
                    val k = intArrayOf(BO, MOCHI, SODA)[DOMINO.size - 1 - i]
                    val c = feet(k) + Offset(stage.radius[k] * 0.95f, -stage.radius[k] * 0.9f) * rt.poses[k].viewScale
                    drawImpact(c, progress(lt, at, 0.3f), r * 0.4f)
                }
            }
            if (lt in 1.55f..RAM_AT) {
                val ch = feet(CHANH)
                val vs = rt.poses[CHANH].viewScale
                repeat(4) { j ->
                    val y = ch.y - stage.radius[CHANH] * vs * (0.4f + j * 0.35f)
                    drawLine(Color.White, Offset(ch.x + r * vs * 0.6f, y), Offset(ch.x + r * vs * (1.4f + hash(j) * 0.8f), y), r * 0.04f, StrokeCap.Round, alpha = 0.7f)
                }
                drawCircle(Color.White, r * vs * 0.2f, Offset(ch.x + r * vs * 0.9f, ch.y - r * vs * 0.15f), alpha = 0.5f)
            }
            if (lt > RAM_AT) {
                val bo = rt.poses[BO]
                val contact = feet(BO) + Offset(stage.radius[BO] * 0.95f, -stage.radius[BO] * 0.6f) * bo.viewScale
                drawImpact(contact, progress(lt, RAM_AT, 0.4f), r * 0.9f)
            }
        }

        Phase.Vomit -> with(Vomit) { draw(rt, sh, lt, now, stage, languages, words[0], miniScene) }

        Phase.Settle -> {
            val a = head(BO)
            val b = head(CHANH)
            val e = progress(p, 0.15f, 0.7f)
            drawHeart(lerp(a, b, 0.5f) + Offset(0f, -r * (0.2f + 0.6f * e)), r * 0.25f * easeOutBack(min(1f, e * 3f), 2.5f), 1f - progress(e, 0.75f, 0.25f))
        }

        Phase.Wander -> {
            for (k in 0 until SLIME_COUNT) {
                val pose = rt.poses[k]
                if (pose.lift < stage.r * 0.03f && pose.squash < 0.95f) drawDust(feet(k), 0.4f, stage.radius[k] * pose.viewScale * 0.6f)
            }
        }
    }
}

/**
 * Where a slime splatted against the glass: a jelly print on the inside of the pane that smears
 * down as it slides, then slowly dries off over the rest of the round.
 */
private fun DrawScope.drawGlassSmudges(sh: Show, phase: Phase, lt: Float, stage: Stage) {
    if (phase.ordinal < Phase.Scatter.ordinal) return
    val elapsed = sh.starts[phase.ordinal] + lt
    val hit = sh.starts[Phase.Scatter.ordinal] + sh.durations[Phase.Scatter.ordinal] * 0.8f
    if (elapsed < hit) return
    val recover = sh.starts[Phase.Recover.ordinal]
    val vs = stage.viewScale(Box.GLASS)
    for (k in 0 until SLIME_COUNT) {
        if (sh.landing[k] != Landing.Glass) continue
        val dry = 1f - progress(elapsed, recover + 2.6f, 6f)
        if (dry <= 0f) continue
        val slide = easeInCubic(progress(elapsed, recover + 1.0f, 1.0f))
        val rr = stage.radius[k] * vs
        fun onGlass(x: Float, y: Float) = Offset(stage.center + (x - stage.center) * vs, stage.eyeY + (y - stage.eyeY) * vs)
        val top = onGlass(sh.landX[k], stage.groundY - stage.r * 1.3f - stage.radius[k] * 0.9f)
        val bottom = onGlass(sh.landX[k], stage.groundY - stage.r * 1.3f * (1f - slide) - stage.radius[k] * 0.9f)
        val tint = Cast[k].glow
        // The smear left behind as it slid down.
        if (slide > 0f) {
            drawLine(tint, top, bottom, rr * 1.3f, StrokeCap.Round, alpha = 0.12f * dry)
            drawLine(Color.White, top + Offset(-rr * 0.35f, 0f), bottom + Offset(-rr * 0.35f, 0f), rr * 0.08f, StrokeCap.Round, alpha = 0.28f * dry)
        }
        // The print itself, with a few droplets.
        drawOval(tint, bottom - Offset(rr * 0.75f, rr * 0.7f), androidx.compose.ui.geometry.Size(rr * 1.5f, rr * 1.4f), alpha = 0.16f * dry)
        drawOval(Color.White, bottom - Offset(rr * 0.5f, rr * 0.55f), androidx.compose.ui.geometry.Size(rr * 0.45f, rr * 0.22f), alpha = 0.35f * dry)
        repeat(5) { j ->
            val a = hash(k * 31 + j) * 2f * PI_F
            val d = rr * (0.85f + 0.35f * hash(k * 31 + j + 7))
            val drop = bottom + Offset(cos(a) * d, sin(a) * d * 0.9f)
            drawCircle(tint, rr * (0.05f + 0.06f * hash(k * 31 + j + 13)), drop, alpha = 0.25f * dry)
            drawCircle(Color.White, rr * 0.025f, drop + Offset(-rr * 0.02f, -rr * 0.02f), alpha = 0.5f * dry)
        }
    }
}

/** Little stars orbiting the head of someone who has just been knocked silly. */
private fun DrawScope.drawDizzyStars(head: Offset, r: Float, now: Float, alpha: Float) {
    if (alpha <= 0f) return
    repeat(3) { j ->
        val a = now * 5f + j * 2f * PI_F / 3f
        val at = head + Offset(cos(a) * r * 0.6f, -r * 0.15f + sin(a) * r * 0.16f)
        drawCartoonStar(at, r * 0.13f, now * 180f + j * 40f, alpha * (0.6f + 0.4f * sin(a)).coerceAtLeast(0.2f))
    }
}

/** Soda's dainty fork. */
private fun DrawScope.drawFork(at: Offset, s: Float) {
    val silver = Color(0xFFDEE2E6)
    drawLine(silver, at, at + Offset(s * 0.1f, -s * 1.1f), s * 0.12f, StrokeCap.Round)
    for (k in -1..1) {
        drawLine(silver, at + Offset(s * 0.1f + k * s * 0.12f, -s * 1.05f), at + Offset(s * 0.12f + k * s * 0.12f, -s * 1.45f), s * 0.07f, StrokeCap.Round)
    }
    drawLine(Color.White, at + Offset(-s * 0.02f, -s * 0.2f), at + Offset(s * 0.05f, -s * 0.9f), s * 0.04f, StrokeCap.Round, alpha = 0.8f)
}

