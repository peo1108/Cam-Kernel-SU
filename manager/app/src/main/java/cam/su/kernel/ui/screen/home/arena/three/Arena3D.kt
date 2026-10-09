package cam.su.kernel.ui.screen.home.arena.three

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.IBLPrefilterContext
import com.google.android.filament.utils.Utils
import cam.su.kernel.ui.screen.home.arena.ArenaLanguage
import cam.su.kernel.ui.screen.home.arena.Box
import cam.su.kernel.ui.screen.home.arena.SLIME_COUNT
import cam.su.kernel.ui.screen.home.arena.SlimePose
import cam.su.kernel.ui.screen.home.arena.Stage
import cam.su.kernel.ui.screen.home.arena.WallSplit
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filament renderer for the status card, staged as a glass-fronted diorama: a soft studio cove
 * whose walls carry a panorama of the country, a ground of that country's material (water, snow,
 * cobbles...) and its sky overhead, with the four jelly slimes inside (screen-space refraction,
 * absorption) whose arms are pulled out of their own bodies by shape keys. The camera looks
 * straight in through the glass with a shifted lens, framed so that the action plane (z = 0)
 * maps 1:1 onto the 2D stage the overlay effects are drawn in.
 */
internal class Arena3D private constructor() {
    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private var asset: FilamentAsset? = null
    private var room: Room? = null
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val tiling = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)

    private val rig = SlimeRig(engine, assetLoader, resourceLoader, scene, candy = false)

    private var walls = 0
    private var floor = 0
    private var ceiling = 0
    private var panoramaTexture: Texture? = null
    private val groundTextures = HashMap<Int, Texture>()
    private val skyTextures = HashMap<Int, Texture>()
    private var dressedFor = -1

    private val sun = EntityManager.get().create()
    private val fill = EntityManager.get().create()
    private var indirect: IndirectLight? = null
    private var iblFor = -1
    private val prefilter = IBLPrefilterContext(engine)

    // Panorama painting runs on a worker; finished frames are uploaded on the main thread.
    private val painter = Executors.newSingleThreadExecutor { r -> Thread(r, "arena-backdrop").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paintBusy = AtomicBoolean(false)
    private val painted = AtomicReference<ImageBitmap?>(null)
    private val freeBitmaps = ArrayDeque<ImageBitmap>()
    private var panoW = 0
    private var panoH = 0
    private var paintDensity = 1f
    private var lastPaint = -1f
    private var latestPanorama: ImageBitmap? = null
    private var pendingRecycle: ImageBitmap? = null

    private var unit = 1f
    private var eyeHeight = 0f
    private var stageKey = 0L

    /** How the panorama wraps round the room, for painting scenery that suits each wall. */
    var split = WallSplit(0.2f, 0.25f)
        private set

    /** Card pixels to panorama pixels on the back wall, for scene changes painted into the walls. */
    var map: PanoramaMap? = null
        private set
    private var lastRender = 0L
    private val tm get() = engine.transformManager
    private val rm get() = engine.renderableManager

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.OPAQUE
        view.renderQuality = View.RenderQuality().apply { hdrColorBuffer = View.QualityLevel.HIGH }
        // Native resolution with 4x MSAA for clean jelly silhouettes, plus FXAA for the edges that
        // show up inside the jelly, in the refracted image of the room behind it.
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
        }
        view.antiAliasing = View.AntiAliasing.FXAA
        // No SSAO: it is computed from the opaque depth only, so the see-through jelly would pick up
        // blocky occlusion from whatever stands behind it (hat brims, the back wall). Contact
        // shadows ground the slimes instead.
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = false }
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = 0.12f
            levels = 6
        }
        // No SSR: it also lands on the glossy jelly, and its hits against the low-resolution depth
        // came out as 8 px blocks across the slimes' bodies.
        view.setScreenSpaceReflectionsOptions(View.ScreenSpaceReflectionsOptions().apply { enabled = false })
        view.setScreenSpaceRefractionEnabled(true)
        // Soft blurred variance shadows: hard shadow-map texels on the floor get magnified by the
        // jelly's lens into visible stair steps.
        view.setShadowType(View.ShadowType.VSM)
        view.vsmShadowOptions = View.VsmShadowOptions().apply {
            anisotropy = 0
            mipmapping = false
            msaaSamples = 1
            lightBleedReduction = 0.25f
        }
        view.dithering = View.Dithering.TEMPORAL
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
        camera.setExposure(1f)
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        }

        LightManager.Builder(LightManager.Type.SUN)
            .intensity(3.4f)
            .direction(-0.4f, -1f, -0.8f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 1024
                shadowCascades = 1
                shadowFar = 24f
                stable = true
                blurWidth = 6f
                // No screen-space contact shadows: marching the depth in a few big steps drew
                // the hat brims' shadow onto the jelly as stair steps.
                screenSpaceContactShadows = false
            })
            .sunAngularRadius(4.5f)
            .sunHaloSize(10f)
            .build(engine, sun)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .intensity(0.8f)
            .direction(0.6f, -0.3f, -0.5f)
            .castShadows(false)
            .build(engine, fill)
        scene.addEntity(sun)
        scene.addEntity(fill)
        rig.open()
    }

    fun attach(textureView: TextureView) {
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }

            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                stageKey = 0L
            }
        }
        uiHelper.attachTo(textureView)
    }

    private fun materialOf(entity: Int): MaterialInstance? {
        if (entity == 0) return null
        val r = rm.getInstance(entity)
        return if (r == 0) null else rm.getMaterialInstanceAt(r, 0)
    }

    private fun shadows(entity: Int, cast: Boolean, receive: Boolean) {
        if (entity == 0) return
        val r = rm.getInstance(entity)
        if (r == 0) return
        rm.setCastShadows(r, cast)
        rm.setReceiveShadows(r, receive)
    }

    private fun texture(w: Int, h: Int, levels: Int): Texture = Texture.Builder()
        .width(w)
        .height(h)
        .levels(levels)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .usage(Texture.Usage.DEFAULT or if (levels > 1) Texture.Usage.GEN_MIPMAPPABLE else 0)
        .format(Texture.InternalFormat.SRGB8_A8)
        .build(engine)

    private fun bitmapTexture(bmp: Bitmap): Texture {
        val levels = 1 + (Math.log(maxOf(bmp.width, bmp.height).toDouble()) / Math.log(2.0)).toInt()
        return texture(bmp.width, bmp.height, levels).also {
            TextureHelper.setBitmap(engine, it, 0, bmp)
            it.generateMipmaps(engine)
        }
    }

    private fun setTransform(entity: Int, m: FloatArray) {
        if (entity != 0) tm.setTransform(tm.getInstance(entity), m)
    }

    // -----------------------------------------------------------------------------------------
    // Model
    // -----------------------------------------------------------------------------------------

    private fun argb(c: Color) = c.toArgb().toLong() and 0xFFFFFFFFL

    /** Builds the slimes and the room for [r]; rebuilt only when the card changes size. */
    private fun buildAsset(r: Room): FilamentAsset {
        val g = Glb()
        val placeholder = g.texture(png(4, 4) { _, _ -> android.graphics.Color.WHITE })

        val rootsOut = ArrayList<Int>(rig.build(g, placeholder))
        fun surface(name: String, roughness: Double, unlit: Boolean) = g.material(JSONObject().apply {
            put("name", name)
            put("doubleSided", true)
            put("pbrMetallicRoughness", JSONObject().apply {
                put("baseColorTexture", JSONObject().put("index", placeholder))
                put("metallicFactor", 0.0)
                put("roughnessFactor", roughness)
            })
            if (unlit) put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
        })
        rootsOut += g.node("walls", g.mesh("walls", r.walls(), surface("walls", 1.0, unlit = true)))
        rootsOut += g.node("floor", g.mesh("floor", r.floor(tile = 1.8f), surface("floor", 0.3, unlit = false)))
        rootsOut += g.node("ceiling", g.mesh("ceiling", r.ceiling(), surface("ceiling", 1.0, unlit = true)))
        return assetLoader.createAsset(g.build(rootsOut)) ?: error("arena asset failed to load")
    }

    private fun installAsset(r: Room) {
        asset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        val a = buildAsset(r)
        resourceLoader.loadResources(a)
        a.releaseSourceData()
        scene.addEntities(a.entities)
        asset = a
        rig.bind(a)
        walls = a.getFirstEntityByName("walls")
        floor = a.getFirstEntityByName("floor")
        ceiling = a.getFirstEntityByName("ceiling")
        shadows(walls, cast = false, receive = false)
        shadows(floor, cast = false, receive = true)
        shadows(ceiling, cast = false, receive = false)
        panoramaTexture?.let { materialOf(walls)?.setParameter("baseColorMap", it, sampler) }
        dressedFor = -1
    }

    private fun png(w: Int, h: Int, color: (Int, Int) -> Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, color(x, y))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    // -----------------------------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------------------------

    /**
     * Camera and room for the current card size. One world unit is the stage radius; the action
     * plane z = 0 is framed exactly like the 2D stage (px = world · unit, floor at groundY), the
     * glass sits at z = GLASS filling the card, the back wall at z = BACK.
     */
    private fun layout(stage: Stage) {
        val key = (stage.w.toBits().toLong() shl 32) or stage.h.toBits().toLong()
        if (key == stageKey) return
        stageKey = key
        unit = stage.r
        val yTop0 = stage.groundY / unit
        val yBot0 = -(stage.h - stage.groundY) / unit
        val xHalf0 = stage.w / 2f / unit
        val dGlass = Box.CAMERA - Box.GLASS
        val camY = -yBot0 * dGlass / Box.GLASS
        val halfW = xHalf0 * dGlass / Box.CAMERA
        val height = camY + (yTop0 - camY) * dGlass / Box.CAMERA
        val n = 0.5
        camera.setProjection(
            Camera.Projection.PERSPECTIVE,
            -xHalf0 * n / Box.CAMERA, xHalf0 * n / Box.CAMERA,
            (yBot0 - camY) * n / Box.CAMERA, (yTop0 - camY) * n / Box.CAMERA,
            n, 120.0,
        )
        eyeHeight = camY
        aim(Offset.Zero)
        // The room reaches forward past the glass so its rounded coves fill the card's corners too.
        val r = Room(
            halfW * 2f, height, Box.GLASS + 2.5f, Box.BACK,
            glass = Box.GLASS,
            corner = (halfW * 0.3f).coerceIn(1.2f, 2.4f),
            cove = (height * 0.2f).coerceIn(0.5f, 1.2f),
        )
        room = r
        split = r.split
        installAsset(r)
        // Panorama resolution follows the walls' run, at a bit under half the screen's density.
        val ppu = unit / 2.4f
        panoW = (r.visibleLength * ppu).toInt().coerceAtLeast(64)
        panoH = (r.wallHeight * ppu).toInt().coerceAtLeast(32)
        paintDensity = stage.px / 2.4f
        map = PanoramaMap(stage, unit, r, panoW, panoH)
    }

    /**
     * Points the camera straight into the box, nudged by [shake] card pixels and rolled [roll]
     * degrees (clockwise on screen, like the overlay's rotation).
     */
    private fun aim(shake: Offset, roll: Float = 0f) {
        val x = (-shake.x / unit).toDouble()
        val y = (eyeHeight + shake.y / unit).toDouble()
        val a = Math.toRadians(roll.toDouble())
        camera.lookAt(x, y, Box.CAMERA.toDouble(), x, y, Box.CAMERA - 1.0, -kotlin.math.sin(a), kotlin.math.cos(a), 0.0)
    }

    // -----------------------------------------------------------------------------------------
    // Per frame
    // -----------------------------------------------------------------------------------------

    fun update(
        stage: Stage,
        poses: Array<SlimePose>,
        now: Float,
        language: Int,
        languages: List<ArenaLanguage>,
        /** Camera shake, in card pixels, and roll in degrees, matching the overlay's. */
        shake: Offset,
        roll: Float,
        paintPanorama: DrawScope.() -> Unit,
    ) {
        layout(stage)
        aim(shake, roll)
        uploadPanorama()
        if (now - lastPaint >= PAINT_INTERVAL || now < lastPaint) {
            if (requestPaint(paintPanorama)) lastPaint = now
        }
        light(language, languages)
        for (k in 0 until SLIME_COUNT) pose(k, poses[k], stage, now)
    }

    private fun pose(k: Int, p: SlimePose, stage: Stage, now: Float) {
        rig.place(k, p, now, unit, (p.x + p.shakeX - stage.center) / unit, 0f, p.depth)
    }

    /** Queues a panorama repaint on the worker; false if it is still busy with the last one. */
    private fun requestPaint(paint: DrawScope.() -> Unit): Boolean {
        val w = panoW
        val h = panoH
        if (w == 0 || h == 0) return false
        val tex = panoramaTexture
        if (tex == null || tex.getWidth(0) != w || tex.getHeight(0) != h) {
            freeBitmaps.clear()
            pendingRecycle = null
            latestPanorama = null
            repeat(3) { freeBitmaps.addLast(ImageBitmap(w, h)) }
            tex?.let { engine.destroyTexture(it) }
            panoramaTexture = texture(w, h, 1).also { materialOf(walls)?.setParameter("baseColorMap", it, sampler) }
            iblFor = -1
        }
        if (!paintBusy.compareAndSet(false, true)) return false
        val bmp = freeBitmaps.removeFirstOrNull() ?: run {
            paintBusy.set(false)
            return false
        }
        val density = paintDensity
        painter.execute {
            try {
                CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat()), paint)
                painted.getAndSet(bmp)?.let { stale -> mainHandler.post { recycle(stale) } }
            } finally {
                paintBusy.set(false)
            }
        }
        return true
    }

    private fun recycle(bmp: ImageBitmap) {
        if (bmp.width == panoW && bmp.height == panoH) freeBitmaps.addLast(bmp)
    }

    private fun uploadPanorama() {
        val bmp = painted.getAndSet(null) ?: return
        val tex = panoramaTexture ?: return
        if (bmp.width != panoW || bmp.height != panoH) return
        // The previous frame's bitmap returns to the pool one upload later, once the GPU copy is done.
        pendingRecycle?.let { recycle(it) }
        pendingRecycle = latestPanorama
        latestPanorama = bmp
        TextureHelper.setBitmap(engine, tex, 0, bmp.asAndroidBitmap())
    }

    /** Key light, ground and sky, and the image-based light for the current country. */
    private fun light(language: Int, languages: List<ArenaLanguage>) {
        val lang = languages[language]
        val l = lang.light
        val dx = -(l.x - 0.5f) * 1.6f
        val dy = -1f
        val dz = -0.9f
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        val lm = engine.lightManager
        lm.setDirection(lm.getInstance(sun), dx / n, dy / n, dz / n)
        lm.setColor(lm.getInstance(sun), l.color.red, l.color.green, l.color.blue)
        lm.setColor(lm.getInstance(fill), l.ambient.red, l.ambient.green, l.ambient.blue)
        if (dressedFor != language) {
            dressedFor = language
            val ground = groundTextures.getOrPut(language) { bitmapTexture(groundBitmap(lang.ground, lang.wash.last())) }
            materialOf(floor)?.apply {
                setParameter("baseColorMap", ground, tiling)
                setParameter("roughnessFactor", lang.ground.roughness)
            }
            val sky = skyTextures.getOrPut(language) {
                // Starts exactly at the panorama's top color, deepening toward the middle.
                bitmapTexture(skyBitmap(lang.wash.first(), lerp(lang.wash.first(), Color.Black, if (lang.night) 0.32f else 0.14f)))
            }
            materialOf(ceiling)?.setParameter("baseColorMap", sky, sampler)
        }
        if (iblFor != language && latestPanorama != null) {
            iblFor = language
            buildIbl(lang)
        }
    }

    private fun buildIbl(lang: ArenaLanguage) {
        val src = latestPanorama?.asAndroidBitmap() ?: return
        val ew = 256
        val eh = 128
        val equi = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(equi)
        cv.drawBitmap(src, null, android.graphics.Rect(0, 0, ew, eh / 2), null)
        val ground = lerp(lang.wash.last(), Color.White, 0.2f)
        cv.drawRect(0f, eh / 2f, ew.toFloat(), eh.toFloat(), android.graphics.Paint().apply { color = ground.toArgb() })
        val tex = texture(ew, eh, 9)
        TextureHelper.setBitmap(engine, tex, 0, equi)
        tex.generateMipmaps(engine)
        val cube = IBLPrefilterContext.EquirectangularToCubemap(prefilter).run(tex)
        val specular = IBLPrefilterContext.SpecularFilter(prefilter).run(cube)
        engine.destroyTexture(tex)
        engine.destroyTexture(cube)

        // Two-band irradiance: average of the walls above, ground color below.
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var count = 0
        for (y in 0 until src.height step 4) for (x in 0 until src.width step 4) {
            val p = src.getPixel(x, y)
            sr += lin(android.graphics.Color.red(p) / 255f)
            sg += lin(android.graphics.Color.green(p) / 255f)
            sb += lin(android.graphics.Color.blue(p) / 255f)
            count++
        }
        sr /= count
        sg /= count
        sb /= count
        val gr = lin(ground.red)
        val gg = lin(ground.green)
        val gb = lin(ground.blue)
        val sh = floatArrayOf(
            (sr + gr) / 2f, (sg + gg) / 2f, (sb + gb) / 2f,
            (sr - gr) * 0.4f, (sg - gg) * 0.4f, (sb - gb) * 0.4f,
            0f, 0f, 0f,
            0f, 0f, 0f,
        )
        val old = indirect
        indirect = IndirectLight.Builder().reflections(specular).irradiance(2, sh).intensity(1.6f).build(engine)
        scene.indirectLight = indirect
        old?.let { engine.destroyIndirectLight(it) }
    }

    /** Renders at most 60 times a second; high refresh panels would otherwise double the GPU work. */
    fun render(frameTimeNanos: Long) {
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender || asset == null) return
        if (frameTimeNanos - lastRender < MIN_FRAME_NANOS) return
        lastRender = frameTimeNanos
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun destroy() {
        rig.destroy()
        painter.shutdownNow()
        uiHelper.detach()
        scene.indirectLight = null
        indirect?.let { engine.destroyIndirectLight(it) }
        prefilter.destroy()
        asset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        panoramaTexture?.let { engine.destroyTexture(it) }
        groundTextures.values.forEach { engine.destroyTexture(it) }
        skyTextures.values.forEach { engine.destroyTexture(it) }
        engine.destroyEntity(sun)
        engine.destroyEntity(fill)
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()
    }

    companion object {
        private const val MIN_FRAME_NANOS = 15_500_000L
        /** The soft panorama moves slowly; repainting it at 30 Hz is indistinguishable. */
        private const val PAINT_INTERVAL = 1f / 30f

        /** Creates the renderer, or null when Filament cannot run on this device (no native libs). */
        fun createOrNull(): Arena3D? = try {
            Utils.init()
            Arena3D()
        } catch (t: Throwable) {
            null
        }

        private fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
}

/** Maps a point of the card, seen on the back wall, to the panorama painted on the walls. */
internal class PanoramaMap(private val stage: Stage, private val unit: Float, private val room: Room, private val width: Int, private val height: Int) {
    private val back = stage.viewScale(Box.BACK)

    /** Panorama pixels per card pixel on the back wall. */
    val scale = width / room.visibleLength / (back * unit)

    fun toPanorama(p: Offset): Offset {
        val x = (p.x - stage.center) / (back * unit)
        val y = (stage.groundY - (stage.eyeY + (p.y - stage.eyeY) / back)) / unit
        return Offset(room.backU(x) * width, room.wallV(y) * height)
    }
}
