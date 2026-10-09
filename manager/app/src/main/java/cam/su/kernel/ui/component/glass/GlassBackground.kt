package cam.su.kernel.ui.component.glass

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.derivedStateOf
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cam.su.kernel.data.repository.PrefsStampStore
import cam.su.kernel.data.repository.WallpaperRepository
import cam.su.kernel.ui.component.miuix.effect.BgEffectBackground
import cam.su.kernel.ui.component.miuix.effect.BgEffectConfig
import cam.su.kernel.ui.component.miuix.effect.DeviceType
import cam.su.kernel.ui.theme.isInDarkTheme
import cam.su.kernel.ui.util.shouldShowSplitPane
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.io.File
import kotlin.math.max

object GlassBackgroundType {
    const val WALLPAPER = 0
    const val GRADIENT = 1
    const val IMAGE = 2
}

enum class GlassSourceKind { Plain, Gradient, Bitmap }

/** Wallpaper falls back to the plain surface; a custom image falls back to the gradient. */
fun pickGlassSourceKind(type: Int, hasBitmap: Boolean): GlassSourceKind = when (type) {
    GlassBackgroundType.GRADIENT -> GlassSourceKind.Gradient
    GlassBackgroundType.IMAGE -> if (hasBitmap) GlassSourceKind.Bitmap else GlassSourceKind.Gradient
    else -> if (hasBitmap) GlassSourceKind.Bitmap else GlassSourceKind.Plain
}

sealed interface GlassSource {
    data object Plain : GlassSource
    data object Gradient : GlassSource
    /** [material] is the pre-blurred copy glass cards draw (see [glassMaterial]). */
    data class Bitmap(val image: ImageBitmap, val material: ImageBitmap, val meanLuma: Float) : GlassSource
}

@Immutable
data class GlassBackgroundState(
    val source: GlassSource,
    val blur: Dp,
    val dim: Float,
    val wallpaperFallback: Boolean,
)

val LocalGlassBackgroundState = staticCompositionLocalOf {
    GlassBackgroundState(GlassSource.Plain, 0.dp, 0f, wallpaperFallback = false)
}

/**
 * What glass surfaces sample. Inside page content it is the page background only; inside bars it is
 * the background plus the content scrolling under the bar. Null outside a [GlassPage].
 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

fun glassImageFile(context: Context): File = File(context.filesDir, "glass_bg.jpg")

/**
 * Process-wide copy of the last decoded background. MainActivity preloads it from the app's own
 * cached file (no root needed) while the splash is up, so the first frame already shows the
 * wallpaper instead of flashing the plain surface.
 */
object GlassBackgroundCache {
    @Volatile
    var decoded: DecodedGlass? = null
        private set

    @Volatile
    var key: String? = null
        private set

    fun maxEdge(context: Context): Int =
        context.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }

    /** Identifies a baked background: the source file version, decode size and the page blur. */
    fun keyOf(file: File, maxEdge: Int, blurDp: Float): String =
        "${file.path}:${file.lastModified()}:${file.length()}:$maxEdge:$blurDp"

    /** The app-private file the background of [type] is drawn from, if one exists yet. */
    fun localFile(context: Context, type: Int): File? = when (type) {
        GlassBackgroundType.GRADIENT -> null
        GlassBackgroundType.IMAGE -> glassImageFile(context)
        else -> File(context.filesDir, "glass_wallpaper")
    }?.takeIf { it.isFile }

    /** Decodes and bakes [file]; null when it cannot be decoded. */
    suspend fun load(context: Context, file: File, blurDp: Float): DecodedGlass? {
        val maxEdge = maxEdge(context)
        val source = decodeGlassSource(file, maxEdge) ?: return null
        val density = context.resources.displayMetrics.density
        return bakeGlass(
            source = source,
            pageBlurPx = blurDp.coerceIn(0f, 40f) * density,
            cardBlurPx = GlassDefaults.cardBlur.value * density,
        )
    }

    suspend fun preload(context: Context, type: Int, blurDp: Float) {
        val file = localFile(context, type) ?: return
        val fullKey = keyOf(file, maxEdge(context), blurDp)
        if (fullKey == key && decoded != null) return
        load(context, file, blurDp)?.let { store(it, fullKey) }
    }

    fun store(value: DecodedGlass, fullKey: String) {
        decoded = value
        key = fullKey
    }
}

