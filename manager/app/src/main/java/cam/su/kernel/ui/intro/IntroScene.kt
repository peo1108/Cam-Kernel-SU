package cam.su.kernel.ui.intro

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cam.su.kernel.ui.intro.IntroGeometry.SIZE
import cam.su.kernel.ui.intro.IntroGeometry.aim
import cam.su.kernel.ui.intro.IntroTimeline.BLAST_AT
import cam.su.kernel.ui.intro.IntroTimeline.CENTER_FLIGHT
import cam.su.kernel.ui.intro.IntroTimeline.CHARGE_FROM
import cam.su.kernel.ui.intro.IntroTimeline.CRAWL_LEN
import cam.su.kernel.ui.intro.IntroTimeline.HUD_FLICKER
import cam.su.kernel.ui.intro.IntroTimeline.KANJI_SLAM
import cam.su.kernel.ui.intro.IntroTimeline.NAME_AT
import cam.su.kernel.ui.intro.IntroTimeline.PLATE_FLASH
import cam.su.kernel.ui.intro.IntroTimeline.RANGE_FROM
import cam.su.kernel.ui.intro.IntroTimeline.RANGE_LEN
import cam.su.kernel.ui.intro.IntroTimeline.REVEAL_LEN
import cam.su.kernel.ui.intro.IntroTimeline.SHOT_FLIGHT
import cam.su.kernel.ui.intro.IntroTimeline.TEXT_END
import cam.su.kernel.ui.intro.IntroTimeline.plates
import cam.su.kernel.ui.intro.IntroTimeline.rivets
import cam.su.kernel.ui.screen.home.arena.drawGlow
import cam.su.kernel.ui.screen.home.arena.drawSparkle
import cam.su.kernel.ui.screen.home.arena.easeInOutCubic
import cam.su.kernel.ui.screen.home.arena.easeOutBack
import cam.su.kernel.ui.screen.home.arena.easeOutCubic
import cam.su.kernel.ui.screen.home.arena.hash
import cam.su.kernel.ui.screen.home.arena.lerp
import cam.su.kernel.ui.screen.home.arena.progress
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

// The start intro's picture at intro time t: the plates of the Inner Veterans triangle thrown
// together on black, nailed down by shots, wired up by lightning, blown up and opened onto the app.
// Everything inside the logo transform is in logo pixels (IntroGeometry); see IntroTimeline for the beats.

/** The HUD readouts' digits and LOCK, drawn on the native canvas (in logo pixels). */
internal class IntroText {
    val chars = "0123456789M".toCharArray()
    val lockChars = "LOCK".toCharArray()
    val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15f
        color = 0xFFA9DCD6.toInt()
    }
    val lock = Paint(hud).apply {
        color = 0xFFFF5A2A.toInt()
        isFakeBoldText = true
    }
}

/** One frame of the intro at [t]; [revealAt] is when the opening started (NaN while it has not). */
internal fun DrawScope.drawIntro(art: IntroArt, t: Float, revealAt: Float, paths: IntroPaths, text: IntroText) {
    val fit = IntroGeometry.fit(size.width, size.height)
    val rp = if (revealAt.isNaN()) 0f else progress(t, revealAt, REVEAL_LEN)
    val center = fit.toScreen(aim)
    val holeR = IntroGeometry.farthestCorner(center, size.width, size.height) * (1f - (1f - rp) * (1f - rp))
    if (holeR > 0.5f) {
        val hole = paths.hole
        hole.rewind()
        hole.fillType = PathFillType.EvenOdd
        hole.addRect(Rect(Offset.Zero, size))
        hole.addOval(Rect(center, holeR))
        clipPath(hole) { drawScene(art, t, rp, fit, paths, text) }
    } else {
        drawScene(art, t, rp, fit, paths, text)
    }
    drawShockwave(center, holeR, 22f * fit.scale, 1f - rp)
}

