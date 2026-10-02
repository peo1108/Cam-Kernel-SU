package me.weishu.kernelsu.ui.component.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Target size that keeps the aspect ratio with the long edge at most [maxEdge]; never upscales. */
fun downscaleTarget(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val longEdge = max(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / longEdge
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/** Decodes [file] downsampled to [maxEdge] on the IO dispatcher; null when the file is missing or invalid. */
suspend fun decodeGlassBitmap(file: File, maxEdge: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    decodeScaledBitmap(file, maxEdge)?.asImageBitmap()
}

internal fun decodeScaledBitmap(file: File, maxEdge: Int): Bitmap? =
    if (file.isFile) decodeScaledBitmap({ file.inputStream() }, maxEdge) else null

/** Decodes the stream that [open] returns (opened twice: bounds, then pixels) downscaled to [maxEdge]. */
internal fun decodeScaledBitmap(open: () -> InputStream?, maxEdge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // A bounds-only decode always returns null; only the missing stream means failure.
    val boundsStream = open() ?: return@runCatching null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
    val (w, h) = downscaleTarget(decoded.width, decoded.height, maxEdge)
    if (w == decoded.width && h == decoded.height) decoded
    else Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
}.getOrNull()

/** Saves the image from [open] to [dest] as a downscaled JPEG (temp file + rename). */
fun writeGlassImage(open: () -> InputStream?, dest: File, maxEdge: Int): Boolean = runCatching {
    val bitmap = decodeScaledBitmap(open, maxEdge) ?: return@runCatching false
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
    if (!tmp.renameTo(dest)) {
        dest.delete()
        tmp.renameTo(dest)
    } else {
        true
    }
}.getOrDefault(false)
