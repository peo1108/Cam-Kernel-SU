package cam.su.kernel.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** In-memory [FsBackend] with POSIX symlink semantics, for JVM tests. */
class FakeFs : FsBackend {
    sealed class Node(var perm: Int) {
        var uid = 0
        var gid = 0
        var context: String? = "u:object_r:system_file:s0"
    }

    class Dir(perm: Int = 0x1ED) : Node(perm) {
        val children = linkedMapOf<String, Node>()
    }

    class Regular(var data: ByteArray, perm: Int = 0x1A4) : Node(perm)

    class Link(val target: String) : Node(0x1FF)

    val root = Dir()

    /** Context the "kernel" gives new files, like a type transition would. */
    var newFileContext: String? = "u:object_r:system_file:s0"

    /** Paths that are other file systems: rename(2) across them fails with EXDEV. */
    var mounts = listOf<String>()

    /** File systems without symlinks (FUSE /sdcard): symlink(2) fails with ENOSYS. */
    var noLinks = listOf<String>()

    /** Reading these fails halfway through. */
    var failRead = setOf<String>()

    private fun mountOf(path: String) = mounts.filter { FsPath.isSameOrInside(path, it) }.maxByOrNull { it.length } ?: "/"

    // --- test setup helpers ---

    fun dir(path: String): Dir = put(path, Dir())

    fun file(path: String, text: String = "", perm: Int = 0x1A4): Regular = put(path, Regular(text.toByteArray(), perm))

    fun link(path: String, target: String): Link = put(path, Link(target))

    fun node(path: String, follow: Boolean = false): Node = resolve(path, follow).second

    fun text(path: String) = String((node(path, follow = true) as Regular).data)

    fun names(path: String) = (node(path, follow = true) as Dir).children.keys.toList()

    private fun <T : Node> put(path: String, node: T): T {
        parentDir(path).children[FsPath.name(path)] = node
        return node
    }

    // --- FsBackend ---

    override fun lstat(path: String) = info(resolve(path, follow = false).second)

    override fun stat(path: String) = info(resolve(path, follow = true).second)

    override fun list(path: String): List<String> {
        val node = resolve(path, follow = true).second
        return (node as? Dir ?: throw FsException(Errno.ENOTDIR, path)).children.keys.toList()
    }

    override fun readlink(path: String): String =
        (resolve(path, follow = false).second as? Link ?: throw FsException(Errno.EINVAL, path)).target

    override fun realpath(path: String) = resolve(path, follow = true).first

    override fun getContext(path: String): String? = try {
        resolve(path, follow = true).second.context
    } catch (_: FsException) {
        null
    }

    override fun setContext(path: String, context: String) {
        resolve(path, follow = true).second.context = context
    }

    override fun mkdir(path: String, mode: Int) {
        create(path, Dir(mode))
    }

    override fun createFile(path: String, mode: Int) {
        create(path, Regular(ByteArray(0), mode))
    }

    override fun symlink(target: String, link: String) {
        if (noLinks.any { FsPath.isSameOrInside(link, it) }) throw FsException(Errno.ENOSYS, link)
        create(link, Link(target))
    }

    override fun rename(from: String, to: String) {
        if (mountOf(from) != mountOf(to)) throw FsException(Errno.EXDEV, from)
        val node = resolve(from, follow = false).second
        val dst = parentDir(to)
        val existing = dst.children[FsPath.name(to)]
        if (existing is Dir && existing.children.isNotEmpty()) throw FsException(Errno.ENOTEMPTY, to)
        parentDir(from).children.remove(FsPath.name(from))
        dst.children[FsPath.name(to)] = node
    }

    override fun chmod(path: String, mode: Int) {
        resolve(path, follow = true).second.perm = mode and FileMode.PERM_MASK
    }

    override fun lchown(path: String, uid: Int, gid: Int) {
        resolve(path, follow = false).second.apply {
            this.uid = uid
            this.gid = gid
        }
    }

    override fun remove(path: String) {
        val node = resolve(path, follow = false).second
        if (node is Dir && node.children.isNotEmpty()) throw FsException(Errno.ENOTEMPTY, path)
        parentDir(path).children.remove(FsPath.name(path))
    }

    override fun openRead(path: String): InputStream {
        val node = resolve(path, follow = true).second
        val data = (node as? Regular ?: throw FsException(Errno.EISDIR, path)).data
        if (path !in failRead) return ByteArrayInputStream(data)
        return object : InputStream() {
            var sent = false
            override fun read(): Int = throw FsException(Errno.EIO, path)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) throw FsException(Errno.EIO, path)
                sent = true
                return minOf(len, data.size / 2).also { System.arraycopy(data, 0, b, off, it) }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val node = resolve(path, follow = true).second as? Regular ?: throw FsException(Errno.EISDIR, path)
        node.data = ByteArray(0)
        return object : ByteArrayOutputStream() {
            override fun close() {
                node.data = toByteArray()
            }
        }
    }

    // --- internals ---

    private fun create(path: String, node: Node) {
        val dir = parentDir(path)
        val name = FsPath.name(path)
        if (name in dir.children) throw FsException(Errno.EEXIST, path)
        if (node !is Link) node.context = newFileContext
        dir.children[name] = node
    }

    private fun parentDir(path: String): Dir =
        resolve(FsPath.parent(path), follow = true).second as? Dir ?: throw FsException(Errno.ENOTDIR, path)

    private fun info(node: Node): StatInfo {
        val type = when (node) {
            is Dir -> FileMode.S_IFDIR
            is Regular -> FileMode.S_IFREG
            is Link -> FileMode.S_IFLNK
        }
        val size = when (node) {
            is Regular -> node.data.size.toLong()
            is Link -> node.target.length.toLong()
            is Dir -> 4096L
        }
        return StatInfo(type or node.perm, node.uid, node.gid, size, mtime = 0)
    }

    /** Returns the real path and node; symlinks in the middle are always followed. */
    private fun resolve(path: String, follow: Boolean, hops: Int = 0): Pair<String, Node> {
        if (hops > 40) throw FsException(Errno.ELOOP, path)
        val parts = path.split('/').filter { it.isNotEmpty() }
        var cur: Node = root
        var curPath = "/"
        for ((i, part) in parts.withIndex()) {
            val dir = cur as? Dir ?: throw FsException(Errno.ENOTDIR, path)
            val next = dir.children[part] ?: throw FsException(Errno.ENOENT, path)
            val last = i == parts.lastIndex
            if (next is Link && (!last || follow)) {
                val target = if (next.target.startsWith("/")) next.target else FsPath.child(curPath, next.target)
                val rest = parts.drop(i + 1).joinToString("/")
                return resolve(FsPath.normalize(if (rest.isEmpty()) target else "$target/$rest"), follow, hops + 1)
            }
            cur = next
            curPath = FsPath.child(curPath, part)
        }
        return curPath to cur
    }
}
