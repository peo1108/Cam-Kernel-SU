package cam.su.kernel.ui.screen.home.arena.three

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import cam.su.kernel.ui.screen.home.arena.Cast
import cam.su.kernel.ui.screen.home.arena.Mood
import cam.su.kernel.ui.screen.home.arena.SLIME_COUNT
import cam.su.kernel.ui.screen.home.arena.SlimeGeo
import cam.su.kernel.ui.screen.home.arena.SlimePose
import cam.su.kernel.ui.screen.home.arena.drawFace
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The four 3D slimes themselves: jelly body with shape-key arms, painted face, and their
 * wardrobe. Shared by the diorama in the status card and by the layer the slimes roam the rest
 * of the app in, so the ones running over the pages are the very same models as in the box.
 *
 * [candy] picks the jelly: inside the box it refracts the room behind it; over the app there is
 * nothing rendered behind it to refract, so it is a glossy see-through candy instead.
 */
internal class SlimeRig(
    private val engine: Engine,
    assetLoader: AssetLoader,
    resourceLoader: ResourceLoader,
    scene: Scene,
    private val candy: Boolean,
    /** The see-through jelly for the candy body: clear in the middle, denser at the rim. */
    private val clear: Material? = null,
) {
    private val clearInstances = ArrayList<MaterialInstance>()
    val roots = IntArray(SLIME_COUNT)
    private val bodies = IntArray(SLIME_COUNT)
    private val faces = IntArray(SLIME_COUNT)
    private val closet = Closet(engine, assetLoader, resourceLoader, scene)
    private val headMatrix = FloatArray(16)
    private val handPoint = FloatArray(3)
    private val faceTextures = Array(SLIME_COUNT) {
        Texture.Builder()
            .width(FACE_W)
            .height(FACE_H)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
    }
    private val faceBitmaps = Array(SLIME_COUNT) { ImageBitmap(FACE_W, FACE_H) }
    private val faceKeys = LongArray(SLIME_COUNT) { -1L }
    private val faceGeo = SlimeGeo()
    private val weights = FloatArray(BodyMorph.entries.size)
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val tm get() = engine.transformManager
    private val rm get() = engine.renderableManager

    /** Starts sewing the outfits on a worker; each slime's appear once ready. */
    fun open() = closet.open()

    /** Adds the four slimes to [g] (with [placeholder] as the face texture until it is painted). */
    fun build(g: Glb, placeholder: Int): List<Int> {
        val bodyMesh = Shapes3D.body()
        val bodyTargets = Morphs.targetsFor(bodyMesh)
        val faceMesh = Shapes3D.faceDecal()
        val faceTargets = Morphs.targetsFor(faceMesh)
        return List(SLIME_COUNT) { k ->
            val skin = Cast[k]
            val bodyArgb = argb(skin.body)
            val jelly = if (candy) candy(g, k, bodyArgb) else jelly(g, k, bodyArgb, argb(lerp(skin.body, Color.White, 0.3f)))
            val faceMat = g.material(JSONObject().apply {
                put("name", "face_$k")
                put("alphaMode", "BLEND")
                put("pbrMetallicRoughness", JSONObject().apply {
                    put("baseColorTexture", JSONObject().put("index", placeholder))
                    put("metallicFactor", 0.0)
                    put("roughnessFactor", 0.3)
                })
            })
            val body = g.node("body_$k", g.mesh("body_$k", bodyMesh, jelly, bodyTargets))
            val face = g.node("face_$k", g.mesh("face_$k", faceMesh, faceMat, faceTargets))
            g.node("slime_$k", children = listOf(body, face))
        }
    }

    private fun jelly(g: Glb, k: Int, bodyArgb: Long, tint: Long) = g.material(JSONObject().apply {
        put("name", "jelly_$k")
        // A little emission stands in for subsurface scattering, which the ubershader lacks.
        put("emissiveFactor", JSONArray(linearRgb3(bodyArgb).let { c -> List(3) { c.getDouble(it) * 0.22 } }))
        put("pbrMetallicRoughness", JSONObject().apply {
            put("baseColorFactor", linearRgb(tint))
            put("metallicFactor", 0.0)
            // Nearly smooth: Filament picks the refraction mip from roughness (0.1 already
            // means 1/16 resolution), and a coarse mip magnified by the jelly breaks what
            // you see through it into blocks. Near zero keeps it at full resolution.
            put("roughnessFactor", 0.03)
        })
        put("extensions", JSONObject().apply {
            put("KHR_materials_transmission", JSONObject().put("transmissionFactor", 0.94))
            put("KHR_materials_volume", JSONObject().apply {
                put("thicknessFactor", 0.75)
                put("attenuationDistance", 3.2)
                put("attenuationColor", linearRgb3(bodyArgb))
            })
            put("KHR_materials_ior", JSONObject().put("ior", 1.38))
        })
    })

    /** The same jelly without refraction: tinted, a little see-through, glossy, glowing from within. */
    private fun candy(g: Glb, k: Int, bodyArgb: Long) = g.material(JSONObject().apply {
        put("name", "candy_$k")
        put("alphaMode", "BLEND")
        put("emissiveFactor", JSONArray(linearRgb3(bodyArgb).let { c -> List(3) { c.getDouble(it) * 0.12 } }))
        put("pbrMetallicRoughness", JSONObject().apply {
            put("baseColorFactor", linearRgb(bodyArgb, 0.9f))
            put("metallicFactor", 0.0)
            put("roughnessFactor", 0.08)
        })
    })

    /** Finds the slimes in the loaded [asset] and dresses them. */
    fun bind(asset: FilamentAsset) {
        for (k in 0 until SLIME_COUNT) {
            roots[k] = asset.getFirstEntityByName("slime_$k")
            bodies[k] = asset.getFirstEntityByName("body_$k")
            faces[k] = asset.getFirstEntityByName("face_$k")
            materialOf(faces[k])?.setParameter("baseColorMap", faceTextures[k], sampler)
            shadows(bodies[k], cast = !candy, receive = false)
            shadows(faces[k], cast = false, receive = false)
            if (candy) {
                // Body first, then the face painted on it, both see-through.
                priority(bodies[k], 5)
                priority(faces[k], 6)
                val ri = if (bodies[k] == 0) 0 else rm.getInstance(bodies[k])
                if (clear != null && ri != 0) {
                    // A little deeper than the skin, and enough of it through the middle, so pale Mochi
                    // and Chanh still read as pink and yellow over the app's light glass.
                    val body = lerp(Cast[k].body, Cast[k].deep, 0.55f)
                    val mi = clear.createInstance()
                    mi.setParameter("tint", Colors.RgbType.SRGB, body.red, body.green, body.blue)
                    mi.setParameter("clarity", 0.24f)
                    mi.setParameter("glow", 0.3f)
                    rm.setMaterialInstanceAt(ri, 0, mi)
                    clearInstances += mi
                }
            }
            faceKeys[k] = -1L
        }
        closet.attach(roots)
    }

    private fun materialOf(entity: Int) = if (entity == 0) null else rm.getInstance(entity).let { if (it == 0) null else rm.getMaterialInstanceAt(it, 0) }

    private fun shadows(entity: Int, cast: Boolean, receive: Boolean) {
        val r = if (entity == 0) 0 else rm.getInstance(entity)
        if (r == 0) return
        rm.setCastShadows(r, cast)
        rm.setReceiveShadows(r, receive)
    }

    private fun priority(entity: Int, p: Int) {
        val r = if (entity == 0) 0 else rm.getInstance(entity)
        if (r != 0) rm.setPriority(r, p)
    }

    fun hide(k: Int) {
        if (roots[k] != 0) tm.setTransform(tm.getInstance(roots[k]), Mat4.HIDDEN)
    }

    /**
     * Poses slime [k] as [p] says, its base at ([x], [y], [z]) in world units, the whole slime
     * turned [angle] degrees clockwise about its base (to stand on a wall) and [yaw] degrees
     * about its own axis (to face where it is going). [unit] is screen pixels per world unit, for
     * the pose's pixel lengths (lift, hat lift).
     */
    fun place(k: Int, p: SlimePose, now: Float, unit: Float, x: Float, y: Float, z: Float, angle: Float = 0f, yaw: Float = 0f) {
        val root = roots[k]
        if (root == 0) return
        if (!p.visible) {
            hide(k)
            return
        }
        val size = Cast[k].size
        var sy = p.squash
        var sx = if (sy < 1f) 1f + (1f - sy) * 0.9f else 1f - (sy - 1f) * 0.5f
        if (p.jiggle > 0f) {
            val j = sin(now * 24f) * p.jiggle * 0.07f
            sx *= 1f + j
            sy *= 1f - j
        }
        val spread = if (p.flatZ < 1f) 1f + (1f - p.flatZ) * 0.4f else 1f
        val scaleX = p.scale * size * sx / sqrt(p.stretch) * spread
        val scaleY = p.scale * size * sy * p.stretch
        val scaleZ = p.scale * size * sx / sqrt(p.stretch) * p.flatZ
        val pivot = BODY_HEIGHT / 2f * scaleY
        tm.setTransform(
            tm.getInstance(root),
            Mat4.of(
                Mat4.translate(x, y, z),
                Mat4.rotateZ(-angle),
                Mat4.translate(0f, p.lift / unit + pivot, 0f),
                Mat4.rotateZ(-p.rotation),
                Mat4.rotateY(yaw),
                Mat4.translate(0f, -pivot, 0f),
                Mat4.scale(scaleX, scaleY, scaleZ),
            ),
        )
        Morphs.weights(p, weights)
        for (e in intArrayOf(bodies[k], faces[k])) {
            val ri = rm.getInstance(e)
            if (ri != 0) rm.setMorphWeights(ri, weights, 0)
        }
        // The costume: the hat rides the head (falling in from above while costumes change), props
        // sit at the arm tips, and the clothes take the body's shape keys.
        val lift = p.hatLift / (unit * scaleY)
        Mat4.of(Mat4.translate(0f, Tailor.HEAD_Y * (1f - p.melt * 0.44f) + lift, 0f), Mat4.rotateZ(-p.hatTilt)).copyInto(headMatrix)
        closet.wear(k, p, headMatrix, handMatrix(p, -1), handMatrix(p, 1), weights)
        paintFace(k, p, now)
    }

    /** Where a held prop goes: the arm's tip, tilted as the arm swings up. */
    private fun handMatrix(p: SlimePose, side: Int): FloatArray {
        Morphs.hand(p, side, handPoint)
        val angle = if (side < 0) p.armL else p.armR
        return Mat4.of(Mat4.translate(handPoint[0], handPoint[1], handPoint[2]), Mat4.rotateZ(-side * (angle - 40f) * 0.35f))
    }

    /** Repaints a face texture only when the expression actually changed. */
    private fun paintFace(k: Int, p: SlimePose, now: Float) {
        val paint = Wardrobe.facePaint(p.outfit ?: p.costume, k)
        val q = { v: Float, steps: Int -> (v.coerceIn(-1f, 1f) * steps).toInt() }
        var key = p.mood.ordinal.toLong()
        key = key * 31 + q(p.blink, 8)
        key = key * 31 + q(p.sleep, 8)
        key = key * 31 + q(p.mouthOpen, 12)
        key = key * 31 + q(p.lookX, 8)
        key = key * 31 + q(p.lookY, 8)
        key = key * 31 + q(p.choke, 6)
        key = key * 31 + paint.ordinal
        if (p.mood == Mood.Dizzy || p.mood == Mood.Surprised) key = key * 31 + (now * 20f).toInt()
        if (key == faceKeys[k]) return
        faceKeys[k] = key
        val bmp = faceBitmaps[k]
        val px = FACE_W / (2f * FACE_HALF_SPAN)
        faceGeo.apply {
            w = 2f * BODY_HALF_WIDTH * px
            h = BODY_HEIGHT * px
            r = w / 1.96f
            cx = FACE_W / 2f
            by = FACE_TOP * BODY_HEIGHT * px
            s = 1f
            t = now
            rotation = 0f
            faceX = cx + p.lookX * w * 0.08f
            eyeY = by - h * 0.47f + p.lookY * h * 0.04f
            eyeDx = w * 0.19f
            eyeW = r * 0.19f
            eyeH = r * 0.26f
            mouthY = eyeY + r * 0.26f
        }
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(FACE_W.toFloat(), FACE_H.toFloat())) {
            drawRect(Color.Transparent, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            if (paint == FacePaint.Mime) {
                // Greasepaint: a white oval under the features, with a soft edge.
                val c = Offset(faceGeo.cx, (faceGeo.eyeY + faceGeo.mouthY) / 2f)
                val rx = faceGeo.w * 0.34f
                val ry = (faceGeo.mouthY - faceGeo.eyeY) * 1.7f
                drawOval(
                    androidx.compose.ui.graphics.Brush.radialGradient(0.85f to Color.White, 1f to Color.White.copy(alpha = 0f), center = c, radius = rx),
                    topLeft = c - Offset(rx, ry),
                    size = Size(rx * 2f, ry * 2f),
                )
            }
            drawFace(p, faceGeo, Cast[k], now)
        }
        TextureHelper.setBitmap(engine, faceTextures[k], 0, bmp.asAndroidBitmap())
    }

    /** Destroys the outfits and face textures (the caller destroys the slimes' own asset). */
    fun destroy() {
        closet.close()
        clearInstances.forEach { engine.destroyMaterialInstance(it) }
        faceTextures.forEach { engine.destroyTexture(it) }
    }

    companion object {
        const val FACE_W = 256
        val FACE_H = (256 * (FACE_TOP - FACE_BOTTOM) * BODY_HEIGHT / (2 * FACE_HALF_SPAN)).toInt()

        private fun argb(c: Color) = c.toArgb().toLong() and 0xFFFFFFFFL

        /** A 4x4 white PNG, the stand-in texture for materials whose map is set at runtime. */
        fun whitePng(): ByteArray {
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