@Composable
fun rememberGlassBackgroundState(type: Int, blur: Float, dim: Float, imageVersion: Long = 0L): GlassBackgroundState {
    val context = LocalContext.current
    val maxEdge = GlassBackgroundCache.maxEdge(context)
    val repository = remember(context) {
        WallpaperRepository(
            filesDir = context.filesDir,
            store = PrefsStampStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE)),
        )
    }

    // Start from the preloaded background so the first frame is never the plain surface.
    val preloaded = GlassBackgroundCache.localFile(context, type)?.let { GlassBackgroundCache.keyOf(it, maxEdge, blur) }
        ?.takeIf { it == GlassBackgroundCache.key }
    var decoded by remember { mutableStateOf(if (preloaded != null) GlassBackgroundCache.decoded else null) }
    var loadedKey by remember { mutableStateOf(preloaded) }
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }

    LaunchedEffect(type, resumeCount, maxEdge, imageVersion, blur) {
        val file = when (type) {
            GlassBackgroundType.GRADIENT -> null
            GlassBackgroundType.IMAGE -> glassImageFile(context).takeIf { it.isFile }
            else -> repository.refresh()
        }
        if (file == null) {
            decoded = null
            loadedKey = null
            return@LaunchedEffect
        }
        // Skip re-baking when the same file version and blur are already on screen.
        val key = GlassBackgroundCache.keyOf(file, maxEdge, blur)
        if (key == loadedKey && decoded != null) return@LaunchedEffect
        // Keep showing the previous background until the new one is baked.
        val baked = GlassBackgroundCache.load(context, file, blur) ?: run {
            decoded = null
            loadedKey = null
            return@LaunchedEffect
        }
        GlassBackgroundCache.store(baked, key)
        decoded = baked
        loadedKey = key
    }

    val kind = pickGlassSourceKind(type, decoded != null)
    val source = when (kind) {
        GlassSourceKind.Plain -> GlassSource.Plain
        GlassSourceKind.Gradient -> GlassSource.Gradient
        GlassSourceKind.Bitmap -> decoded!!.let { GlassSource.Bitmap(it.image, it.material, it.meanLuma) }
    }
    return GlassBackgroundState(
        source = source,
        blur = blur.coerceIn(0f, 40f).dp,
        dim = dim.coerceIn(0f, 0.6f),
        wallpaperFallback = type != GlassBackgroundType.GRADIENT && type != GlassBackgroundType.IMAGE &&
            kind == GlassSourceKind.Plain,
    )
}

/**
 * A navigation page: paints the glass background behind [content] and records it into a
 * backdrop that glass surfaces in [content] sample through [LocalGlassBackdrop]. Each page paints
 * its own copy so outgoing pages never show through during transitions.
 */
@Composable
fun GlassPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val pageBackdrop = rememberLayerBackdrop()
    val overlayBackdrop = rememberLayerBackdrop()
    val anchor = remember { GlassPageAnchor() }
    val transition = LocalNavTransitionScope.current
    // Only flips at the start and end of a transition, so pages recompose twice per navigation.
    val moving by remember(transition) { derivedStateOf { transition?.isRunning == true } }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { anchor.coordinates = it }
            .layerBackdrop(overlayBackdrop)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
        ) {
            GlassBackgroundLayer(LocalGlassBackgroundState.current, moving)
        }
        CompositionLocalProvider(
            LocalGlassBackdrop provides pageBackdrop,
            LocalGlassOverlayBackdrop provides overlayBackdrop,
            LocalGlassPageAnchor provides anchor,
            LocalGlassPageMoving provides moving,
        ) {
            content()
        }
    }
}

@Composable
private fun GlassBackgroundLayer(state: GlassBackgroundState, moving: Boolean) {
    when (val source = state.source) {
        GlassSource.Plain -> Box(
            Modifier
                .fillMaxSize()
                .background(colorScheme.surface)
        )

        GlassSource.Gradient -> GradientLayer(animate = !moving)

        // Surface behind the image so transparent pixels of a custom PNG do not show the window.
        is GlassSource.Bitmap -> Box(
            Modifier
                .fillMaxSize()
                .background(colorScheme.surface)
        ) {
            Image(
                bitmap = source.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Already blurred once at bake time (a live blur re-ran every frame).
                modifier = Modifier.fillMaxSize(),
            )
            val dark = isInDarkTheme()
            // Raised automatically when the image fights the theme, so text stays readable.
            val dim = effectiveDim(state.dim, source.meanLuma, dark)
            if (dim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background((if (dark) Color.Black else Color.White).copy(alpha = dim))
                )
            }
        }
    }
}

@Composable
private fun GradientLayer(animate: Boolean) {
    val seed = colorScheme.primary
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateAsState()
        // The gradient has no fine detail: rasterize the noise shader at 1/scale in an offscreen
        // layer and stretch it, so it costs 1/scale^2 of the pixels. Paused while the page slides.
        val scale = GlassDefaults.gradientDownscale.toFloat()
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            BgEffectBackground(
                dynamicBackground = animate && lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                modifier = Modifier
                    .requiredSize(maxWidth / scale, maxHeight / scale)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                    },
                isFullSize = true,
                seedColor = seed,
            ) { }
        }
    } else {
        // RuntimeShader needs API 33: draw a static gradient of the same tinted colors.
        val deviceType = if (shouldShowSplitPane()) DeviceType.PAD else DeviceType.PHONE
        val dark = isInDarkTheme()
        val colors = remember(seed, deviceType, dark) {
            val config = BgEffectConfig.tint(BgEffectConfig.get(deviceType, dark), seed.toArgb())
            List(4) { i ->
                Color(config.colors1[i * 4], config.colors1[i * 4 + 1], config.colors1[i * 4 + 2])
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(colorScheme.surface)
                .background(Brush.linearGradient(colors))
        )
    }
}
