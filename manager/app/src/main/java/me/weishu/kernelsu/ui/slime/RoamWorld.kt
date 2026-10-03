package me.weishu.kernelsu.ui.slime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import me.weishu.kernelsu.ui.screen.home.arena.BO
import me.weishu.kernelsu.ui.screen.home.arena.CHANH
import me.weishu.kernelsu.ui.screen.home.arena.Cast
import me.weishu.kernelsu.ui.screen.home.arena.Costume
import me.weishu.kernelsu.ui.screen.home.arena.MOCHI
import me.weishu.kernelsu.ui.screen.home.arena.Mood
import me.weishu.kernelsu.ui.screen.home.arena.PI_F
import me.weishu.kernelsu.ui.screen.home.arena.SLIME_COUNT
import me.weishu.kernelsu.ui.screen.home.arena.SODA
import me.weishu.kernelsu.ui.screen.home.arena.SlimePose
import me.weishu.kernelsu.ui.screen.home.arena.easeInOutCubic
import me.weishu.kernelsu.ui.screen.home.arena.easeOutBack
import me.weishu.kernelsu.ui.screen.home.arena.lerp
import me.weishu.kernelsu.ui.screen.home.arena.progress
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** What a roaming slime is up to. */
internal enum class RoamMode { Away, Walk, Idle, Hop, Fall, Climb, React, Held, Thrown, Splat, Home, Social, Laser }

/** How a slime answers a poke. */
internal enum class Reaction { Wiggle, Huff, Explode, Flee, Melt, Flip }

/** A walkable top edge: a card's, or the floor above the navigation bar. */
internal class Ledge(val key: Any, val left: Float, val right: Float, val y: Float)

/** The left and right screen edges, which the slimes can climb. */
private val WALL_L = Any()
private val WALL_R = Any()
private val FLOOR = Any()
/** Hop target meaning "the door of the box". */
private val DOOR = Any()

/**
 * One cast member out on the app's pages. Positions are the slime's contact point in root
 * pixels; [angle] is the surface it stands on (0 on a ledge, 90 on the left wall, -90 on the right).
 */
internal class Roamer(val k: Int) {
    val pose = SlimePose().also { it.stretch = Cast[k].stretch }
    var mode = RoamMode.Away
    var modeT = 0f
    var on: Any? = null
    /** Along the surface: x from the ledge's left edge, or y on a wall. */
    var along = 0f
    var dir = 1f
    var x = 0f
    var y = 0f
    var angle = 0f
    var vx = 0f
    var vy = 0f
    // Hops.
    var fromX = 0f
    var fromY = 0f
    var hopTo: Any? = null
    var hopAlong = 0f
    var hopDur = 0.6f
    var hopStart = 0f
    // Reactions and timing.
    var reaction = Reaction.Wiggle
    var nextThink = 0f
    var backAt = 0f
    var idleAct = 0
    var splatSide = 1f
    var taps = 0
    var lastTap = -10f
    var seed = k * 7919
    /** True while this slime belongs on the current page. */
    var wanted = false
    /** Size relative to roaming size, easing back to 1 from [growAt] (it bursts out box-sized). */
    var grow = 1f
    var growAt = -10f
    /** What it is up to with another slime, if anything, and when it may start something new. */
    var duet: Duet? = null
    var socialAfter = 0f
    /** Running faster than usual (from a chaser) until [boostUntil]. */
    var boost = 1f
    var boostUntil = 0f
    /** When its hat was knocked off (it flies up, spins and lands back). */
    var hatPop = -10f
    /** Chasing the laser dot: when it last pounced at it, and last jumped to another card for it. */
    var pounceAt = -10f
    var laserHopAt = -10f

    fun switch(m: RoamMode) {
        mode = m
        modeT = 0f
    }
}

/**
 * The little world the roaming slimes live in: the ledges on screen, their brains and their
 * physics. Units are pixels and seconds; [r] is the slime radius in pixels.
 */
internal class RoamWorld {
    val roamers = Array(SLIME_COUNT) { Roamer(it) }
    val ledges = ArrayList<Ledge>()
    var w = 0f
    var h = 0f
    var floorY = 0f
    var topY = 0f
    var r = 40f
    var now = 0f
    var costume = Costume.NonLa
    val rnd = Random()
    private var pageKey: Any? = null
    /** Which kind of page they are on: 0 any, 1 Superuser, 2 modules, 3 settings. */
    private var pageKind = 0
    val social = Social(this)

    /** How many come out on each page: 0 for a random 1 to 4. */
    var maxCount = 0
    /** Whether they get sleepy at night. */
    var nightNap = true
        set(value) {
            if (field != value) nightCheckedAt = -100f
            field = value
        }
    /** Between 22:00 and 6:00 (and [nightNap] on): slower, yawning, mostly asleep. */
    var night = false
        private set
    private var nightCheckedAt = -100f

    /** The laser dot: a finger held still on the page, which they chase like cats. */
    var laser: Offset? = null
        private set

    /** Spots the user has been tapping lately: the slimes keep clear of them (and the buttons there). */
    private class Spot(val x: Float, val y: Float, val t: Float)
    private val avoid = ArrayDeque<Spot>()
    private var shookAt = -10f

    fun radius(k: Int) = r * Cast[k].size

    /** Rebuilds the ledges from the cards on screen. */
    fun refresh() {
        ledges.clear()
        ledges += Ledge(FLOOR, 0f, w, floorY)
        SlimeSurfaces.forEach { key, rect ->
            if (rect.width < r * 4f) return@forEach
            if (rect.top < topY + r * 2.6f || rect.top > floorY - r * 1.5f) return@forEach
            if (rect.left < -r || rect.right > w + r) return@forEach
            ledges += Ledge(key, rect.left + r * 0.6f, rect.right - r * 0.6f, rect.top)
        }
    }

