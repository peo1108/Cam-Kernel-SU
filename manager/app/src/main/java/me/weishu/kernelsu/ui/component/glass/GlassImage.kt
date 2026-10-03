package me.weishu.kernelsu.ui.component.glass

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Target size that keeps the aspect ratio with the long edge at most [maxEdge]; never upscales. */
fun downscaleTarget(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longEdge = max(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / longEdge
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/**
 * A decoded glass background: [image] is drawn behind the page (already blurred by the user's
 * background blur), [material] is a pre-blurred, saturated, half-resolution copy that glass cards draw
 * instead of sampling a live backdrop, [meanLuma] (0..1) keeps text readable.
 */
class DecodedGlass(val image: ImageBitmap, val material: ImageBitmap, val meanLuma: Float)

/** Decodes the software bitmap of [file] downsampled to [maxEdge] on the IO dispatcher; null when missing or invalid. */
suspend fun decodeGlassSource(file: File, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
    decodeScaledBitmap(file, maxEdge)
}

/**
 * Bakes [source] into a [DecodedGlass] once (GPU blur), so pages and cards only draw images:
 * a live blur or backdrop sample per frame is what made transitions stutter.
 */
suspend fun bakeGlass(source: Bitmap, pageBlurPx: Float, cardBlurPx: Float): DecodedGlass = withContext(Dispatchers.Default) {
    val page = if (pageBlurPx > 0f) blurForGlass(source, pageBlurPx, saturation = 1f) ?: source else source
    // Cards see the page through glass: page blur and card blur combine (Gaussian radii add in quadrature).
    val half = Bitmap.createScaledBitmap(source, max(1, source.width / 2), max(1, source.height / 2), true)
    val combined = sqrt(pageBlurPx * pageBlurPx + cardBlurPx * cardBlurPx) / 2f
    val material = blurForGlass(half, combined, saturation = GlassDefaults.materialSaturation) ?: half
    DecodedGlass(page.asImageBitmap(), material.asImageBitmap(), meanLuma(source))
}

/** Gaussian blur + saturation of [src] rendered once on the GPU; null if rendering fails. */
internal fun blurForGlass(src: Bitmap, radiusPx: Float, saturation: Float): Bitmap? = runCatching {
    val w = src.width
    val h = src.height
    val reader = ImageReader.newInstance(
        w, h, PixelFormat.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )
    val renderer = HardwareRenderer()
    try {
        val node = RenderNode("glassBlur")
        node.setPosition(0, 0, w, h)
        var effect = RenderEffect.createBlurEffect(radiusPx.coerceAtLeast(0.1f), radiusPx.coerceAtLeast(0.1f), Shader.TileMode.CLAMP)
        if (saturation != 1f) {
            val matrix = ColorMatrix().apply { setSaturation(saturation) }
            effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), effect)
        }
        node.setRenderEffect(effect)
        val canvas = node.beginRecording()
        canvas.drawBitmap(src, 0f, 0f, null)
        node.endRecording()
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val image = reader.acquireNextImage() ?: return@runCatching null
        image.use { img ->
            val buffer = img.hardwareBuffer ?: return@runCatching null
            buffer.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        }
    } finally {
        renderer.destroy()
        reader.close()
    }
}.getOrNull()

internal fun decodeScaledBitmap(file: File, maxEdge: Int): Bitmap? =
    if (file.isFile) decodeScaledBitmap(ImageDecoder.createSource(file), maxEdge) else null

/** Decodes the stream that [open] returns, downscaled to [maxEdge]. */
internal fun decodeScaledBitmap(open: () -> InputStream?, maxEdge: Int): Bitmap? = runCatching {
    val bytes = open()?.use { it.readBytes() } ?: return@runCatching null
    decodeScaledBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxEdge)
}.getOrNull()

// ImageDecoder applies EXIF orientation (camera photos), unlike BitmapFactory.
private fun decodeScaledBitmap(source: ImageDecoder.Source, maxEdge: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (w, h) = downscaleTarget(info.size.width, info.size.height, maxEdge)
        decoder.setTargetSize(w, h)
        // Software pixels: the bitmap is read back (luma, JPEG compress).
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

/** Average Rec. 709 luma of [bitmap], sampled on a 16x16 thumbnail. */
internal fun meanLuma(bitmap: Bitmap): Float {
    val thumb = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
    val pixels = IntArray(16 * 16)
    thumb.getPixels(pixels, 0, 16, 0, 0, 16, 16)
    if (thumb !== bitmap) thumb.recycle()
    return pixels.sumOf { p ->
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
    }.toFloat() / pixels.size
}

/**
 * Saves the image from [open] to [dest], downscaled (unique temp file + atomic replace). Images with
 * transparency are kept as PNG (JPEG would turn it black); the decoder sniffs the format, not the name.
 */
fun writeGlassImage(open: () -> InputStream?, dest: File, maxEdge: Int): Boolean = runCatching {
    val bitmap = decodeScaledBitmap(open, maxEdge) ?: return@runCatching false
    val tmp = File.createTempFile(dest.name, ".tmp", dest.parentFile)
    try {
        val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        tmp.outputStream().use { bitmap.compress(format, 90, it) }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        bitmap.recycle()
        tmp.delete()
    }
    true
}.getOrDefault(false)
