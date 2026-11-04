package cam.su.kernel.ui.screen.home.arena.three

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary glTF (GLB) writer, so the arena's meshes and PBR materials can be built in code
 * and loaded through gltfio's ubershaders (transmission, volume, clearcoat, sheen, unlit) without
 * any authored model files or offline material compilation.
 */
internal class Glb {
    private val bin = ByteArrayOutputStream()
    private val bufferViews = JSONArray()
    private val accessors = JSONArray()
    private val meshes = JSONArray()
    private val materials = JSONArray()
    private val nodes = JSONArray()
    private val images = JSONArray()
    private val textures = JSONArray()
    private val extensions = linkedSetOf<String>()

    private fun align() {
        while (bin.size() % 4 != 0) bin.write(0)
    }

    private fun view(bytes: ByteArray, target: Int?): Int {
        align()
        val offset = bin.size()
        bin.write(bytes)
        bufferViews.put(JSONObject().apply {
            put("buffer", 0)
            put("byteOffset", offset)
            put("byteLength", bytes.size)
            if (target != null) put("target", target)
        })
        return bufferViews.length() - 1
    }

    private fun floats(data: FloatArray, components: Int, type: String, minMax: Boolean): Int {
        val bytes = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { bytes.putFloat(it) }
        val v = view(bytes.array(), 34962)
        accessors.put(JSONObject().apply {
            put("bufferView", v)
            put("componentType", 5126)
            put("count", data.size / components)
            put("type", type)
            if (minMax) {
                val min = FloatArray(components) { Float.MAX_VALUE }
                val max = FloatArray(components) { -Float.MAX_VALUE }
                for (i in data.indices) {
                    val c = i % components
                    if (data[i] < min[c]) min[c] = data[i]
                    if (data[i] > max[c]) max[c] = data[i]
                }
                put("min", JSONArray(min.map { it.toDouble() }))
                put("max", JSONArray(max.map { it.toDouble() }))
            }
        })
        return accessors.length() - 1
    }

    private fun indices(data: IntArray): Int {
        val bytes = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { bytes.putInt(it) }
        val v = view(bytes.array(), 34963)
        accessors.put(JSONObject().apply {
            put("bufferView", v)
            put("componentType", 5125)
            put("count", data.size)
            put("type", "SCALAR")
        })
        return accessors.length() - 1
    }

    /** Embeds a PNG and returns its texture index. */
    fun texture(png: ByteArray): Int {
        val v = view(png, null)
        images.put(JSONObject().apply {
            put("bufferView", v)
            put("mimeType", "image/png")
        })
        textures.put(JSONObject().put("source", images.length() - 1))
        return textures.length() - 1
    }

    /** Adds a material (a glTF material object); extension names are collected automatically. */
    fun material(json: JSONObject): Int {
        json.optJSONObject("extensions")?.keys()?.forEach { extensions += it }
        materials.put(json)
        return materials.length() - 1
    }

    /** Adds a mesh; [targets] are morph targets as (position deltas, normal deltas). */
    fun mesh(name: String, m: MeshData, material: Int, targets: List<Pair<FloatArray, FloatArray>> = emptyList()): Int {
        val attributes = JSONObject().apply {
            put("POSITION", floats(m.positions, 3, "VEC3", true))
            put("NORMAL", floats(m.normals, 3, "VEC3", false))
            put("TEXCOORD_0", floats(m.uvs, 2, "VEC2", false))
        }
        meshes.put(JSONObject().apply {
            put("name", name)
            put("primitives", JSONArray().put(JSONObject().apply {
                put("attributes", attributes)
                put("indices", indices(m.indices))
                put("material", material)
                if (targets.isNotEmpty()) {
                    put("targets", JSONArray(targets.map { (dp, dn) ->
                        JSONObject().apply {
                            put("POSITION", floats(dp, 3, "VEC3", true))
                            put("NORMAL", floats(dn, 3, "VEC3", false))
                        }
                    }))
                }
            }))
            if (targets.isNotEmpty()) put("weights", JSONArray(List(targets.size) { 0.0 }))
        })
        return meshes.length() - 1
    }

    fun node(name: String, mesh: Int? = null, children: List<Int> = emptyList()): Int {
        nodes.put(JSONObject().apply {
            put("name", name)
            if (mesh != null) put("mesh", mesh)
            if (children.isNotEmpty()) put("children", JSONArray(children))
        })
        return nodes.length() - 1
    }

    fun build(roots: List<Int>): ByteBuffer {
        align()
        val binBytes = bin.toByteArray()
        val json = JSONObject().apply {
            put("asset", JSONObject().put("version", "2.0").put("generator", "arena"))
            put("scene", 0)
            put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray(roots))))
            put("nodes", nodes)
            put("meshes", meshes)
            put("materials", materials)
            put("accessors", accessors)
            put("bufferViews", bufferViews)
            put("buffers", JSONArray().put(JSONObject().put("byteLength", binBytes.size)))
            if (images.length() > 0) {
                put("images", images)
                put("textures", textures)
            }
            if (extensions.isNotEmpty()) put("extensionsUsed", JSONArray(extensions.toList()))
        }
        // org.json escapes "/" as "\/", which cgltf does not unescape in mime types.
        var jsonBytes = json.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8)
        if (jsonBytes.size % 4 != 0) jsonBytes += ByteArray(4 - jsonBytes.size % 4) { ' '.code.toByte() }
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        return ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(total)
            putInt(jsonBytes.size)
            putInt(0x4E4F534A)
            put(jsonBytes)
            putInt(binBytes.size)
            putInt(0x004E4942)
            put(binBytes)
            flip()
        }
    }
}

/** Interleaving-free mesh data: positions and normals (xyz), uvs (uv), triangle indices. */
internal class MeshData(val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray, val indices: IntArray)

/** Linear-space glTF color array from an sRGB ARGB color. */
internal fun linearRgb(argb: Long, alpha: Float = 1f): JSONArray {
    fun ch(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return JSONArray(listOf(ch(16), ch(8), ch(0), alpha.toDouble()))
}

internal fun linearRgb3(argb: Long): JSONArray = linearRgb(argb).let { JSONArray(listOf(it[0], it[1], it[2])) }
