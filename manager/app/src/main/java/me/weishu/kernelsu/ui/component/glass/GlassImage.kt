package me.weishu.kernelsu.ui.component.glass

import android.graphics.Bitmap
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

/** Target size that keeps the aspect ratio with the long edge at most [maxEdge]; never upscales. */
fun downscaleTarget(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longEdge = max(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / longEdge
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** A decoded glass background plus its average luma (0..1), used to keep text readable. */
class DecodedGlass(val image: ImageBitmap, val meanLuma: Float)

/** Decodes [file] downsampled to [maxEdge] on the IO dispatcher; null when the file is missing or invalid. */
suspend fun decodeGlassBitmap(file: File, maxEdge: Int): DecodedGlass? = withContext(Dispatchers.IO) {
    val bitmap = decodeScaledBitmap(file, maxEdge) ?: return@withContext null
    DecodedGlass(bitmap.asImageBitmap(), meanLuma(bitmap))
}

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

/** Saves the image from [open] to [dest] as a downscaled JPEG (unique temp file + atomic replace). */
fun writeGlassImage(open: () -> InputStream?, dest: File, maxEdge: Int): Boolean = runCatching {
    val bitmap = decodeScaledBitmap(open, maxEdge) ?: return@runCatching false
    val tmp = File.createTempFile(dest.name, ".tmp", dest.parentFile)
    try {
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        bitmap.recycle()
        tmp.delete()
    }
    true
}.getOrDefault(false)
