package cam.su.kernel.files

import java.io.InputStream

/**
 * File operations of the Files tab, on top of [FsBackend]. Runs in [CamFileService] (uid 0);
 * kept free of Android calls so the JVM tests drive it with a fake backend.
 *
 * Rules: walks never follow symlinks; saving a file goes through a temp file in the same
 * directory and keeps the old file's mode, owner and SELinux label.
 */
class RootFs(private val fs: FsBackend) {

    fun listDir(path: String): List<FileEntry> {
        val dir = FsPath.normalize(path)
        return fs.list(dir).mapNotNull { name ->
            val child = FsPath.child(dir, name)
            try {
                entry(child)
            } catch (e: FsException) {
                // Gone between list and lstat: skip it. Anything else still shows up.
                if (e.errno == Errno.ENOENT) null else unreadable(child)
            }
        }
    }

    /** Null when nothing is at [path] (a broken symlink counts as something). */
    fun stat(path: String): FileEntry? = try {
        entry(FsPath.normalize(path))
    } catch (e: FsException) {
        if (e.errno == Errno.ENOENT) null else throw e
    }

    fun mkdir(path: String) = fs.mkdir(newPath(path), 0x1ED) // 0755

    fun createFile(path: String) = fs.createFile(newPath(path), 0x1A4) // 0644

    fun symlink(target: String, link: String) {
        if (target.isEmpty() || '\u0000' in target) throw FsException(Errno.EINVAL, link)
        fs.symlink(target, newPath(link))
    }

    /** Never replaces anything: rename(2) would silently overwrite [to]. */
    fun rename(from: String, to: String) {
        val src = FsPath.normalize(from)
        val dst = newPath(to)
        if (src == "/" || FsPath.isSameOrInside(dst, src)) throw FsException(Errno.EINVAL, dst)
        if (exists(dst)) throw FsException(Errno.EEXIST, dst)
        fs.rename(src, dst)
    }

    /** Symlinks have no mode of their own; chmod(2) would change the target, so they are skipped. */
    fun chmod(path: String, mode: Int, recursive: Boolean) {
        val bits = mode and FileMode.PERM_MASK
        walk(FsPath.normalize(path), recursive) { p, st ->
            if (!FileMode.isLink(st.mode)) fs.chmod(p, bits)
        }
    }

    fun chown(path: String, uid: Int, gid: Int, recursive: Boolean) {
        walk(FsPath.normalize(path), recursive) { p, _ -> fs.lchown(p, uid, gid) }
    }

    /** setxattr(2) follows symlinks, so they are skipped like in [chmod]. */
    fun setContext(path: String, context: String, recursive: Boolean) {
        if (context.isBlank()) throw FsException(Errno.EINVAL, path)
        walk(FsPath.normalize(path), recursive) { p, st ->
            if (!FileMode.isLink(st.mode)) fs.setContext(p, context)
        }
    }

    /**
     * The media provider opens shared storage on its disk ([SharedStorage]) through the group
     * bits. A folder root made there directly (umask 077, then chowned) has none, so nobody can
     * open it through /storage, root included. Gives the disk copy of [path] and everything under
     * it the owner, group, mode and SELinux label of the folder it sits in; files get only that
     * mode's read and write bits. Symlinks are left alone.
     */
    fun repairShared(path: String) {
        val lower = SharedStorage.lower(path) ?: throw FsException(Errno.EINVAL, path, "not in shared storage: $path")
        val parent = FsPath.parent(lower)
        val ref = fs.lstat(parent)
        val context = fs.getContext(parent)
        val dirMode = ref.mode and FileMode.PERM_MASK
        val fileMode = dirMode and 0x1B6 // rw-rw-rw- at most
        walk(lower, recursive = true) { p, st ->
            if (FileMode.isLink(st.mode)) return@walk
            if (st.uid != ref.uid || st.gid != ref.gid) fs.lchown(p, ref.uid, ref.gid)
            val mode = if (FileMode.isDir(st.mode)) dirMode else fileMode
            if (st.mode and FileMode.PERM_MASK != mode) fs.chmod(p, mode)
            if (context != null && context != fs.getContext(p)) fs.setContext(p, context)
        }
    }

    fun statFs(path: String): LongArray = fs.statFs(FsPath.normalize(path))

