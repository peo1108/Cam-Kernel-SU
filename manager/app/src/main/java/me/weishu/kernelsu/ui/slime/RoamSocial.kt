package me.weishu.kernelsu.ui.slime

import androidx.compose.ui.geometry.Offset
import me.weishu.kernelsu.ui.screen.home.arena.BO
import me.weishu.kernelsu.ui.screen.home.arena.CHANH
import me.weishu.kernelsu.ui.screen.home.arena.Cast
import me.weishu.kernelsu.ui.screen.home.arena.MOCHI
import me.weishu.kernelsu.ui.screen.home.arena.Mood
import me.weishu.kernelsu.ui.screen.home.arena.PI_F
import me.weishu.kernelsu.ui.screen.home.arena.SODA
import me.weishu.kernelsu.ui.screen.home.arena.SlimePose
import me.weishu.kernelsu.ui.screen.home.arena.easeInOutCubic
import me.weishu.kernelsu.ui.screen.home.arena.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** Things two roaming slimes get up to together. */
internal enum class Duo { HighFive, Ride, Prank, Chase, Brawl }

/** One of those, under way: [a] and [b] play the parts the [kind] gives them. */
internal class Duet(val kind: Duo, val a: Roamer, val b: Roamer, now: Float) {
    var phase = 0
    var phaseAt = now
    /** Where they meet on their ledge (x). */
    var meet = 0f
    /** Which side of [b] [a] is on. */
    var side = 1f
    /** A one-off cue in the current phase has been played. */
    var cued = false

    fun next(now: Float) {
        phase++
        phaseAt = now
        cued = false
    }
}

internal enum class FxKind { Say, Clap, Cloud }

/** A short-lived effect drawn over the page: a word from [who], a clap, a scuffle's dust cloud. */
internal class SocialFx(val kind: FxKind, val who: Roamer?, val at: Offset, val text: String, val t0: Float, val dur: Float)

/**
 * The slimes' social life on the roaming pages: every so often two of them sharing a ledge
 * get up to something, picked by who they are. Mochi is bossy and picks fights, Bơ is the big
 * sleepy one the others climb on, Soda shows off, Chanh is trouble and pinches hats. One pair at
 * a time, so it stays readable; a poke, a grab or a change of page breaks it up.
 */
internal class Social(private val w: RoamWorld) {
    var duet: Duet? = null
        private set
    val fx = ArrayList<SocialFx>()
    private var nextTry = 5f

    private val now get() = w.now

    fun say(s: Roamer, text: String, dur: Float = 1.3f) {
        fx += SocialFx(FxKind.Say, s, Offset.Zero, text, now, dur)
    }

    /** Whether [s] is out of sight right now (inside a dust cloud). */
    fun hidden(s: Roamer) = s.duet?.let { it.kind == Duo.Brawl && it.phase == 1 } == true

    // -----------------------------------------------------------------------------------------
    // Each frame
    // -----------------------------------------------------------------------------------------

    fun update(dt: Float) {
        fx.removeAll { now - it.t0 > it.dur }
        duet?.let { d -> if (!valid(d)) end(d) }
        duet?.let { drive(it, dt) }
        if (duet == null && now >= nextTry && w.laser == null) {
            nextTry = now + 0.6f
            // Fewer games at night.
            if (start()) nextTry = now + (7f + w.rnd.nextFloat() * 7f) * (if (w.night) 2.5f else 1f)
        }
    }

    private fun free(s: Roamer) = s.duet == null &&
        (s.mode == RoamMode.Walk || s.mode == RoamMode.Idle) &&
        s.angle == 0f && s.on != null && s.grow == 1f &&
        now > s.socialAfter && s.x > 0f && s.x < w.w

