package cam.su.kernel.ui.screen.home.arena.three

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry helpers for the slimes' costumes, in the body's own units (half width
 * [BODY_HALF_WIDTH], height [BODY_HEIGHT], front facing +Z). Garments hug the jelly using its
 * real silhouette, hats are modelled round the head anchor ([HEAD_Y] is their y = 0), and props
 * are plain primitives moved into place with [Mat4] transforms.
 */
internal object Tailor {
    /** Where hats sit: their y = 0 is this high on the body. */
    const val HEAD_Y = BODY_HEIGHT * 0.84f

    private const val TAU = (2 * PI).toFloat()
    private val profile = Shapes3D.mochiProfile(80)

    /** Radius of the jelly at height [y] (0 at the very bottom and top). */
    fun radiusAt(y: Float): Float {
        if (y <= 0f) return profile.first().first
        if (y >= BODY_HEIGHT) return 0f
        for (i in 1 until profile.size) {
            val (r0, y0) = profile[i - 1]
            val (r1, y1) = profile[i]
            if (y <= y1) return if (y1 - y0 < 1e-6f) r1 else r0 + (r1 - r0) * (y - y0) / (y1 - y0)
        }
        return 0f
    }

    /** Radius of the head [h] above [HEAD_Y]. */
    fun headRadius(h: Float) = radiusAt(HEAD_Y + h)

    /** A point on the jelly's surface at height [y], [phi] radians round from the front, [out] off it. */
    fun onBody(y: Float, phi: Float, out: Float = 0f): FloatArray {
        val r = radiusAt(y) + out
        return floatArrayOf(r * sin(phi), y, r * cos(phi))
    }