/** Everything but the hole: the black, the logo and its effects, the blast's flash. */
private fun DrawScope.drawScene(art: IntroArt, t: Float, rp: Float, fit: IntroGeometry.Fit, paths: IntroPaths, text: IntroText) {
    drawRect(Color.Black)
    val shake = shakeAt(t) * fit.scale
    // The camera creeps in the whole time, and the logo is blown back a little as it opens.
    val zoom = (1f + 0.035f * easeOutCubic(progress(t, 0f, TEXT_END))) * (1f + 0.1f * rp * rp)
    withTransform({
        translate(fit.left + shake.x, fit.top + shake.y)
        scale(fit.scale, fit.scale, Offset.Zero)
        scale(zoom, zoom, aim)
    }) {
        drawHud(art.logo, t, paths)
        drawPlates(art, t)
        drawReadouts(t, text)
        drawLettering(art.logo, t)
        drawCharge(t, 1f - rp, paths)
        drawShots(t, paths)
    }
    if (t >= BLAST_AT) {
        drawRect(Color.White, alpha = 0.92f * (1f - progress(t, BLAST_AT, 0.06f)))
        drawRect(IntroTeal, alpha = 0.1f * (1f - progress(t, BLAST_AT, 0.18f)))
    }
}

/** The HUD round the triangle: piece by piece, each flickering on like a bad tube. */
private fun DrawScope.drawHud(logo: ImageBitmap, t: Float, paths: IntroPaths) {
    val hud = IntroGeometry.hud
    clipPath(paths.hud) {
        if (t >= IntroTimeline.hudAt(hud.size - 1) + HUD_FLICKER) {
            drawImage(logo)
        } else {
            val frame = floor(t * 60f).toInt()
            for (i in hud.indices) {
                val at = IntroTimeline.hudAt(i)
                if (t < at) continue
                val settling = t < at + HUD_FLICKER
                if (settling && hash(frame * 7 + i * 31) < 0.45f) continue
                val r = hud[i]
                val src = IntOffset(r.left.toInt(), r.top.toInt())
                val sz = IntSize(r.width.toInt(), r.height.toInt())
                drawImage(logo, src, sz, src, sz, alpha = if (settling) 0.55f + 0.45f * hash(frame + i) else 1f)
            }
        }
    }
}

/** The plates: thrown in from in front of the screen, with trails, slamming down with a flash. */
private fun DrawScope.drawPlates(art: IntroArt, t: Float) {
    for (i in plates.indices) {
        val p = plates[i]
        val start = p.landAt - p.flight
        if (t < start) continue
        val img = art.plates[i]
        val at = art.plateAt[i]
        if (t >= p.landAt) {
            drawImage(img, at)
            val flash = 1f - progress(t, p.landAt, PLATE_FLASH)
            // the reticle is one big disc: a full white flash on it reads as a blank circle
            if (flash > 0f) drawImage(img, at, alpha = (if (p.ring == 3) 0.35f else 0.85f) * flash, colorFilter = WhiteFlash)
            val age = t - p.landAt
            when (p.ring) {
                // the ones a rivet nails down get the rivet's sparks instead
                0 -> if (i % 2 == 0) drawSparks(IntroGeometry.bandPoint(p.edge, p.along, IntroGeometry.BAND_OUT), p.from, age, i * 13, 7)
                1 -> drawSparks(p.center, p.from, age, i * 13, 4)
                3 -> {
                    // locked: a ring of light leaves the reticle
                    val e = progress(age, 0f, 0.3f)
                    if (e < 1f) drawCircle(IntroCyan, IntroGeometry.AIM_RADIUS * (1f + 1.6f * easeOutCubic(e)), aim, alpha = 0.8f * (1f - e), style = Stroke(4f * (1f - e) + 1f))
                }
            }
            continue
        }
        val f = (t - start) / p.flight
        if (p.ring < 3) {
            // motion trail: where it was a moment ago
            for (g in 2 downTo 1) {
                val back = f - 0.07f * g
                if (back > 0.05f) drawInFlight(img, at, p, back, 0.22f / g)
            }
        }
        drawInFlight(img, at, p, f, progress(f, 0f, 0.25f))
    }
}