    private fun start(): Boolean {
        val pairs = ArrayList<Pair<Roamer, Roamer>>()
        for (a in w.roamers) for (b in w.roamers) {
            if (a.k >= b.k || !free(a) || !free(b)) continue
            // Anywhere on screen: if they are on different cards, one jumps over to the other first.
            if (a.on === b.on || w.ledge(a.on) != null && w.ledge(b.on) != null) pairs += a to b
        }
        if (pairs.isEmpty()) return false
        // Neighbours are likelier to get together than slimes across the screen.
        pairs.sortBy { (a, b) -> kotlin.math.hypot(a.x - b.x, (a.y - b.y) * 1.5f) }
        val (a, b) = pairs[min(pairs.size - 1, (w.rnd.nextFloat() * w.rnd.nextFloat() * pairs.size).toInt())]
        val ks = intArrayOf(a.k, b.k)
        val options = ArrayList<Pair<Duo, Int>>()
        options += Duo.HighFive to (if (SODA in ks) 4 else 3)
        if (BO in ks) options += Duo.Ride to 4
        if (CHANH in ks && BO !in ks || CHANH in ks && w.rnd.nextBoolean()) options += Duo.Prank to 4
        options += Duo.Brawl to (if (MOCHI in ks) 3 else 1)
        options += Duo.Chase to 2
        var roll = w.rnd.nextInt(options.sumOf { it.second })
        val kind = options.first { (roll - it.second).also { r -> roll = r } < 0 }.first
        begin(kind, a, b)
        return true
    }

    private fun begin(kind: Duo, a: Roamer, b: Roamer) {
        val d = when (kind) {
            Duo.HighFive, Duo.Brawl -> {
                val (l, r) = if (a.x <= b.x) a to b else b to a
                Duet(kind, l, r, now).also { it.meet = (l.x + r.x) / 2f }
            }

            Duo.Ride -> if (a.k == BO) Duet(kind, b, a, now) else Duet(kind, a, b, now)
            Duo.Prank -> if (a.k == CHANH) Duet(kind, a, b, now) else Duet(kind, b, a, now)
            Duo.Chase -> if (w.rnd.nextBoolean()) Duet(kind, a, b, now) else Duet(kind, b, a, now)
        }
        d.side = sign(d.a.x - d.b.x).let { if (it == 0f) 1f else it }
        d.a.hopTo = null
        d.b.hopTo = null
        if (kind != Duo.Chase && d.a.on !== d.b.on) {
            // Over to the other's card first: to its left for a face-off, else on the side it came from.
            val gap = w.radius(d.a.k) + w.radius(d.b.k)
            val x = if (kind == Duo.HighFive || kind == Duo.Brawl) d.b.x - gap * 1.5f else d.b.x + d.side * gap * 1.2f
            hop(d.a, d.b.on!!, x)
            d.phase = -1
        }
        duet = d
        d.a.duet = d
        d.b.duet = d
        d.a.switch(RoamMode.Social)
        if (kind == Duo.Chase) {
            say(d.a, "đứng lại!")
            run(d.b, d.a)
        } else {
            d.b.switch(RoamMode.Social)
        }
    }

    /** [runner] legs it away from [from], quicker than usual. */
    private fun run(runner: Roamer, from: Roamer) {
        runner.dir = sign(runner.x - from.x).let { if (it == 0f) 1f else it }
        runner.boost = 1.9f
        runner.boostUntil = now + 3.5f
        runner.nextThink = now + 3f
        runner.switch(RoamMode.Walk)
    }

    private fun valid(d: Duet): Boolean {
        fun inIt(s: Roamer) = s.duet === d && s.mode == RoamMode.Social
        fun around(s: Roamer) = s.duet === d && (s.mode == RoamMode.Social || s.mode == RoamMode.Walk || s.mode == RoamMode.Idle || s.mode == RoamMode.Hop || s.mode == RoamMode.Fall)
        return when (d.kind) {
            Duo.Chase -> inIt(d.a) && around(d.b)
            Duo.Ride -> inIt(d.b) && (d.phase >= 3 || inIt(d.a))
            Duo.Prank -> inIt(d.b) && (d.phase >= 2 || inIt(d.a))
            else -> inIt(d.a) && inIt(d.b)
        }
    }

    private fun end(d: Duet) {
        for (s in arrayOf(d.a, d.b)) {
            if (s.duet !== d) continue
            s.duet = null
            s.socialAfter = now + 6f
            if (s.mode == RoamMode.Social) release(s)
        }
        if (duet === d) duet = null
    }

    /** Back to its own business: walking on if it still has a ledge, falling if not. */
    private fun release(s: Roamer) {
        if (s.on != null && w.stand(s)) {
            s.nextThink = now + 1.5f
            s.switch(RoamMode.Walk)
        } else {
            w.lose(s)
        }
    }

