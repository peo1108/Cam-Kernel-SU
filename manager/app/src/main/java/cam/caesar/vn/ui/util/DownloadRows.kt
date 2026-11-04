package cam.su.kernel.ui.util

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * A file deleted with root (rm, the Files tab) leaves its MediaStore row behind. MediaStore then
 * gives a new download that path (also the numbered "name (1)" ones), and publishing it fails on
 * the row's unique path. Call before inserting a download. Rows whose file is gone are all safe
 * to drop, whatever their name. The query does not return pending rows.
 */
internal fun ContentResolver.dropStaleDownloadRows() {
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    runCatching {
        query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA), null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val path = rows.getString(1) ?: continue
                if (!File(path).exists()) {
                    delete(ContentUris.withAppendedId(collection, rows.getLong(0)), null, null)
                }
            }
        }
    }.onFailure { Log.w("DownloadRows", "Failed to clean stale rows", it) }
}