/** Plate [p] at [f] (0..1) of its flight in. */
private fun DrawScope.drawInFlight(img: ImageBitmap, at: Offset, p: IntroTimeline.Plate, f: Float, alpha: Float) {
    if (p.ring == 3) {
        // the reticle grows out of its socket spinning, and jolts to a stop
        val s = easeOutBack(f, 2.2f).coerceAtLeast(0f)
        val turn = p.spin * (1f - easeOutCubic(f))
        withTransform({
            rotate(turn, p.center)
            scale(s, s, p.center)
        }) { drawImage(img, at, alpha = alpha) }
        return
    }
    val e = easeOutCubic(f)
    val off = p.from * (p.reach * (1f - e))
    // from bigger (nearer) down to its size, a touch past it on impact
    val s = 1f + (p.depth - 1f) * (1f - easeOutBack(f, 1.6f))
    withTransform({
        translate(off.x, off.y)
        rotate(p.spin * (1f - e), p.center)
        scale(s, s, p.center)
    }) { drawImage(img, at, alpha = alpha) }
}

/** The rangefinder counting down to zero, then LOCK blinking by TGT. */
private fun DrawScope.drawReadouts(t: Float, text: IntroText) {
    if (t < RANGE_FROM) return
    val range = (500f * (1f - easeInOutCubic(progress(t, RANGE_FROM, RANGE_LEN)))).toInt() / 10 * 10
    val r = IntroGeometry.rng
    drawRect(Color.Black, r.topLeft, r.size)
    val canvas = drawContext.canvas.nativeCanvas
    val base = IntroGeometry.rngBaseline
    var div = 1000
    for (i in 0..3) {
        canvas.drawText(text.chars, range / div % 10, 1, base.x + i * IntroGeometry.RNG_PITCH, base.y, text.hud)
        div /= 10
    }
    canvas.drawText(text.chars, 10, 1, base.x + 4 * IntroGeometry.RNG_PITCH, base.y, text.hud)
    if (t >= RANGE_FROM + RANGE_LEN) {
        val on = floor((t - RANGE_FROM - RANGE_LEN) * 8f).toInt() % 2 == 0
        text.lock.alpha = if (on) 255 else 64
        val l = IntroGeometry.lockBaseline
        canvas.drawText(text.lockChars, 0, text.lockChars.size, l.x, l.y, text.lock)
    }
}

/** 前輩內部 slammed in a character at a time, the slashes beside it, INNER VETERANS lit letter by letter. */
private fun DrawScope.drawLettering(logo: ImageBitmap, t: Float) {
    val g = IntroGeometry
    val top = g.TEXT_TOP.toInt()
    val height = (g.KANJI_BOTTOM - g.TEXT_TOP).toInt()
    for (k in 0..3) {
        val land = IntroTimeline.kanjiAt[k]
        if (t < land - KANJI_SLAM) continue
        val src = IntOffset(g.kanji[k].toInt(), top)
        val sz = IntSize((g.kanji[k + 1] - g.kanji[k]).toInt(), height)
        if (t < land) {
            // thrown at the screen: huge, split into its colors, closing in
            val f = (t - (land - KANJI_SLAM)) / KANJI_SLAM
            val s = 1f + 1.4f * (1f - easeOutBack(f, 1.4f))
            val center = Offset(src.x + sz.width / 2f, src.y + sz.height / 2f)
            withTransform({ scale(s, s, center) }) { drawSplit(logo, src, sz, (26f * (1f - f)).toInt() + 3, progress(f, 0f, 0.3f)) }
        } else {
            val settle = progress(t, land, 0.14f)
            if (settle < 1f) {
                // landed: a burst of glitch, the colors snapping back together, and a flare
                drawGlitchSlice(logo, src, sz, (9f * (1f - settle)).toInt(), 30f * (1f - settle), floor(t * 45f).toInt() + k * 17, 3)
                drawImage(logo, src, sz, src, sz, alpha = 0.7f * (1f - settle), blendMode = BlendMode.Plus)
            } else {
                drawImage(logo, src, sz, src, sz)
            }
        }
    }

    val slash = progress(t, FLOURISH_AT, 0.14f)
    if (slash > 0f) {
        for ((i, r) in g.flourishes.withIndex()) {
            val dx = (if (i == 0) -1f else 1f) * 120f * (1f - easeOutCubic(slash))
            val src = IntOffset(r.left.toInt(), r.top.toInt())
            val sz = IntSize(r.width.toInt(), r.height.toInt())
            drawImage(logo, src, sz, IntOffset(src.x + dx.toInt(), src.y), sz, alpha = slash)
        }
    }

    val letters = g.letterLeft.size
    val np = progress(t, NAME_AT, TEXT_END - NAME_AT)
    val lit = ceil(np * letters).toInt()
    if (lit > 0) {
        val right = if (lit >= letters) SIZE else (g.letterRight[lit - 1] + g.letterLeft[lit]) / 2f
        clipRect(0f, g.KANJI_BOTTOM, right, g.NAME_BOTTOM) { drawImage(logo) }
        if (np < 1f) {
            // the letter just lit flares
            clipRect(g.letterLeft[lit - 1] - 6f, g.KANJI_BOTTOM, g.letterRight[lit - 1] + 6f, g.NAME_BOTTOM) {
                drawImage(logo, blendMode = BlendMode.Plus)
            }
        }
    }
    val ornament = progress(t, ORNAMENT_AT, 0.12f)
    if (ornament > 0f) clipRect(0f, g.NAME_BOTTOM, SIZE, SIZE) { drawImage(logo, alpha = ornament) }
}

