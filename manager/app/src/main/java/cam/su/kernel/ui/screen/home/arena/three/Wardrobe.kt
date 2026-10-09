package cam.su.kernel.ui.screen.home.arena.three

import cam.su.kernel.ui.screen.home.arena.BO
import cam.su.kernel.ui.screen.home.arena.Costume
import cam.su.kernel.ui.screen.home.arena.MOCHI
import cam.su.kernel.ui.screen.home.arena.SODA
import cam.su.kernel.ui.screen.home.arena.three.Tailor.HEAD_Y
import cam.su.kernel.ui.screen.home.arena.three.Tailor.at
import cam.su.kernel.ui.screen.home.arena.three.Tailor.box
import cam.su.kernel.ui.screen.home.arena.three.Tailor.curve
import cam.su.kernel.ui.screen.home.arena.three.Tailor.cut
import cam.su.kernel.ui.screen.home.arena.three.Tailor.cylinder
import cam.su.kernel.ui.screen.home.arena.three.Tailor.disc
import cam.su.kernel.ui.screen.home.arena.three.Tailor.dome
import cam.su.kernel.ui.screen.home.arena.three.Tailor.ellipsoid
import cam.su.kernel.ui.screen.home.arena.three.Tailor.hoop
import cam.su.kernel.ui.screen.home.arena.three.Tailor.lathe
import cam.su.kernel.ui.screen.home.arena.three.Tailor.merge
import cam.su.kernel.ui.screen.home.arena.three.Tailor.onBody
import cam.su.kernel.ui.screen.home.arena.three.Tailor.onSurface
import cam.su.kernel.ui.screen.home.arena.three.Tailor.overHead
import cam.su.kernel.ui.screen.home.arena.three.Tailor.pair
import cam.su.kernel.ui.screen.home.arena.three.Tailor.radiusAt
import cam.su.kernel.ui.screen.home.arena.three.Tailor.ring
import cam.su.kernel.ui.screen.home.arena.three.Tailor.shell
import cam.su.kernel.ui.screen.home.arena.three.Tailor.sphere
import cam.su.kernel.ui.screen.home.arena.three.Tailor.transform
import cam.su.kernel.ui.screen.home.arena.three.Tailor.tube
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a costume piece is worn. Body pieces move with the jelly (arms, melting); hats sit on the head. */
internal enum class Slot { Body, Head, HandL, HandR }

/** A texture painted in code: [paint] gives the ARGB of pixel (x, y). */
internal class Pattern(val key: String, val size: Int, val paint: (Int, Int) -> Int)

/** A costume material: fabric (with sheen), fur, metal, glass (blended) or glowing parts. */
internal class Cloth(
    val color: Long,
    val roughness: Float = 0.8f,
    val metallic: Float = 0f,
    val sheen: Long? = null,
    val pattern: Pattern? = null,
    val emissive: Long? = null,
    val alpha: Float = 1f,
) {
    val key = "c${color}_${roughness}_${metallic}_${sheen}_${pattern?.key}_${emissive}_$alpha"
}

internal class Piece(val slot: Slot, val mesh: MeshData, val cloth: Cloth)

/** How the face texture is painted under the features. */
internal enum class FacePaint { None, Mime }

internal class Outfit(val pieces: List<Piece>, val face: FacePaint)

private class Fit {
    val pieces = ArrayList<Piece>()
    var face = FacePaint.None
    fun body(mesh: MeshData, cloth: Cloth) {
        pieces += Piece(Slot.Body, mesh, cloth)
    }

    fun head(mesh: MeshData, cloth: Cloth) {
        pieces += Piece(Slot.Head, mesh, cloth)
    }

    fun hand(side: Int, mesh: MeshData, cloth: Cloth) {
        pieces += Piece(if (side < 0) Slot.HandL else Slot.HandR, mesh, cloth)
    }
}

private fun fit(block: Fit.() -> Unit): Outfit = Fit().apply(block).let { Outfit(it.pieces, it.face) }

// ---------------------------------------------------------------------------------------------
// Materials and patterns
// ---------------------------------------------------------------------------------------------

private const val TAU = (2 * PI).toFloat()
private const val PI_F = PI.toFloat()
private const val WHITE = 0xFFFFFFFFL

private fun argb(c: Long) = c.toInt()

private fun mixL(a: Long, b: Long, t: Float): Long {
    fun ch(c: Long, s: Int) = ((c shr s) and 0xFF).toFloat()
    fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255).toLong()
    return (0xFFL shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
}

private fun mix(a: Long, b: Long, t: Float) = mixL(a, b, t).toInt()

private fun hash(i: Int): Float {
    val s = sin(i * 12.9898f + 78.233f) * 43758.547f
    return s - floor(s)
}

private fun silk(c: Long, pattern: Pattern? = null) = Cloth(if (pattern != null) WHITE else c, 0.32f, sheen = WHITE, pattern = pattern)
private fun cotton(c: Long, pattern: Pattern? = null) = Cloth(if (pattern != null) WHITE else c, 0.85f, sheen = 0xFF9E9E9E, pattern = pattern)
private fun fur(c: Long) = Cloth(c, 1f, sheen = mixL(c, WHITE, 0.5f))
private fun felt(c: Long) = Cloth(c, 0.9f)
private fun leather(c: Long) = Cloth(c, 0.5f)
private fun gloss(c: Long) = Cloth(c, 0.25f)
private fun metal(c: Long, rough: Float = 0.28f) = Cloth(c, rough, metallic = 1f)
private fun glass(c: Long, alpha: Float) = Cloth(c, 0.05f, alpha = alpha)
private fun glow(c: Long) = Cloth(c, 0.4f, emissive = c)
private fun printed(pattern: Pattern, rough: Float = 0.6f) = Cloth(WHITE, rough, pattern = pattern)

private val Gold = metal(0xFFE8B84A)
private val Silver = metal(0xFFD5DDE3)
private val Brass = metal(0xFFC98A3B, 0.35f)
private val Ink = gloss(0xFF1E1E24)

private fun gingham(dark: Long, light: Long, n: Int) = Pattern("ging_${dark}_${light}_$n", 64) { x, y ->
    val a = (x * n / 64) % 2 == 0
    val b = (y * n / 64) % 2 == 0
    when {
        a && b -> argb(dark)
        a || b -> mix(dark, light, 0.55f)
        else -> argb(light)
    }
}

private fun tartan(base: Long, lines: Long, accent: Long) = Pattern("tartan_${base}_${lines}_$accent", 64) { x, y ->
    val bx = x % 16
    val by = y % 16
    var c = base
    if (bx < 3 || by < 3) c = mixL(c, lines, 0.6f)
    if (bx == 8 || by == 8) c = mixL(c, accent, 0.8f)
    argb(c)
}

private fun stripes(a: Long, b: Long, n: Int) = Pattern("stripe_${a}_${b}_$n", 64) { _, y -> argb(if ((y * n / 64) % 2 == 0) a else b) }

