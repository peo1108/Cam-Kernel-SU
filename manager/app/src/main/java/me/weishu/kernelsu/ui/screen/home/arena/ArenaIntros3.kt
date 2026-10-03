package me.weishu.kernelsu.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Binary: con bọ trong máy
// ---------------------------------------------------------------------------------------------

/**
 * Bơ picks up the baguette that came from Paris and plugs it into his laptop like a USB stick:
 * "ting!". He types away, every key popping out a little digit. Chanh the bug crawls across the
 * floor leaving static, and the whole picture glitches. Soda's visor flashes red, "BUG.
 * DETECTED.", and she does a robot victory dance. Chanh fires strings of 0s and 1s at Mochi, who
 * leans back to dodge in slow motion... too far, and lands flat. Bơ slams Delete: Chanh hops
 * aside and half of Soda turns to pixels, which she frantically gathers back. Bơ hits the
 * keyboard so hard the Enter key flies off (it will turn up on a ship), and they all chase the bug.
 */
internal object BinaryIntro : SceneIntro {
    private const val PICK = 0.2f
    private const val PLUG = 0.8f
    private const val TING = 1.1f
    private const val TYPE = 1.4f
    private const val CRAWL = 2.4f
    private const val DETECTED = 4.0f
    private const val DANCE = 4.5f
    private val darts = floatArrayOf(5.1f, 5.4f, 5.7f)
    private const val DODGE = 5.1f
    private const val FLAT = 6.3f
    private const val DELETE = 6.6f
    private const val PIXELS = 6.9f
    private const val MEND = 7.6f
    private const val ENTER = 8.0f
    private const val CHASE = 8.2f
    private const val CLOUD = 8.8f
    override val seconds = 9.6f
    override val taps = floatArrayOf(TING, DETECTED, darts[0], darts[1], darts[2], ENTER)
    override val thumps = floatArrayOf(FLAT, DELETE)

