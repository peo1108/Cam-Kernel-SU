package cam.su.kernel.files

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** One row of a directory listing, as seen by root. */
@Parcelize
data class FileEntry(
    val name: String,
    val path: String,
    val type: Int,
    val size: Long,
    val mtime: Long,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val owner: String,
    val group: String,
    /** SELinux label; null for symlinks and file systems without labels. */
    val context: String?,
    val linkTarget: String? = null,
    val linkBroken: Boolean = false,
    val targetIsDir: Boolean = false,
) : Parcelable {
    val isDir: Boolean
        get() = type == TYPE_DIR || (type == TYPE_LINK && targetIsDir)

    val isLink: Boolean
        get() = type == TYPE_LINK

    companion object {
        const val TYPE_FILE = 0
        const val TYPE_DIR = 1
        const val TYPE_LINK = 2
        const val TYPE_OTHER = 3
    }
}