    fun ledge(key: Any?): Ledge? = if (key == null) null else ledges.firstOrNull { it.key === key }

    // -----------------------------------------------------------------------------------------
    // Pages
    // -----------------------------------------------------------------------------------------

    /**
     * A new page. On the home page everyone files back into the box through its door; leaving
     * it, whoever is inside smashes the glass and bursts out onto the new page. Between other
     * pages, 1 to 4 random slimes belong on each; the others leave and the newcomers drop in.
     */
    fun page(key: Any, home: Boolean, quiet: Boolean = false) {
        if (key == pageKey) return
        pageKey = key
        onHome = home
        leftEarly = false
        pageKind = when (key.toString()) {
            "main_1" -> 1
            "main_2" -> 2
            "main_3" -> 3
            else -> 0
        }
        if (home) {
            comeHome()
            return
        }
        // Anyone still walking home turns round and carries on out here.
        for (s in roamers) if (s.mode == RoamMode.Home) {
            s.hopTo = null
            lose(s)
        }
        if (quiet) {
            // Flashing or installing: everyone out of the way. Those in the box stay in it.
            for (s in roamers) {
                s.wanted = false
                if (s.mode != RoamMode.Away) flee(s)
            }
            return
        }
        if (SlimeHome.inside.any { it }) {
            breakOut()
            return
        }
        // Fresh out of the box, they tumble onto this page together.
        if (SlimeHome.clock() - SlimeHome.brokeAt < 4f) return
        val count = pageCount()
        val picked = (0 until SLIME_COUNT).shuffled(rnd).take(count).toSet()
        for (s in roamers) {
            s.wanted = s.k in picked
            if (!s.wanted && s.mode != RoamMode.Away) flee(s)
            if (s.wanted && s.mode == RoamMode.Away) s.backAt = now + 0.3f + rnd.nextFloat() * 1.6f
        }
    }

    private var onHome = false
    private var leftEarly = false

    private fun pageCount() = if (maxCount in 1..SLIME_COUNT) maxCount else 1 + rnd.nextInt(SLIME_COUNT)

    /** Out through the glass: everyone inside flies out from where they stood toward the new page. */
    private fun breakOut() {
        SlimeHome.broken = true
        SlimeHome.brokeAt = SlimeHome.clock()
        SlimeHome.mendAt = -100f
        SlimeHome.hasLeft = true
        val card = SlimeHome.cardRect
        // All of them burst out; the ones beyond this page's count wander off once they have landed.
        val keep = (0 until SLIME_COUNT).shuffled(rnd).take(pageCount()).toSet()
        for (s in roamers) {
            if (!SlimeHome.inside[s.k]) continue
            SlimeHome.inside[s.k] = false
            val p = SlimeHome.boxPos[s.k].takeIf { it.isSpecified } ?: card?.center ?: Offset(w / 2f, topY + r * 6f)
            s.x = p.x
            s.y = p.y
            s.grow = (SlimeHome.boxUnit[s.k] / r).takeIf { it > 0f } ?: 1f
            s.growAt = now
            s.vx = r * (9f + 9f * rnd.nextFloat())
            s.vy = -r * (9f + 7f * rnd.nextFloat())
            s.angle = 0f
            s.on = null
            s.wanted = s.k in keep
            s.reaction = Reaction.Wiggle
            s.switch(RoamMode.Fall)
        }
    }

    /** Back home: the door opens and they hop in one after another, Mochi first, Chanh last. */
    private fun comeHome() {
        for ((i, k) in intArrayOf(MOCHI, SODA, BO, CHANH).withIndex()) {
            val s = roamers[k]
            if (SlimeHome.inside[k]) continue
            s.wanted = false
            if (s.mode == RoamMode.Away) {
                // Running in from off screen.
                val left = rnd.nextBoolean()
                s.x = if (left) -radius(k) * 2f else w + radius(k) * 2f
                s.y = floorY
            }
            s.hopTo = null
            s.backAt = 0.35f + i * 0.45f
            s.angle = 0f
            s.switch(RoamMode.Home)
        }
    }

    private fun home(s: Roamer) {
        if (s.modeT < s.backAt) {
            if (s.on != null && !stand(s)) s.on = null
            return
        }
        val door = SlimeHome.door()
        if (door == null) {
            inside(s)
            return
        }
        if (s.hopTo !== DOOR) {
            s.fromX = s.x
            s.fromY = s.y
            s.hopTo = DOOR
            s.hopStart = s.modeT
            s.hopDur = (0.55f + hypot(door.x - s.x, door.y - s.y) / (r * 24f)).coerceIn(0.55f, 1.3f)
            s.dir = sign(door.x - s.x).let { if (it == 0f) 1f else it }
        }
        val e = ((s.modeT - s.hopStart) / s.hopDur).coerceIn(0f, 1f)
        val apex = min(s.fromY, door.y) - radius(s.k) * 3.5f
        val a = 1f - e
        s.x = lerp(s.fromX, door.x, e)
        s.y = a * a * s.fromY + 2f * a * e * apex + e * e * door.y
        if (e >= 1f) inside(s)
    }

    private fun inside(s: Roamer) {
        SlimeHome.inside[s.k] = true
        SlimeHome.cameIn[s.k] = SlimeHome.clock()
        s.hopTo = null
        s.wanted = false
        s.switch(RoamMode.Away)
        s.backAt = Float.MAX_VALUE
    }