    private fun laptop(c: IntroCtx) = c.near(BO, 0f, c.radius(BO) * 0.55f) + Offset(0f, 0f)

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val bo = p[BO]
        val ch = p[CHANH]
        val so = p[SODA]
        val mo = p[MOCHI]
        // Bơ: pick up the baguette, plug it in, type.
        bo.lookY = 0.7f
        if (t > PICK && t < TING) {
            bo.armR = lerp(18f, 110f, c.win(PICK, 0.3f))
            bo.reachR = 1.3f
        }
        if (t > TING && t < TING + 0.4f) {
            bo.mood = Mood.Happy
            bo.lift += c.hop(TING, 0.3f, r * 0.2f)
        }
        if (t > TYPE && t < DELETE) {
            val fast = ((t - TYPE) * 12f).toInt()
            bo.armL = if (fast % 2 == 0) 70f else 85f
            bo.armR = if (fast % 2 == 0) 85f else 70f
            bo.squash = 1f + 0.02f * sin(c.now * 40f)
            bo.mood = Mood.Normal
        }
        // Chanh, the bug, crawls across the floor.
        if (t > CRAWL && t < DELETE + 0.3f) {
            val e = c.win(CRAWL, DETECTED - CRAWL + 0.4f)
            ch.x = lerp(c.home(CHANH), c.home(MOCHI) + c.radius(MOCHI), easeInOutCubic(e))
            ch.depth = lerp(c.depth(CHANH), c.depth(MOCHI) + 1.2f, e)
            ch.squash = 0.75f + 0.08f * sin(c.now * 30f)
            ch.rotation = 8f * sin(c.now * 20f)
            ch.mood = Mood.Happy
            ch.lookX = -1f
        }
        // Soda: BUG DETECTED, then the robot dance.
        if (t > DETECTED && t < DELETE) {
            so.mood = Mood.Angry
            so.armR = so.aimAt(1, c.head(CHANH), c.radius(SODA), c.stage.groundY, c.now)
            so.reachR = 1.3f
            if (t > DANCE) {
                val step = ((t - DANCE) * 4f).toInt()
                so.rotation = if (step % 2 == 0) -15f else 15f
                so.armL = if (step % 2 == 0) 90f else 170f
                so.armR = if (step % 2 == 0) 170f else 90f
                so.lift += if (step % 4 == 0) r * 0.2f else 0f
                so.mood = Mood.Happy
            }
        }
        // Chanh's digit darts; Mochi's slow-motion dodge, too far.
        if (t > darts[0] - 0.2f && t < FLAT) {
            ch.armL = ch.aimAt(-1, c.head(MOCHI), c.radius(CHANH), c.stage.groundY, c.now)
            ch.reachL = 1.3f
        }
        if (t > DODGE) {
            val lean = c.win(DODGE, FLAT - DODGE)
            mo.rotation = 95f * easeInCubic(lean)
            mo.mood = if (t < FLAT) Mood.Surprised else Mood.Dizzy
            mo.armL = 150f
            mo.armR = 150f
            if (t > FLAT) {
                mo.rotation = 95f
                mo.dizzy = 1f
                c.shake = r * 0.1f * exp(-(t - FLAT) * 8f)
            }
        }
        // Delete: Chanh hops away, Soda pixelates and reassembles.
        if (t > DELETE - 0.3f && t < DELETE + 0.2f) {
            bo.armR = 160f
            bo.reachR = 1.4f
            bo.mood = Mood.Angry
        }
        if (t > DELETE) {
            ch.lift += c.hop(DELETE + 0.1f, 0.4f, r * 0.9f)
            ch.mood = Mood.Happy
            ch.whistle = c.win(DELETE + 0.5f, 0.2f)
        }
        if (t > PIXELS && t < MEND + 0.3f) so.visible = false
        if (t > MEND + 0.3f) {
            so.mood = Mood.Surprised
            so.sweat = 1f
            so.jiggle = 1f - c.win(MEND + 0.3f, 0.5f)
        }
        if (t > ENTER - 0.2f && t < ENTER + 0.2f) {
            bo.armL = 160f
            bo.armR = 160f
            bo.squash = 0.85f
            bo.mood = Mood.Angry
        }
        if (t > CHASE) for (k in intArrayOf(BO, MOCHI, SODA)) {
            p[k].mood = Mood.Angry
            p[k].lookX = sign(ch.x - p[k].x)
        }
        c.pileIn(CHASE, 0.8f)
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        // The baguette from Paris, plugged into the laptop.
        val rest = c.floor(c.home(BO) + c.radius(BO) * 1.25f, c.depth(BO) + 0.8f) + Offset(0f, -r * 0.12f)
        val held = c.hand(BO, 1)
        val socket = laptop(c) + Offset(c.radius(BO) * 0.55f * c.vs(BO), 0f)
        val bag = when {
            t < PICK -> rest
            t < PLUG -> lerp(rest, held, easeInOutCubic(c.win(PICK, PLUG - PICK)))
            else -> lerp(held, socket + Offset(r * 0.6f, 0f), easeInOutCubic(c.win(PLUG, TING - PLUG)))
        }
        if (t < CLOUD) drawBaguette(bag, r * 1.4f * c.vs(BO), if (t < PLUG) -8f else 0f)
        comic(c, "ting!", socket + Offset(0f, -r * 0.5f), TING, 0.6f)
        if (t > TING && t < TING + 0.4f) drawGlow(laptop(c), r * 1.2f, Color(0xFF39FF88), 1f - c.win(TING, 0.4f))
        // Typed digits floating up.
        val zero = c.zero
        val one = c.one
        if (t > TYPE && t < DELETE && zero != null && one != null) repeat(8) { i ->
            val e = ((t - TYPE) * 1.5f + i / 8f) % 1f
            val g = if (hash(i + floor((t - TYPE) * 1.5f).toInt() * 13) > 0.5f) one else zero
            val at = laptop(c) + Offset((hash(i + 50) - 0.5f) * r * 1.2f, -r * 0.3f - e * r * 1.4f)
            drawText(g, topLeft = at - Offset(g.size.width / 2f, g.size.height / 2f), alpha = 1f - e)
        }
        // Static trail behind the crawling bug, and the glitching picture.
        if (t > CRAWL && t < DELETE) {
            repeat(10) { i ->
                val u = (c.win(CRAWL, DETECTED - CRAWL + 0.4f) - i * 0.06f).coerceIn(0f, 1f)
                val x = lerp(c.home(CHANH), c.home(MOCHI) + c.radius(MOCHI), easeInOutCubic(u))
                val z = lerp(c.depth(CHANH), c.depth(MOCHI) + 1.2f, u)
                val at = c.floor(x, z)
                drawRect(Color(0xFF39FF88), at + Offset((hash(i * 3) - 0.5f) * r * 0.3f, -r * 0.1f), androidx.compose.ui.geometry.Size(r * 0.1f, r * 0.06f), alpha = 0.7f)
            }
        }
        drawGlitch(c.now, c.win(CRAWL + 0.4f, 0.6f) * (1f - c.win(DETECTED, 0.5f)) + c.win(DELETE, 0.2f) * (1f - c.win(DELETE + 0.4f, 0.4f)))
        // Soda's visor flashing red.
        if (t > DETECTED && t < DANCE) {
            val on = ((t - DETECTED) * 8f).toInt() % 2 == 0
            if (on) drawGlow(c.near(SODA, 0f, c.bodyH(SODA) * 0.5f), r * 0.9f, Color(0xFFFF2E4D), 0.9f)
        }
        bubble(c, SODA, "BUG. DETECTED.", DETECTED, 0.8f)
        if (zero != null && one != null) for (at in darts) drawDigitDart(c.hand(CHANH, -1), c.head(MOCHI) + Offset(-r * 0.8f, 0f), c.win(at, 0.45f), zero, one)
        comic(c, "BỊCH", c.base(MOCHI) + Offset(0f, -r * 0.6f), FLAT, 0.4f)
        comic(c, "DELETE", laptop(c) + Offset(0f, -r * 0.8f), DELETE, 0.6f, 4f)
        // Half of Soda in pixels, then gathered back.
        if (t > PIXELS - 0.1f && t < MEND + 0.4f) {
            val scatter = if (t < MEND) easeOutCubic(c.win(PIXELS - 0.1f, 0.4f)) else 1f - easeInOutCubic(c.win(MEND, 0.35f))
            val w = c.radius(SODA) * 1.96f * c.vs(SODA)
            drawPixels(c.base(SODA), w, c.bodyH(SODA), Cast[SODA].body, scatter, 1)
        }
        // The Enter key popping off the keyboard.
        if (t > ENTER && t < CLOUD + 0.4f) {
            val e = c.win(ENTER, 1f)
            drawEnterKey(arc(laptop(c), Offset(c.stage.w * 1.05f, c.stage.h * 0.2f), c.stage.h * 0.4f, e), r * 0.3f, e * 720f)
        }
        comic(c, "BỐP!", laptop(c) + Offset(-r * 0.6f, -r * 0.5f), ENTER, 0.4f)
        cloudIn(c, CLOUD)
    }
}