private fun dots(base: Long, dot: Long, n: Int, size: Float) = Pattern("dots_${base}_${dot}_${n}_$size", 64) { x, y ->
    val cell = 64f / n
    val row = floor(y / cell).toInt()
    val shift = if (row % 2 == 0) 0f else cell / 2f
    val cx = (floor((x - shift) / cell) + 0.5f) * cell + shift
    val cy = (row + 0.5f) * cell
    val dx = x - cx
    val dy = y - cy
    if (dx * dx + dy * dy < (cell * size) * (cell * size)) argb(dot) else argb(base)
}

private fun floral(base: Long, petal: Long, center: Long) = Pattern("floral_${base}_${petal}_$center", 64) { x, y ->
    var c = argb(base)
    for (i in 0 until 4) {
        val fx = (hash(i * 7 + 1) * 64f + (i % 2) * 32f) % 64f
        val fy = (hash(i * 7 + 2) * 64f + (i / 2) * 32f) % 64f
        var dx = x - fx
        var dy = y - fy
        if (dx > 32) dx -= 64
        if (dx < -32) dx += 64
        if (dy > 32) dy -= 64
        if (dy < -32) dy += 64
        val d = sqrt(dx * dx + dy * dy)
        val a = atan2(dy, dx)
        val r = 5.5f * (0.65f + 0.35f * abs(cos(a * 2.5f)))
        if (d < 2f) c = argb(center) else if (d < r) c = argb(petal)
    }
    c
}

private fun sequins(base: Long, glint: Long) = Pattern("seq_${base}_$glint", 64) { x, y ->
    val cell = (x / 4) * 31 + (y / 4) * 17
    mix(base, glint, hash(cell) * hash(cell + 3))
}

private fun brocade(base: Long, motif: Long) = Pattern("broc_${base}_$motif", 64) { x, y ->
    val cx = x % 16 - 8
    val cy = y % 16 - 8
    val ring = abs(sqrt((cx * cx + cy * cy).toFloat()) - 5f) < 1.2f
    val dia = abs(cx) + abs(cy) < 3
    argb(if (ring || dia) motif else base)
}

private val Cardboard = Pattern("cardboard", 64) { x, y ->
    val corr = (sin(x * 0.9f) * 0.5f + 0.5f) * 0.08f
    val tape = y in 26..34
    mix(if (tape) 0xFFD9C39AL else 0xFFC48A52L, 0xFF7A4E25, corr + hash(x * 13 + y * 7) * 0.05f)
}

private val PaintSpots = Pattern("paintspots", 64) { x, y ->
    var c = argb(0xFFCFE3F5)
    val colors = longArrayOf(0xFFE63946, 0xFFFFC300, 0xFF2A9D8F, 0xFF8E44AD, 0xFFF77F00)
    for (i in 0 until 7) {
        val px = hash(i * 5 + 11) * 64f
        val py = hash(i * 5 + 12) * 64f
        val d = sqrt((x - px) * (x - px) + (y - py) * (y - py))
        if (d < 3f + hash(i + 40) * 3f) c = argb(colors[i % colors.size])
    }
    c
}

private val Screen = Pattern("screen", 64) { x, y ->
    val line = y % 8
    val len = (hash(y / 8) * 48).toInt() + 8
    when {
        y < 6 -> argb(0xFF1E3A2F)
        line in 3..4 && x in 6..len -> argb(0xFF39FF88)
        else -> argb(0xFF07140F)
    }
}

private val Leds = Pattern("leds", 64) { x, y ->
    val bar = y in 26..38
    val left = x in 10..26
    val right = x in 38..54
    when {
        bar && (left || right) -> argb(0xFFFF2E4D)
        y in 22..42 -> argb(0xFF14161C)
        else -> argb(0xFF2A2E38)
    }
}

// ---------------------------------------------------------------------------------------------
// Reusable pieces
// ---------------------------------------------------------------------------------------------

/** Lower garment from the floor up to [top] (below the mouth), with a hem that swings out by [flare]. */
private fun lower(top: Float = 0.42f, flare: Float = 0.03f, gap: Float = 0.025f, repeatU: Float = 3f) =
    shell(0.03f, top, gap, flare = flare, repeatU = repeatU)

private fun collar(y: Float = 0.43f, tube: Float = 0.04f) = ring(y, tube, 0.02f)

/** A column of buttons down the front, [phi] round from the middle. */
private fun buttons(ys: List<Float>, size: Float = 0.035f, phi: Float = 0f) =
    merge(ys.map { y -> onBody(y, phi, 0.05f).let { p -> sphere(p[0], p[1], p[2], size, 6, 10) } })

/** A cape over the back, clear of the arms. */
private fun cape(y0: Float, y1: Float, flare: Float = 0.12f) =
    shell(y0, y1, 0.05f, phi0 = 1.9f, phi1 = TAU - 1.9f, flare = flare, repeatU = 2f)

/** A bow at the front of the collar line (bow ties, hanbok bows). */
private fun bow(y: Float, size: Float, phi: Float = 0f): MeshData {
    val p = onBody(y, phi, 0.06f)
    val lobe = ellipsoid(size * 0.75f, 0f, 0f, size * 0.8f, size * 0.55f, size * 0.3f, 8, 12)
    return transform(
        merge(listOf(pair(lobe), sphere(0f, 0f, 0.02f, size * 0.32f, 6, 10))),
        Mat4.of(Mat4.translate(p[0], p[1], p[2]), Mat4.rotateY(Math.toDegrees(phi.toDouble()).toFloat())),
    )
}

/** Two pointed cat ears on the head. */
private fun catEars(f: Fit, outer: Cloth, inner: Cloth) {
    val ear = transform(lathe(listOf(0f to 0f, 0.17f to 0f, 0.17f to 0f, 0f to 0.34f), 16), Mat4.of(Mat4.translate(0.36f, 0.1f, 0.08f), Mat4.rotateZ(-22f), Mat4.scale(1f, 1f, 0.45f)))
    val innerEar = transform(lathe(listOf(0f to 0f, 0.1f to 0f, 0.1f to 0f, 0f to 0.24f), 12), Mat4.of(Mat4.translate(0.37f, 0.13f, 0.14f), Mat4.rotateZ(-22f), Mat4.scale(1f, 1f, 0.3f)))
    f.head(pair(ear), outer)
    f.head(pair(innerEar), inner)
}

/** Nón lá, the conical palm-leaf hat. */
private fun nonLa(f: Fit) = f.head(Shapes3D.nonLa(), Cloth(0xFFF2D48F, 0.85f, sheen = 0xFFFFE9B0))

/** A band round the head at height [h] above the anchor. */
private fun headBand(h: Float, tube: Float, gap: Float = 0.01f, phi0: Float = 0f, phi1: Float = TAU): MeshData =
    transform(ring(HEAD_Y + h, tube, gap, phi0 = phi0, phi1 = phi1), Mat4.translate(0f, -HEAD_Y, 0f))

