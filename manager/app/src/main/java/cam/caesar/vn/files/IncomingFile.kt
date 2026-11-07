package cam.caesar.vn.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file another app asked Cam to open (Telegram's "Open with", like MT Manager): the path the
 * Files tab shows it at. Its real place when root can see it there (the descriptor the sender
 * opened names it), so edits go to the file itself; otherwise a copy in the cache.
 */
object IncomingFile {
    private const val TAG = "IncomingFile"
    private const val DIR = "files_incoming"
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    /** Null when the file cannot be read at all. */
    suspend fun resolve(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path?.takeIf { it.startsWith("/") }
        realPath(context, uri) ?: runCatching { copy(context, uri) }.onFailure { Log.w(TAG, "copy $uri", it) }.getOrNull()
    }

    private suspend fun realPath(context: Context, uri: Uri): String? {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .onFailure { Log.w(TAG, "open $uri", it) }.getOrNull() ?: return null
        return pfd.use {
            val link = runCatching { Os.readlink("/proc/self/fd/${it.fd}") }.getOrNull() ?: return null
            Log.i(TAG, "$uri is $link")
            val size = it.statSize
            // a pipe, a deleted file, ashmem: nothing on disk to point at
            if (size < 0) return null
            candidates(link).firstOrNull { path ->
                val entry = runCatching { CamFileClient.stat(path) }.onFailure { e -> Log.w(TAG, "stat $path", e) }.getOrNull()
                entry != null && entry.type == FileEntry.TYPE_FILE && entry.size == size
            } ?: null.also { Log.i(TAG, "root does not see $link ($size bytes): copying it") }
        }
    }

    /**
     * Where root may find what /proc/self/fd names. Shared storage is named by the mount the
     * sender's process saw (/mnt/user/0/emulated/0/..., /data/media/0/...): /storage/emulated
     * first, the way the Files tab shows it, then the name as it is.
     */
    fun candidates(link: String): List<String> {
        if (!link.startsWith("/") || link.endsWith(" (deleted)")) return emptyList()
        val shared = SHARED.matchEntire(link)?.let { m -> "/storage/emulated/${m.groupValues[1]}/${m.groupValues[2]}" }
        return listOfNotNull(shared, link).distinct()
    }

    private val SHARED = Regex("""^/(?:mnt/(?:user|pass_through|installer|androidwritable)/\d+/emulated|data/media|storage/emulated)/(\d+)/(.+)$""")

    private fun copy(context: Context, uri: Uri): String? {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > KEEP_MS) it.deleteRecursively() }
        val file = File(dir, safeName(displayName(context, uri) ?: uri.lastPathSegment))
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        return file.absolutePath
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** One plain name in the cache folder: no folders, never "." or "..". */
    fun safeName(name: String?): String {
        val base = name?.substringAfterLast('/')?.replace('\u0000', '_')?.trim().orEmpty()
        return if (base.isEmpty() || base == "." || base == "..") "file" else base
    }
}