// ---------------------------------------------------------------------------------------------
// Morse: hiểu lầm tín hiệu
// ---------------------------------------------------------------------------------------------

/**
 * The Enter key from the hacker's keyboard lands on deck; Chanh, the telegraph operator, mounts
 * it as his key and taps "tít tít te", his antenna blinking. Below, Soda the diver reads it all
 * wrong and bursts out of the hatch like a dolphin: "TẤN CÔNG!". Captain Mochi bellows "BÌNH
 * TĨNH!!!" into her megaphone so loud the whole ship tilts. Bơ, flying overhead with goggles and a
 * propeller, gets caught by the radar beam, loops in a panic and lands on the deck with a thud.
 * Chanh taps one more message and Soda reads it out: "LÀ. DO. MOCHI." Mochi charges, megaphone
 * and all.
 */
internal object MorseIntro : SceneIntro {
    private const val PICK = 0.2f
    private val taps0 = floatArrayOf(0.9f, 1.05f, 1.3f)
    private const val SURFACE = 1.7f
    private const val ATTACK = 2.1f
    private const val SHOUT = 2.9f
    private const val TILT = 3.1f
    private const val BEAM = 4.4f
    private const val CAUGHT = 4.75f
    private const val THUD = 5.5f
    private val taps1 = floatArrayOf(5.9f, 6.05f, 6.3f, 6.45f)
    private const val READ = 6.7f
    private const val CHARGE = 7.6f
    private const val CLOUD = 8.3f
    override val seconds = 9.2f
    override val taps = taps0 + taps1 + floatArrayOf(ATTACK)
    override val thumps = floatArrayOf(SURFACE, SHOUT, THUD)