    /**
     * [bytes, files, folders] under [path], not crossing symlinks; the folder itself is not
     * counted. Entries that cannot be read are skipped, like `du` does, and so are /proc, /sys
     * and /dev below [path] (their sizes are not disk use: /proc/kcore claims terabytes).
     * Stops early, with what it counted so far, when [cancelled] says so.
     */
    fun measure(path: String, cancelled: () -> Boolean = { false }): LongArray {
        val root = FsPath.normalize(path)
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        val pending = ArrayDeque<String>()
        val rootSt = fs.lstat(root)
        if (!FileMode.isDir(rootSt.mode)) return longArrayOf(rootSt.size, 1, 0)
        pending.addLast(root)
        while (pending.isNotEmpty() && !cancelled()) {
            val dir = pending.removeLast()
            val names = try {
                fs.list(dir)
            } catch (_: FsException) {
                continue
            }
            for (name in names) {
                val child = FsPath.child(dir, name)
                val st = try {
                    fs.lstat(child)
                } catch (_: FsException) {
                    continue
                }
                if (FileMode.isDir(st.mode)) {
                    dirs++
                    if (child !in PSEUDO) pending.addLast(child)
                } else {
                    files++
                    if (FileMode.isRegular(st.mode)) bytes += st.size
                }
            }
        }
        return longArrayOf(bytes, files, dirs)
    }