    private fun enter(s: Roamer) {
        if (rnd.nextBoolean()) {
            // Drop in from the top onto whatever is below.
            s.x = r * 2f + rnd.nextFloat() * (w - r * 4f)
            s.y = topY - r * 2f
            s.vx = 0f
            s.vy = 0f
            s.angle = 0f
            s.switch(RoamMode.Fall)
        } else {
            // Stroll in along the floor from a side.
            val left = rnd.nextBoolean()
            s.on = FLOOR
            s.along = if (left) -radius(s.k) * 2f else w + radius(s.k) * 2f
            s.dir = if (left) 1f else -1f
            s.angle = 0f
            s.switch(RoamMode.Walk)
            s.nextThink = now + 2.5f
        }
    }

    private fun flee(s: Roamer) {
        s.vx = (if (s.x < w / 2f) -1f else 1f) * r * 26f
        s.vy = -r * 14f
        s.angle = 0f
        s.reaction = Reaction.Flee
        s.switch(RoamMode.Thrown)
    }

    // -----------------------------------------------------------------------------------------
    // Each frame
    // -----------------------------------------------------------------------------------------

    fun step(dt: Float) {
        now += dt
        refresh()
        if (onHome) {
            val pos = SlimeHome.pagerPos
            if (!leftEarly && pos > 0.12f && SlimeHome.inside.any { it }) {
                // The swipe is carrying the home page away: out they go, right now.
                leftEarly = true
                breakOut()
            } else if (leftEarly && pos < 0.02f) {
                // The swipe snapped back to the home page: back in through the door.
                leftEarly = false
                comeHome()
            }
        }
        checkNight()
        while (avoid.isNotEmpty() && now - avoid.first().t > AVOID_SECONDS) avoid.removeFirst()
        social.update(dt)
        for (s in roamers) {
            s.modeT += dt
            // Ones not meant for this page (the extras from a break-out) wander off.
            if (!onHome && !s.wanted && s.duet == null && (s.mode == RoamMode.Walk || s.mode == RoamMode.Idle) && s.modeT > 1.5f) flee(s)
            // Everyone free goes after the laser dot.
            if (laser != null && s.wanted && s.duet == null && s.on != null && s.angle == 0f && (s.mode == RoamMode.Walk || s.mode == RoamMode.Idle)) s.switch(RoamMode.Laser)
            when (s.mode) {
                RoamMode.Away -> if (s.wanted && now >= s.backAt) enter(s)
                RoamMode.Walk -> walk(s, dt)
                RoamMode.Idle -> idle(s)
                RoamMode.Hop -> hop(s)
                RoamMode.Fall, RoamMode.Thrown -> fly(s, dt)
                RoamMode.Climb -> climb(s, dt)
                RoamMode.React -> react(s)
                RoamMode.Held -> {}
                RoamMode.Splat -> splat(s, dt)
                RoamMode.Home -> home(s)
                RoamMode.Social -> {}
                RoamMode.Laser -> chaseLaser(s, dt)
            }
            pose(s, dt)
        }
        bump()
        // The door opens for whoever is on the way in; the glass mends once all four are back.
        val coming = roamers.any { it.mode == RoamMode.Home && it.modeT > it.backAt - 0.4f }
        SlimeHome.doorOpen += ((if (coming) 1f else 0f) - SlimeHome.doorOpen) * min(1f, dt * 7f)
        if (SlimeHome.broken && SlimeHome.allInside()) {
            val t = SlimeHome.clock()
            if (SlimeHome.mendAt < SlimeHome.brokeAt) SlimeHome.mendAt = t + 0.7f
            if (t > SlimeHome.mendAt + SlimeHome.MEND_SECONDS) SlimeHome.broken = false
        }
    }

    fun speed(k: Int) = r * when (k) {
        MOCHI -> 1.6f
        BO -> 0.9f
        SODA -> 1.9f
        else -> 2.8f
    }

    /** Places a slime standing on [s.on] at [s.along]; false if that surface is gone. */
    fun stand(s: Roamer): Boolean {
        when (s.on) {
            WALL_L -> {
                s.x = 0f
                s.y = s.along
                s.angle = 90f
            }

            WALL_R -> {
                s.x = w
                s.y = s.along
                s.angle = -90f
            }

            else -> {
                val l = ledge(s.on) ?: return false
                s.x = l.left + s.along
                s.y = l.y
                s.angle = 0f
            }
        }
        return true
    }

    fun lose(s: Roamer) {
        // The card scrolled away under it: fall.
        s.vx = 0f
        s.vy = 0f
        s.angle = 0f
        s.switch(RoamMode.Fall)
    }

    private fun walk(s: Roamer, dt: Float) {
        val l = ledge(s.on)
        if (l == null) {
            lose(s)
            return
        }
        val span = l.right - l.left
        val rad = radius(s.k)
        val entering = s.along < 0f || s.along > span
        val boost = if (now < s.boostUntil) s.boost else if (night) 0.6f else 1f
        // Steer clear of where the user keeps tapping.
        if (now >= s.boostUntil && !nearAvoid(s.x, s.y, radius(s.k) * 1.6f) && nearAvoid(s.x + s.dir * radius(s.k) * 1.8f, s.y, radius(s.k) * 1.6f)) s.dir = -s.dir
        s.along += s.dir * speed(s.k) * boost * dt
        if (!stand(s)) return lose(s)
        if (entering) return
        val atEnd = (s.dir < 0f && s.along <= 0f) || (s.dir > 0f && s.along >= span)
        if (atEnd) {
            s.along = s.along.coerceIn(0f, span)
            stand(s)
            // Floor ends against the screen edge: climb it sometimes.
            if (s.on === FLOOR && rnd.nextFloat() < 0.5f && s.k != BO) {
                s.on = if (s.dir < 0f) WALL_L else WALL_R
                s.along = floorY - rad
                s.switch(RoamMode.Climb)
                return
            }
            if (!tryHop(s, prefer = s.dir) && !(s.on !== FLOOR && dropDown(s))) s.dir = -s.dir
            return
        }
        if (now > s.nextThink) {
            s.nextThink = now + 1.5f + rnd.nextFloat() * 3f
            when (rnd.nextInt(10)) {
                0, 1, 2 -> startIdle(s)
                3, 4 -> tryHop(s, prefer = if (rnd.nextBoolean()) 1f else -1f)
                5 -> s.dir = -s.dir
                else -> {}
            }
        }
    }

