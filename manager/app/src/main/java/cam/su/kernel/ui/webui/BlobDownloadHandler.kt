package cam.su.kernel.ui.webui

import android.util.Base64
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Handles big blob downloads by spooling base64 chunks to a temporary file, which
 * [WebUIDownloadInterface] then saves to Downloads.
 */
object BlobDownloadHandler {
    private const val STALE_THRESHOLD_MS = 5 * 60 * 1000L

    private val pendingDownloads = ConcurrentHashMap<String, PendingDownload>()
    @Volatile
    private var lastCleanupTime = System.currentTimeMillis()

    private data class PendingDownload(
        val fileName: String,
        val mimeType: String?,
        val tempFile: File,
        val outputStream: OutputStream,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var completed: Boolean = false,
    )

    class CompletedBlob(val fileName: String, val mimeType: String?, val file: File)

    /** Register a new blob download session and return its id. */
    fun registerBlobDownload(fileName: String, mimeType: String?, cacheDir: File): String {
        val downloadId = UUID.randomUUID().toString()
        val tempFile = File.createTempFile("ksu-blob-", ".download", cacheDir)
        pendingDownloads[downloadId] = PendingDownload(
            fileName = fileName,
            mimeType = mimeType,
            tempFile = tempFile,
            outputStream = tempFile.outputStream().buffered(64 * 1024),
        )

        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > STALE_THRESHOLD_MS) {
            lastCleanupTime = now
            cleanupStaleDownloads()
        }

        return downloadId
    }

    /** Write one base64 encoded chunk to the session. */
    fun writeChunk(downloadId: String, base64Chunk: String): Boolean {
        val download = pendingDownloads[downloadId] ?: return false
        return try {
            synchronized(download) {
                if (download.completed) return false
                download.outputStream.write(Base64.decode(base64Chunk, Base64.DEFAULT))
            }
            true
        } catch (e: Exception) {
            Log.e("BlobDownloadHandler", "Failed to write chunk for $downloadId", e)
            closeDownload(downloadId)
            false
        }
    }

    /** Close the spooled file. The session stays until [cancelDownload] deletes the file. */
    fun completeDownload(downloadId: String): CompletedBlob? {
        val download = pendingDownloads[downloadId] ?: return null
        return try {
            synchronized(download) {
                if (download.completed) return null
                download.outputStream.flush()
                download.outputStream.close()
                download.completed = true
            }
            CompletedBlob(download.fileName, download.mimeType, download.tempFile)
        } catch (e: Exception) {
            Log.e("BlobDownloadHandler", "Failed to complete download $downloadId", e)
            closeDownload(downloadId)
            null
        }
    }

    fun cancelDownload(downloadId: String) = closeDownload(downloadId)

    private fun closeDownload(downloadId: String) {
        pendingDownloads.remove(downloadId)?.let { download ->
            runCatching { download.outputStream.close() }
            runCatching { download.tempFile.delete() }
        }
    }

    private fun cleanupStaleDownloads() {
        val now = System.currentTimeMillis()
        val staleIds = pendingDownloads.entries
            .filter { now - it.value.createdAt > TimeUnit.MINUTES.toMillis(5) }
            .map { it.key }
        staleIds.forEach { downloadId ->
            Log.w("BlobDownloadHandler", "Cleaning up stale download: $downloadId")
            closeDownload(downloadId)
        }
    }

    fun cleanup() = pendingDownloads.keys.toList().forEach(::closeDownload)
}
