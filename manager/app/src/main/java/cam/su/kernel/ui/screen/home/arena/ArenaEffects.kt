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

// The effects drawn over the slimes during a show; the show itself is in ArenaShow.kt.

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

