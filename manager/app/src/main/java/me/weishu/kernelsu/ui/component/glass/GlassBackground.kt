package me.weishu.kernelsu.ui.component.glass

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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import me.weishu.kernelsu.data.repository.PrefsStampStore
import me.weishu.kernelsu.data.repository.WallpaperRepository
import me.weishu.kernelsu.ui.component.miuix.effect.BgEffectBackground
import me.weishu.kernelsu.ui.component.miuix.effect.BgEffectConfig
import me.weishu.kernelsu.ui.component.miuix.effect.DeviceType
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import me.weishu.kernelsu.ui.util.shouldShowSplitPane
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
    data class Bitmap(val image: ImageBitmap) : GlassSource
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

@Composable
fun rememberGlassBackgroundState(type: Int, blur: Float, dim: Float): GlassBackgroundState {
    val context = LocalContext.current
    val containerSize = LocalWindowInfo.current.containerSize
    val maxEdge = max(containerSize.width, containerSize.height).takeIf { it > 0 }
        ?: max(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
    val repository = remember(context) {
        WallpaperRepository(
            filesDir = context.filesDir,
            store = PrefsStampStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE)),
        )
    }

    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }

    LaunchedEffect(type, resumeCount, maxEdge) {
        val file = when (type) {
            GlassBackgroundType.GRADIENT -> null
            GlassBackgroundType.IMAGE -> glassImageFile(context).takeIf { it.isFile }
            else -> repository.refresh()
        }
        if (file == null) {
            bitmap = null
            loadedKey = null
            return@LaunchedEffect
        }
        // Skip re-decoding when the same file version is already on screen.
        val key = "${file.path}:${file.lastModified()}:${file.length()}:$maxEdge"
        if (key == loadedKey && bitmap != null) return@LaunchedEffect
        bitmap = decodeGlassBitmap(file, maxEdge)
        loadedKey = if (bitmap != null) key else null
    }

    val kind = pickGlassSourceKind(type, bitmap != null)
    val source = when (kind) {
        GlassSourceKind.Plain -> GlassSource.Plain
        GlassSourceKind.Gradient -> GlassSource.Gradient
        GlassSourceKind.Bitmap -> GlassSource.Bitmap(bitmap!!)
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .layerBackdrop(overlayBackdrop)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
        ) {
            GlassBackgroundLayer(LocalGlassBackgroundState.current)
        }
        CompositionLocalProvider(
            LocalGlassBackdrop provides pageBackdrop,
            LocalGlassOverlayBackdrop provides overlayBackdrop,
        ) {
            content()
        }
    }
}

@Composable
private fun GlassBackgroundLayer(state: GlassBackgroundState) {
    when (val source = state.source) {
        GlassSource.Plain -> Box(
            Modifier
                .fillMaxSize()
                .background(colorScheme.surface)
        )

        GlassSource.Gradient -> GradientLayer()

        is GlassSource.Bitmap -> Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = source.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (state.blur > 0.dp) Modifier.blur(state.blur, BlurredEdgeTreatment.Rectangle) else Modifier
                    ),
            )
            if (state.dim > 0f) {
                val dimColor = if (isInDarkTheme()) Color.Black else Color.White
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(dimColor.copy(alpha = state.dim))
                )
            }
        }
    }
}

@Composable
private fun GradientLayer() {
    val seed = colorScheme.primary
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateAsState()
        BgEffectBackground(
            dynamicBackground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
            modifier = Modifier.fillMaxSize(),
            isFullSize = true,
            seedColor = seed,
        ) { }
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