    private fun keyAt(c: IntroCtx) = c.floor(c.home(CHANH) + c.radius(CHANH) * 1.6f, c.depth(CHANH) + 0.5f)
    private fun hatchAt(c: IntroCtx) = c.floor(c.home(SODA), c.depth(SODA))

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val ch = p[CHANH]
        val so = p[SODA]
        val mo = p[MOCHI]
        val bo = p[BO]
        // Chanh taps out his message.
        ch.lookY = 0.5f
        ch.lookX = 1f
        for (at in taps0 + taps1) {
            val e = c.win(at - 0.05f, 0.12f)
            if (e > 0f && e < 1f) {
                ch.armR = 60f + 20f * sin(PI_F * e)
                ch.squash = 1f - 0.05f * sin(PI_F * e)
            }
        }
        // Soda below deck, then out of the hatch like a dolphin.
        if (t < SURFACE) {
            so.lift = -c.bodyH(SODA) * 1.3f
        } else {
            val e = c.win(SURFACE, 0.8f)
            so.lift = lerp(-c.bodyH(SODA) * 1.3f, 0f, e) + sin(PI_F * e) * r * 2.2f
            so.rotation = 360f * easeOutCubic(e)
            so.mood = Mood.Angry
            so.armL = 160f
            so.armR = 160f
            if (e >= 1f) {
                so.rotation = 0f
                so.armL = 120f + 30f * sin(c.now * 8f)
                so.armR = 120f + 30f * cos(c.now * 8f)
            }
        }
        // Captain Mochi's megaphone, and the ship rolling with it.
        mo.armR = 100f
        mo.reachR = 1.2f
        if (t > SHOUT && t < BEAM) {
            mo.mood = Mood.Angry
            mo.mouthOpen = 1f
            mo.squash = 1f + 0.12f * sin(PI_F * c.win(SHOUT, 0.4f))
        }
        // The deck lurches: everyone slides and leans (the box itself stays put; rolling the camera
        // would swing the room's edges into view).
        val roll = sin(PI_F * c.win(TILT, 1.0f)) * -6f
        if (abs(roll) > 0.1f) for (k in 0 until SLIME_COUNT) {
            if (k == BO) continue
            p[k].x += roll * r * 0.12f
            p[k].rotation += roll * 0.6f
            if (p[k].mood == Mood.Normal) p[k].mood = Mood.Surprised
        }
        // Bơ flying overhead, then caught by the radar and down on deck.
        if (t < CAUGHT) {
            bo.lift = r * 2.4f + sin(c.now * 2f) * r * 0.2f
            bo.x = c.home(BO) + sin(c.now * 0.8f) * r * 1.2f
            bo.armL = 90f
            bo.armR = 90f
            bo.reachL = 1.3f
            bo.reachR = 1.3f
            bo.rotation = sin(c.now * 1.6f) * 8f
            bo.mood = Mood.Happy
        } else {
            val e = c.win(CAUGHT, THUD - CAUGHT)
            bo.x = c.home(BO) + sin(CAUGHT * 0.8f) * r * 1.2f * (1f - e)
            bo.lift = r * 2.4f * (1f - easeInCubic(e)) + sin(PI_F * e) * r * 0.6f
            bo.rotation = 720f * easeInOutCubic(e)
            bo.mood = if (t < THUD) Mood.Surprised else Mood.Dizzy
            bo.armL = 150f
            bo.armR = 150f
            if (t > THUD) {
                bo.rotation = 0f
                bo.dizzy = 1f - c.win(THUD + 0.5f, 0.5f)
                bo.squash = 1f - 0.25f * sin(PI_F * c.win(THUD, 0.3f))
                c.shake = r * 0.25f * exp(-(t - THUD) * 6f)
            }
        }
        // The fateful message.
        if (t > READ && t < CHARGE) {
            so.mood = Mood.Normal
            so.mouthOpen = 0.3f + 0.3f * abs(sin(c.now * 12f))
            mo.mood = Mood.Angry
            mo.flush = c.win(READ + 0.3f, 0.6f)
            mo.steam = c.win(READ + 0.6f, 0.3f)
            ch.whistle = 1f
            ch.lookY = -1f
        }
        c.pileIn(CHARGE, 0.8f)
    }

    override fun DrawScope.back(c: IntroCtx) {
        val t = c.t
        // The hatch Soda pops out of.
        if (t < CLOUD) drawHatch(hatchAt(c), c.radius(SODA) * 1.1f * c.scaleAt(c.depth(SODA)), c.win(SURFACE - 0.1f, 0.2f))
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        // The Enter key: lands, becomes Chanh's telegraph key.
        val key = keyAt(c)
        val ks = r * 0.5f * c.scaleAt(c.depth(CHANH) + 0.5f)
        if (t < CLOUD) {
            if (t < PICK + 0.3f) {
                drawEnterKey(key + Offset(0f, -ks * 0.4f), ks * 0.8f, 0f)
            } else {
                val pressed = (taps0 + taps1).any { t > it - 0.05f && t < it + 0.07f }
                drawTelegraphKey(key, ks, pressed)
            }
        }
        // Dots and dashes floating up, the antenna blinking with them.
        for ((i, at) in (taps0 + taps1).withIndex()) {
            val e = c.win(at, 1.0f)
            if (e > 0f && e < 1f) {
                val dash = i == 2 || i == 5
                val pos = key + Offset((i % 3 - 1) * r * 0.3f, -r * 0.6f - e * r * 1.2f)
                if (dash) drawLine(Color(0xFF7DF9FF), pos - Offset(r * 0.12f, 0f), pos + Offset(r * 0.12f, 0f), r * 0.07f, StrokeCap.Round, alpha = 1f - e)
                else drawCircle(Color(0xFF7DF9FF), r * 0.05f, pos, alpha = 1f - e)
            }
            if (t > at - 0.05f && t < at + 0.1f) drawGlow(c.head(CHANH) + Offset(0f, -c.radius(CHANH) * 0.5f), r * 0.5f, Color(0xFFFF5252), 1f)
        }
        // Splash out of the hatch.
        drawSplash(hatchAt(c), r * 0.9f, c.win(SURFACE, 0.6f))
        bubble(c, SODA, "TẤN CÔNG!", ATTACK, 0.8f)
        // The megaphone blast.
        bubble(c, MOCHI, "BÌNH TĨNH!!!", SHOUT, 0.9f)
        if (t > SHOUT && t < BEAM) repeat(4) { i ->
            val e = ((t - SHOUT) * 2f + i / 4f) % 1f
            val from = c.hand(MOCHI, 1)
            drawArc(Color.White, -40f, 80f, false, from - Offset(r * (0.2f + e * 1.6f), r * (0.2f + e * 1.6f)), androidx.compose.ui.geometry.Size(r * (0.4f + e * 3.2f), r * (0.4f + e * 3.2f)), alpha = 0.6f * (1f - e), style = Stroke(r * 0.05f))
        }
        // Bơ's propeller, the radar beam catching him.
        if (t < THUD) drawPropeller(c.head(BO) + Offset(0f, -r * 0.15f), r * 0.5f * c.vs(BO), c.now)
        if (t > BEAM - 0.4f && t < CAUGHT + 0.6f) {
            val origin = Offset(c.stage.w * 0.86f, c.stage.h * 0.22f)
            val target = c.head(BO)
            val aim = kotlin.math.atan2(target.y - origin.y, target.x - origin.x)
            val sweep = lerp(aim + 0.9f, aim, easeOutCubic(c.win(BEAM - 0.4f, CAUGHT - BEAM + 0.4f)))
            drawRadarBeam(origin, sweep, c.stage.w * 0.7f, 1f - c.win(CAUGHT + 0.2f, 0.4f))
        }
        comic(c, "BÍP!", c.head(BO) + Offset(r * 0.6f, -r * 0.4f), CAUGHT, 0.4f)
        comic(c, "BỊCH!", c.base(BO) + Offset(0f, -r * 0.7f), THUD, 0.5f)
        bubble(c, SODA, "LÀ. DO. MOCHI.", READ, 0.9f)
        cloudIn(c, CLOUD)
        if (t > CLOUD + 0.2f) comic(c, "tít te", c.stage.brawl + Offset(c.r * 1.6f, -c.r * 1.1f), CLOUD + 0.2f, 0.6f, 10f)
    }
}