/** A cap hugging the head from [h0] above the anchor to the top. */
private fun headShell(h0: Float, gap: Float, phi0: Float = PI_F, phi1: Float = 3f * PI_F, repeatU: Float = 2f): MeshData =
    transform(shell(HEAD_Y + h0, BODY_HEIGHT - 0.005f, gap, phi0, phi1, rings = 10, segments = 40, repeatU = repeatU), Mat4.translate(0f, -HEAD_Y, 0f))

/** A patch on the front of the body between two heights, [half] radians either side of the middle. */
private fun front(y0: Float, y1: Float, half: Float, gap: Float = 0.03f, flare: Float = 0f) =
    shell(y0, y1, gap, -half, half, flare = flare, rings = 8, segments = 24)

/** A flat visor sticking out of a cap, in head coordinates. */
private fun visor(width: Float, depth: Float, z: Float, y: Float = 0f, tilt: Float = -12f) =
    transform(ellipsoid(0f, 0f, 0f, width, 0.025f, depth, 6, 16), Mat4.of(Mat4.translate(0f, y, z), Mat4.rotateX(tilt)))

/** A point of the face (eyes at y 0.81, mouth at 0.55) a little off the jelly. */
private fun face(y: Float, x: Float, out: Float = 0.03f): FloatArray {
    val r = radiusAt(y)
    return onBody(y, kotlin.math.asin((x / r).coerceIn(-1f, 1f)), out)
}

// ---------------------------------------------------------------------------------------------
// The wardrobe: four outfits for each of the nine countries
// ---------------------------------------------------------------------------------------------

internal object Wardrobe {
    /** How [k]'s face is painted in [theme], known without sewing the whole outfit. */
    fun facePaint(theme: Costume, k: Int) = if (theme == Costume.Beret && k == SODA) FacePaint.Mime else FacePaint.None

    fun outfit(theme: Costume, k: Int): Outfit = when (theme) {
        Costume.NonLa -> vietnam(k)
        Costume.TopHat -> london(k)
        Costume.Hachimaki -> japan(k)
        Costume.Gat -> korea(k)
        Costume.Ushanka -> russia(k)
        Costume.Beret -> paris(k)
        Costume.Hacker -> binary(k)
        Costume.Radio -> morse(k)
        Costume.Cat -> cats(k)
    }

    private fun vietnam(k: Int) = when (k) {
        // Áo dài + nón lá.
        MOCHI -> fit {
            body(lower(0.42f, 0.02f), silk(WHITE, brocade(0xFFC1121F, 0xFFFFC857)))
            body(collar(0.43f, 0.032f), Gold)
            body(buttons(listOf(0.38f, 0.3f, 0.22f), 0.03f, 0.35f), Gold)
            nonLa(this)
        }
        // Áo bà ba and khăn rằn, under a nón lá too: the shuttlecock has to stick in its tip.
        BO -> fit {
            val khan = cotton(WHITE, gingham(0xFF151515, 0xFFF4F4F4, 8))
            body(lower(0.4f, 0.02f), cotton(0xFF4E342E))
            body(buttons(listOf(0.34f, 0.26f, 0.18f, 0.1f), 0.032f), Cloth(0xFFF5F0E6, 0.4f))
            body(ring(0.42f, 0.05f, 0.02f), khan)
            body(merge(listOf(-0.12f, 0.12f).map { x -> tube(curve(face(0.42f, x, 0.09f), face(0.32f, x * 2.2f, 0.14f), face(0.2f, x * 1.8f, 0.1f)), 0.05f, 0.035f) }), khan)
            nonLa(this)
        }
        // Áo tứ thân with yếm and sash, khăn mỏ quạ.
        SODA -> fit {
            body(lower(0.42f, 0.04f), cotton(0xFF7B4A2D))
            body(front(0.22f, 0.43f, 0.55f, 0.045f), silk(0xFFE5383B))
            body(ring(0.2f, 0.035f, 0.04f), silk(0xFF2A9D8F))
            body(merge(listOf(-0.1f, 0.1f).map { x -> tube(listOf(face(0.2f, x, 0.08f), face(0.08f, x * 1.3f, 0.1f), face(0.03f, x * 1.1f, 0.08f)), 0.03f, 0.022f) }), silk(0xFF2A9D8F))
            head(headShell(-0.08f, 0.03f), cotton(0xFF1A1A1A))
            head(transform(lathe(listOf(0f to 0f, 0.13f to 0f, 0.13f to 0f, 0f to 0.3f), 10), Mat4.of(Mat4.translate(0f, 0.24f, 0.36f), Mat4.rotateX(50f), Mat4.scale(1.2f, 1f, 0.6f))), cotton(0xFF1A1A1A))
        }
        // Yếm đỏ + búi tóc trái đào.
        else -> fit {
            body(front(0.06f, 0.46f, 0.85f, 0.03f), silk(0xFFD62828))
            body(ring(0.46f, 0.016f, 0.03f, phi0 = 0.95f, phi1 = TAU - 0.95f), silk(0xFFD62828))
            head(transform(merge(listOf(sphere(0f, 0f, 0f, 0.17f, 10, 14), lathe(listOf(0.1f to 0.1f, 0f to 0.28f), 12))), Mat4.of(Mat4.translate(0f, 0.27f, 0f), Mat4.rotateZ(-12f))), Cloth(0xFF2B2B2B, 0.55f))
            head(transform(Tailor.hoop(0.12f, 0.03f), Mat4.of(Mat4.translate(0f, 0.2f, 0f), Mat4.rotateX(90f))), silk(0xFFE63946))
        }
    }