    /**
     * A garment hugging the body from [y0] to [y1], [gap] off the jelly, between [phi0] and [phi1]
     * (0 is the front; the default wraps all the way round with the seam at the back). [flare]
     * swings the hem out. uv: u round the body ([repeatU] times), v from top (0) to bottom (1).
     */
    fun shell(
        y0: Float,
        y1: Float,
        gap: Float,
        phi0: Float = PI.toFloat(),
        phi1: Float = 3f * PI.toFloat(),
        flare: Float = 0f,
        rings: Int = 14,
        segments: Int = 56,
        repeatU: Float = 1f,
    ): MeshData {
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0..rings) {
            val y = y0 + (y1 - y0) * i / rings
            val f = 1f - i / rings.toFloat()
            pts += (radiusAt(y) + flare * f * f) to y
        }
        return Shapes3D.lathe(pts, segments, phi0, phi1, offset = gap) { _, _, _, t, f -> f * repeatU to 1f - t }
    }

    /**
     * A round tube (torus) hugging the body at height [y]: belts' edges, collars, trims. [phi0] to
     * [phi1] limit it to an arc (0 is the front), for trims that run only round the back.
     */
    fun ring(y: Float, tube: Float, gap: Float = 0f, segments: Int = 56, sides: Int = 10, phi0: Float = 0f, phi1: Float = TAU): MeshData {
        val center = radiusAt(y) + gap + tube
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0..sides) {
            val a = i / sides.toFloat() * TAU
            pts += (center + tube * cos(a)) to (y + tube * sin(a))
        }
        return Shapes3D.lathe(pts, segments, phi0, phi1) { _, _, _, t, f -> f * 8f to t }
    }

    /** A hoop of [radius] made of a [tube] facing +Z at the origin: rims, monocles, portholes. */
    fun hoop(radius: Float, tube: Float, segments: Int = 32): MeshData {
        val pts = (0..10).map { i ->
            val a = i / 10f * TAU
            (radius + tube * cos(a)) to (tube * sin(a))
        }
        return transform(Shapes3D.lathe(pts, segments) { _, _, _, t, f -> f to t }, Mat4.rotateX(90f))
    }

    /**
     * An arc over the top of the head from one ear to the other, hugging it (headbands, headsets):
     * in head coordinates, so it goes in a Head slot.
     */
    fun overHead(tube: Float, gap: Float = 0.03f, reach: Float = 1.3f, tilt: Float = 0f): MeshData {
        val pts = (0..10).map { i ->
            val a = i / 10f * TAU
            (1f + gap + tube * cos(a)) to (tube * sin(a))
        }
        val arc = Shapes3D.lathe(pts, 36, -reach, reach) { _, _, _, t, f -> f to t }
        return transform(arc, Mat4.of(Mat4.translate(0f, -0.72f, 0f), Mat4.rotateX(tilt), Mat4.rotateZ(0f), Mat4.rotateX(-90f)))
    }

    /** Drops the triangles whose centroid [inside] says to cut away (a window in a helmet). */
    fun cut(mesh: MeshData, inside: (Float, Float, Float) -> Boolean): MeshData {
        val keep = ArrayList<Int>()
        val p = mesh.positions
        for (t in 0 until mesh.indices.size / 3) {
            val a = mesh.indices[t * 3]
            val b = mesh.indices[t * 3 + 1]
            val c = mesh.indices[t * 3 + 2]
            val x = (p[a * 3] + p[b * 3] + p[c * 3]) / 3f
            val y = (p[a * 3 + 1] + p[b * 3 + 1] + p[c * 3 + 1]) / 3f
            val z = (p[a * 3 + 2] + p[b * 3 + 2] + p[c * 3 + 2]) / 3f
            if (!inside(x, y, z)) keep += listOf(a, b, c)
        }
        return MeshData(mesh.positions, mesh.normals, mesh.uvs, keep.toIntArray())
    }

    /** A surface of revolution round the head anchor from (radius, height) pairs. */
    fun lathe(points: List<Pair<Float, Float>>, segments: Int = 48, repeatU: Float = 1f, phi0: Float = 0f, phi1: Float = TAU): MeshData =
        Shapes3D.lathe(points, segments, phi0, phi1) { _, _, _, t, f -> f * repeatU to t }

    /** Profile of a dome of [radius] standing [height] tall from y = [base]. */
    fun dome(radius: Float, height: Float, base: Float = 0f, steps: Int = 10): List<Pair<Float, Float>> =
        (0..steps).map { i ->
            val a = i / steps.toFloat() * PI.toFloat() / 2f
            radius * cos(a) to base + height * sin(a)
        }

    fun sphere(cx: Float, cy: Float, cz: Float, r: Float, rings: Int = 10, segments: Int = 16) = ellipsoid(cx, cy, cz, r, r, r, rings, segments)

    fun ellipsoid(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, rings: Int = 10, segments: Int = 16): MeshData {
        val pts = (0..rings).map { i ->
            val a = -PI.toFloat() / 2f + i / rings.toFloat() * PI.toFloat()
            cos(a) to sin(a)
        }
        val unit = Shapes3D.lathe(pts, segments) { _, _, _, t, f -> f to t }
        return transform(unit, Mat4.of(Mat4.translate(cx, cy, cz), Mat4.scale(rx, ry, rz)))
    }

    /** A flat cylinder (or a rod) of [radius] along Y from [y0] to [y1], capped. */
    fun cylinder(radius: Float, y0: Float, y1: Float, segments: Int = 24, radiusTop: Float = radius): MeshData =
        Shapes3D.lathe(
            listOf(0f to y0, radius to y0, radius to y0, radiusTop to y1, radiusTop to y1, 0f to y1),
            segments,
        ) { _, _, _, t, f -> f to t }

    /** A box centered at (cx, cy, cz) with half sizes (hx, hy, hz) and flat faces. */
    fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float): MeshData {
        val pos = ArrayList<Float>()
        val nor = ArrayList<Float>()
        val uv = ArrayList<Float>()
        val idx = ArrayList<Int>()
        // (normal, u axis, v axis)
        val faces = listOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        )
        val h = floatArrayOf(hx, hy, hz)
        for (f in faces) {
            val base = pos.size / 3
            for ((su, sv) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                for (c in 0 until 3) pos += floatArrayOf(cx, cy, cz)[c] + (f[c] + f[3 + c] * su + f[6 + c] * sv) * h[c]
                for (c in 0 until 3) nor += f[c]
                uv += (su + 1f) / 2f
                uv += (1f - sv) / 2f
            }
            idx += listOf(base, base + 1, base + 2, base + 2, base + 1, base + 3)
        }
        return MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }

    /** A round tube swept along [points] (x, y, z triples) with [radius], tapering to [tipRadius]. */
    fun tube(points: List<FloatArray>, radius: Float, tipRadius: Float = radius, sides: Int = 10): MeshData {
        val n = points.size
        val pos = FloatArray(n * (sides + 1) * 3)
        val nor = FloatArray(n * (sides + 1) * 3)
        val uv = FloatArray(n * (sides + 1) * 2)
        var prevNormal: FloatArray? = null
        for (i in 0 until n) {
            val a = points[max(0, i - 1)]
            val b = points[min(n - 1, i + 1)]
            val t = norm(floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
            // A frame carried along the curve so the tube does not twist.
            var nrm = prevNormal ?: perpendicular(t)
            val d = dot(nrm, t)
            nrm = norm(floatArrayOf(nrm[0] - t[0] * d, nrm[1] - t[1] * d, nrm[2] - t[2] * d))
            prevNormal = nrm
            val bin = cross(t, nrm)
            val r = radius + (tipRadius - radius) * i / max(1, n - 1).toFloat()
            for (j in 0..sides) {
                val ang = j / sides.toFloat() * TAU
                val k = i * (sides + 1) + j
                val dx = nrm[0] * cos(ang) + bin[0] * sin(ang)
                val dy = nrm[1] * cos(ang) + bin[1] * sin(ang)
                val dz = nrm[2] * cos(ang) + bin[2] * sin(ang)
                pos[k * 3] = points[i][0] + dx * r
                pos[k * 3 + 1] = points[i][1] + dy * r
                pos[k * 3 + 2] = points[i][2] + dz * r
                nor[k * 3] = dx
                nor[k * 3 + 1] = dy
                nor[k * 3 + 2] = dz
                uv[k * 2] = j / sides.toFloat()
                uv[k * 2 + 1] = i / max(1, n - 1).toFloat()
            }
        }
        val idx = IntArray((n - 1) * sides * 6)
        var m = 0
        for (i in 0 until n - 1) for (j in 0 until sides) {
            val a = i * (sides + 1) + j
            val b = a + sides + 1
            idx[m++] = a; idx[m++] = b; idx[m++] = a + 1
            idx[m++] = a + 1; idx[m++] = b; idx[m++] = b + 1
        }
        val body = MeshData(pos, nor, uv, idx)
        // Round caps at both ends.
        val caps = listOf(points.first() to radius, points.last() to tipRadius).map { (p, r) -> sphere(p[0], p[1], p[2], r, 6, sides) }
        return merge(listOf(body) + caps)
    }

    /** Points along a quadratic curve from [a] through control [c] to [b]. */
    fun curve(a: FloatArray, c: FloatArray, b: FloatArray, steps: Int = 12): List<FloatArray> = (0..steps).map { i ->
        val t = i / steps.toFloat()
        val u = 1f - t
        floatArrayOf(
            u * u * a[0] + 2 * u * t * c[0] + t * t * b[0],
            u * u * a[1] + 2 * u * t * c[1] + t * t * b[1],
            u * u * a[2] + 2 * u * t * c[2] + t * t * b[2],
        )
    }

    /** A flat disc of [radius] facing +Z at the origin, [thickness] deep, as a short capped cylinder. */
    fun disc(radius: Float, thickness: Float, segments: Int = 24): MeshData =
        transform(cylinder(radius, -thickness / 2f, thickness / 2f, segments), Mat4.rotateX(90f))

    /** Moves a mesh by [m]; normals use the same matrix and are renormalized. */
    fun transform(mesh: MeshData, m: FloatArray): MeshData {
        val n = mesh.positions.size / 3
        val pos = FloatArray(n * 3)
        val nor = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = mesh.positions[i * 3]
            val y = mesh.positions[i * 3 + 1]
            val z = mesh.positions[i * 3 + 2]
            pos[i * 3] = m[0] * x + m[4] * y + m[8] * z + m[12]
            pos[i * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
            pos[i * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
            val nx = mesh.normals[i * 3]
            val ny = mesh.normals[i * 3 + 1]
            val nz = mesh.normals[i * 3 + 2]
            val v = norm(
                floatArrayOf(
                    m[0] * nx + m[4] * ny + m[8] * nz,
                    m[1] * nx + m[5] * ny + m[9] * nz,
                    m[2] * nx + m[6] * ny + m[10] * nz,
                ),
            )
            nor[i * 3] = v[0]
            nor[i * 3 + 1] = v[1]
            nor[i * 3 + 2] = v[2]
        }
        return MeshData(pos, nor, mesh.uvs, mesh.indices)
    }

    fun at(mesh: MeshData, x: Float, y: Float, z: Float, vararg then: FloatArray): MeshData =
        transform(mesh, Mat4.of(Mat4.translate(x, y, z), *then.ifEmpty { arrayOf(Mat4.identity()) }))

    /** Sits a mesh on the jelly's surface at height [y], [phi] round from the front, facing out. */
    fun onSurface(mesh: MeshData, y: Float, phi: Float, out: Float = 0f, tilt: Float = 0f): MeshData {
        val p = onBody(y, phi, out)
        return transform(mesh, Mat4.of(Mat4.translate(p[0], p[1], p[2]), Mat4.rotateY(Math.toDegrees(phi.toDouble()).toFloat()), Mat4.rotateX(tilt)))
    }

    fun merge(meshes: List<MeshData>): MeshData {
        val pos = ArrayList<Float>()
        val nor = ArrayList<Float>()
        val uv = ArrayList<Float>()
        val idx = ArrayList<Int>()
        for (m in meshes) {
            val base = pos.size / 3
            m.positions.forEach { pos += it }
            m.normals.forEach { nor += it }
            m.uvs.forEach { uv += it }
            m.indices.forEach { idx += it + base }
        }
        return MeshData(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }

    /** Mirror copy across x = 0 (left and right pairs: ears, epaulettes, lenses). */
    fun mirrored(mesh: MeshData): MeshData {
        val pos = mesh.positions.copyOf()
        val nor = mesh.normals.copyOf()
        for (i in 0 until pos.size / 3) {
            pos[i * 3] = -pos[i * 3]
            nor[i * 3] = -nor[i * 3]
        }
        val idx = mesh.indices.copyOf()
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3 + 1]
            idx[t * 3 + 1] = idx[t * 3 + 2]
            idx[t * 3 + 2] = a
        }
        return MeshData(pos, nor, mesh.uvs, idx)
    }

    fun pair(mesh: MeshData) = merge(listOf(mesh, mirrored(mesh)))

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(v: FloatArray): FloatArray {
        val l = sqrt(dot(v, v)).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun perpendicular(t: FloatArray): FloatArray =
        norm(if (abs(t[1]) < 0.9f) cross(t, floatArrayOf(0f, 1f, 0f)) else cross(t, floatArrayOf(1f, 0f, 0f)))
}