    private fun startIdle(s: Roamer) {
        // Never settle down over a spot the user has just been tapping.
        if (nearAvoid(s.x, s.y, radius(s.k) * 2.2f)) return
        if (night && rnd.nextFloat() < 0.7f) {
            s.idleAct = 0
            if (rnd.nextFloat() < 0.4f) social.say(s, YAWNS[rnd.nextInt(YAWNS.size)], 1.6f)
            s.switch(RoamMode.Idle)
            return
        }
        s.idleAct = rnd.nextInt(5)
        if (s.k == BO && rnd.nextFloat() < 0.5f) s.idleAct = 0
        // Each page has its own pastime: guarding root, peeking at modules, fiddling with settings.
        if (pageKind != 0 && s.idleAct != 0 && rnd.nextFloat() < 0.5f) {
            s.idleAct = 4 + pageKind
            if (rnd.nextFloat() < 0.6f) {
                val words = when (pageKind) {
                    1 -> arrayOf("root?", "ai xin quyền?", "cho phép!", "gác cổng~")
                    2 -> arrayOf("mod mới à?", "cài gì đấy?", "ngó tí…", "zip này ngon")
                    else -> arrayOf("chỉnh tí…", "bật hay tắt?", "vặn vặn", "để t xem")
                }
                social.say(s, words[rnd.nextInt(words.size)], 1.6f)
            }
        }
        s.switch(RoamMode.Idle)
    }

    private fun idle(s: Roamer) {
        if (!stand(s)) return lose(s)
        val dur = if (s.idleAct == 0) (if (night) 11f else 5f) else 2.4f
        if (s.modeT > dur) {
            s.nextThink = now + 2f + rnd.nextFloat() * 2f
            if (rnd.nextFloat() < 0.4f && tryHop(s, prefer = s.dir)) return
            s.switch(RoamMode.Walk)
        }
    }

    /** Looks for a ledge within jumping range and hops to it. */
    private fun tryHop(s: Roamer, prefer: Float): Boolean {
        val rad = radius(s.k)
        var best: Ledge? = null
        var bestAlong = 0f
        var bestScore = Float.MAX_VALUE
        for (l in ledges) {
            if (l.key === s.on) continue
            val dy = l.y - s.y
            if (dy < -rad * 7f || dy > rad * 14f) continue
            val tx = (s.x + prefer * rad * 2.5f).coerceIn(l.left, l.right)
            val dx = tx - s.x
            if (abs(dx) > rad * 9f) continue
            val score = abs(dx) + abs(dy) * 0.5f + rnd.nextFloat() * rad * 3f - (if (sign(dx) == prefer) rad * 2f else 0f)
            if (score < bestScore) {
                bestScore = score
                best = l
                bestAlong = tx - l.left
            }
        }
        val l = best ?: return false
        s.fromX = s.x
        s.fromY = s.y
        s.hopTo = l.key
        s.hopAlong = bestAlong
        s.hopDur = (0.45f + hypot(l.left + bestAlong - s.x, l.y - s.y) / (rad * 20f)).coerceAtMost(0.9f)
        s.angle = 0f
        s.switch(RoamMode.Hop)
        return true
    }

    /** Steps off the end of a ledge and falls to what is below. */
    private fun dropDown(s: Roamer): Boolean {
        s.vx = s.dir * r * 3f
        s.vy = -r * 4f
        s.switch(RoamMode.Fall)
        return true
    }

    private fun hop(s: Roamer) {
        val l = ledge(s.hopTo)
        val e = (s.modeT / s.hopDur).coerceIn(0f, 1f)
        val tx = if (l != null) l.left + s.hopAlong else s.fromX
        val ty = l?.y ?: s.fromY
        val apex = min(s.fromY, ty) - radius(s.k) * 3f
        val u = easeInOutCubic(e) * 0.3f + e * 0.7f
        s.x = lerp(s.fromX, tx, u)
        val a = 1f - u
        s.y = a * a * s.fromY + 2f * a * u * apex + u * u * ty
        if (e >= 1f) {
            if (l == null) return lose(s)
            s.on = l.key
            s.hopTo = null
            s.along = s.hopAlong
            s.dir = sign(tx - s.fromX).let { if (it == 0f) s.dir else it }
            land(s, 0.8f)
        }
    }

    private fun land(s: Roamer, impact: Float) {
        s.reaction = Reaction.Wiggle
        s.vx = 0f
        s.vy = 0f
        s.nextThink = now + 1f + rnd.nextFloat() * 2f
        s.switch(RoamMode.Walk)
        s.pose.jiggle = impact
        landedAt[s.k] = now
        landImpact[s.k] = impact
    }

    private companion object {
        const val AVOID_SECONDS = 25f
        val YAWNS = arrayOf("oáp~", "buồn ngủ…", "zzz…", "ngủ thôi…")
    }

    private val landedAt = FloatArray(SLIME_COUNT) { -10f }
    private val landImpact = FloatArray(SLIME_COUNT)

