package cam.su.kernel.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.util.Size
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Small pictures for the Files tab's rows, made in the root process (which can read the file):
 * a photo scaled down (EXIF rotation applied), an APK's own icon, a video's frame. Sent back as a
 * few KB of WebP rather than a bitmap.
 */
class Thumbnailer(private val context: Context) {

    fun make(path: String, size: Int): ByteArray? {
        val file = File(path)
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val bitmap = when (ext) {
            "apk" -> apkIcon(path, size)
            in IMAGES -> image(file, size)
            in VIDEOS -> runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(size, size), null) }.getOrNull()?.let { crop(it, size) }
            else -> null
        } ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun image(file: File, size: Int): Bitmap? = runCatching {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            // decode at about the size shown (the short side just covers it), never the full photo
            val w = info.size.width
            val h = info.size.height
            val scale = size.toFloat() / minOf(w, h)
            if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        crop(decoded, size)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun apkIcon(path: String, size: Int): Bitmap? = runCatching {
        val pm = context.packageManager
        val app = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
        app.sourceDir = path
        app.publicSourceDir = path
        app.loadIcon(pm).toBitmap(size, size)
    }.getOrNull()

    /** The centre square, [size] on a side. */
    private fun crop(source: Bitmap, size: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = if (side == size) square else Bitmap.createScaledBitmap(square, size, size, true)
        if (square !== source && square !== scaled) square.recycle()
        if (source !== scaled) source.recycle()
        return scaled
    }

    companion object {
        val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        val VIDEOS = setOf("mp4", "mkv", "webm", "mov", "3gp", "avi")
    }
}
