package cam.su.kernel.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import cam.su.kernel.BuildConfig
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Open with" / Share for files only root can read, without copying them: the other app gets the
 * descriptor the root service opened, so a 4 GB video opens at once and can seek. Only files the
 * Files tab handed out are served, each behind a random token (no path can be guessed into), and
 * the provider is not exported: an app reaches a file only through the grant on its URI.
 */
class RootFileProvider : ContentProvider() {
    private class Shared(val path: String, val name: String, val size: Long)

    override fun onCreate() = true

    private fun shared(uri: Uri): Shared =
        uri.pathSegments.getOrNull(1)?.let { SHARED[it] } ?: throw FileNotFoundException(uri.toString())

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val file = shared(uri)
        return try {
            // a binder thread of this provider: blocking on the root service is fine here
            runBlocking { CamFileClient.openFd(file.path) }
        } catch (e: FsException) {
            throw FileNotFoundException(e.message)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = shared(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.size
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String = mimeOf(shared(uri).name)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.rootfiles"
        private val SHARED = ConcurrentHashMap<String, Shared>()

        /**
         * Files the provider can hand out: shared storage and the read-only partitions. A file in
         * another app's private data keeps its SELinux label on the descriptor, so the receiving
         * app may not be allowed to read it: those are still copied.
         */
        fun canStream(entry: FileEntry): Boolean {
            if (Archives.isInside(entry.path) || entry.isDir || entry.type == FileEntry.TYPE_OTHER) return false
            val p = entry.path
            return p.startsWith("/storage/") || p.startsWith("/sdcard/") ||
                listOf("/system/", "/vendor/", "/product/", "/system_ext/", "/odm/").any { p.startsWith(it) }
        }

        fun uriFor(entry: FileEntry): Uri {
            val token = UUID.randomUUID().toString()
            SHARED[token] = Shared(entry.path, entry.name, entry.contentSize)
            return Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("f").appendPath(token).appendPath(entry.name).build()
        }

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
    }
}
