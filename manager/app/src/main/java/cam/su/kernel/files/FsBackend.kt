package cam.su.kernel.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Linux errno values (the same on every Android ABI). */
object Errno {
    const val EPERM = 1
    const val ENOENT = 2
    const val EIO = 5
    const val EAGAIN = 11
    const val EACCES = 13
    const val EEXIST = 17
    const val EXDEV = 18
    const val ENOTDIR = 20
    const val EISDIR = 21
    const val EINVAL = 22
    const val EFBIG = 27
    const val ENOSPC = 28
    const val EPIPE = 32
    const val EROFS = 30
    const val ENOTEMPTY = 39
    const val ELOOP = 40
    const val ENOSYS = 38
    const val EOPNOTSUPP = 95
}

class FsException(val errno: Int, val path: String? = null, detail: String? = null) :
    IOException(detail ?: path?.let { "errno $errno: $it" } ?: "errno $errno") {

    /** Binder only carries a few exception types; the errno rides in the message. */
    fun toRemote() = IllegalStateException("$REMOTE_PREFIX$errno|$message")

    companion object {
        private const val REMOTE_PREFIX = "cam-errno:"

        fun fromRemote(e: IllegalStateException): FsException? {
            val msg = e.message ?: return null
            if (!msg.startsWith(REMOTE_PREFIX)) return null
            val rest = msg.removePrefix(REMOTE_PREFIX)
            val errno = rest.substringBefore('|').toIntOrNull() ?: return null
            return FsException(errno, detail = rest.substringAfter('|'))
        }
    }
}

data class StatInfo(
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val mtime: Long,
)

/** File mode bits from `<sys/stat.h>`. */
object FileMode {
    const val S_IFMT = 0xF000
    const val S_IFDIR = 0x4000
    const val S_IFREG = 0x8000
    const val S_IFLNK = 0xA000
    const val PERM_MASK = 0xFFF

    fun isDir(mode: Int) = mode and S_IFMT == S_IFDIR
    fun isRegular(mode: Int) = mode and S_IFMT == S_IFREG
    fun isLink(mode: Int) = mode and S_IFMT == S_IFLNK

    fun typeOf(mode: Int) = when (mode and S_IFMT) {
        S_IFDIR -> FileEntry.TYPE_DIR
        S_IFREG -> FileEntry.TYPE_FILE
        S_IFLNK -> FileEntry.TYPE_LINK
        else -> FileEntry.TYPE_OTHER
    }
}

/**
 * Raw file system calls. Every call throws [FsException] with the errno; none of them
 * follows a symlink unless its name says so ([stat], [realpath], and the calls that
 * open or relabel a file, as the syscalls do).
 */
interface FsBackend {
    fun lstat(path: String): StatInfo
    fun stat(path: String): StatInfo
    fun list(path: String): List<String>
    fun readlink(path: String): String
    fun realpath(path: String): String

    /** SELinux label, or null when the file system has none. Never throws. */
    fun getContext(path: String): String?
    fun setContext(path: String, context: String)

    fun mkdir(path: String, mode: Int)

    /** Creates an empty regular file; fails with EEXIST if anything is there. */
    fun createFile(path: String, mode: Int)
    fun symlink(target: String, link: String)
    fun rename(from: String, to: String)
    fun chmod(path: String, mode: Int)
    fun lchown(path: String, uid: Int, gid: Int)

    /** unlink(2) for a file or link, rmdir(2) for a directory. */
    fun remove(path: String)
    fun openRead(path: String): InputStream

    /** Truncates; closing the stream fsyncs before it closes. */
    fun openWrite(path: String): OutputStream

    /** [total, available] bytes of the file system holding [path]. */
    fun statFs(path: String): LongArray
}
