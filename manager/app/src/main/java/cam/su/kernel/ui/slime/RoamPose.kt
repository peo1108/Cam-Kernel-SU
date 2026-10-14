package cam.su.kernel.ui.slime

import cam.su.kernel.ui.screen.home.arena.BO
import cam.su.kernel.ui.screen.home.arena.CHANH
import cam.su.kernel.ui.screen.home.arena.MOCHI
import cam.su.kernel.ui.screen.home.arena.Mood
import cam.su.kernel.ui.screen.home.arena.PI_F
import cam.su.kernel.ui.screen.home.arena.SODA
import cam.su.kernel.ui.screen.home.arena.SlimePose
import cam.su.kernel.ui.screen.home.arena.easeInOutCubic
import cam.su.kernel.ui.screen.home.arena.easeOutBack
import cam.su.kernel.ui.screen.home.arena.lerp
import cam.su.kernel.ui.screen.home.arena.progress
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/** Looks: each roaming slime's [SlimePose] for what it is up to, set every frame by [RoamWorld.step]. */
internal fun RoamWorld.pose(s: Roamer, dt: Float) {
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

private fun RoamWorld.reactPose(s: Roamer, p: SlimePose) {
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