    private fun london(k: Int) = when (k) {
        // Royal guard: bearskin and red tunic.
        MOCHI -> fit {
            body(lower(0.42f, 0.02f), felt(0xFFC1121F))
            body(ring(0.2f, 0.04f, 0.03f), Cloth(0xFFF8F8F8, 0.5f))
            body(buttons(listOf(0.36f, 0.28f, 0.12f), 0.03f), Gold)
            body(collar(0.43f, 0.035f), felt(0xFF14213D))
            head(lathe(listOf(0.8f to -0.06f, 0.84f to 1.05f) + dome(0.84f, 0.5f, 1.05f, 8).drop(1), 40), fur(0xFF141414))
            head(headBand(-0.03f, 0.03f, 0.06f), Gold)
        }
        // Detective: deerstalker, Inverness cape, pipe.
        BO -> fit {
            val check = cotton(WHITE, tartan(0xFFA67C52, 0xFF5C4033, 0xFFC8102E))
            body(cape(0.2f, 0.62f, 0.14f), check)
            body(lower(0.4f, 0.02f), check)
            head(headShell(-0.06f, 0.035f, repeatU = 3f), check)
            head(visor(0.3f, 0.24f, 0.74f), check)
            head(transform(visor(0.28f, 0.22f, 0.72f), Mat4.rotateY(180f)), check)
            head(sphere(0f, 0.31f, 0f, 0.05f), cotton(0xFF5C4033))
            val p = face(0.52f, 0.3f, 0.02f)
            body(tube(listOf(p, floatArrayOf(p[0] + 0.18f, p[1] - 0.04f, p[2] + 0.12f), floatArrayOf(p[0] + 0.3f, p[1] - 0.02f, p[2] + 0.16f)), 0.022f), Ink)
            body(at(cylinder(0.06f, 0f, 0.13f, 14, 0.07f), p[0] + 0.32f, p[1] - 0.02f, p[2] + 0.16f), leather(0xFF6D4C41))
        }
        // Gentleman: top hat, monocle, bow tie, tailcoat.
        SODA -> fit {
            body(lower(0.42f, 0.0f), felt(0xFF1B1B2F))
            body(front(0.2f, 0.43f, 0.32f, 0.04f), cotton(0xFFF8F8F8))
            body(bow(0.44f, 0.1f), silk(0xFF6A040F))
            head(cylinder(0.92f, 0.1f, 0.14f, 40), felt(0xFF141414))
            head(cylinder(0.6f, 0.12f, 1.0f, 40, 0.64f), silk(0xFF141414))
            head(cylinder(0.62f, 0.15f, 0.28f, 40), silk(0xFF6A040F))
            val eye = face(0.81f, 0.37f, 0.02f)
            val turn = Mat4.rotateY(22f)
            body(at(disc(0.13f, 0.015f), eye[0], eye[1], eye[2], turn), glass(0xFFDDEEFF, 0.25f))
            body(at(hoop(0.13f, 0.014f), eye[0], eye[1], eye[2] + 0.01f, turn), Gold)
            body(tube(curve(floatArrayOf(eye[0] + 0.12f, eye[1] - 0.05f, eye[2]), floatArrayOf(eye[0] + 0.25f, eye[1] - 0.35f, eye[2] - 0.05f), floatArrayOf(eye[0] + 0.2f, 0.46f, eye[2] - 0.12f)), 0.008f), Gold)
        }
        // Newsboy: flat cap and a satchel stuffed with papers.
        else -> fit {
            head(headShell(-0.1f, 0.07f, repeatU = 4f), cotton(WHITE, tartan(0xFF8D8D8D, 0xFF5E5E5E, 0xFFB0B0B0)))
            head(visor(0.42f, 0.25f, 0.74f, -0.06f, -8f), cotton(0xFF6E6E6E))
            head(sphere(0f, 0.33f, 0f, 0.05f), cotton(0xFF6E6E6E))
            body(transform(ring(0.36f, 0.03f, 0.02f), Mat4.of(Mat4.translate(0f, 0.36f, 0f), Mat4.rotateZ(28f), Mat4.translate(0f, -0.36f, 0f))), leather(0xFF6D4C41))
            body(onSurface(box(0f, 0f, 0.08f, 0.22f, 0.16f, 0.08f), 0.2f, 1.15f, 0.02f), leather(0xFF8D5A3B))
            body(onSurface(merge(listOf(box(-0.06f, 0.15f, 0.08f, 0.12f, 0.1f, 0.02f), box(0.07f, 0.13f, 0.1f, 0.11f, 0.09f, 0.02f))), 0.2f, 1.15f, 0.02f), Cloth(0xFFF1EFE6, 0.9f))
        }
    }

    private fun japan(k: Int) = when (k) {
        // Kimono, obi with a bow behind, kanzashi.
        MOCHI -> fit {
            body(lower(0.42f, 0.03f), silk(WHITE, floral(0xFFF7A1C4, 0xFFFFFFFF, 0xFFE5383B)))
            body(shell(0.16f, 0.32f, 0.05f, rings = 4), silk(WHITE, brocade(0xFFFFC857, 0xFFE76F51)))
            body(transform(pair(ellipsoid(0.16f, 0f, 0f, 0.18f, 0.12f, 0.06f, 8, 12)), Mat4.translate(0f, 0.26f, -0.93f)), silk(0xFFE76F51))
            body(merge(listOf(-1f, 1f).map { s -> transform(front(0.3f, 0.44f, 0.12f, 0.04f), Mat4.rotateY(s * 14f)) }), silk(0xFFFFFFFF))
            head(at(merge(listOf(sphere(0f, 0f, 0f, 0.08f)) + (0 until 5).map { i -> sphere(cos(i * TAU / 5) * 0.09f, sin(i * TAU / 5) * 0.09f, 0f, 0.07f, 6, 10) }), 0.42f, 0.14f, 0.42f), silk(0xFFFF8FB3))
            head(merge((0 until 3).map { i -> sphere(0.47f + i * 0.02f, 0.0f - i * 0.08f, 0.45f, 0.025f, 5, 8) }), Gold)
        }
        // Sumo: mawashi with sagari, chonmage topknot.
        BO -> fit {
            body(shell(0.03f, 0.22f, 0.04f, rings = 6, repeatU = 1f), silk(0xFF1D3557))
            body(ring(0.22f, 0.04f, 0.04f), silk(0xFF1D3557))
            body(merge((0 until 7).map { i -> val x = -0.27f + i * 0.09f; tube(listOf(face(0.2f, x, 0.07f), face(0.05f, x, 0.13f)), 0.02f, 0.016f) }), silk(0xFF1D3557))
            head(transform(merge(listOf(ellipsoid(0f, 0f, 0f, 0.09f, 0.08f, 0.2f, 8, 12), ellipsoid(0f, 0.04f, 0.2f, 0.16f, 0.05f, 0.1f, 6, 12))), Mat4.translate(0f, 0.27f, -0.02f)), Cloth(0xFF151515, 0.4f))
        }
        // Ninja: hood, mask over the mouth, headband with a plate, scarf tails.
        SODA -> fit {
            val cloth = cotton(0xFF22223B)
            body(lower(0.42f, 0.02f), cloth)
            body(front(0.42f, 0.66f, 1.05f, 0.03f), cloth)
            head(headShell(-0.22f, 0.03f), cloth)
            head(headBand(-0.12f, 0.035f, 0.03f), cotton(0xFF8D99AE))
            head(transform(onSurface(box(0f, 0f, 0f, 0.14f, 0.07f, 0.015f), HEAD_Y - 0.12f, 0f, 0.075f), Mat4.translate(0f, -HEAD_Y, 0f)), Silver)
            head(merge(listOf(-0.08f, 0.08f).map { x -> tube(listOf(floatArrayOf(x, -0.12f, -0.86f), floatArrayOf(x * 3f, -0.2f, -1.05f), floatArrayOf(x * 4f, -0.35f, -1.2f)), 0.03f, 0.02f) }), cotton(0xFF8D99AE))
        }
        // Gyoji referee: eboshi, robe, war fan.
        else -> fit {
            body(lower(0.42f, 0.06f), silk(WHITE, brocade(0xFF6A4C93, 0xFFFFC857)))
            body(collar(0.43f, 0.04f), silk(0xFFF1FAEE))
            head(transform(lathe(listOf(0.78f to -0.04f, 0.6f to 0.35f, 0.34f to 0.75f, 0f to 0.82f), 32), Mat4.of(Mat4.rotateX(-8f), Mat4.scale(1f, 1f, 0.9f))), gloss(0xFF101010))
            hand(1, merge(listOf(cylinder(0.03f, -0.05f, 0.25f, 10), at(transform(cylinder(0.22f, -0.01f, 0.01f, 24), Mat4.of(Mat4.rotateX(90f), Mat4.scale(1f, 1.15f, 1f))), 0f, 0.42f, 0f))), gloss(0xFF2B2B2B))
            hand(1, at(disc(0.13f, 0.03f), 0f, 0.42f, 0.02f), Gold)
        }
    }