    private fun fly(s: Roamer, dt: Float) {
        val rad = radius(s.k)
        val g = r * 48f
        val prevY = s.y
        s.vy += g * dt
        s.x += s.vx * dt
        s.y += s.vy * dt
        val fleeing = s.mode == RoamMode.Thrown && s.reaction == Reaction.Flee
        if (fleeing) {
            if (s.x < -rad * 3f || s.x > w + rad * 3f || s.y > h + rad * 3f) {
                s.switch(RoamMode.Away)
                s.backAt = now + 4f + rnd.nextFloat() * 5f
            }
            return
        }
        // Walls: a hard throw sticks to them.
        if (s.x < rad * 0.9f || s.x > w - rad * 0.9f) {
            val side = if (s.x < w / 2f) -1f else 1f
            if (abs(s.vx) > r * 14f && s.mode == RoamMode.Thrown) {
                s.splatSide = side
                s.x = if (side < 0f) 0f else w
                s.angle = if (side < 0f) 90f else -90f
                s.switch(RoamMode.Splat)
                return
            }
            s.x = s.x.coerceIn(rad * 0.9f, w - rad * 0.9f)
            s.vx = -s.vx * 0.4f
        }
        if (s.y < topY + rad && s.vy < 0f) s.vy = abs(s.vy) * 0.3f
        // Landing on a ledge when coming down through its top edge.
        if (s.vy > 0f) {
            for (l in ledges) {
                if (s.x < l.left || s.x > l.right) continue
                if (prevY <= l.y && s.y >= l.y) {
                    s.on = l.key
                    s.along = s.x - l.left
                    s.y = l.y
                    if (s.vy > r * 22f) {
                        // Bounce off it.
                        s.vy = -s.vy * 0.35f
                        s.vx *= 0.6f
                        landedAt[s.k] = now
                        landImpact[s.k] = 1f
                        return
                    }
                    land(s, (s.vy / (r * 22f)).coerceIn(0.3f, 1f))
                    return
                }
            }
        }
        if (s.y > h + rad * 4f) {
            // Fell out of the world; come back later.
            s.switch(RoamMode.Away)
            s.backAt = now + 2f
        }
    }

    private fun climb(s: Roamer, dt: Float) {
        val rad = radius(s.k)
        s.along -= speed(s.k) * 0.8f * dt
        stand(s)
        // Step onto a card whose top is level with us and close to the wall.
        for (l in ledges) {
            if (l.key === FLOOR) continue
            val near = if (s.on === WALL_L) l.left < rad * 3f else l.right > w - rad * 3f
            if (near && abs(l.y - s.y) < rad * 0.6f) {
                s.on = l.key
                s.along = if (s.on === WALL_L) 0f else l.right - l.left
                s.dir = if (l.left < rad * 3f && s.x < w / 2f) 1f else -1f
                land(s, 0.4f)
                return
            }
        }
        if (s.y < topY + rad * 3f || s.modeT > 6f) {
            // Push off the wall back into the room.
            s.vx = (if (s.on === WALL_L) 1f else -1f) * r * 6f
            s.vy = -r * 2f
            s.x += (if (s.on === WALL_L) 1f else -1f) * rad
            s.angle = 0f
            s.switch(RoamMode.Fall)
        }
    }

    private fun splat(s: Roamer, dt: Float) {
        // Stuck flat on the wall, sliding down, then peeling off.
        s.y += r * 1.2f * dt
        if (s.modeT > 1.3f || s.y > floorY - radius(s.k)) {
            s.vx = -s.splatSide * r * 4f
            s.vy = 0f
            s.x += -s.splatSide * radius(s.k)
            s.angle = 0f
            s.switch(RoamMode.Fall)
        }
    }

