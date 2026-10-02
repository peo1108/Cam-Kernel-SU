package me.weishu.kernelsu.ui.component.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

internal fun decodeScaledBitmap(file: File, maxEdge: Int): Bitmap? = runCatching {
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@runCatching null
    val (w, h) = downscaleTarget(decoded.width, decoded.height, maxEdge)
    if (w == decoded.width && h == decoded.height) decoded
    else Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
}.getOrNull()