    private fun korea(k: Int) = when (k) {
        // Hanbok: red chima, yellow jeogori with a bow, daenggi ribbon.
        MOCHI -> fit {
            body(lower(0.3f, 0.07f), silk(0xFFE63946))
            body(shell(0.3f, 0.43f, 0.05f, rings = 5), silk(0xFFFFD166))
            body(bow(0.36f, 0.09f, 0.2f), silk(0xFFE63946))
            body(tube(listOf(onBody(0.36f, 0.2f, 0.07f), onBody(0.2f, 0.25f, 0.1f), onBody(0.08f, 0.22f, 0.12f)), 0.025f, 0.02f), silk(0xFFE63946))
            head(merge(listOf(-1f, 1f).map { s -> tube(listOf(floatArrayOf(0f, 0.12f, -0.66f), floatArrayOf(s * 0.12f, -0.1f, -0.82f), floatArrayOf(s * 0.16f, -0.35f, -0.88f)), 0.05f, 0.04f) }), silk(0xFFC1121F))
        }
        // Scholar: translucent gat, white dopo, folding fan.
        BO -> fit {
            body(lower(0.42f, 0.05f), cotton(0xFFF4F1E8))
            body(collar(0.43f, 0.04f), cotton(0xFF2B2D42))
            head(cylinder(1.25f, 0.19f, 0.22f, 48), glass(0xFF151515, 0.75f))
            head(cylinder(0.46f, 0.2f, 0.78f, 32, 0.4f), glass(0xFF151515, 0.85f))
            head(merge(listOf(-1f, 1f).map { s -> tube(listOf(floatArrayOf(s * 0.62f, 0.2f, 0.3f), floatArrayOf(s * 0.7f, -0.45f, 0.62f), floatArrayOf(s * 0.3f, -1.0f, 0.88f)), 0.012f) }), Cloth(0xFF2B2B2B, 0.6f))
            hand(1, transform(lathe(listOf(0f to 0f, 0.32f to 0f, 0.32f to 0.02f, 0f to 0.02f), 16, phi0 = -0.7f, phi1 = 0.7f), Mat4.rotateX(90f)), cotton(0xFFF4F1E8))
        }
        // Idol: sequin jacket and headset mic.
        SODA -> fit {
            body(lower(0.42f, 0.03f), Cloth(WHITE, 0.25f, metallic = 0.6f, pattern = sequins(0xFFB8C0FF, 0xFFFFFFFF)))
            body(collar(0.43f, 0.035f), Silver)
            head(overHead(0.022f, 0.03f), Ink)
            body(tube(curve(onBody(0.98f, 1.42f, 0.04f), onBody(0.66f, 1.1f, 0.09f), face(0.56f, 0.34f, 0.07f)), 0.016f), Ink)
            body(face(0.56f, 0.34f, 0.08f).let { sphere(it[0], it[1], it[2], 0.05f) }, Ink)
        }
        // Taekwondo: white dobok with black V-neck trim, black belt with tails.
        else -> fit {
            val black = cotton(0xFF151515)
            body(lower(0.42f, 0.02f), cotton(0xFFF8F8F8))
            body(merge(listOf(-1f, 1f).map { s -> tube(listOf(onBody(0.43f, s * 0.45f, 0.04f), onBody(0.3f, 0f, 0.05f)), 0.025f) }), black)
            body(ring(0.18f, 0.04f, 0.04f), black)
            body(merge(listOf(-1f, 1f).map { s -> tube(listOf(onBody(0.18f, s * 0.1f, 0.08f), onBody(0.06f, s * 0.22f, 0.14f)), 0.03f, 0.026f) }), black)
        }
    }

    private fun russia(k: Int) = when (k) {
        // Matryoshka: floral headscarf tied under the chin, painted apron.
        MOCHI -> fit {
            val scarf = silk(WHITE, floral(0xFFE63946, 0xFFFFD166, 0xFF2A9D8F))
            body(lower(0.42f, 0.03f), silk(0xFFC1121F))
            body(front(0.06f, 0.4f, 0.6f, 0.05f), silk(WHITE, floral(0xFFFFF3D6, 0xFFE63946, 0xFFFFC300)))
            head(headShell(-0.05f, 0.042f, repeatU = 3f), scarf)
            head(headShell(-0.55f, 0.04f, phi0 = 1.25f, phi1 = TAU - 1.25f), scarf)
            body(face(0.45f, 0f, 0.06f).let { p -> merge(listOf(sphere(p[0], p[1], p[2], 0.06f), pair(ellipsoid(0.1f, p[1] - 0.05f, p[2], 0.08f, 0.05f, 0.03f, 6, 10)))) }, silk(0xFFE63946))
        }
        // Fur coat with fluffy trims and an ushanka.
        BO -> fit {
            body(lower(0.42f, 0.04f), fur(0xFF6D4C41))
            body(ring(0.05f, 0.07f, 0.03f), fur(0xFFEDE6DD))
            body(ring(0.42f, 0.06f, 0.02f), fur(0xFFEDE6DD))
            body(buttons(listOf(0.33f, 0.22f, 0.11f), 0.04f), leather(0xFF3E2723))
            head(headShell(-0.08f, 0.06f), fur(0xFF8D6E63))
            head(headBand(-0.06f, 0.09f, 0.03f), fur(0xFFEDE6DD))
            head(pair(transform(ellipsoid(0f, 0f, 0f, 0.08f, 0.28f, 0.22f, 6, 12), Mat4.of(Mat4.translate(0.86f, -0.26f, 0.02f), Mat4.rotateZ(-8f)))), fur(0xFFEDE6DD))
        }
        // Ballet: leotard, tulle tutu, little tiara.
        SODA -> fit {
            body(lower(0.4f, 0f, 0.02f), silk(0xFFFFC8DD))
            body(lathe((0..24).map { i -> val t = i / 24f; (radiusAt(0.24f) + 0.04f + t * 0.42f) to (0.24f + 0.05f * sin(t * PI_F) - t * 0.04f) }, 64, repeatU = 12f), Cloth(0xFFFFAFCC, 0.9f, sheen = WHITE, alpha = 0.82f))
            head(transform(lathe(listOf(0.52f to 0f) + (0..10).map { i -> val a = i / 10f * PI_F; (0.5f - 0.04f * sin(a)) to (0.1f * sin(a)) }, 32, phi0 = -1.1f, phi1 = 1.1f), Mat4.translate(0f, 0.12f, 0.06f)), Silver)
            head(sphere(0f, 0.27f, 0.5f, 0.05f), glass(0xFFB3E5FC, 0.9f))
        }
        // Astronaut: suit, collar ring, glass helmet and a backpack.
        else -> fit {
            body(lower(0.43f, 0.02f), cotton(0xFFF2F4F7))
            body(ring(0.45f, 0.06f, 0.02f), Silver)
            body(at(box(0f, 0f, 0f, 0.38f, 0.32f, 0.16f), 0f, 0.55f, -1.0f), cotton(0xFFDDE2E8))
            body(face(0.28f, 0f, 0.04f).let { box(it[0], it[1], it[2] + 0.02f, 0.16f, 0.08f, 0.02f) }, glow(0xFF4DD0E1))
            body(ellipsoid(0f, 1.05f, 0f, 1.12f, 0.98f, 1.12f, 16, 28), glass(0xFFE3F2FD, 0.16f))
            body(merge(listOf(-1f, 1f).map { s -> at(cylinder(0.02f, 0f, 0.35f, 8), s * 0.55f, 1.82f, 0f, Mat4.rotateZ(-s * 20f)) }), Silver)
        }
    }