private const val FLOURISH_AT = 1.40f
private const val ORNAMENT_AT = 1.40f

/** The frame's lightning: crawling out of the rivets, closing the loop, surging at the blast. */
private fun DrawScope.drawCharge(t: Float, alpha: Float, paths: IntroPaths) {
    if (t < CHARGE_FROM || alpha <= 0.01f) return
    val seed = floor(t * 20f).toInt()
    val surge = if (t >= BLAST_AT) 2f - progress(t, BLAST_AT, 0.3f) else 1f
    val amp = 9f * surge
    val phase = hash(seed + 11) * 190f
    val bolts = paths.bolts
    val branches = paths.branches
    bolts.rewind()
    branches.rewind()

    val reach = IntroTimeline.crawlReach * easeInOutCubic(progress(t, CHARGE_FROM, CRAWL_LEN))
    if (reach >= IntroTimeline.crawlReach - 0.5f) {
        paths.appendOutline(bolts, 0f, IntroGeometry.perimeter, seed, amp, phase)
    } else {
        val from = IntroTimeline.crawlFrom
        for (i in from.indices) {
            val gap = IntroTimeline.crawlGap[i]
            val r = min(reach, gap / 2f)
            paths.appendOutline(bolts, from[i], from[i] + r, seed + i * 7, amp, phase)
            paths.appendOutline(bolts, from[i] + gap - r, from[i] + gap, seed + i * 7 + 3, amp, phase)
            if (r < gap / 2f) {
                drawSparkle(IntroGeometry.perimeterPoint(from[i] + r), 16f, IntroCyan, alpha)
                drawSparkle(IntroGeometry.perimeterPoint(from[i] + gap - r), 16f, IntroCyan, alpha)
            }
        }
    }

    for (i in rivets.indices) {
        val rv = rivets[i]
        if (t >= IntroTimeline.explodeAt(i)) continue
        // each rivet is wired to the outline, and crackles
        val p = rv.point
        paths.appendBolt(bolts, p, IntroGeometry.edgePoint(rv.edge, rv.along), seed * 5 + i, 6f)
        for (k in 0..2) {
            if (hash(seed * 13 + i * 3 + k) < 0.45f) continue
            val a = hash(seed * 17 + i * 3 + k) * 6.283f
            val len = 14f + 20f * hash(seed * 19 + i * 3 + k)
            paths.appendBolt(branches, p, p + Offset(cos(a), sin(a)) * len, seed * 23 + i * 3 + k, len * 0.25f)
        }
    }

    if (t >= BLAST_AT) {
        val bp = progress(t, BLAST_AT, 0.35f)
        // three bolts from the corners into the reticle
        if (t < BLAST_AT + 0.2f) for (c in 0..2) paths.appendBolt(bolts, IntroGeometry.corner(c), aim, seed * 3 + c, 22f)
        // branches thrown off the frame, fewer and shorter as it settles
        val count = if (bp < 1f) 8 else 2
        for (k in 0 until count) {
            if (bp >= 1f && hash(seed * 29 + k) < 0.6f) continue
            val s = hash(seed * 31 + k) * IntroGeometry.perimeter
            val start = IntroGeometry.perimeterPoint(s)
            val n = IntroGeometry.edgeNormal(IntroGeometry.perimeterEdge(s))
            val turn = (hash(seed * 37 + k) - 0.5f) * 1.2f
            val dir = Offset(n.x * cos(turn) - n.y * sin(turn), n.x * sin(turn) + n.y * cos(turn))
            val len = if (bp < 1f) (60f + 110f * hash(seed * 41 + k)) * (1f - 0.6f * bp) else 30f + 40f * hash(seed * 41 + k)
            paths.appendBolt(branches, start, start + dir * len, seed * 43 + k, len * 0.2f)
        }
    }

    drawBolts(bolts, 2.2f * (0.8f + 0.2f * surge), alpha)
    drawBolts(branches, 1.3f, alpha)
}