// ---------------------------------------------------------------------------------------------
// Cats: chấm laser
// ---------------------------------------------------------------------------------------------

/**
 * Mochi and Chanh play tug-of-war with a ball of yarn until they are wound into one fluffy ball
 * with two tails sticking out. Bơ the lion finds the captain's megaphone that blew in from the
 * ship, breathes in, mane bristling, and roars... "meo~". Silence; a cricket chirps. Soda waves her
 * lucky paw faster and faster until it spins like a propeller and she lifts off. Then the red dot
 * appears: everyone freezes, pupils huge, bottoms wiggling... and they are off after it, up the
 * wall, over the ceiling, down again, until it stops dead in the middle and they crash into each
 * other. The dot creeps slowly up... onto Chanh, who was holding the laser all along. He points
 * at... the pointer in his own paw.
 */
internal object CatIntro : SceneIntro {
    private const val TUG = 0.2f
    private const val TANGLE = 1.7f
    private const val GRAB = 2.0f
    private const val BREATH = 2.5f
    private const val ROAR = 3.0f
    private const val CRICKET = 3.4f
    private const val WAVE = 3.9f
    private const val LIFTOFF = 4.8f
    private const val DOT = 5.3f
    private const val CHASE = 5.9f
    private const val CRASH = 7.6f
    private const val REVEAL = 8.0f
    private const val POINT = 8.8f
    private const val CLOUD = 9.2f
    override val seconds = 10.0f
    override val taps = floatArrayOf(TANGLE, GRAB, CRICKET, DOT)
    override val thumps = floatArrayOf(ROAR, CRASH)