    private fun paris(k: Int) = when (k) {
        // Painter: beret, paint-spotted smock, palette.
        MOCHI -> fit {
            body(lower(0.42f, 0.05f), printed(PaintSpots, 0.85f))
            body(bow(0.44f, 0.1f), silk(0xFF1D3557))
            head(transform(lathe(listOf(0f to 0f, 0.68f to 0.06f, 0.84f to 0.16f, 0.7f to 0.28f, 0.3f to 0.34f, 0f to 0.35f), 40), Mat4.of(Mat4.translate(0.08f, 0.17f, 0f), Mat4.rotateZ(-12f))), felt(0xFFC1121F))
            head(at(cylinder(0.03f, 0f, 0.1f, 8), 0.02f, 0.5f, 0f), felt(0xFFC1121F))
            hand(-1, transform(cylinder(0.3f, 0f, 0.025f, 32), Mat4.of(Mat4.rotateX(80f), Mat4.scale(1f, 1f, 0.75f))), Cloth(0xFFD9A066, 0.5f))
            hand(-1, merge((0 until 4).map { i -> val a = i * 0.9f + 0.5f; sphere(cos(a) * 0.18f, sin(a) * 0.14f, 0.05f, 0.045f, 5, 8) }), Cloth(0xFFE63946, 0.3f))
        }
        // Chef: toque, curled mustache, red neckerchief, white jacket.
        BO -> fit {
            body(lower(0.42f, 0.02f), cotton(0xFFF8F8F8))
            body(buttons(listOf(0.34f, 0.24f, 0.14f), 0.035f, -0.3f), cotton(0xFFE0E0E0))
            body(ring(0.42f, 0.05f, 0.02f), cotton(0xFFE63946))
            val m = face(0.68f, 0f, 0.02f)
            body(pair(tube(listOf(floatArrayOf(0.02f, m[1], m[2]), floatArrayOf(0.14f, m[1] - 0.02f, m[2] - 0.01f), floatArrayOf(0.26f, m[1] + 0.03f, m[2] - 0.04f), floatArrayOf(0.28f, m[1] + 0.09f, m[2] - 0.06f)), 0.05f, 0.02f)), gloss(0xFF2B1B10))
            head(cylinder(0.8f, -0.06f, 0.5f, 40, 0.74f), cotton(0xFFFAFAFA))
            head(merge((0 until 6).map { i -> val a = i * TAU / 6; sphere(cos(a) * 0.42f, 0.64f, sin(a) * 0.42f, 0.32f, 8, 12) } + listOf(sphere(0f, 0.76f, 0f, 0.34f, 8, 12))), cotton(0xFFFAFAFA))
        }
        // Mime: white face, striped shirt, suspenders, red scarf, little black beret.
        SODA -> fit {
            face = FacePaint.Mime
            body(lower(0.42f, 0.01f, repeatU = 1f), cotton(WHITE, stripes(0xFF151515, 0xFFF8F8F8, 10)))
            body(merge(listOf(-1f, 1f).map { s -> transform(front(0.03f, 0.43f, 0.06f, 0.05f), Mat4.rotateY(s * 22f)) }), cotton(0xFF151515))
            body(ring(0.43f, 0.035f, 0.02f), silk(0xFFE63946))
            head(transform(lathe(listOf(0f to 0f, 0.6f to 0.05f, 0.62f to 0.12f, 0.4f to 0.2f, 0f to 0.21f), 32), Mat4.of(Mat4.translate(-0.1f, 0.18f, 0f), Mat4.rotateZ(10f))), felt(0xFF151515))
        }
        // Napoleon: bicorne with cockade, navy coat, gold epaulettes.
        else -> fit {
            body(lower(0.42f, 0.03f), felt(0xFF1D3557))
            body(buttons(listOf(0.36f, 0.26f, 0.16f), 0.032f, 0.25f), Gold)
            body(buttons(listOf(0.36f, 0.26f, 0.16f), 0.032f, -0.25f), Gold)
            val epaulette = merge(listOf(cylinder(0.16f, 0f, 0.04f, 20)) + (0 until 8).map { i -> val a = i / 8f * PI_F; at(cylinder(0.012f, -0.1f, 0f, 6), cos(a) * 0.15f, 0f, sin(a) * 0.15f) })
            body(pair(at(epaulette, 0.82f, 0.46f, 0.15f, Mat4.rotateZ(-20f))), Gold)
            head(transform(lathe(dome(1.0f, 0.56f, 0f, 12), 40), Mat4.of(Mat4.translate(0f, 0.1f, 0f), Mat4.scale(1f, 1f, 0.65f))), felt(0xFF141414))
            head(transform(Tailor.tube((0..16).map { i -> val a = i / 16f * PI_F; floatArrayOf(cos(a) * 1.0f, 0.1f + sin(a) * 0.56f, 0f) }, 0.025f), Mat4.scale(1f, 1f, 1f)), Gold)
            head(at(disc(0.13f, 0.02f), 0.32f, 0.32f, 0.58f), Cloth(0xFF0055A4, 0.5f))
            head(at(disc(0.09f, 0.02f), 0.32f, 0.32f, 0.592f), Cloth(0xFFF8F8F8, 0.5f))
            head(at(disc(0.05f, 0.02f), 0.32f, 0.32f, 0.604f), Cloth(0xFFEF4135, 0.5f))
        }
    }

