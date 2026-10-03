package me.weishu.kernelsu.ui.screen.home.arena.three

import me.weishu.kernelsu.ui.screen.home.arena.SlimePose
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shape keys of the jelly body. Arms are not separate limbs: each is the body's own jelly pulled
 * out of its side like dough (a smooth lump with a pinched neck and a round tip), so the surface
 * and its refraction stay one piece. Melt slumps the body into a puddle.
 */
internal enum class BodyMorph { ArmLDown, ArmLOut, ArmLUp, ArmLFwd, ArmRDown, ArmROut, ArmRUp, ArmRFwd, Melt }

internal object Morphs {
    private class Arm(val side: Int, dx: Float, dy: Float, dz: Float, val length: Float) {
        val dir: FloatArray = floatArrayOf(side * dx, dy, dz).also { normalize(it) }
        val anchor = floatArrayOf(side * 0.92f, 0.74f, 0.2f)
    }

    private val arms = mapOf(
        BodyMorph.ArmLDown to Arm(-1, 0.45f, -0.8f, 0.4f, 0.22f),
        BodyMorph.ArmLOut to Arm(-1, 1f, 0.15f, 0.2f, 0.48f),
        BodyMorph.ArmLUp to Arm(-1, 0.5f, 1f, 0.1f, 0.5f),
        BodyMorph.ArmLFwd to Arm(-1, -0.3f, -0.1f, 1f, 0.44f),
        BodyMorph.ArmRDown to Arm(1, 0.45f, -0.8f, 0.4f, 0.22f),
        BodyMorph.ArmROut to Arm(1, 1f, 0.15f, 0.2f, 0.48f),
        BodyMorph.ArmRUp to Arm(1, 0.5f, 1f, 0.1f, 0.5f),
        BodyMorph.ArmRFwd to Arm(1, -0.3f, -0.1f, 1f, 0.44f),
    )

    /** Radius of body surface each arm is pulled from: small, so the arms come out as slim nubs. */
    private const val REACH = 0.36f

    private fun normalize(v: FloatArray) {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        v[0] /= l
        v[1] /= l
        v[2] /= l
    }

    /** Displacement of the surface point (x, y, z) for [morph], written to [out]. */
    fun displace(morph: BodyMorph, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = 0f
        out[1] = 0f
        out[2] = 0f
        if (morph == BodyMorph.Melt) {
            val yn = (y / BODY_HEIGHT).coerceIn(0f, 1f)
            out[0] = x * 0.4f * (1f - yn)
            out[1] = -y * 0.44f
            out[2] = z * 0.4f * (1f - yn)
            return
        }
        val arm = arms.getValue(morph)
        val ax = arm.anchor[0] - x
        val ay = arm.anchor[1] - y
        val az = arm.anchor[2] - z
        val d = sqrt(ax * ax + ay * ay + az * az)
        if (d >= REACH) return
        val w = 1f - d / REACH
        val f = w * w * (3f - 2f * w)
        // A flat-topped push gives the nub a rounded end instead of a cone's point.
        val push = arm.length * Math.pow(f.toDouble(), 0.8).toFloat()
        // Pinch toward the arm's axis only around mid-falloff: a slim neck, then a round tip.
        val along = ax * arm.dir[0] + ay * arm.dir[1] + az * arm.dir[2]
        val px = ax - arm.dir[0] * along
        val py = ay - arm.dir[1] * along
        val pz = az - arm.dir[2] * along
        val pinch = 1.1f * f * (1f - f)
        out[0] = arm.dir[0] * push + px * pinch
        out[1] = arm.dir[1] * push + py * pinch
        out[2] = arm.dir[2] * push + pz * pinch
    }

