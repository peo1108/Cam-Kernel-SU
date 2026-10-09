package cam.su.kernel.ui.screen.home.arena.three

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
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
import cam.su.kernel.ui.screen.home.arena.SLIME_COUNT
import cam.su.kernel.ui.screen.home.arena.SlimePose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * The 3D slimes out of their box: a see-through Filament layer over the whole app that draws
 * only the slimes, the same models as in the status card. Positions come in screen pixels; the
 * camera looks straight at the screen from far enough that the perspective stays gentle, framed
 * so that the z = 0 plane maps 1:1 onto the screen.
 */
internal class Roam3D private constructor(jellyPayload: ByteBuffer?) {
    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
    private var swapChain: SwapChain? = null
    private var swapFlags = 0L

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val clearJelly: Material? = jellyPayload?.let { Material.Builder().payload(it, it.remaining()).build(engine) }
    private val rig = SlimeRig(engine, assetLoader, resourceLoader, scene, candy = true, clear = clearJelly)
    private val asset: FilamentAsset
    private val sun = EntityManager.get().create()
    private val fill = EntityManager.get().create()
    private val indirect: IndirectLight
    private val prefilter = IBLPrefilterContext(engine)

    private var width = 0
    private var height = 0
    private var unit = 1f
    private var framed = 0L
    private var lastRender = 0L

