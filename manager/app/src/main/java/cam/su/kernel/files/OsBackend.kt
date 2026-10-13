package cam.su.kernel.files

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.O_CLOEXEC
import android.system.OsConstants.O_CREAT
import android.system.OsConstants.O_EXCL
import android.system.OsConstants.O_RDONLY
import android.system.OsConstants.O_TRUNC
import android.system.OsConstants.O_WRONLY
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream

/** [FsBackend] on the real file system through android.system.Os. */
object OsBackend : FsBackend {
    private const val SELINUX_XATTR = "security.selinux"

    private inline fun <T> os(path: String, block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw FsException(e.errno, path, "${e.message}: $path")
    }

    private fun StructStat.info() = StatInfo(
        mode = st_mode,
        uid = st_uid,
        gid = st_gid,
        size = st_size,
        mtime = st_mtim.tv_sec * 1000 + st_mtim.tv_nsec / 1_000_000,
    )

    override fun lstat(path: String) = os(path) { Os.lstat(path).info() }

    override fun stat(path: String) = os(path) { Os.stat(path).info() }

    override fun list(path: String): List<String> {
        File(path).list()?.let { return it.asList() }
        // File.list() hides the reason; stat tells missing, not a directory, or unreadable.
        val st = stat(path)
        throw FsException(if (FileMode.isDir(st.mode)) Errno.EACCES else Errno.ENOTDIR, path)
    }

    override fun readlink(path: String) = os(path) { Os.readlink(path) }

    override fun realpath(path: String): String {
        stat(path)
        return File(path).canonicalPath
    }

    override fun getContext(path: String): String? = try {
        val raw = Os.getxattr(path, SELINUX_XATTR)
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        String(raw, 0, end)
    } catch (_: ErrnoException) {
        null
    }

    override fun setContext(path: String, context: String) = os(path) {
        Os.setxattr(path, SELINUX_XATTR, context.toByteArray() + 0, 0)
    }

    override fun mkdir(path: String, mode: Int) = os(path) { Os.mkdir(path, mode) }

    override fun createFile(path: String, mode: Int) = os(path) {
        Os.close(Os.open(path, O_WRONLY or O_CREAT or O_EXCL or O_CLOEXEC, mode))
    }

    override fun symlink(target: String, link: String) = os(link) { Os.symlink(target, link) }

    override fun rename(from: String, to: String) = os(from) { Os.rename(from, to) }

    override fun chmod(path: String, mode: Int) = os(path) { Os.chmod(path, mode) }

    override fun lchown(path: String, uid: Int, gid: Int) = os(path) { Os.lchown(path, uid, gid) }

    override fun remove(path: String) = os(path) { Os.remove(path) }

    override fun openRead(path: String): InputStream =
        FdInputStream(os(path) { Os.open(path, O_RDONLY or O_CLOEXEC, 0) }, path)

    override fun openWrite(path: String): OutputStream =
        FdOutputStream(os(path) { Os.open(path, O_WRONLY or O_TRUNC or O_CLOEXEC, 0) }, path)

    private class FdInputStream(private val fd: FileDescriptor, private val path: String) : InputStream() {
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = os(path) { Os.read(fd, b, off, len) }
            return if (n == 0) -1 else n
        }

        override fun close() {
            if (closed) return
            closed = true
            os(path) { Os.close(fd) }
        }
    }

    private class FdOutputStream(private val fd: FileDescriptor, private val path: String) : OutputStream() {
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var done = 0
            while (done < len) done += os(path) { Os.write(fd, b, off + done, len - done) }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                // EINVAL: the file system has no fsync (some FUSE and pseudo file systems).
                try {
                    os(path) { Os.fsync(fd) }
                } catch (e: FsException) {
                    if (e.errno != Errno.EINVAL) throw e
                }
            } finally {
                os(path) { Os.close(fd) }
            }
        }
    }
}
