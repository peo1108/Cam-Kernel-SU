package me.weishu.kernelsu.ui.screen.home.arena.three

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import me.weishu.kernelsu.ui.screen.home.arena.Costume
import me.weishu.kernelsu.ui.screen.home.arena.SLIME_COUNT
import me.weishu.kernelsu.ui.screen.home.arena.SlimePose
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The slimes' costumes on the 3D stage. Each slime gets one glTF asset holding all nine of its
 * outfits, built on a background thread at start-up and loaded when ready, so the first frames
 * are not held up. Every outfit has a body group (its pieces carry the jelly's shape keys, so
 * clothes follow the arms and the melting), a head group placed on the head each frame (hats can
 * fall and sit crooked) and a group per hand. Only the outfit being worn is visible; the rest are
 * masked out of the view, so they cost nothing.
 */
internal class Closet(
    private val engine: Engine,
    private val assetLoader: AssetLoader,
    private val resourceLoader: ResourceLoader,
    private val scene: Scene,
) {
    private class Group(val entity: Int, val pieces: IntArray)

    private class Rack(val asset: FilamentAsset, val groups: Array<Array<Group?>>)

    private val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "arena-closet").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val racks = arrayOfNulls<Rack>(SLIME_COUNT)
    private val shownHead = IntArray(SLIME_COUNT) { -1 }
    private val shownBody = IntArray(SLIME_COUNT) { -1 }
    private val parents = IntArray(SLIME_COUNT)
    @Volatile
    private var closed = false
    private val tm get() = engine.transformManager
    private val rm get() = engine.renderableManager

    /** Starts sewing the outfits; each slime's appears once its asset is ready. */
    fun open() {
        for (k in 0 until SLIME_COUNT) {
            builder.execute {
                val glb = try {
                    sew(k)
                } catch (t: Throwable) {
                    null
                }
                if (glb != null) main.post { if (!closed) hang(k, glb) }
            }
        }
    }

    /** Hangs every slime's outfits under its root entity (again, after the stage is rebuilt). */
    fun attach(roots: IntArray) {
        for (k in 0 until SLIME_COUNT) {
            parents[k] = roots[k]
            attach(k)
        }
    }

    private fun attach(k: Int) {
        val rack = racks[k] ?: return
        val root = parents[k]
        if (root == 0) return
        val parent = tm.getInstance(root)
        for (row in rack.groups) for (g in row) {
            if (g != null) tm.setParent(tm.getInstance(g.entity), parent)
        }
    }

    /**
     * Shows what slime [k] is wearing in [p] and places it: [head] for the hat, [handL] and
     * [handR] for held props (all relative to the slime's root), [weights] for the body pieces.
     */
    fun wear(k: Int, p: SlimePose, head: FloatArray, handL: FloatArray, handR: FloatArray, weights: FloatArray) {
        val rack = racks[k] ?: return
        val headTheme = p.costume.ordinal
        val bodyTheme = (p.outfit ?: p.costume).ordinal
        if (shownHead[k] != headTheme) {
            show(rack, shownHead[k], Slot.Head, false)
            show(rack, headTheme, Slot.Head, true)
            shownHead[k] = headTheme
        }
        if (shownBody[k] != bodyTheme) {
            for (s in listOf(Slot.Body, Slot.HandL, Slot.HandR)) {
                show(rack, shownBody[k], s, false)
                show(rack, bodyTheme, s, true)
            }
            shownBody[k] = bodyTheme
        }
        rack.groups[headTheme][Slot.Head.ordinal]?.let { tm.setTransform(tm.getInstance(it.entity), head) }
        rack.groups[bodyTheme][Slot.HandL.ordinal]?.let { tm.setTransform(tm.getInstance(it.entity), handL) }
        rack.groups[bodyTheme][Slot.HandR.ordinal]?.let { tm.setTransform(tm.getInstance(it.entity), handR) }
        rack.groups[bodyTheme][Slot.Body.ordinal]?.let { g ->
            for (e in g.pieces) {
                val ri = rm.getInstance(e)
                if (ri != 0) rm.setMorphWeights(ri, weights, 0)
            }
        }
    }

    private fun show(rack: Rack, theme: Int, slot: Slot, visible: Boolean) {
        if (theme < 0) return
        val g = rack.groups[theme][slot.ordinal] ?: return
        for (e in g.pieces) {
            val ri = rm.getInstance(e)
            if (ri != 0) rm.setLayerMask(ri, 0xFF, if (visible) 0x1 else 0)
        }
    }

    private fun hang(k: Int, glb: ByteBuffer) {
        val asset = assetLoader.createAsset(glb) ?: return
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        val groups = Array(Costume.entries.size) { arrayOfNulls<Group>(Slot.entries.size) }
        for (t in Costume.entries.indices) for (s in Slot.entries.indices) {
            val e = asset.getFirstEntityByName("fit_${t}_$s")
            if (e == 0) continue
            // Pieces are looked up one by one by their exact names.
            val found = ArrayList<Int>()
            while (true) {
                val piece = asset.getFirstEntityByName("fit_${t}_${s}_${found.size}")
                if (piece == 0) break
                found += piece
            }
            val pieces = found.toIntArray()
            for (piece in pieces) {
                val ri = rm.getInstance(piece)
                if (ri == 0) continue
                rm.setLayerMask(ri, 0xFF, 0)
                rm.setCastShadows(ri, true)
                rm.setReceiveShadows(ri, true)
            }
            groups[t][s] = Group(e, pieces)
        }
        racks[k] = Rack(asset, groups)
        shownHead[k] = -1
        shownBody[k] = -1
        attach(k)
    }

    fun close() {
        closed = true
        builder.shutdownNow()
        for (rack in racks) {
            if (rack == null) continue
            scene.removeEntities(rack.asset.entities)
            assetLoader.destroyAsset(rack.asset)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Sewing (background thread)
    // -----------------------------------------------------------------------------------------

    private fun sew(k: Int): ByteBuffer {
        val g = Glb()
        val materials = HashMap<String, Int>()
        val textures = HashMap<String, Int>()
        val roots = ArrayList<Int>()
        for (theme in Costume.entries) {
            val outfit = Wardrobe.outfit(theme, k)
            for (slot in Slot.entries) {
                val pieces = outfit.pieces.filter { it.slot == slot }
                if (pieces.isEmpty()) continue
                val children = pieces.mapIndexed { i, piece ->
                    val mat = materials.getOrPut(piece.cloth.key) { g.material(material(g, piece.cloth, textures)) }
                    val targets = if (slot == Slot.Body) Morphs.targetsFor(piece.mesh) else emptyList()
                    g.node("fit_${theme.ordinal}_${slot.ordinal}_$i", g.mesh("m${theme.ordinal}_${slot.ordinal}_$i", piece.mesh, mat, targets))
                }
                roots += g.node("fit_${theme.ordinal}_${slot.ordinal}", children = children)
            }
        }
        return g.build(roots)
    }

    private fun material(g: Glb, c: Cloth, textures: HashMap<String, Int>): JSONObject {
        val tex = c.pattern?.let { pt -> textures.getOrPut(pt.key) { g.texture(png(pt)) } }
        return JSONObject().apply {
            put("name", c.key)
            put("doubleSided", true)
            if (c.alpha < 1f) put("alphaMode", "BLEND")
            put("pbrMetallicRoughness", JSONObject().apply {
                put("baseColorFactor", linearRgb(c.color, c.alpha))
                if (tex != null) put("baseColorTexture", JSONObject().put("index", tex))
                put("metallicFactor", c.metallic.toDouble())
                put("roughnessFactor", c.roughness.toDouble())
            })
            c.emissive?.let { e ->
                if (tex != null) {
                    put("emissiveFactor", JSONArray(listOf(1.0, 1.0, 1.0)))
                    put("emissiveTexture", JSONObject().put("index", tex))
                } else {
                    put("emissiveFactor", linearRgb3(e))
                }
            }
            // The ubershader only has sheen for opaque surfaces; a blended one with sheen would
            // look up uniforms its material does not have.
            c.sheen?.takeIf { c.alpha >= 1f }?.let { s ->
                val lin = linearRgb3(s)
                put("extensions", JSONObject().put("KHR_materials_sheen", JSONObject().apply {
                    put("sheenColorFactor", JSONArray(List(3) { lin.getDouble(it) * 0.6 }))
                    put("sheenRoughnessFactor", 0.55)
                }))
            }
        }
    }

    private fun png(p: Pattern): ByteArray {
        val px = IntArray(p.size * p.size)
        for (y in 0 until p.size) for (x in 0 until p.size) px[y * p.size + x] = p.paint(x, y)
        val bmp = Bitmap.createBitmap(px, p.size, p.size, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
