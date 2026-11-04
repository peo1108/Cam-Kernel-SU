package cam.su.kernel.ui.screen.home.arena.three

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Body proportions in world units (one unit = the slime's stage radius). */
internal const val BODY_HALF_WIDTH = 0.98f
internal const val BODY_HEIGHT = 1.72f

/** Face texture covers this much of the body front: x in ±FACE_HALF_SPAN, y in [FACE_BOTTOM, FACE_TOP]·H. */
internal const val FACE_HALF_SPAN = 0.75f
internal const val FACE_BOTTOM = 0.2f
internal const val FACE_TOP = 0.95f

internal object Shapes3D {
    private const val TAU = (2 * PI).toFloat()

    private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3
    }

    /** Right half of the mochi silhouette from bottom center to top center, as (radius, height). */
    fun mochiProfile(steps: Int = 28): List<Pair<Float, Float>> {
        val w = BODY_HALF_WIDTH * 2f
        val h = BODY_HEIGHT
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            out += cubic(0f, w * 0.34f, w * 0.5f, w * 0.5f, t) to cubic(0f, 0f, h * 0.1f, h * 0.45f, t)
        }
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            out += cubic(w * 0.5f, w * 0.5f, w * 0.27f, 0f, t) to cubic(h * 0.45f, h * 0.82f, h, h, t)
        }
        return out
    }

    /**
     * Surface of revolution of [profile] (radius, height) around +Y, between angles [phiFrom] and
     * [phiTo] measured from +Z (the side facing the camera). [offset] pushes the surface out along
     * its normal (for decals). [uv] maps (x, y, z, profile t, phi t) to texture coordinates.
     */
    fun lathe(
        profile: List<Pair<Float, Float>>,
        segments: Int,
        phiFrom: Float = 0f,
        phiTo: Float = TAU,
        offset: Float = 0f,
        uv: (Float, Float, Float, Float, Float) -> Pair<Float, Float>,
    ): MeshData {
        val rings = profile.size
        val cols = segments + 1
        val pos = FloatArray(rings * cols * 3)
        val nor = FloatArray(rings * cols * 3)
        val tex = FloatArray(rings * cols * 2)
        for (i in 0 until rings) {
            val prev = profile[maxOf(0, i - 1)]
            val next = profile[minOf(rings - 1, i + 1)]
            var dx = next.first - prev.first
            var dy = next.second - prev.second
            val len = hypot(dx, dy).coerceAtLeast(1e-6f)
            dx /= len
            dy /= len
            // Outward normal in the profile plane.
            val nr = dy
            val ny = -dx
            val (r, y) = profile[i]
            for (j in 0..segments) {
                val ft = j / segments.toFloat()
                val phi = phiFrom + (phiTo - phiFrom) * ft
                val sx = sin(phi)
                val cz = cos(phi)
                val k = (i * cols + j)
                val rr = r + nr * offset
                val yy = y + ny * offset
                pos[k * 3] = rr * sx
                pos[k * 3 + 1] = yy
                pos[k * 3 + 2] = rr * cz
                nor[k * 3] = nr * sx
                nor[k * 3 + 1] = ny
                nor[k * 3 + 2] = nr * cz
                val (u, v) = uv(rr * sx, yy, rr * cz, i / (rings - 1f), ft)
                tex[k * 2] = u
                tex[k * 2 + 1] = v
            }
        }
        val idx = IntArray((rings - 1) * segments * 6)
        var n = 0
        for (i in 0 until rings - 1) {
            for (j in 0 until segments) {
                val a = i * cols + j
                val b = a + cols
                idx[n++] = a; idx[n++] = a + 1; idx[n++] = b
                idx[n++] = b; idx[n++] = a + 1; idx[n++] = b + 1
            }
        }
        return MeshData(pos, nor, tex, idx)
    }

    private const val PROFILE_STEPS = 40
    private const val BODY_SEGMENTS = 96

    /** Seam at the back (phi = π) so morphs never tear the front. */
    fun body() = lathe(mochiProfile(PROFILE_STEPS), BODY_SEGMENTS, PI.toFloat(), 3f * PI.toFloat()) { _, _, _, t, f -> f to t }

    /**
     * The front patch of the body that carries the painted face, a hair above the jelly. It uses
     * the body's own rings and columns so the two surfaces never cross, and stops well short of
     * the silhouette, where the two would otherwise interleave into a jagged edge.
     */
    fun faceDecal(): MeshData {
        val h = BODY_HEIGHT
        val profile = mochiProfile(PROFILE_STEPS).filter { it.second in (FACE_BOTTOM + 0.02f) * h..(FACE_TOP - 0.02f) * h && it.first > 0.05f }
        val span = (FACE_TOP - FACE_BOTTOM) * h
        val reach = 0.95f
        val columns = (BODY_SEGMENTS * 2f * reach / TAU).toInt()
        return lathe(profile, columns, -reach, reach, offset = 0.012f) { x, y, _, _, _ ->
            (x + FACE_HALF_SPAN) / (2f * FACE_HALF_SPAN) to (FACE_TOP * h - y) / span
        }
    }

    /** Vietnamese conical hat, apex up, rim at y = 0. */
    fun nonLa(): MeshData {
        val rim = BODY_HALF_WIDTH * 1.36f
        val height = BODY_HEIGHT * 0.4f
        val profile = ArrayList<Pair<Float, Float>>()
        val steps = 24
        // Underside lip, rim, then a gently concave cone up to the apex.
        profile += rim * 0.96f to -0.01f
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val r = rim * (1f - t)
            val y = height * (t + 0.12f * t * (1f - t))
            profile += r to y
        }
        return lathe(profile, 64) { _, _, _, t, f -> f * 6f to t }
    }

    /** Flat rectangle on the XZ plane (ground), uv v=0 at the back edge. */
    fun ground(halfW: Float, zBack: Float, zFront: Float): MeshData = MeshData(
        floatArrayOf(-halfW, 0f, zBack, halfW, 0f, zBack, -halfW, 0f, zFront, halfW, 0f, zFront),
        floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        intArrayOf(0, 2, 1, 1, 2, 3),
    )

    /** Unit rectangle on the XY plane facing +Z, centered on the origin. */
    fun quad(): MeshData = MeshData(
        floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
        intArrayOf(0, 1, 2, 2, 1, 3),
    )
}