    /** Starts [s] jumping onto ledge [key] at x [x]. */
    private fun hop(s: Roamer, key: Any, x: Float) {
        val l = w.ledge(key) ?: return
        s.fromX = s.x
        s.fromY = s.y
        s.hopTo = key
        s.hopAlong = (x - l.left).coerceIn(0f, l.right - l.left)
        s.hopStart = now
        s.hopDur = (0.5f + kotlin.math.hypot(l.left + s.hopAlong - s.x, l.y - s.y) / (w.r * 22f)).coerceIn(0.5f, 1.3f)
        s.dir = sign(l.left + s.hopAlong - s.x).let { if (it == 0f) s.dir else it }
    }

    /** Moves [s] along its jump; true once it has landed. */
    private fun hopStep(s: Roamer): Boolean {
        val key = s.hopTo ?: return true
        val l = w.ledge(key)
        val e = ((now - s.hopStart) / s.hopDur).coerceIn(0f, 1f)
        val tx = if (l != null) l.left + s.hopAlong else s.fromX
        val ty = l?.y ?: s.fromY
        val apex = min(s.fromY, ty) - w.radius(s.k) * 3f
        val u = 1f - e
        s.x = lerp(s.fromX, tx, e)
        s.y = u * u * s.fromY + 2f * u * e * apex + e * e * ty
        if (e < 1f) return false
        if (l != null) {
            s.on = l.key
            s.along = s.hopAlong
            w.stand(s)
        }
        s.hopTo = null
        return true
    }

    /** Walks [s] along its ledge toward x [tx]; true once there (or as far as the ledge goes). */
    private fun approach(s: Roamer, tx: Float, speed: Float, dt: Float): Boolean {
        val l = w.ledge(s.on) ?: return true
        val dx = tx - s.x
        if (abs(dx) < 2f) return true
        s.dir = sign(dx)
        val span = l.right - l.left
        val before = s.along
        s.along = (s.along + s.dir * min(abs(dx), speed * dt)).coerceIn(0f, span)
        w.stand(s)
        return abs(tx - s.x) < 2f || s.along == before
    }

    private fun place(s: Roamer, x: Float) {
        val l = w.ledge(s.on) ?: return
        s.along = (x - l.left).coerceIn(0f, l.right - l.left)
        w.stand(s)
    }

    /** Height of [s]'s head above its feet, in pixels. */
    private fun top(s: Roamer, squash: Float = 1f) = 1.72f * w.radius(s.k) * Cast[s.k].stretch * squash