    private fun binary(k: Int) = when (k) {
        // Agent: black coat, white collar and tie, shades, earpiece.
        MOCHI -> fit {
            body(lower(0.42f, 0.03f), gloss(0xFF15151C))
            body(front(0.3f, 0.44f, 0.22f, 0.045f), cotton(0xFFF8F8F8))
            body(front(0.12f, 0.43f, 0.05f, 0.055f), silk(0xFF15151C))
            val l = face(0.81f, -0.37f, 0.03f)
            val r = face(0.81f, 0.37f, 0.03f)
            body(merge(listOf(l, r).map { p -> at(transform(disc(0.17f, 0.03f), Mat4.scale(1.15f, 0.8f, 1f)), p[0], p[1], p[2], Mat4.rotateY(if (p[0] < 0) -22f else 22f)) }), gloss(0xFF05050A))
            body(tube(listOf(floatArrayOf(l[0] + 0.17f, l[1] + 0.03f, l[2] + 0.02f), floatArrayOf(0f, l[1] + 0.05f, radiusAt(0.84f) + 0.06f), floatArrayOf(r[0] - 0.17f, r[1] + 0.03f, r[2] + 0.02f)), 0.018f), Ink)
            body(tube(curve(onBody(0.9f, 1.5f, 0.02f), onBody(0.7f, 1.55f, 0.08f), onBody(0.5f, 1.4f, 0.04f)), 0.012f), glass(0xFFDDEEFF, 0.6f))
        }
        // Hacker: hoodie with hood and strings, laptop with a glowing screen.
        BO -> fit {
            val hoodie = cotton(0xFF37474F)
            body(lower(0.42f, 0.04f), hoodie)
            body(cape(0.42f, 1.5f, 0.06f), hoodie)
            body(merge(listOf(-1f, 1f).map { s -> tube(listOf(onBody(0.44f, s * 0.2f, 0.06f), onBody(0.28f, s * 0.24f, 0.09f)), 0.016f) }), cotton(0xFFECEFF1))
            body(at(box(0f, 0f, 0f, 0.42f, 0.02f, 0.28f), 0f, 0.3f, 1.2f), Silver)
            body(at(transform(box(0f, 0.27f, 0f, 0.42f, 0.27f, 0.015f), Mat4.rotateX(-18f)), 0f, 0.31f, 0.94f), Silver)
            body(at(transform(box(0f, 0.27f, 0.018f, 0.38f, 0.23f, 0.004f), Mat4.rotateX(-18f)), 0f, 0.31f, 0.94f), Cloth(WHITE, 0.3f, pattern = Screen, emissive = 0xFF7DFFB0))
        }
        // Robot: LED visor across the eyes, ear bolts, chest lights, antenna.
        SODA -> fit {
            body(lower(0.42f, 0.0f), metal(0xFFB0BEC5, 0.4f))
            body(buttons(listOf(0.32f, 0.2f), 0.05f), glow(0xFF4DD0E1))
            body(shell(0.71f, 0.93f, 0.05f, -1.2f, 1.2f, rings = 4, segments = 28), Cloth(WHITE, 0.15f, pattern = Leds, emissive = 0xFFFF2E4D))
            body(merge(listOf(-1f, 1f).map { s -> onBody(0.82f, s * 1.5f, 0f).let { p -> at(cylinder(0.12f, 0f, 0.12f, 16), p[0], p[1], p[2], Mat4.rotateZ(-s * 90f)) } }), Silver)
            head(at(cylinder(0.025f, 0f, 0.42f, 8), 0f, 0.2f, 0f), Silver)
            head(sphere(0f, 0.66f, 0f, 0.07f), glow(0xFFFF2E4D))
        }
        // Bug: antennae and a split ladybird shell on the back.
        else -> fit {
            body(shell(0.15f, 1.25f, 0.06f, phi0 = 1.75f, phi1 = TAU - 1.75f, flare = 0.04f, repeatU = 2f), printed(dots(0xFFD62828, 0xFF111111, 4, 0.22f), 0.25f))
            body(tube(listOf(onBody(1.25f, PI_F, 0.07f), onBody(0.15f, PI_F, 0.07f)), 0.02f), Ink)
            head(merge(listOf(-1f, 1f).map { s -> tube(curve(floatArrayOf(s * 0.18f, 0.22f, 0.15f), floatArrayOf(s * 0.3f, 0.65f, 0.3f), floatArrayOf(s * 0.5f, 0.75f, 0.28f)), 0.02f, 0.015f) }), Ink)
            head(merge(listOf(-1f, 1f).map { s -> sphere(s * 0.5f, 0.75f, 0.28f, 0.07f) }), glow(0xFF39FF88))
        }
    }