/** Jelly arm hanging down -Y from the shoulder at the origin: tapered limb ending in a round hand. */
internal fun Shapes3D.arm(): MeshData {
    val len = 0.55f
    val limb = 0.1f
    val hand = 0.14f
    val profile = ArrayList<Pair<Float, Float>>()
    // Hand ball (bottom pole up to its equator), limb, then a rounded shoulder cap.
    for (i in 0..10) {
        val a = -Math.PI.toFloat() / 2f + i / 10f * Math.PI.toFloat() / 2f
        profile += hand * kotlin.math.cos(a) to -len + hand * kotlin.math.sin(a)
    }
    profile += limb * 1.05f to -len + hand * 1.15f
    profile += limb to -len * 0.4f
    for (i in 0..8) {
        val a = i / 8f * Math.PI.toFloat() / 2f
        profile += limb * kotlin.math.cos(a) to limb * kotlin.math.sin(a)
    }
    return lathe(profile, 24) { _, _, _, t, f -> f to t }
}

/** Column-major 4×4 helpers for node transforms. */
internal object Mat4 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
        return out
    }

    fun translate(x: Float, y: Float, z: Float) = identity().also {
        it[12] = x
        it[13] = y
        it[14] = z
    }

    fun scale(x: Float, y: Float, z: Float) = identity().also {
        it[0] = x
        it[5] = y
        it[10] = z
    }

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble())

    fun rotateX(deg: Float) = identity().also {
        val c = kotlin.math.cos(rad(deg)).toFloat()
        val s = kotlin.math.sin(rad(deg)).toFloat()
        it[5] = c; it[6] = s; it[9] = -s; it[10] = c
    }

    fun rotateY(deg: Float) = identity().also {
        val c = kotlin.math.cos(rad(deg)).toFloat()
        val s = kotlin.math.sin(rad(deg)).toFloat()
        it[0] = c; it[2] = -s; it[8] = s; it[10] = c
    }

    fun rotateZ(deg: Float) = identity().also {
        val c = kotlin.math.cos(rad(deg)).toFloat()
        val s = kotlin.math.sin(rad(deg)).toFloat()
        it[0] = c; it[1] = s; it[4] = -s; it[5] = c
    }

    fun of(vararg m: FloatArray): FloatArray = m.reduce { acc, x -> mul(acc, x) }

    /** A matrix that hides an entity far below the stage. */
    val HIDDEN = of(translate(0f, -100f, 0f), scale(0.001f, 0.001f, 0.001f))
}

/** Unit quad facing +Z whose UVs repeat [tilesU] × [tilesV] times (for tiling textures). */
internal fun Shapes3D.tiledQuad(tilesU: Float, tilesV: Float): MeshData = MeshData(
    floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f, 0.5f, 0f),
    floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
    floatArrayOf(0f, tilesV, tilesU, tilesV, 0f, 0f, tilesU, 0f),
    intArrayOf(0, 1, 2, 2, 1, 3),
)