    private fun drive(d: Duet, dt: Float) {
        val a = d.a
        val b = d.b
        val ra = w.radius(a.k)
        val rb = w.radius(b.k)
        val t = now - d.phaseAt
        // Whoever stands on a ledge stays on it while the scene plays out.
        for (s in arrayOf(a, b)) {
            if (s.duet === d && s.mode == RoamMode.Social && s.on != null && w.ledge(s.on) == null) {
                end(d)
                return
            }
        }
        if (d.phase == -1) {
            // Still on the way over.
            if (hopStep(a)) {
                d.next(now)
                d.meet = (a.x + b.x) / 2f
                d.side = sign(a.x - b.x).let { if (it == 0f) 1f else it }
            }
            return
        }
        when (d.kind) {
            Duo.HighFive -> when (d.phase) {
                0 -> {
                    val gap = (ra + rb) * 1.0f
                    val ha = approach(a, d.meet - gap / 2f, w.speed(a.k), dt)
                    val hb = approach(b, d.meet + gap / 2f, w.speed(b.k), dt)
                    if (ha && hb || t > 3f) {
                        a.dir = 1f
                        b.dir = -1f
                        d.next(now)
                    }
                }

                1 -> {
                    if (!d.cued && t > 0.33f) {
                        d.cued = true
                        val y = min(a.y - top(a), b.y - top(b)) - max(ra, rb) * 1.3f
                        fx += SocialFx(FxKind.Clap, null, Offset((a.x + b.x) / 2f, y), "bốp!", now, 0.9f)
                    }
                    if (t > 0.7f) d.next(now)
                }

                else -> if (t > 0.8f) {
                    a.dir = -1f
                    b.dir = 1f
                    end(d)
                }
            }

            Duo.Ride -> when (d.phase) {
                0 -> {
                    place(b, b.x)
                    if (approach(a, b.x + d.side * (ra + rb) * 0.95f, w.speed(a.k), dt) || t > 3.5f) {
                        a.fromX = a.x
                        a.fromY = a.y
                        a.dir = -d.side
                        d.next(now)
                    }
                }

                1 -> {
                    // Up onto Bơ's head.
                    val e = (t / 0.6f).coerceIn(0f, 1f)
                    val ty = b.y - top(b, 0.88f)
                    val apex = min(a.fromY, ty) - ra * 2.5f
                    val u = 1f - e
                    a.x = lerp(a.fromX, b.x, e)
                    a.y = u * u * a.fromY + 2f * u * e * apex + e * e * ty
                    if (e >= 1f) d.next(now)
                }

                2 -> {
                    a.x = b.x
                    a.y = b.y - top(b, 0.88f - 0.04f * abs(sin(t * 6f)))
                    if (!d.cued) {
                        d.cued = true
                        say(a, if (a.k == CHANH) "hí hí" else "yee~")
                    }
                    if (t > 3.6f) d.next(now)
                }

                3 -> {
                    if (!d.cued) {
                        d.cued = true
                        say(b, "ơ…?")
                    }
                    if (a.duet === d && t < 0.4f) {
                        a.x = b.x
                        a.y = b.y - top(b, 0.88f)
                    } else if (a.duet === d) {
                        // Bơ wakes up and shakes the rider off.
                        a.duet = null
                        a.socialAfter = now + 6f
                        a.on = null
                        a.vx = d.side * w.r * 7f
                        a.vy = -w.r * 9f
                        a.reaction = Reaction.Wiggle
                        a.switch(RoamMode.Fall)
                    }
                    if (t > 1.1f) {
                        b.duet = null
                        b.socialAfter = now + 6f
                        duet = null
                        b.idleAct = 0
                        b.switch(RoamMode.Idle)
                    }
                }
            }

            Duo.Prank -> when (d.phase) {
                0 -> {
                    // Victim looking the other way; Chanh tiptoes up behind.
                    b.dir = -d.side
                    if (approach(a, b.x + d.side * (ra + rb) * 0.95f, w.speed(a.k) * 0.4f, dt) || t > 4f) {
                        a.dir = -d.side
                        d.next(now)
                    }
                }

                1 -> {
                    if (!d.cued) {
                        d.cued = true
                        b.hatPop = now
                        say(b, "ê!")
                    }
                    if (t > 0.35f) {
                        d.next(now)
                        // Off he goes, giggling.
                        a.duet = null
                        a.socialAfter = now + 8f
                        say(a, "hehe")
                        run(a, b)
                    }
                }

                else -> {
                    b.dir = d.side
                    if (t > 0.9f) {
                        if (b.k == BO || a.mode == RoamMode.Away) {
                            end(d)
                        } else {
                            // After him.
                            val chase = Duet(Duo.Chase, b, a, now)
                            b.hopTo = null
                            b.duet = chase
                            a.duet = chase
                            duet = chase
                            say(b, "đứng lại!")
                        }
                    }
                }
            }

            Duo.Chase -> when (d.phase) {
                0 -> {
                    val gone = b.mode == RoamMode.Away || b.x < -rb || b.x > w.w + rb
                    if (t > 7f || gone) {
                        d.next(now)
                        d.phase = 2
                        return
                    }
                    if (b.mode == RoamMode.Walk) {
                        b.boostUntil = max(b.boostUntil, now + 0.3f)
                        if (b.on === a.on) b.dir = sign(b.x - a.x).let { if (it == 0f) b.dir else it }
                    }
                    if (a.hopTo != null) {
                        // Jumping after them onto their ledge.
                        hopStep(a)
                    } else if (b.on === a.on && b.mode != RoamMode.Hop && b.mode != RoamMode.Fall) {
                        val speed = max(w.speed(a.k) * 1.8f, w.speed(CHANH) * 1.15f)
                        approach(a, b.x, speed, dt)
                        if (abs(a.x - b.x) < (ra + rb) * 0.85f) {
                            // Got 'em.
                            b.duet = d
                            b.switch(RoamMode.Social)
                            a.fromX = a.x
                            a.fromY = a.y
                            say(a, "bắt được!")
                            d.next(now)
                        }
                    } else if (b.on != null && (b.mode == RoamMode.Walk || b.mode == RoamMode.Idle)) {
                        if (w.ledge(b.on) != null && b.on !== a.on) hop(a, b.on!!, b.x)
                    }
                }

                1 -> {
                    // A pounce and a pile-up.
                    val e = (t / 0.5f).coerceIn(0f, 1f)
                    a.x = lerp(a.fromX, b.x - a.dir * rb * 0.3f, e)
                    if (t > 1.1f) {
                        a.dir = -a.dir
                        b.dir = -a.dir
                        end(d)
                    }
                }

                else -> {
                    // Gave up, huffing.
                    if (t > 0.9f) end(d)
                }
            }

            Duo.Brawl -> when (d.phase) {
                0 -> {
                    val gap = (ra + rb) * 0.7f
                    val ha = approach(a, d.meet - gap / 2f, w.speed(a.k) * 1.4f, dt)
                    val hb = approach(b, d.meet + gap / 2f, w.speed(b.k) * 1.4f, dt)
                    if (ha && hb || t > 3f) {
                        d.next(now)
                        val y = (a.y + b.y) / 2f - max(ra, rb) * 1.0f
                        fx += SocialFx(FxKind.Cloud, null, Offset((a.x + b.x) / 2f, y), "", now, 1.7f)
                    }
                }

                1 -> if (t > 1.45f) {
                    val gap = (ra + rb) * 0.75f
                    place(a, d.meet - gap)
                    place(b, d.meet + gap)
                    a.dir = -1f
                    b.dir = 1f
                    d.next(now)
                    when (MOCHI) {
                        a.k -> say(a, "hứ!")
                        b.k -> say(b, "hứ!")
                    }
                }

                else -> if (t > 1.1f) end(d)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Looks
    // -----------------------------------------------------------------------------------------

    fun pose(s: Roamer, p: SlimePose) {
        val d = s.duet ?: return
        val t = now - d.phaseAt
        val rad = w.radius(s.k)
        val isA = s === d.a
        if (d.phase == -1) {
            if (isA) {
                p.squash = 1.1f
                p.armL = 150f
                p.armR = 150f
                p.mood = Mood.Happy
            } else if (d.kind != Duo.Prank) {
                // Watching the other come over.
                p.lookX = sign(d.a.x - s.x) * 0.8f
                p.lookY = -0.3f
                p.mood = if (d.kind == Duo.Brawl) Mood.Angry else Mood.Happy
            } else {
                p.whistle = 1f
                p.lookY = -0.8f
            }
            return
        }
        when (d.kind) {
            Duo.HighFive -> when (d.phase) {
                0 -> stroll(s, p)
                1 -> {
                    val e = (t / 0.7f).coerceIn(0f, 1f)
                    p.lift = sin(PI_F * e) * rad * 1.3f
                    p.squash = if (e < 0.1f || e > 0.92f) 0.84f else 1.08f
                    // The inner hands meet overhead.
                    if (isA) {
                        p.armR = 172f
                        p.armL = 50f
                    } else {
                        p.armL = 172f
                        p.armR = 50f
                    }
                    p.mood = Mood.Happy
                    p.lookX = if (isA) 0.8f else -0.8f
                }

                else -> {
                    p.mood = Mood.Happy
                    p.sparkle = 1f
                    p.armL = 140f
                    p.armR = 140f
                    if (s.k == SODA) {
                        // Can't help showing off.
                        val e = (t / 0.7f).coerceIn(0f, 1f)
                        p.lift = sin(PI_F * e) * rad * 1.6f
                        p.rotation = 360f * easeInOutCubic(e) * s.dir
                    }
                }
            }

            Duo.Ride -> if (isA) {
                when (d.phase) {
                    0 -> stroll(s, p)
                    1 -> {
                        p.mood = Mood.Happy
                        p.armL = 150f
                        p.armR = 150f
                        p.squash = 1.1f
                    }

                    else -> {
                        p.mood = Mood.Happy
                        p.armL = 150f + 20f * sin(t * 10f)
                        p.armR = 150f - 20f * sin(t * 10f)
                        p.lift = abs(sin(t * 6f)) * rad * 0.18f
                        p.lookX = 0.4f * s.dir
                        p.sparkle = 0.6f
                    }
                }
            } else {
                when (d.phase) {
                    0, 1, 2 -> {
                        p.sleep = 1f
                        p.snore = 0.4f + 0.2f * sin(t * 2.4f)
                        if (d.phase == 2) p.squash = 0.88f - 0.04f * abs(sin(t * 6f))
                    }

                    else -> {
                        val e = (t / 1.1f).coerceIn(0f, 1f)
                        if (t < 0.4f) {
                            p.sleep = 1f - t / 0.4f
                            p.mood = Mood.Surprised
                            p.squash = 0.88f
                        } else {
                            p.mood = Mood.Angry
                            p.jiggle = 1f - e
                            p.rotation = sin(t * 40f) * 9f * (1f - e)
                            p.armL = 120f
                            p.armR = 120f
                        }
                    }
                }
            }

            Duo.Prank -> if (isA) {
                when (d.phase) {
                    0 -> {
                        // Tiptoeing.
                        p.mood = Mood.Happy
                        p.squash = 0.9f
                        p.lift = abs(sin(t * 7f)) * rad * 0.12f
                        p.armL = 70f
                        p.armR = 70f
                        p.lookX = -d.side * 0.8f
                    }

                    else -> {
                        p.mood = Mood.Happy
                        if (d.side > 0f) p.armL = 172f else p.armR = 172f
                        p.lookX = -d.side * 0.8f
                    }
                }
            } else {
                when (d.phase) {
                    0 -> {
                        p.whistle = 1f
                        p.lookY = -0.8f
                        p.lookX = -d.side * 0.6f
                    }

                    1 -> {
                        p.mood = Mood.Surprised
                        p.jiggle = 1f
                    }

                    else -> {
                        p.mood = Mood.Angry
                        p.anger = 1f
                        p.akimbo = 1f
                        p.steam = 1f
                        p.flush = 0.5f
                        p.lookX = d.side * 0.8f
                    }
                }
            }

            Duo.Chase -> if (isA) {
                when (d.phase) {
                    0 -> {
                        if (s.hopTo != null) {
                            p.squash = 1.1f
                            p.armL = 160f
                            p.armR = 160f
                        } else {
                            stroll(s, p, fast = true)
                        }
                        p.mood = Mood.Angry
                        p.anger = 1f
                    }

                    1 -> {
                        val e = (t / 0.5f).coerceIn(0f, 1f)
                        p.lift = sin(PI_F * e) * rad * 1.1f
                        p.mood = Mood.Happy
                        p.armL = 160f
                        p.armR = 160f
                        if (e >= 1f) {
                            // Sitting on the catch.
                            p.lift = top(d.b, 0.6f) * 0.55f
                        }
                    }

                    else -> {
                        p.mood = Mood.Angry
                        p.steam = 1f
                        p.akimbo = 1f
                    }
                }
            } else {
                // The runner, once caught: squashed flat, dizzy.
                p.mood = Mood.Squeeze
                p.squash = if (t < 0.45f) 1f else 0.6f
                p.dizzy = if (t > 0.6f) 1f else 0f
            }

            Duo.Brawl -> when (d.phase) {
                0 -> {
                    stroll(s, p, fast = true)
                    p.mood = Mood.Angry
                    p.anger = 1f
                    p.flush = 0.5f
                }

                else -> {
                    val winner = s.k == MOCHI
                    if (winner) {
                        p.mood = Mood.Happy
                        p.akimbo = 1f
                        p.sparkle = 0.8f
                    } else {
                        p.mood = Mood.Dizzy
                        p.dizzy = 1f
                        p.squash = 0.92f
                        p.rotation = sin(t * 9f) * 6f
                    }
                }
            }
        }
    }

    /** A plain walking gait while they make their way over. */
    private fun stroll(s: Roamer, p: SlimePose, fast: Boolean = false) {
        val rad = w.radius(s.k)
        val phase = now * (if (fast) 14f else 9f)
        p.lift = abs(sin(phase)) * rad * (if (fast) 0.3f else 0.18f)
        p.armL = 30f + 20f * sin(phase)
        p.armR = 30f - 20f * sin(phase)
        p.mood = Mood.Happy
        p.lookX = s.dir * 0.7f
    }
}
