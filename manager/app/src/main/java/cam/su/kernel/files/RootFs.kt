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

    fun statFs(path: String): LongArray = fs.statFs(FsPath.normalize(path))

    /**
     * [bytes, files, folders] under [path], not crossing symlinks; the folder itself is not
     * counted. Entries that cannot be read are skipped, like `du` does.
     */
    fun measure(path: String): LongArray {
        val root = FsPath.normalize(path)
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        val pending = ArrayDeque<String>()
        val rootSt = fs.lstat(root)
        if (!FileMode.isDir(rootSt.mode)) return longArrayOf(rootSt.size, 1, 0)
        pending.addLast(root)
        while (pending.isNotEmpty()) {
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
                    pending.addLast(child)
                } else {
                    files++
                    if (FileMode.isRegular(st.mode)) bytes += st.size
                }
            }
        }
        return longArrayOf(bytes, files, dirs)
    }

    fun openRead(path: String): InputStream {
        val p = FsPath.normalize(path)
        val st = fs.stat(p)
        if (FileMode.isDir(st.mode)) throw FsException(Errno.EISDIR, p)
        return fs.openRead(p)
    }

    /**
     * Replaces the file at [path] with [input]. A symlink is written through to its target.
     * [beforeCommit] runs after all data is on disk and before the rename; if it throws,
     * the old file is left untouched.
     */
    fun writeAtomic(path: String, input: InputStream, beforeCommit: () -> Unit = {}) {
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
            fs.openWrite(tmp).use { input.copyTo(it) }
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
        if (type == FileEntry.TYPE_LINK) {
            linkTarget = runCatching { fs.readlink(path) }.getOrNull()
            val target = try {
                fs.stat(path)
            } catch (_: FsException) {
                null
            }
            broken = target == null
            targetIsDir = target != null && FileMode.isDir(target.mode)
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
    }
}