    /** Where the laser dot is on screen. */
    private fun dot(c: IntroCtx): Offset {
        val t = c.t
        val s = c.stage
        val floorY = c.floor(s.center, 0.5f).y
        return when {
            t < CHASE -> Offset(s.w * 0.62f + sin(c.now * 7f) * c.r * 0.1f, floorY - c.r * 0.2f)
            t < CRASH -> {
                // Floor, up the left wall, over the ceiling, down the right, back to the middle.
                val u = (t - CHASE) / (CRASH - CHASE)
                val pts = listOf(
                    Offset(s.w * 0.62f, floorY), Offset(s.w * 0.2f, floorY), Offset(s.w * 0.12f, s.h * 0.2f),
                    Offset(s.w * 0.5f, s.h * 0.06f), Offset(s.w * 0.86f, s.h * 0.2f), Offset(s.w * 0.75f, floorY), Offset(s.center, floorY),
                )
                val f = u * (pts.size - 1)
                val i = floor(f).toInt().coerceAtMost(pts.size - 2)
                lerp(pts[i], pts[i + 1], easeInOutCubic(f - i))
            }

            t < REVEAL -> Offset(s.center, floorY)
            else -> lerp(Offset(s.center, floorY), c.head(CHANH) + Offset(0f, c.bodyH(CHANH) * 0.5f), easeInOutCubic(c.win(REVEAL, 0.6f)))
        }
    }

