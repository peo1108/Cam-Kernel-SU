package cam.su.kernel.files

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest

/** Reads and writes block devices for [Partitions], in the root process. */
class PartitionOps(
    private val listener: OpListener,
    private val isCancelled: () -> Boolean = { false },
) {
    private var lastEmit = 0L

    /** Copies partition [name] into the file [dest] (written atomically, like any save). */
    fun backup(name: String, dest: String): OpResult {
        val device = device(name)
        val size = size(device)
        val target = FsPath.normalize(dest)
        RootFs(OsBackend).writeAtomicWith(target, beforeCommit = { checkCancel() }) { out ->
            val fd = open(device, OsConstants.O_RDONLY)
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                var done = 0L
                while (done < size) {
                    checkCancel()
                    val n = read(fd, device, buffer, minOf(buffer.size.toLong(), size - done).toInt())
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    done += n
                    emit(done, size, name)
                }
                if (done != size) throw FsException(Errno.EIO, device, "read $done of $size bytes")
            } finally {
                Os.close(fd)
            }
        }
        listener.onProgress(size, size, name)
        return OpResult(1, 0)
    }

    /**
     * Writes [image] over partition [name], then reads it back and compares SHA-256. Cancel only
     * counts before the first byte is written: a half written boot partition does not boot.
     */
    fun flash(image: String, name: String): OpResult {
        if (!Partitions.canFlash(name)) throw FsException(Errno.EPERM, name, "only the boot partitions can be flashed: $name")
        val source = FsPath.normalize(image)
        val st = OsBackend.stat(source)
        if (!FileMode.isRegular(st.mode)) throw FsException(Errno.EINVAL, source, "not a regular file: $source")
        val device = device(name)
        val capacity = size(device)
        if (st.size <= 0) throw FsException(Errno.EINVAL, source, "empty image")
        if (st.size > capacity) throw FsException(Errno.EFBIG, source, "image is ${st.size} bytes, $name holds $capacity")
        checkCancel()

        val written = MessageDigest.getInstance("SHA-256")
        val fd = open(device, OsConstants.O_WRONLY)
        try {
            OsBackend.openRead(source).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    var off = 0
                    while (off < n) off += os(device) { Os.write(fd, buffer, off, n - off) }
                    written.update(buffer, 0, n)
                    done += n
                    // writing is the first 90%, checking it back the rest
                    emit(done * 9 / 10, st.size, name)
                }
                if (done != st.size) throw FsException(Errno.EIO, source, "read $done of ${st.size} bytes")
            }
            os(device) { Os.fsync(fd) }
        } finally {
            Os.close(fd)
        }

        val readBack = MessageDigest.getInstance("SHA-256")
        val back = open(device, OsConstants.O_RDONLY)
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            var done = 0L
            while (done < st.size) {
                val n = read(back, device, buffer, minOf(buffer.size.toLong(), st.size - done).toInt())
                if (n <= 0) break
                readBack.update(buffer, 0, n)
                done += n
                emit(st.size * 9 / 10 + done / 10, st.size, name)
            }
        } finally {
            Os.close(back)
        }
        if (!written.digest().contentEquals(readBack.digest())) {
            throw FsException(Errno.EIO, device, "verify failed: $name does not hold the image now, flash it again")
        }
        listener.onProgress(st.size, st.size, name)
        return OpResult(1, 0)
    }

    private fun emit(done: Long, total: Long, current: String) {
        val now = System.nanoTime()
        if (now - lastEmit < 100_000_000L) return
        lastEmit = now
        listener.onProgress(done, total, current)
    }

    private fun checkCancel() {
        if (isCancelled()) throw OpCancelledException()
    }

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024

        /** Every partition with its size; empty when the phone has no by-name folder. */
        fun list(): List<Partition> {
            val dir = Partitions.DIRS.firstOrNull { File(it).isDirectory } ?: return emptyList()
            return File(dir).list().orEmpty().sorted().mapNotNull { name ->
                runCatching { Partition(name, size("$dir/$name")) }.getOrNull()
            }
        }

        /** The phone's slot suffix ("_a", "_b"), "" without A/B. */
        fun slot(): String = runCatching {
            ProcessBuilder("getprop", "ro.boot.slot_suffix").start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

        private fun device(name: String): String {
            if ('/' in name || name.isEmpty() || name.startsWith(".")) throw FsException(Errno.EINVAL, name)
            val dir = Partitions.DIRS.firstOrNull { File("$it/$name").exists() } ?: throw FsException(Errno.ENOENT, name, "no partition $name")
            return "$dir/$name"
        }

        private fun size(device: String): Long {
            val fd = open(device, OsConstants.O_RDONLY)
            try {
                return os(device) { Os.lseek(fd, 0, OsConstants.SEEK_END) }
            } finally {
                Os.close(fd)
            }
        }

        private fun open(device: String, flags: Int): FileDescriptor = os(device) { Os.open(device, flags or OsConstants.O_CLOEXEC, 0) }

        private fun read(fd: FileDescriptor, device: String, buffer: ByteArray, length: Int): Int =
            os(device) { Os.read(fd, buffer, 0, length) }

        private inline fun <T> os(path: String, block: () -> T): T = try {
            block()
        } catch (e: ErrnoException) {
            throw FsException(e.errno, path, "${e.message}: $path")
        }
    }
}