    /** Whether anything is on screen; the host hides the layer when nothing is. */
    var anyVisible = false
        private set
    private var shownNow = false

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        // Straight into the window, no full-screen post pass for a layer that is mostly empty;
        // the swap chain does the sRGB encoding and the multisampling instead.
        val direct = SwapChain.isSRGBSwapChainSupported(engine)
        view.isPostProcessingEnabled = !direct
        swapFlags = SwapChainFlags.CONFIG_TRANSPARENT
        if (direct) {
            swapFlags = swapFlags or SwapChainFlags.CONFIG_SRGB_COLORSPACE
            if (SwapChain.isMSAASwapChainSupported(engine, 4)) swapFlags = swapFlags or SwapChainFlags.CONFIG_MSAA_4_SAMPLES
        } else {
            view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
            view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
                enabled = true
                sampleCount = 4
            }
            view.antiAliasing = View.AntiAliasing.NONE
            view.bloomOptions = View.BloomOptions().apply { enabled = false }
        }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = false }
        view.setShadowingEnabled(false)
        view.dithering = View.Dithering.NONE
        camera.setExposure(1f)
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }

        // Light from the top of the screen, a little from the left, like the status bar's sky.
        LightManager.Builder(LightManager.Type.SUN)
            .intensity(2.4f)
            .direction(0.35f, -1f, -0.7f)
            .color(1f, 0.96f, 0.9f)
            .castShadows(false)
            .sunAngularRadius(4.5f)
            .sunHaloSize(10f)
            .build(engine, sun)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .intensity(0.5f)
            .direction(-0.6f, -0.2f, -0.6f)
            .color(0.75f, 0.8f, 0.95f)
            .castShadows(false)
            .build(engine, fill)
        scene.addEntity(sun)
        scene.addEntity(fill)
        indirect = studioLight()
        scene.indirectLight = indirect

        val g = Glb()
        val placeholder = g.texture(SlimeRig.whitePng())
        asset = assetLoader.createAsset(g.build(rig.build(g, placeholder))) ?: error("roam asset failed to load")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        rig.bind(asset)
        rig.open()
        for (k in 0 until SLIME_COUNT) rig.hide(k)
    }

    /** A soft studio: bright sky above, a warm floor below, for the jelly's reflections. */
    private fun studioLight(): IndirectLight {
        val ew = 256
        val eh = 128
        val equi = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(equi)
        val sky = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, eh / 2f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE4F5.toInt(), 0xFFC9D2E6.toInt()), null, Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, ew.toFloat(), eh / 2f, sky)
        cv.drawRect(0f, eh / 2f, ew.toFloat(), eh.toFloat(), Paint().apply { color = 0xFFB8AFA6.toInt() })
        // A couple of bright panels for crisp highlights.
        val panel = Paint().apply { color = 0xFFFFFFFF.toInt() }
        cv.drawRect(ew * 0.18f, eh * 0.12f, ew * 0.3f, eh * 0.3f, panel)
        cv.drawRect(ew * 0.62f, eh * 0.16f, ew * 0.7f, eh * 0.28f, panel)
        val tex = Texture.Builder()
            .width(ew)
            .height(eh)
            .levels(9)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
        TextureHelper.setBitmap(engine, tex, 0, equi)
        tex.generateMipmaps(engine)
        val cube = IBLPrefilterContext.EquirectangularToCubemap(prefilter).run(tex)
        val specular = IBLPrefilterContext.SpecularFilter(prefilter).run(cube)
        engine.destroyTexture(tex)
        engine.destroyTexture(cube)
        val sh = floatArrayOf(
            0.5f, 0.52f, 0.56f,
            0.1f, 0.11f, 0.15f,
            0f, 0f, 0f,
            0f, 0f, 0f,
        )
        return IndirectLight.Builder().reflections(specular).irradiance(2, sh).intensity(0.7f).build(engine)
    }

    fun attach(textureView: TextureView) {
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, swapFlags or uiHelper.swapChainFlags)
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
                this@Roam3D.width = width
                this@Roam3D.height = height
                framed = 0L
            }
        }
        uiHelper.attachTo(textureView)
    }

    /** Frames the screen with [unit] pixels per world unit (the roaming slime radius). */
    private fun frame(unit: Float) {
        val key = (width.toLong() shl 40) xor (height.toLong() shl 20) xor unit.toBits().toLong()
        if (key == framed || width == 0 || height == 0) return
        framed = key
        this.unit = unit
        val halfW = width / 2f / unit
        val halfH = height / 2f / unit
        val d = max(halfW, halfH) * 3.2f
        val n = d * 0.25
        camera.setProjection(Camera.Projection.PERSPECTIVE, -halfW * n / d, halfW * n / d, -halfH * n / d, halfH * n / d, n, d * 4.0)
        camera.lookAt(0.0, 0.0, d.toDouble(), 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    }

    /** Starts a frame of poses; [unit] is screen pixels per world unit. */
    fun begin(unit: Float) {
        frame(unit)
        shownNow = false
    }

    /**
     * Slime [k] in pose [p], its base at screen pixel ([x], [y]), turned [angle] degrees clockwise
     * (standing on a wall), facing [yaw] degrees round, [scale] times its roaming size.
     */
    fun place(k: Int, p: SlimePose, now: Float, x: Float, y: Float, angle: Float, yaw: Float) {
        if (!p.visible) {
            rig.hide(k)
            return
        }
        shownNow = true
        rig.place(k, p, now, unit, (x - width / 2f) / unit, (height / 2f - y) / unit, k * 0.02f, angle, yaw)
    }

    fun hide(k: Int) = rig.hide(k)

    /** Ends the frame; [anyVisible] tells whether anything was drawn. */
    fun end() {
        anyVisible = shownNow
    }

    /** Renders at most 60 times a second. */
    fun render(frameTimeNanos: Long) {
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (frameTimeNanos - lastRender < MIN_FRAME_NANOS) return
        lastRender = frameTimeNanos
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    fun destroy() {
        uiHelper.detach()
        scene.indirectLight = null
        engine.destroyIndirectLight(indirect)
        prefilter.destroy()
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
        // After the slimes' own renderables are gone: their jelly instances, outfits and faces.
        rig.destroy()
        resourceLoader.destroy()
        assetLoader.destroy()
        clearJelly?.let { engine.destroyMaterial(it) }
        materialProvider.destroyMaterials()
        materialProvider.destroy()
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

        /** Creates the renderer, or null when Filament cannot run here. */
        fun createOrNull(context: Context): Roam3D? = try {
            Utils.init()
            // The see-through jelly, compiled ahead of time (it needs a material of its own).
            val jelly = runCatching {
                val bytes = context.assets.open("roam_jelly.filamat").use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
            }.getOrNull()
            Roam3D(jelly)
        } catch (t: Throwable) {
            null
        }
    }
}