    override fun pose(c: IntroCtx) {
        val t = c.t
        val p = c.poses
        val r = c.r
        val mo = p[MOCHI]
        val ch = p[CHANH]
        val bo = p[BO]
        val so = p[SODA]
        val knot = lerp(c.home(MOCHI), c.home(CHANH), 0.5f)
        val knotZ = lerp(c.depth(MOCHI), c.depth(CHANH), 0.5f)
        // The tug of war, then wound up together.
        if (t > TUG && t < DOT) {
            val pull = sin((t - TUG) * 5f)
            for ((k, side) in listOf(MOCHI to 1f, CHANH to -1f)) {
                val e = c.win(TANGLE - 0.5f, 0.5f)
                p[k].x = lerp(c.home(k) + side * pull * r * 0.2f, knot, easeInCubic(e))
                p[k].depth = lerp(c.depth(k), knotZ, e)
                p[k].rotation = -side * 15f * pull
                p[k].mood = Mood.Angry
                p[k].lookX = side
                if (side > 0) p[k].armR = 95f else p[k].armL = 95f
                if (t > TANGLE) p[k].visible = false
            }
        }
        // Bơ, the megaphone, the breath, the roar that comes out as a mew.
        if (t > GRAB - 0.3f) {
            bo.armR = lerp(18f, 110f, c.win(GRAB - 0.3f, 0.3f))
            bo.reachR = 1.2f
        }
        if (t > BREATH && t < ROAR) {
            bo.scale = 1f + 0.18f * c.win(BREATH, ROAR - BREATH)
            bo.jiggle = 0.5f
            bo.mood = Mood.Squeeze
        }
        if (t > ROAR && t < WAVE) {
            bo.mouthOpen = if (t < ROAR + 0.6f) 0.25f else 0f
            bo.mood = if (t < ROAR + 0.6f) Mood.Normal else Mood.Worried
            bo.sweat = c.win(ROAR + 0.5f, 0.3f)
            so.lookX = sign(bo.x - so.x)
        }
        // Soda's paw: faster and faster, then a propeller.
        if (t > WAVE && t < DOT) {
            val speed = 4f + 30f * c.win(WAVE, LIFTOFF - WAVE)
            so.armR = 120f + 40f * sin((t - WAVE) * speed)
            so.mood = Mood.Happy
            if (t > LIFTOFF) {
                so.lift = c.win(LIFTOFF, 0.5f) * r * 1.4f + sin(c.now * 6f) * r * 0.1f
                so.armR = 170f
                so.rotation = sin(c.now * 30f) * 5f
                so.mood = Mood.Surprised
            }
        }
        // The dot: freeze, wiggle, chase.
        if (t > DOT && t < CHASE) {
            for (k in 0 until SLIME_COUNT) p[k].apply {
                visible = true
                mood = Mood.Surprised
                lookX = sign(dot(c).x - x)
                lookY = 0.6f
                rotation = sin(c.now * 40f + k) * 7f * c.win(DOT + 0.2f, 0.2f)
                squash = 0.9f
            }
            so.lift = (1f - c.win(DOT, 0.3f)) * r * 1.4f
            mo.x = knot - r * 0.6f
            ch.x = knot + r * 0.6f
            mo.depth = knotZ
            ch.depth = knotZ
        }
        if (t > CHASE && t < CRASH + 0.3f) {
            val d = dot(c)
            for (k in 0 until SLIME_COUNT) p[k].apply {
                visible = true
                val lag = 0.12f + k * 0.07f
                val past = c.t - lag
                val trail = if (past > CHASE) dotAt(c, past) else d
                // Screen point back to a stage x and a height above the floor.
                x = trail.x
                depth = 0f
                lift = (c.stage.groundY - trail.y).coerceAtLeast(0f)
                rotation = when {
                    trail.y < c.stage.h * 0.25f -> 180f
                    trail.x < c.stage.w * 0.18f -> 90f
                    trail.x > c.stage.w * 0.82f -> -90f
                    else -> 0f
                }
                mood = Mood.Happy
                lookX = sign(d.x - trail.x)
            }
        }
        if (t > CRASH && t < REVEAL) {
            for (k in 0 until SLIME_COUNT) p[k].apply {
                visible = true
                x = c.stage.center + (k - 1.5f) * r * 0.45f
                depth = 0f
                lift = 0f
                rotation = (k - 1.5f) * 20f * (1f - c.win(CRASH + 0.3f, 0.3f))
                mood = Mood.Dizzy
                dizzy = 1f
                c.shake = r * 0.2f * exp(-(t - CRASH) * 7f)
            }
        }
        if (t > REVEAL) {
            for (k in 0 until SLIME_COUNT) p[k].apply {
                visible = true
                x = c.stage.center + (k - 1.5f) * r * 0.9f
                depth = 0f
                if (k != CHANH) {
                    mood = if (t > POINT) Mood.Angry else Mood.Surprised
                    lookX = sign(p[CHANH].x - x)
                }
            }
            ch.armR = 80f
            ch.mood = Mood.Surprised
            ch.lookY = 0.6f
            if (t > POINT) {
                ch.armL = ch.aimAt(-1, c.hand(CHANH, 1), c.radius(CHANH), c.stage.groundY, c.now)
                ch.reachL = 1.2f
                ch.mood = Mood.Normal
                ch.whistle = 1f
            }
        }
        c.pileIn(CLOUD - 0.3f, 0.7f)
    }