    /** MD5, SHA-1 and SHA-256 in one pass, lowercase hex. */
    fun checksums(path: String, cancelled: () -> Boolean = { false }): List<String> {
        val digests = listOf("MD5", "SHA-1", "SHA-256").map { java.security.MessageDigest.getInstance(it) }
        openRead(path).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (cancelled()) throw OpCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                digests.forEach { it.update(buffer, 0, n) }
            }
        }
        return digests.map { d -> d.digest().joinToString("") { "%02x".format(it) } }
    }

    /** Up to [length] bytes from [offset], for the hex viewer. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray {
        openRead(path).use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) return ByteArray(0)
                skipped += n
            }
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(minOf(length, 64 * 1024))
            while (out.size() < length) {
                val n = input.read(buffer, 0, minOf(buffer.size, length - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    /**
     * Files under [root] whose name matches [pattern] (`*` and `?` wildcards, case-insensitive,
     * a plain word matches anywhere) and, when [content] is set, that contain it. Symlinks are
     * listed but not followed. Stops at [limit] results or when [cancelled] says so.
     */
    fun search(root: String, pattern: String, content: String?, limit: Int, cancelled: () -> Boolean = { false }): List<FileEntry> {
        val nameRegex = SearchPattern.toRegex(pattern)
        val needle = content?.takeIf { it.isNotEmpty() }?.toByteArray()
        val found = mutableListOf<FileEntry>()
        val pending = ArrayDeque(listOf(FsPath.normalize(root)))
        while (pending.isNotEmpty() && found.size < limit && !cancelled()) {
            val dir = pending.removeLast()
            val names = try {
                fs.list(dir)
            } catch (_: FsException) {
                continue
            }
            for (name in names) {
                if (found.size >= limit || cancelled()) break
                val path = FsPath.child(dir, name)
                val st = try {
                    fs.lstat(path)
                } catch (_: FsException) {
                    continue
                }
                // pseudo file systems are endless or slow to read; skip their content
                if (FileMode.isDir(st.mode) && path !in PSEUDO) pending.addLast(path)
                if (!nameRegex.containsMatchIn(name)) continue
                if (needle != null && (!FileMode.isRegular(st.mode) || !contains(path, needle))) continue
                runCatching { entry(path) }.getOrNull()?.let { found += it }
            }
        }
        return found
    }

    private fun contains(path: String, needle: ByteArray): Boolean = try {
        fs.openRead(path).use { scan(it, needle) }
    } catch (_: FsException) {
        false
    }

    private fun scan(input: InputStream, needle: ByteArray): Boolean {
        val buffer = ByteArray(256 * 1024 + needle.size)
        var keep = 0
        while (true) {
            val n = input.read(buffer, keep, buffer.size - keep)
            if (n < 0) return false
            val end = keep + n
            if (indexOf(buffer, end, needle) >= 0) return true
            // carry the tail over so a match across two reads is not missed
            keep = minOf(needle.size - 1, end)
            System.arraycopy(buffer, end - keep, buffer, 0, keep)
        }
    }

    private fun indexOf(data: ByteArray, end: Int, needle: ByteArray): Int {
        outer@ for (i in 0..end - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** A regular file, or a link to one; devices, fifos and sockets may never end or never start. */
    fun openRead(path: String): InputStream {
        val p = FsPath.normalize(path)
        val st = fs.stat(p)
        if (FileMode.isDir(st.mode)) throw FsException(Errno.EISDIR, p)
        if (!FileMode.isRegular(st.mode)) throw FsException(Errno.EINVAL, p, "not a regular file: $p")
        return fs.openRead(p)
    }

    /**
     * Replaces the file at [path] with [input]. A symlink is written through to its target.
     * [beforeCommit] runs after all data is on disk and before the rename; if it throws,
     * the old file is left untouched.
     */
    fun writeAtomic(path: String, input: InputStream, beforeCommit: () -> Unit = {}) =
        writeAtomicWith(path, beforeCommit) { input.copyTo(it) }

    /** [writeAtomic] for content produced by [body] (an archive being rewritten, say). */
    fun writeAtomicWith(path: String, beforeCommit: () -> Unit = {}, body: (java.io.OutputStream) -> Unit) {
        var target = FsPath.normalize(path)
        var old = lstatOrNull(target)
        if (old != null && FileMode.isLink(old.mode)) {
            target = fs.realpath(target)
            old = lstatOrNull(target)
        }
        if (old != null && !FileMode.isRegular(old.mode)) {
            throw FsException(if (FileMode.isDir(old.mode)) Errno.EISDIR else Errno.EINVAL, target)
        }
        val tmp = FsPath.child(FsPath.parent(target), ".${FsPath.name(target)}$TMP_SUFFIX")
        lstatOrNull(tmp)?.let { if (FileMode.isRegular(it.mode)) fs.remove(tmp) }
        fs.createFile(tmp, (old?.mode ?: DEFAULT_FILE_MODE) and FileMode.PERM_MASK)
        try {
            fs.openWrite(tmp).use(body)
            beforeCommit()
            copyMetadata(old, target, tmp)
            fs.rename(tmp, target)
        } catch (e: Throwable) {
            runCatching { fs.remove(tmp) }
            throw e
        }
    }

    /**
     * Owner first (chown clears setuid bits), then mode, then label. Each is only set when it
     * differs, so files on storage that refuses chown (FUSE /sdcard) still save.
     */
    private fun copyMetadata(old: StatInfo?, target: String, tmp: String) {
        val now = fs.lstat(tmp)
        val mode = (old?.mode ?: DEFAULT_FILE_MODE) and FileMode.PERM_MASK
        if (old != null && (now.uid != old.uid || now.gid != old.gid)) fs.lchown(tmp, old.uid, old.gid)
        if (fs.lstat(tmp).mode and FileMode.PERM_MASK != mode) fs.chmod(tmp, mode)
        if (old != null) {
            val context = fs.getContext(target)
            if (context != null && context != fs.getContext(tmp)) fs.setContext(tmp, context)
        }
    }

    private fun entry(path: String): FileEntry {
        val st = fs.lstat(path)
        val type = FileMode.typeOf(st.mode)
        var linkTarget: String? = null
        var broken = false
        var targetIsDir = false
        var targetSize = 0L
        if (type == FileEntry.TYPE_LINK) {
            linkTarget = runCatching { fs.readlink(path) }.getOrNull()
            val target = try {
                fs.stat(path)
            } catch (_: FsException) {
                null
            }
            broken = target == null
            targetIsDir = target != null && FileMode.isDir(target.mode)
            if (target != null && FileMode.isRegular(target.mode)) targetSize = target.size
        }
        return FileEntry(
            name = FsPath.name(path).ifEmpty { "/" },
            path = path,
            type = type,
            size = st.size,
            mtime = st.mtime,
            mode = st.mode,
            uid = st.uid,
            gid = st.gid,
            owner = AndroidIds.name(st.uid),
            group = AndroidIds.name(st.gid),
            context = if (type == FileEntry.TYPE_LINK) null else fs.getContext(path),
            linkTarget = linkTarget,
            linkBroken = broken,
            targetIsDir = targetIsDir,
            targetSize = targetSize,
        )
    }

    private fun unreadable(path: String) = FileEntry(
        name = FsPath.name(path), path = path, type = FileEntry.TYPE_OTHER, size = 0, mtime = 0,
        mode = 0, uid = -1, gid = -1, owner = "?", group = "?", context = null,
    )

    /** Visits [root] and, when [recursive], everything under it without crossing symlinks. */
    private fun walk(root: String, recursive: Boolean, action: (String, StatInfo) -> Unit) {
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val path = pending.removeLast()
            val st = fs.lstat(path)
            action(path, st)
            if (recursive && FileMode.isDir(st.mode)) {
                fs.list(path).forEach { pending.addLast(FsPath.child(path, it)) }
            }
        }
    }

    private fun newPath(path: String): String {
        val p = FsPath.normalize(path)
        if (p == "/" || !FsPath.isValidName(FsPath.name(p))) throw FsException(Errno.EINVAL, p)
        return p
    }

    private fun exists(path: String) = lstatOrNull(path) != null

    private fun lstatOrNull(path: String): StatInfo? = try {
        fs.lstat(path)
    } catch (e: FsException) {
        if (e.errno == Errno.ENOENT) null else throw e
    }

    companion object {
        const val TMP_SUFFIX = ".camtmp"
        private const val DEFAULT_FILE_MODE = 0x1A4 // 0644

        /** Kernel views, not disk: walks below another folder do not go in. */
        private val PSEUDO = setOf("/proc", "/sys", "/dev")
    }
}