/** Rivet shots in the air, stuck in the frame, their sparks and their chain of explosions. */
private fun DrawScope.drawShots(t: Float, paths: IntroPaths) {
    for (i in rivets.indices) {
        val rv = rivets[i]
        val f = (t - (rv.time - SHOT_FLIGHT)) / SHOT_FLIGHT
        if (f in 0f..1f && t < rv.time) {
            val e = IntroTimeline.bulletEase(f)
            val speed = (IntroTimeline.bulletEase(f + 0.02f) - e) / 0.02f * SHOT_RANGE / SHOT_FLIGHT
            drawBullet(paths, lerp(rv.point + rv.from * SHOT_RANGE, rv.point, e), -rv.from, (speed * 0.03f).coerceIn(24f, 560f), t)
        }
        val blowUp = IntroTimeline.explodeAt(i)
        if (t >= rv.time && t < blowUp) drawEmbedded(rv.point, t - rv.time, t, i)
        drawSparks(rv.point, rv.from, t - rv.time, i * 50, 14)
        drawFireball(rv.point, t - blowUp, 46f, i * 31)
    }

    // The last shot, fired from the viewer straight into the reticle: rifling rings closing in.
    val f = progress(t, BLAST_AT - CENTER_FLIGHT, CENTER_FLIGHT)
    if (f > 0f && t < BLAST_AT) {
        val r = 320f * (1f - IntroTimeline.bulletEase(f)) + 6f
        for (k in 0..2) {
            drawArc(
                if (k == 1) IntroEmberHot else IntroCyan,
                startAngle = t * 1100f + k * 120f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = aim - Offset(r, r),
                size = Size(r * 2f, r * 2f),
                alpha = 0.4f + 0.6f * f,
                style = Stroke(5f + 6f * (1f - f)),
            )
        }
        drawGlow(aim, 30f + 60f * f, IntroEmber, f)
        drawCircle(Color.White, 3f + 4f * f, aim)
    }
    if (t >= BLAST_AT) {
        drawSparks(aim, Offset(0f, -1f), t - BLAST_AT, 900, 24)
        drawFireball(aim, t - BLAST_AT, 150f, 777, life = 0.6f)
    }
}

/** How far the landings, shots and the blast jolt the logo, in logo pixels. */
private fun shakeAt(t: Float): Offset {
    var amount = 0f
    for (p in plates) {
        if (t >= p.landAt) amount += (if (p.ring == 3) 8f else 3.5f) * exp(-(t - p.landAt) * 30f)
    }
    for (i in rivets.indices) {
        val rv = rivets[i]
        if (t >= rv.time) amount += 8f * exp(-(t - rv.time) * 26f)
        val e = IntroTimeline.explodeAt(i)
        if (t >= e) amount += 5f * exp(-(t - e) * 20f)
    }
    for (k in IntroTimeline.kanjiAt) if (t >= k) amount += 6f * exp(-(t - k) * 24f)
    if (t >= BLAST_AT) amount += 30f * exp(-(t - BLAST_AT) * 8f)
    if (amount < 0.3f) return Offset.Zero
    val frame = floor(t * 60f).toInt()
    return Offset((hash(frame) - 0.5f) * 2f * amount, (hash(frame + 999) - 0.5f) * 2f * amount)
}

private const val SHOT_RANGE = 1500f
