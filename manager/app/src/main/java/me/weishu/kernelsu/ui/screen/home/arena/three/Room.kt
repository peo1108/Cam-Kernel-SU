package me.weishu.kernelsu.ui.screen.home.arena.three

import me.weishu.kernelsu.ui.screen.home.arena.WallSplit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The inside of the diorama box as a soft photo-studio cyclorama: the left, back and right walls
 * are one continuous surface with rounded back corners, and the floor and the ceiling sweep up
 * (and down) into it through rounded coves, so no seam in the box is a hard edge.
 *
 * Three meshes share the plan: the walls, from the ceiling's edge down through the upper cove to
 * the floor's cove, carry the country panorama (u along the walls from the glass on the left round
 * to the glass on the right, v top to bottom); the floor runs on up through the lower cove with
 * its tiling ground texture; the flat ceiling has a sky gradient that starts with exactly the
 * panorama's top color, so the bend overhead shows no seam.
 * The room reaches forward past the glass so the coves also fill the card's corners.
 */
internal class Room(
    val width: Float,
    val height: Float,
    val front: Float,
    val back: Float,
    /** z of the glass: the panorama spans the walls from here round to here again. */
    glass: Float = front,
    corner: Float = 1.6f,
    cove: Float = 0.9f,
) {
    private class PathPoint(val x: Float, val z: Float, val nx: Float, val nz: Float, val s: Float)

    private val cove = cove.coerceIn(0.2f, height * 0.3f)
    private val corner = max(corner, this.cove + 0.3f)
    private val path: List<PathPoint> = buildPath()

    /** Length of the walls' run, which sets the panorama's aspect ratio. */
    val wallLength: Float get() = path.last().s

    private val coveArc = this.cove * PI.toFloat() / 2f

    /** The floor's cove is tighter: a wide one reads as a ledge round the floor. */
    private val floorCove = this.cove * 0.45f

    /** Height the panorama is painted for: the upper cove's curve plus the straight wall below it. */
    val wallHeight: Float get() = coveArc + height - cove - floorCove

    /** Arc length (along the walls) of the glass line on the left; the panorama starts here. */
    private val glassS = (front - glass).coerceAtLeast(0f)

    /** Panorama length: the part of the walls behind the glass. */
    val visibleLength: Float get() = wallLength - 2f * glassS

    /** Where the walls bend, in panorama u: left corner start and end (the right ones mirror them). */
    val split: WallSplit

    init {
        val side = front - (back + this.corner) - glassS
        val arc = this.corner * PI.toFloat() / 2f
        split = WallSplit(side / visibleLength, (side + arc) / visibleLength)
    }

    private fun buildPath(): List<PathPoint> {
        val hw = width / 2f
        val r = corner
        val out = ArrayList<PathPoint>()
        var s = 0f
        fun add(x: Float, z: Float, nx: Float, nz: Float) {
            if (out.isNotEmpty()) s += hypot(x - out.last().x, z - out.last().z)
            out += PathPoint(x, z, nx, nz, s)
        }

        fun straight(x0: Float, z0: Float, x1: Float, z1: Float, nx: Float, nz: Float, skipFirst: Boolean) {
            val n = max(2, (hypot(x1 - x0, z1 - z0) / 1.2f).toInt())
            for (i in (if (skipFirst) 1 else 0)..n) {
                val t = i / n.toFloat()
                add(x0 + (x1 - x0) * t, z0 + (z1 - z0) * t, nx, nz)
            }
        }

        fun arc(cx: Float, cz: Float, from: Float, to: Float) {
            val n = 14
            for (i in 1..n) {
                val a = from + (to - from) * i / n
                val x = cx + r * cos(a)
                val z = cz + r * sin(a)
                add(x, z, (cx - x) / r, (cz - z) / r)
            }
        }
        val pi = PI.toFloat()
        straight(-hw, front, -hw, back + r, 1f, 0f, skipFirst = false)
        arc(-hw + r, back + r, pi, 1.5f * pi)
        straight(-hw + r, back, hw - r, back, 0f, 1f, skipFirst = true)
        arc(hw - r, back + r, 1.5f * pi, 2f * pi)
        straight(hw, back + r, hw, front, -1f, 0f, skipFirst = true)
        return out
    }

    /** Panorama u of the point [x] across the straight part of the back wall. */
    fun backU(x: Float): Float {
        val backStart = front - (back + corner) + corner * PI.toFloat() / 2f
        return (backStart + x - (-width / 2f + corner) - glassS) / visibleLength
    }

    /** Panorama v of height [y] on the straight part of the wall. */
    fun wallV(y: Float) = (coveArc + (height - cove - y)) / wallHeight

    /** The walls from the ceiling's edge, round the upper cove and down to the floor's cove. */
    fun walls(): MeshData {
        val cols = path.size
        val n = 10
        val rows = n + 1 + 3
        val pos = FloatArray(rows * cols * 3)
        val nor = FloatArray(rows * cols * 3)
        val uv = FloatArray(rows * cols * 2)
        val len = visibleLength
        val total = wallHeight
        for (i in 0 until rows) {
            val inset: Float
            val y: Float
            val nIn: Float
            val nY: Float
            val down: Float
            if (i <= n) {
                val a = i / n.toFloat() * PI.toFloat() / 2f
                inset = cove - cove * sin(a)
                y = height - cove + cove * cos(a)
                nIn = sin(a)
                nY = -cos(a)
                down = i / n.toFloat() * coveArc
            } else {
                val f = (i - n) / 3f
                inset = 0f
                y = height - cove - f * (height - cove - floorCove)
                nIn = 1f
                nY = 0f
                down = coveArc + f * (height - cove - floorCove)
            }
            for (j in 0 until cols) {
                val p = path[j]
                val k = i * cols + j
                pos[k * 3] = p.x + p.nx * inset
                pos[k * 3 + 1] = y
                pos[k * 3 + 2] = p.z + p.nz * inset
                val l = sqrt(nIn * nIn + nY * nY).coerceAtLeast(1e-6f)
                nor[k * 3] = p.nx * nIn / l
                nor[k * 3 + 1] = nY / l
                nor[k * 3 + 2] = p.nz * nIn / l
                uv[k * 2] = (p.s - glassS) / len
                uv[k * 2 + 1] = down / total
            }
        }
        return MeshData(pos, nor, uv, grid(rows, cols, flip = true))
    }

    /**
     * Floor plus the lower cove, as one surface. uv is planar (x, z) / [tile] on the flat part and
     * keeps unrolling up the cove, so the ground's pattern climbs the curve without a seam.
     */
    fun floor(tile: Float): MeshData = sweep(up = true) { x, z, arc, p ->
        if (p == null) floatArrayOf(x / tile, z / tile) else floatArrayOf((p.x + p.nx * (floorCove - arc)) / tile, (p.z + p.nz * (floorCove - arc)) / tile)
    }

    /** The flat ceiling inside the upper cove; v runs from 0 at its edge to 1 in the middle. */
    fun ceiling(): MeshData {
        val cols = path.size
        val pos = FloatArray((cols + 1) * 3)
        val nor = FloatArray((cols + 1) * 3)
        val uv = FloatArray((cols + 1) * 2)
        for (j in 0 until cols) {
            val p = path[j]
            pos[j * 3] = p.x + p.nx * cove
            pos[j * 3 + 1] = height
            pos[j * 3 + 2] = p.z + p.nz * cove
            nor[j * 3 + 1] = -1f
            uv[j * 2] = 0.5f
        }
        pos[cols * 3 + 1] = height
        pos[cols * 3 + 2] = (front + back) / 2f
        nor[cols * 3 + 1] = -1f
        uv[cols * 2] = 0.5f
        uv[cols * 2 + 1] = 1f
        val idx = IntArray(cols * 3)
        for (j in 0 until cols) {
            idx[j * 3] = cols
            idx[j * 3 + 1] = j
            idx[j * 3 + 2] = (j + 1) % cols
        }
        return MeshData(pos, nor, uv, idx)
    }

    /**
     * A cove strip along the walls (from the wall's edge to the flat part) and a fan closing the flat
     * part. [uvOf] gets (x, z, arc length from the flat part's edge, path point) for cove vertices,
     * or a null path point for the fan's center; fan edge vertices reuse the cove's inner row.
     */
    private fun sweep(up: Boolean, uvOf: (Float, Float, Float, PathPoint?) -> FloatArray): MeshData {
        val n = 10
        val cols = path.size
        val rows = n + 1
        val vCount = rows * cols + 1
        val pos = FloatArray(vCount * 3)
        val nor = FloatArray(vCount * 3)
        val uv = FloatArray(vCount * 2)
        val cove = floorCove
        val arcLen = cove * PI.toFloat() / 2f
        for (i in 0 until rows) {
            // i = 0 is the flat part's edge, i = n is where the cove meets the wall.
            val f = i / n.toFloat()
            val a = f * PI.toFloat() / 2f
            val inset = cove - cove * sin(a)
            val rise = cove - cove * cos(a)
            val y = if (up) rise else height - rise
            val nIn = sin(a)
            val nY = cos(a) * (if (up) 1f else -1f)
            for (j in 0 until cols) {
                val p = path[j]
                val k = i * cols + j
                val x = p.x + p.nx * inset
                val z = p.z + p.nz * inset
                pos[k * 3] = x
                pos[k * 3 + 1] = y
                pos[k * 3 + 2] = z
                val nx = p.nx * nIn
                val nz = p.nz * nIn
                val l = sqrt(nx * nx + nY * nY + nz * nz).coerceAtLeast(1e-6f)
                nor[k * 3] = nx / l
                nor[k * 3 + 1] = nY / l
                nor[k * 3 + 2] = nz / l
                val t = uvOf(x, z, f * arcLen, p)
                uv[k * 2] = t[0]
                uv[k * 2 + 1] = t[1]
            }
        }
        val c = rows * cols
        val cz = (front + back) / 2f
        pos[c * 3 + 1] = if (up) 0f else height
        pos[c * 3 + 2] = cz
        nor[c * 3 + 1] = if (up) 1f else -1f
        val t = uvOf(0f, cz, 0f, null)
        uv[c * 2] = t[0]
        uv[c * 2 + 1] = t[1]

        val strip = grid(rows, cols, flip = !up)
        val fan = IntArray(cols * 3)
        for (j in 0 until cols) {
            val a = j
            val b = (j + 1) % cols
            // The last triangle closes the fan across the front edge.
            if (up) {
                fan[j * 3] = c; fan[j * 3 + 1] = b; fan[j * 3 + 2] = a
            } else {
                fan[j * 3] = c; fan[j * 3 + 1] = a; fan[j * 3 + 2] = b
            }
        }
        return MeshData(pos, nor, uv, strip + fan)
    }

    private fun grid(rows: Int, cols: Int, flip: Boolean): IntArray {
        val idx = IntArray((rows - 1) * (cols - 1) * 6)
        var n = 0
        for (i in 0 until rows - 1) for (j in 0 until cols - 1) {
            val a = i * cols + j
            val b = a + cols
            if (flip) {
                idx[n++] = a; idx[n++] = a + 1; idx[n++] = b
                idx[n++] = b; idx[n++] = a + 1; idx[n++] = b + 1
            } else {
                idx[n++] = a; idx[n++] = b; idx[n++] = a + 1
                idx[n++] = b; idx[n++] = b + 1; idx[n++] = a + 1
            }
        }
        return idx
    }
}