    /** Two walkers meeting on the same ledge bump and turn back. */
    private fun bump() {
        for (a in roamers) for (b in roamers) {
            if (a.k >= b.k || a.mode != RoamMode.Walk || b.mode != RoamMode.Walk || a.on !== b.on) continue
            val d = b.x - a.x
            if (abs(d) < (radius(a.k) + radius(b.k)) * 1.6f && sign(d) == a.dir && sign(-d) == b.dir) {
                a.dir = -a.dir
                b.dir = -b.dir
                a.pose.jiggle = 0.6f
                b.pose.jiggle = 0.6f
                landedAt[a.k] = now
                landedAt[b.k] = now
                landImpact[a.k] = 0.4f
                landImpact[b.k] = 0.4f
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Touch
    // -----------------------------------------------------------------------------------------

    // -----------------------------------------------------------------------------------------
    // Night, the user's fingers, shaking
    // -----------------------------------------------------------------------------------------

    private fun checkNight() {
        if (now - nightCheckedAt < 30f) return
        nightCheckedAt = now
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        night = nightNap && (hour >= 22 || hour < 6)
    }

    private fun nearAvoid(x: Float, y: Float, reach: Float) = avoid.any { abs(it.x - x) < reach && it.y < y + reach && it.y > y - reach * 2.6f }

    /**
     * A quick tap that was not on a slime (a button, a card): anyone standing near it scurries
     * off, and they keep clear of the spot for a while, so they stop sitting on what you press.
     */
    fun shoo(p: Offset) {
        avoid.addLast(Spot(p.x, p.y, now))
        while (avoid.size > 8) avoid.removeFirst()
        for (s in roamers) {
            if (s.mode != RoamMode.Walk && s.mode != RoamMode.Idle) continue
            val c = center(s)
            if (hypot(c.x - p.x, c.y - p.y) > radius(s.k) * 3.4f) continue
            s.dir = if (s.x < p.x) -1f else 1f
            s.boost = 2.4f
            s.boostUntil = now + 1.1f
            s.nextThink = now + 2.5f
            landedAt[s.k] = now
            landImpact[s.k] = 0.7f
            if (rnd.nextFloat() < 0.5f) social.say(s, "!", 0.8f)
            s.switch(RoamMode.Walk)
        }
    }

    /** Moves the laser dot to [p], or switches it off (null): they are left looking around for it. */
    fun laserAt(p: Offset?) {
        if (p == null && laser != null) {
            for (s in roamers) {
                if (s.mode != RoamMode.Laser) continue
                s.idleAct = 4
                s.switch(RoamMode.Idle)
                if (rnd.nextFloat() < 0.6f) social.say(s, "?", 1f)
            }
        }
        laser = p
    }

    private fun chaseLaser(s: Roamer, dt: Float) {
        val dot = laser
        val l = ledge(s.on)
        if (dot == null || l == null) {
            if (l == null) lose(s) else s.switch(RoamMode.Walk)
            return
        }
        val rad = radius(s.k)
        // The first card top under the dot: if it is another one, jump over to it.
        val under = ledges.filter { dot.x >= it.left - rad && dot.x <= it.right + rad && it.y >= dot.y - rad * 0.5f }.minByOrNull { it.y }
        if (under != null && under.key !== s.on && now - s.laserHopAt > 0.4f && abs(under.y - s.y) < rad * 16f) {
            s.laserHopAt = now
            s.fromX = s.x
            s.fromY = s.y
            s.hopTo = under.key
            s.hopAlong = (dot.x - under.left).coerceIn(0f, under.right - under.left)
            s.hopDur = (0.45f + hypot(under.left + s.hopAlong - s.x, under.y - s.y) / (rad * 22f)).coerceAtMost(0.9f)
            s.dir = sign(dot.x - s.x).let { if (it == 0f) s.dir else it }
            s.switch(RoamMode.Hop)
            return
        }
        val dx = dot.x - s.x
        if (abs(dx) > rad * 0.3f && now - s.pounceAt > 0.55f) {
            s.dir = sign(dx)
            s.along = (s.along + s.dir * min(abs(dx), speed(s.k) * 1.9f * dt)).coerceIn(0f, l.right - l.left)
            stand(s)
        }
        // Right under it: pounce.
        if (abs(dx) < rad * 1.2f && s.y - dot.y in rad * 0.5f..rad * 7f && now - s.pounceAt > 1.1f) s.pounceAt = now
    }

    /** The phone was shaken: everyone standing anywhere is thrown off, spinning. */
    fun shake(strength: Float) {
        if (now - shookAt < 1.2f || onHome) return
        shookAt = now
        var said = false
        val k = strength.coerceIn(0.7f, 1.6f)
        for (s in roamers) {
            if (s.mode == RoamMode.Away || s.mode == RoamMode.Held || s.mode == RoamMode.Home) continue
            if (s.angle != 0f) s.x += (if (s.angle > 0f) 1f else -1f) * radius(s.k) * 1.2f
            s.vx = (rnd.nextFloat() * 2f - 1f) * r * 18f * k
            s.vy = -r * (10f + 8f * rnd.nextFloat()) * k
            s.angle = 0f
            s.on = null
            s.hopTo = null
            s.reaction = Reaction.Wiggle
            s.switch(RoamMode.Thrown)
            if (!said) {
                social.say(s, "ááá!", 1f)
                said = true
            }
        }
    }

    /** The slime under [p], if any (generous, they are small). */
    fun hit(p: Offset): Roamer? {
        var best: Roamer? = null
        var bestD = Float.MAX_VALUE
        for (s in roamers) {
            if (s.mode == RoamMode.Away || !s.pose.visible && s.mode != RoamMode.React) continue
            val c = center(s)
            val d = hypot(p.x - c.x, p.y - c.y)
            // Just the body: a press that misses it goes through to whatever is underneath.
            if (d < radius(s.k) * 1.2f && d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    /** Middle of the slime's body on screen. */
    fun center(s: Roamer): Offset {
        val h = 1.72f * radius(s.k) * Cast[s.k].stretch * 0.5f
        val a = Math.toRadians(s.angle.toDouble())
        return Offset(s.x + (sin(a) * h).toFloat(), s.y - (kotlin.math.cos(a) * h).toFloat() - s.pose.lift)
    }

    fun tap(s: Roamer) {
        if (now - s.lastTap > 2.5f) s.taps = 0
        s.taps++
        s.lastTap = now
        s.reaction = when {
            s.taps >= 4 -> Reaction.Explode.also { s.taps = 0 }
            else -> pick(s.k)
        }
        if (s.reaction == Reaction.Flee) {
            flee(s)
            return
        }
        if (s.mode == RoamMode.Fall || s.mode == RoamMode.Thrown || s.mode == RoamMode.Hop || s.mode == RoamMode.Splat) return
        s.seed = rnd.nextInt(10000)
        s.switch(RoamMode.React)
    }

    /** Each one's own idea of how to answer a poke. */
    private fun pick(k: Int): Reaction {
        val weights = when (k) {
            MOCHI -> intArrayOf(25, 40, 10, 10, 5, 10)
            BO -> intArrayOf(30, 15, 15, 5, 30, 5)
            SODA -> intArrayOf(25, 5, 15, 15, 5, 35)
            else -> intArrayOf(15, 5, 20, 45, 5, 10)
        }
        var roll = rnd.nextInt(weights.sum())
        for ((i, wgt) in weights.withIndex()) {
            if (roll < wgt) return Reaction.entries[i]
            roll -= wgt
        }
        return Reaction.Wiggle
    }

    private fun reactDuration(r: Reaction) = when (r) {
        Reaction.Wiggle -> 0.9f
        Reaction.Huff -> 1.5f
        Reaction.Explode -> 1.7f
        Reaction.Flee -> 0f
        Reaction.Melt -> 1.8f
        Reaction.Flip -> 0.8f
    }

    private fun react(s: Roamer) {
        if (s.on != null && !stand(s)) return lose(s)
        if (s.modeT > reactDuration(s.reaction)) {
            s.nextThink = now + 1f
            s.switch(RoamMode.Walk)
        }
    }

    fun grab(s: Roamer, p: Offset) {
        s.switch(RoamMode.Held)
        s.angle = 0f
        drag(s, p)
    }

    fun drag(s: Roamer, p: Offset) {
        // Held by the top of the head: the feet dangle below the finger.
        s.x = p.x
        s.y = p.y + 1.72f * radius(s.k) * Cast[s.k].stretch
    }

    fun release(s: Roamer, velocity: Offset) {
        s.vx = velocity.x.coerceIn(-r * 60f, r * 60f)
        s.vy = velocity.y.coerceIn(-r * 60f, r * 60f)
        s.reaction = Reaction.Wiggle
        s.switch(RoamMode.Thrown)
    }

    // -----------------------------------------------------------------------------------------
    // Looks
    // -----------------------------------------------------------------------------------------

    private fun pose(s: Roamer, dt: Float) {
        val p = s.pose
        val k = s.k
        val t = now
        val jiggle = p.jiggle
        p.reset(0f)
        p.costume = costume
        p.lookX = s.dir * 0.7f
        p.squash = 1f + 0.03f * sin(t * (2.6f + k * 0.37f) + k)
        val blinkT = (t + k * 1.7f) % (3.4f + k * 0.37f)
        p.blink = if (blinkT < 0.14f) sin(blinkT / 0.14f * PI_F) else 0f
        p.jiggle = max(0f, jiggle - dt * 1.5f)
        // Landing squash.
        val since = t - landedAt[k]
        if (since < 0.3f) {
            p.squash = 1f - 0.3f * landImpact[k] * sin(PI_F * since / 0.3f)
            p.jiggle = max(p.jiggle, landImpact[k] * (1f - since / 0.3f))
        }
        when (s.mode) {
            RoamMode.Walk -> {
                val rad = radius(k)
                val phase = t * speed(k) / (rad * 1.6f)
                when (k) {
                    MOCHI -> p.lift = abs(sin(phase * PI_F)) * rad * 0.35f
                    BO -> p.squash *= 1f + 0.08f * sin(phase * PI_F * 2f)
                    SODA -> {
                        p.lift = abs(sin(phase * PI_F * 0.5f)) * rad * 0.15f
                        p.rotation = sin(phase * PI_F * 0.5f) * 6f
                        p.sparkle = 0.3f
                    }

                    else -> {
                        p.lift = abs(sin(phase * PI_F * 1.2f)) * rad * 0.6f
                        p.squash *= if (p.lift < rad * 0.05f) 0.8f else 1.12f
                    }
                }
                p.mood = Mood.Happy
                p.armL = 30f + 15f * sin(phase * PI_F)
                p.armR = 30f - 15f * sin(phase * PI_F)
            }

            RoamMode.Idle -> when (s.idleAct) {
                0 -> {
                    p.sleep = 1f
                    p.snore = 0.4f + 0.2f * sin(t * 2.4f)
                }

                1 -> {
                    // Looks at you.
                    p.lookX = 0f
                    p.lookY = 0.3f
                    p.mood = Mood.Happy
                }

                2 -> {
                    p.armR = 120f + 35f * sin(t * 12f)
                    p.mood = Mood.Happy
                }

                3 -> {
                    p.whistle = 1f
                    p.lookY = -1f
                }

                5 -> {
                    // On guard over the root list.
                    p.akimbo = 1f
                    p.lookY = 0.4f
                    p.lookX = sin(t * 0.9f) * 0.7f
                    p.anger = 0.3f
                }

                6 -> {
                    // Leaning over the edge to peek.
                    p.rotation = s.dir * 16f
                    p.lookY = 0.9f
                    p.lookX = s.dir * 0.6f
                    p.mood = Mood.Surprised
                }

                7 -> {
                    // Fiddling with something.
                    p.armR = 100f + 40f * sin(t * 16f)
                    p.armL = 60f + 30f * sin(t * 13f + 1f)
                    p.mood = Mood.Happy
                    p.lookY = 0.6f
                    p.sparkle = 0.4f
                }

                else -> p.lookX = sin(t * 1.4f)
            }

            RoamMode.Hop -> {
                val e = (s.modeT / s.hopDur).coerceIn(0f, 1f)
                p.squash = if (e < 0.15f) 0.8f else 1.12f
                p.armL = 140f
                p.armR = 140f
                p.mood = Mood.Happy
                if (k == SODA) p.rotation = 360f * easeInOutCubic(e) * s.dir
            }

            RoamMode.Fall -> {
                p.mood = Mood.Surprised
                p.armL = 150f
                p.armR = 150f
                p.squash = 1.08f
            }

            RoamMode.Thrown -> {
                p.mood = if (s.reaction == Reaction.Flee) Mood.Worried else Mood.Dizzy
                p.dizzy = 1f
                p.rotation = s.modeT * 540f * sign(s.vx + 0.01f)
                p.armL = 160f
                p.armR = 160f
                if (s.reaction == Reaction.Flee) {
                    p.sweat = 1f
                    p.rotation = 0f
                }
            }

            RoamMode.Held -> {
                p.mood = Mood.Surprised
                p.armL = 160f + 10f * sin(t * 18f)
                p.armR = 160f - 10f * sin(t * 18f)
                p.rotation = sin(t * 5f) * 10f
                p.squash = 1.15f
                p.sweat = 0.6f
            }

            RoamMode.Climb -> {
                p.mood = Mood.Normal
                p.armL = 90f + 30f * sin(t * 10f)
                p.armR = 90f - 30f * sin(t * 10f)
                p.lookX = 0.8f
            }

            RoamMode.Splat -> {
                p.mood = Mood.Squeeze
                p.squash = 0.55f
                p.armL = 120f
                p.armR = 120f
            }

            RoamMode.React -> reactPose(s, p)
            RoamMode.Home -> {
                if (s.hopTo === DOOR) {
                    val e = ((s.modeT - s.hopStart) / s.hopDur).coerceIn(0f, 1f)
                    p.mood = Mood.Happy
                    p.armL = 150f
                    p.armR = 150f
                    p.squash = if (e < 0.12f) 0.82f else 1.1f
                    // Growing back to the size they are in the box on the way to its door.
                    val big = (SlimeHome.doorR / r).takeIf { it > 0f } ?: 1f
                    p.scale *= lerp(1f, big, easeInOutCubic(e))
                } else {
                    p.lookX = 0f
                    p.lookY = -0.6f
                    p.mood = Mood.Happy
                }
            }

            RoamMode.Social -> social.pose(s, p)
            RoamMode.Laser -> {
                val dot = laser
                val rad = radius(k)
                val phase = t * 15f
                p.lift = abs(sin(phase)) * rad * 0.25f
                p.armL = 40f + 25f * sin(phase)
                p.armR = 40f - 25f * sin(phase)
                p.mood = Mood.Happy
                p.sparkle = 0.3f
                if (dot != null) {
                    p.lookX = ((dot.x - s.x) / (rad * 3f)).coerceIn(-1f, 1f)
                    p.lookY = -((s.y - dot.y) / (rad * 4f)).coerceIn(0f, 1f)
                }
                // The pounce: a wiggle, then up at the dot with both arms.
                val e = now - s.pounceAt
                if (e in 0f..0.55f) {
                    val f = e / 0.55f
                    p.lift = sin(PI_F * f) * rad * 2.6f
                    p.armL = 172f
                    p.armR = 172f
                    p.mood = Mood.Surprised
                    p.squash = if (f < 0.12f) 0.8f else 1.12f
                }
            }

            RoamMode.Away -> {}
        }
        if (night && s.duet == null && (s.mode == RoamMode.Walk || s.mode == RoamMode.Idle && s.idleAct != 0)) {
            // Droopy-eyed.
            p.sleep = max(p.sleep, 0.45f)
        }
        if (s.mode == RoamMode.Walk && now < s.boostUntil) {
            // Legging it.
            p.lift *= 1.5f
            p.sweat = if (s.duet != null) 0.6f else 0f
            if (s.k == CHANH) p.mood = Mood.Happy
        }
        // A hat knocked off flies up, spins and drops back on.
        val pop = now - s.hatPop
        if (pop in 0f..1.4f) {
            val e = pop / 1.4f
            p.hatLift += sin(PI_F * e) * radius(k) * 3f
            p.hatTilt += 720f * easeInOutCubic(e)
        }
        p.visible = !social.hidden(s) && s.mode != RoamMode.Away && !(s.mode == RoamMode.Home && s.hopTo !== DOOR && (s.x < 0f || s.x > w)) && !(s.mode == RoamMode.React && s.reaction == Reaction.Explode && s.modeT in 0.12f..1.35f)
        if (s.grow != 1f) {
            val e = progress(now - s.growAt, 0.1f, 0.8f)
            p.scale *= lerp(s.grow, 1f, easeInOutCubic(e))
            if (e >= 1f) s.grow = 1f
        }
    }

    private fun reactPose(s: Roamer, p: SlimePose) {
        val e = s.modeT
        val rad = radius(s.k)
        when (s.reaction) {
            Reaction.Wiggle -> {
                p.mood = Mood.Surprised
                p.jiggle = 1f - e / 0.9f
                p.squash = 1f + 0.18f * sin(e * 30f) * (1f - e / 0.9f)
                p.lookX = 0f
                p.lookY = 0.3f
            }

            Reaction.Huff -> {
                p.mood = Mood.Angry
                p.akimbo = 1f
                p.anger = 1f
                p.steam = progress(e, 0.2f, 0.3f)
                p.flush = progress(e, 0f, 0.5f) * 0.6f
                p.lookX = 0f
                p.lookY = 0.2f
                if (s.k == BO) p.lift = abs(sin(PI_F * progress(e, 0f, 0.5f))) * rad * 0.8f
            }

            Reaction.Explode -> {
                if (e < 0.12f) {
                    p.scale = 1f + 2.5f * e
                    p.mood = Mood.Squeeze
                } else if (e > 1.35f) {
                    val pop = progress(e, 1.35f, 0.25f)
                    p.scale = easeOutBack(pop, 2.5f)
                    p.mood = Mood.Dizzy
                    p.dizzy = 1f
                    p.jiggle = 1f
                }
            }

            Reaction.Melt -> {
                p.melt = when {
                    e < 0.4f -> progress(e, 0f, 0.4f)
                    e < 1.1f -> 1f
                    else -> 1f - progress(e, 1.1f, 0.6f)
                }
                p.mood = if (e < 1.1f) Mood.Happy else Mood.Normal
                p.squash = 1f - 0.45f * p.melt
            }

            Reaction.Flip -> {
                val f = progress(e, 0f, 0.8f)
                p.lift = sin(PI_F * f) * rad * 2.2f
                p.rotation = 360f * easeInOutCubic(f) * s.dir
                p.mood = Mood.Happy
                p.sparkle = 1f
                p.armL = 160f
                p.armR = 160f
            }

            Reaction.Flee -> {}
        }
    }
}
