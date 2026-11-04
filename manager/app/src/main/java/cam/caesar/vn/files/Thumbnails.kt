package cam.su.kernel.files

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * Row pictures for the Files tab, kept in memory (about 10 MB) and asked from root at most three
 * at a time, so a fast fling through DCIM does not queue hundreds of decodes.
 */
object Thumbnails {
    const val SIZE = 96
    private const val MAX_IMAGE_BYTES = 200L * 1024 * 1024

    private val cache = LruCache<String, ImageBitmap>(300)

    /** Files that gave no picture: not asked again until they change. */
    private val none = Collections.synchronizedSet(HashSet<String>())
    private val gate = Semaphore(3)

    private fun key(entry: FileEntry) = "${entry.path}|${entry.mtime}|${entry.contentSize}"

    fun wants(entry: FileEntry): Boolean {
        if (entry.isDir || entry.type == FileEntry.TYPE_OTHER || entry.linkBroken || Archives.isInside(entry.path)) return false
        if (entry.contentSize <= 0) return false
        val ext = FileKinds.extension(entry.name)
        return when {
            ext == "apk" -> true
            ext in Thumbnailer.IMAGES -> entry.contentSize <= MAX_IMAGE_BYTES
            ext in Thumbnailer.VIDEOS -> true
            else -> false
        }
    }

    fun cached(entry: FileEntry): ImageBitmap? = cache.get(key(entry))

    suspend fun load(entry: FileEntry): ImageBitmap? {
        val key = key(entry)
        cache.get(key)?.let { return it }
        if (key in none) return null
        val bytes = gate.withPermit {
            cache.get(key)?.let { return it }
            // not runCatching: a row scrolled away cancels its load, and that must not count as
            // "no picture" (it was marking every other row of a fling for good)
            try {
                CamFileClient.thumbnail(entry.path, SIZE)
            } catch (_: FsException) {
                null
            }
        }
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
        if (bitmap == null) none += key else cache.put(key, bitmap)
        return bitmap
    }
}