    private fun morse(k: Int) = when (k) {
        // Captain: white cap with black visor and badge, navy jacket, megaphone.
        MOCHI -> fit {
            body(lower(0.42f, 0.02f), felt(0xFF14213D))
            body(buttons(listOf(0.36f, 0.26f, 0.16f), 0.032f, 0.2f), Gold)
            body(buttons(listOf(0.36f, 0.26f, 0.16f), 0.032f, -0.2f), Gold)
            head(cylinder(0.79f, -0.06f, 0.16f, 40), felt(0xFF14213D))
            head(transform(lathe(listOf(0f to 0f, 0.9f to 0.02f, 0.95f to 0.12f, 0.6f to 0.2f, 0f to 0.22f), 40), Mat4.translate(0f, 0.14f, -0.06f)), felt(0xFFF8F8F8))
            head(visor(0.48f, 0.3f, 0.74f, -0.02f, -14f), gloss(0xFF101010))
            head(at(disc(0.09f, 0.02f), 0f, 0.07f, 0.8f), Gold)
            hand(1, transform(lathe(listOf(0.05f to 0f, 0.07f to 0.12f, 0.2f to 0.45f, 0.2f to 0.47f, 0.18f to 0.47f, 0.05f to 0.14f), 24), Mat4.rotateZ(-70f)), Cloth(0xFFF8F8F8, 0.4f))
            hand(1, transform(lathe((0..10).map { i -> val a = i / 10f * TAU; (0.2f + 0.015f * cos(a)) to (0.46f + 0.015f * sin(a)) }, 24), Mat4.rotateZ(-70f)), metal(0xFFE63946, 0.4f))
        }
        // Pilot: leather cap with flaps, goggles on the forehead, long white scarf.
        BO -> fit {
            body(lower(0.42f, 0.02f), leather(0xFF795548))
            body(ring(0.42f, 0.055f, 0.02f), cotton(0xFFF8F8F8))
            body(tube(curve(onBody(0.44f, 2.6f, 0.08f), onBody(0.42f, 3.4f, 0.4f), floatArrayOf(-0.9f, 0.2f, -1.3f)), 0.06f, 0.05f), cotton(0xFFF8F8F8))
            head(headShell(-0.18f, 0.04f), leather(0xFF6D4C41))
            head(pair(transform(ellipsoid(0f, 0f, 0f, 0.07f, 0.22f, 0.16f, 6, 12), Mat4.translate(0.82f, -0.14f, 0.05f))), leather(0xFF6D4C41))
            head(headBand(0.06f, 0.035f, 0.05f), leather(0xFF3E2723))
            val g = onBody(HEAD_Y + 0.06f, 0.32f, 0.08f)
            head(pair(at(hoop(0.12f, 0.03f, 20), g[0], g[1] - HEAD_Y, g[2], Mat4.rotateY(18f))), Brass)
            head(pair(at(disc(0.12f, 0.02f), g[0], g[1] - HEAD_Y, g[2] + 0.01f, Mat4.rotateY(18f))), glass(0xFF90CAF9, 0.55f))
        }
        // Diver: brass helmet with a round window, bolts and a hose.
        SODA -> fit {
            val w = face(0.85f, 0f, 0.12f)
            body(lower(0.42f, 0.03f), cotton(0xFF8D6E63))
            body(ring(0.43f, 0.08f, 0.02f), Brass)
            val helmet = lathe(listOf(1.08f to 0.44f) + dome(1.08f, 1.45f, 0.44f, 16).drop(1), 48)
            body(cut(helmet) { x, y, z -> z > 0f && x * x + (y - w[1]) * (y - w[1]) < 0.5f * 0.5f }, Brass)
            body(at(hoop(0.5f, 0.055f, 36), 0f, w[1], 1.06f), Brass)
            body(at(disc(0.5f, 0.02f), 0f, w[1], 1.05f), glass(0xFFB3E5FC, 0.18f))
            body(merge((0 until 10).map { i -> val a = i * TAU / 10; sphere(cos(a) * 0.58f, w[1] + sin(a) * 0.58f, 1.02f, 0.045f, 5, 8) }), Brass)
            body(tube(curve(floatArrayOf(0f, 1.4f, -0.95f), floatArrayOf(0f, 1.9f, -1.5f), floatArrayOf(0.3f, 0.6f, -1.6f)), 0.07f), leather(0xFF263238))
        }
        // Telegraph operator: green eyeshade, headphones, blinking antenna.
        else -> fit {
            body(lower(0.42f, 0.02f), cotton(0xFFF4F1E8))
            body(merge(listOf(-1f, 1f).map { s -> transform(front(0.05f, 0.43f, 0.05f, 0.05f), Mat4.rotateY(s * 25f)) }), cotton(0xFF6D4C41))
            head(headBand(0.0f, 0.03f, 0.02f), cotton(0xFF2E7D32))
            head(transform(lathe(listOf(0.76f to 0f, 1.05f to -0.08f), 32, phi0 = -1.2f, phi1 = 1.2f), Mat4.translate(0f, 0.0f, 0f)), glass(0xFF43A047, 0.7f))
            head(overHead(0.025f, 0.05f, 1.35f), Ink)
            head(pair(at(transform(cylinder(0.2f, -0.06f, 0.06f, 20), Mat4.rotateZ(90f)), 0.99f, -0.44f, 0f)), leather(0xFF263238))
            head(at(cylinder(0.02f, 0f, 0.5f, 8), 0.1f, 0.2f, -0.05f, Mat4.rotateZ(-8f)), Silver)
            head(sphere(0.17f, 0.72f, -0.05f, 0.07f), glow(0xFFFF5252))
        }
    }

    private fun cats(k: Int) = when (k) {
        // Queen: crown, ears, velvet cape with ermine trim.
        MOCHI -> fit {
            catEars(this, fur(0xFFF4C7A1), fur(0xFFFFB3C6))
            body(cape(0.1f, 1.0f, 0.18f), silk(0xFF8E1B3A))
            body(ring(1.0f, 0.06f, 0.06f, phi0 = 1.9f, phi1 = TAU - 1.9f), fur(0xFFF8F8F8))
            head(lathe(listOf(0.5f to 0.14f, 0.52f to 0.32f, 0.48f to 0.32f, 0.46f to 0.16f), 40), Gold)
            head(merge((0 until 5).map { i -> val a = i * TAU / 5; at(lathe(listOf(0f to 0f, 0.07f to 0f, 0f to 0.16f), 8), cos(a) * 0.5f, 0.32f, sin(a) * 0.5f) }), Gold)
            head(merge((0 until 5).map { i -> val a = i * TAU / 5 + 0.6f; sphere(cos(a) * 0.53f, 0.23f, sin(a) * 0.53f, 0.035f, 5, 8) }), glass(0xFFE63946, 0.95f))
        }
        // Lion: a big mane all round the face, round ears.
        BO -> fit {
            body(merge((0 until 22).map { i ->
                val a = i / 22f * TAU
                val y = 0.78f + 0.64f * cos(a)
                val x = 0.98f * sin(a)
                val ry = radiusAt(y.coerceIn(0.05f, 1.68f))
                val z = sqrt((ry * ry - x * x).coerceAtLeast(0f)) * 0.55f
                sphere(x * 1.02f, y, z - 0.15f, 0.26f, 6, 10)
            }), fur(0xFFC77D2E))
            head(pair(transform(sphere(0f, 0f, 0f, 0.15f, 8, 12), Mat4.of(Mat4.translate(0.5f, 0.16f, 0.1f), Mat4.scale(1f, 1f, 0.5f)))), fur(0xFFE0A15A))
            body(lower(0.3f, 0.02f), fur(0xFFE0A15A))
        }
        // Maneki-neko: ears, red collar with a bell, gold koban in the left paw.
        SODA -> fit {
            catEars(this, fur(0xFFFAFAFA), fur(0xFFFFB3C6))
            body(ring(0.43f, 0.045f, 0.02f), silk(0xFFD62828))
            body(face(0.34f, 0f, 0.1f).let { sphere(it[0], it[1], it[2], 0.1f, 8, 12) }, Gold)
            hand(-1, transform(cylinder(0.22f, -0.02f, 0.02f, 24), Mat4.of(Mat4.rotateX(90f), Mat4.scale(1f, 1.4f, 1f))), Gold)
        }
        // Kitten in a cardboard box.
        else -> fit {
            catEars(this, fur(0xFFF4A261), fur(0xFFFFB3C6))
            val w = 1.05f
            val h = 0.5f
            body(
                merge(listOf(
                    box(0f, h / 2f, w, w, h / 2f, 0.025f), box(0f, h / 2f, -w, w, h / 2f, 0.025f),
                    box(w, h / 2f, 0f, 0.025f, h / 2f, w), box(-w, h / 2f, 0f, 0.025f, h / 2f, w),
                    at(box(0f, 0f, 0f, w * 0.5f, 0.02f, 0.3f), 0f, h + 0.18f, w + 0.25f, Mat4.rotateX(55f)),
                    at(box(0f, 0f, 0f, 0.3f, 0.02f, w * 0.5f), w + 0.22f, h + 0.15f, 0f, Mat4.rotateZ(-60f)),
                )),
                printed(Cardboard, 0.95f),
            )
        }
    }
}