    /** Morph targets for [mesh]: per-vertex position deltas and the matching normal deltas. */
    fun targetsFor(mesh: MeshData): List<Pair<FloatArray, FloatArray>> {
        val n = mesh.positions.size / 3
        val base = vertexNormals(mesh.positions, mesh.indices)
        val tmp = FloatArray(3)
        return BodyMorph.entries.map { morph ->
            val dp = FloatArray(n * 3)
            val moved = mesh.positions.copyOf()
            for (i in 0 until n) {
                displace(morph, mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2], tmp)
                for (c in 0 until 3) {
                    dp[i * 3 + c] = tmp[c]
                    moved[i * 3 + c] += tmp[c]
                }
            }
            val bent = vertexNormals(moved, mesh.indices)
            val dn = FloatArray(n * 3) { bent[it] - base[it] }
            dp to dn
        }
    }

    private fun vertexNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val nor = FloatArray(pos.size)
        var t = 0
        while (t < idx.size) {
            val a = idx[t] * 3
            val b = idx[t + 1] * 3
            val c = idx[t + 2] * 3
            val ux = pos[b] - pos[a]
            val uy = pos[b + 1] - pos[a + 1]
            val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]
            val vy = pos[c + 1] - pos[a + 1]
            val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) {
                nor[v] += nx
                nor[v + 1] += ny
                nor[v + 2] += nz
            }
            t += 3
        }
        for (i in 0 until nor.size / 3) {
            val l = sqrt(nor[i * 3] * nor[i * 3] + nor[i * 3 + 1] * nor[i * 3 + 1] + nor[i * 3 + 2] * nor[i * 3 + 2]).coerceAtLeast(1e-6f)
            nor[i * 3] /= l
            nor[i * 3 + 1] /= l
            nor[i * 3 + 2] /= l
        }
        return nor
    }

    /** Shape-key weights for a pose, in [BodyMorph] order. */
    fun weights(p: SlimePose, out: FloatArray) {
        arm(p.armL, p.reachL, out, 0)
        arm(p.armR, p.reachR, out, 4)
        if (p.cross > 0.01f) {
            for (base in intArrayOf(0, 4)) {
                for (k in 0 until 3) out[base + k] *= 1f - p.cross
                out[base + 3] = max(out[base + 3], p.cross)
            }
        }
        if (p.akimbo > 0.01f) {
            for (base in intArrayOf(0, 4)) {
                out[base] = lerp(out[base], 0.75f, p.akimbo)
                out[base + 1] = lerp(out[base + 1], 0.45f, p.akimbo)
                out[base + 2] *= 1f - p.akimbo
            }
        }
        out[8] = p.melt.coerceIn(0f, 1f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private val scratch = FloatArray(BodyMorph.entries.size)

    /**
     * Where the tip of the [side] arm is, in body coordinates, for something held in that hand:
     * the arm's anchor pushed out the way its shape keys pull it, slumped with the melt.
     */
    fun hand(p: SlimePose, side: Int, out: FloatArray) {
        weights(p, scratch)
        val base = if (side < 0) 0 else 4
        var x = side * 0.92f
        var y = 0.74f
        var z = 0.2f
        for (i in 0 until 4) {
            val arm = arms.getValue(BodyMorph.entries[base + i])
            val w = scratch[base + i]
            x += arm.dir[0] * arm.length * w * 1.15f
            y += arm.dir[1] * arm.length * w * 1.15f
            z += arm.dir[2] * arm.length * w * 1.15f
        }
        val melt = scratch[8]
        val yn = (y / BODY_HEIGHT).coerceIn(0f, 1f)
        out[0] = x * (1f + 0.4f * melt * (1f - yn))
        out[1] = y * (1f - 0.44f * melt)
        out[2] = z * (1f + 0.4f * melt * (1f - yn))
    }

    private fun arm(angle: Float, reach: Float, out: FloatArray, base: Int) {
        val r = reach.coerceIn(0f, 1.5f)
        val a = angle.coerceIn(0f, 170f)
        if (a <= 90f) {
            out[base] = (1f - a / 90f) * r * 0.9f
            out[base + 1] = a / 90f * r
            out[base + 2] = 0f
        } else {
            val u = ((a - 90f) / 70f).coerceIn(0f, 1f)
            out[base] = 0f
            out[base + 1] = (1f - u) * r
            out[base + 2] = u * r
        }
        out[base + 3] = 0f
    }
}