    private fun dotAt(c: IntroCtx, at: Float): Offset {
        val saved = c.t
        c.t = at
        val d = dot(c)
        c.t = saved
        return d
    }

    override fun DrawScope.front(c: IntroCtx) {
        val t = c.t
        val r = c.r
        // The yarn, then the two of them wound into one ball with two tails.
        if (t > TUG && t < TANGLE) {
            val a = c.hand(MOCHI, 1)
            val b = c.hand(CHANH, -1)
            drawLine(Color(0xFFFF8FB3), a, b, r * 0.04f)
            drawYarn(lerp(a, b, 0.5f) + Offset(0f, r * 0.2f), r * 0.3f, Color(0xFFFF8FB3), t * 200f)
        }
        if (t > TANGLE && t < DOT + 0.2f) {
            val at = c.point(lerp(c.home(MOCHI), c.home(CHANH), 0.5f), r * 0.9f, lerp(c.depth(MOCHI), c.depth(CHANH), 0.5f))
            val rr = r * 1.05f * c.scaleAt(lerp(c.depth(MOCHI), c.depth(CHANH), 0.5f))
            drawYarn(at, rr, Color(0xFFFF8FB3), c.now * 40f)
            for ((i, col) in listOf(Cast[MOCHI].body, Cast[CHANH].body).withIndex()) {
                val side = if (i == 0) -1f else 1f
                val base = at + Offset(side * rr * 0.8f, rr * 0.4f)
                val tip = base + Offset(side * rr * (0.5f + 0.15f * sin(c.now * 8f + i)), -rr * 0.4f)
                drawLine(col, base, tip, rr * 0.14f, StrokeCap.Round)
            }
            if (t > DOT) drawSmokePuff(at, rr, c.win(DOT, 0.3f))
        }
        // The megaphone from the ship.
        val rest = c.floor(c.home(BO) + c.radius(BO) * 1.3f, c.depth(BO) + 0.9f) + Offset(0f, -r * 0.28f)
        if (t < CHASE) drawMegaphone(if (t < GRAB) rest else lerp(rest, c.hand(BO, 1), c.win(GRAB - 0.3f, 0.3f)), r * 0.6f * c.vs(BO), if (t < GRAB) 200f else 160f)
        bubble(c, BO, "meo~", ROAR, 0.7f)
        comic(c, "cri... cri...", Offset(c.stage.w * 0.86f, c.stage.h * 0.8f), CRICKET, 0.9f, 4f)
        if (t > LIFTOFF && t < DOT) comic(c, "vù vù", c.head(SODA) + Offset(r * 0.6f, -r * 0.2f), LIFTOFF, 0.6f)
        // The laser dot, and finally the pointer in Chanh's paw.
        if (t > DOT && t < CLOUD) drawLaserDot(dot(c), r * 0.1f, c.now)
        comic(c, "VÚT!", Offset(c.stage.w * 0.3f, c.stage.h * 0.5f), CHASE, 0.4f)
        comic(c, "BỐP!", c.stage.brawl + Offset(0f, -r * 0.8f), CRASH, 0.5f)
        if (t > REVEAL && t < CLOUD) {
            val hand = c.hand(CHANH, 1)
            drawLaserPointer(hand, r * 0.4f * c.vs(CHANH), -40f)
            drawLine(Color(0xFFFF1744), hand, dot(c), r * 0.015f, alpha = 0.5f)
        }
        cloudIn(c, CLOUD)
    }
}
