package cam.su.kernel.files

/** One thing in the trash: [id] is its folder in [root], [original] where it came from. */
data class TrashItem(val root: String, val id: String, val original: String, val deletedAt: Long, val size: Long, val isDir: Boolean) {
    val name get() = FsPath.name(original)
    val dir get() = FsPath.child(root, id)
    val path get() = FsPath.child(dir, name)
}

/**
 * The Files tab's trash: Delete moves things here instead of unlinking them, and they can be put
 * back for [KEEP_DAYS] days. Each deletion is a folder `<time>-<n>` holding the item under its
 * own name and a `.cam_trash` file with the original path. Shared storage has a trash of its own
 * (a rename there, not a copy of a 4 GB video into /data); everything else goes to Cam's folder.
 */
class Trash(private val fs: FsBackend, private val now: () -> Long = System::currentTimeMillis) {

    /** Where [path] goes when deleted: a trash on the same file system, so it is a rename. */
    fun rootFor(path: String): String = if (SHARED_PREFIXES.any { path.startsWith(it) }) SHARED_ROOT else DATA_ROOT

    /** Inside a trash already: deleting there is for good. */
    fun isInTrash(path: String) = ROOTS.any { FsPath.isSameOrInside(canonical(FsPath.normalize(path)), it) }

    /** /sdcard/x and /storage/self/primary/x are /storage/emulated/0/x. */
    private fun canonical(path: String): String {
        for (alias in listOf("/sdcard", "/storage/self/primary")) {
            if (path == alias || path.startsWith("$alias/")) return "/storage/emulated/0" + path.removePrefix(alias)
        }
        return path
    }

    /** Moves [sources] into the trash; returns how many went. */
    fun put(sources: List<String>, listener: OpListener, cancelled: () -> Boolean): OpResult {
        var items = 0
        var skipped = 0
        for ((n, raw) in sources.withIndex()) {
            if (cancelled()) throw OpCancelledException()
            val src = FsPath.normalize(raw)
            val root = rootFor(src)
            mkdirs(root)
            val dir = FsPath.child(root, "${now()}-$n")
            fs.mkdir(dir, 0x1C0)
            fs.createFile(FsPath.child(dir, INFO), 0x180)
            fs.openWrite(FsPath.child(dir, INFO)).use { it.write(src.toByteArray()) }
            // a rename on the same file system; the engine copies when it is another one
            val result = FileOpEngine(fs, listener, cancelled).run(FileOp(OpKind.MOVE, listOf(src), dir))
            items += result.items
            skipped += result.skipped
        }
        return OpResult(items, skipped)
    }

    /** Everything in both trashes, newest first; what is older than [KEEP_DAYS] is removed first. */
    fun list(): List<TrashItem> {
        val out = mutableListOf<TrashItem>()
        for (root in ROOTS) {
            val names = try {
                fs.list(root)
            } catch (_: FsException) {
                continue
            }
            for (id in names) {
                val dir = FsPath.child(root, id)
                val original = try {
                    fs.openRead(FsPath.child(dir, INFO)).use { it.readBytes().decodeToString() }
                } catch (_: FsException) {
                    continue
                }
                val deletedAt = id.substringBefore('-').toLongOrNull() ?: continue
                if (now() - deletedAt > KEEP_DAYS * DAY_MS) {
                    runCatching { FileOpEngine(fs, quiet).run(FileOp(OpKind.DELETE, listOf(dir))) }
                    continue
                }
                val item = FsPath.child(dir, FsPath.name(original))
                val st = try {
                    fs.lstat(item)
                } catch (_: FsException) {
                    continue
                }
                out += TrashItem(root, id, original, deletedAt, RootFs(fs).measure(item)[0], FileMode.isDir(st.mode))
            }
        }
        return out.sortedByDescending { it.deletedAt }
    }

    /** Puts the item kept in [dir] (a trash folder) back where it was, making its folder again if gone. */
    fun restore(dir: String, listener: OpListener, cancelled: () -> Boolean): OpResult {
        val d = FsPath.normalize(dir)
        if (!isInTrash(d)) throw FsException(Errno.EINVAL, d, "not in the trash: $d")
        val original = fs.openRead(FsPath.child(d, INFO)).use { it.readBytes().decodeToString() }
        val item = FsPath.child(d, FsPath.name(original))
        val parent = FsPath.parent(original)
        mkdirs(parent)
        val result = FileOpEngine(fs, listener, cancelled).run(FileOp(OpKind.MOVE, listOf(item), parent))
        // the folder goes once nothing of it is left (a skipped conflict stays restorable)
        if (result.skipped == 0) runCatching { FileOpEngine(fs, quiet).run(FileOp(OpKind.DELETE, listOf(d))) }
        return result
    }

    /** Trash folders gone for good. */
    fun purge(dirs: List<String>, listener: OpListener, cancelled: () -> Boolean): OpResult {
        val list = dirs.map(FsPath::normalize)
        list.forEach { if (!isInTrash(it) || ROOTS.contains(it)) throw FsException(Errno.EINVAL, it, "not a trash item: $it") }
        return FileOpEngine(fs, listener, cancelled).run(FileOp(OpKind.DELETE, list))
    }

    private fun mkdirs(path: String) {
        if (path == "/") return
        try {
            fs.lstat(path)
            return
        } catch (e: FsException) {
            if (e.errno != Errno.ENOENT) throw e
        }
        mkdirs(FsPath.parent(path))
        fs.mkdir(path, 0x1C0)
    }

    companion object {
        /** Shared storage, under every name the panels may show it by. */
        val SHARED_PREFIXES = listOf("/storage/emulated/0/", "/sdcard/", "/storage/self/primary/")
        const val SHARED_ROOT = "/storage/emulated/0/.CamTrash"
        const val DATA_ROOT = "/data/adb/cam/trash"
        val ROOTS = listOf(DATA_ROOT, SHARED_ROOT)
        const val INFO = ".cam_trash"
        const val KEEP_DAYS = 7L
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private val quiet = object : OpListener {
            override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean) = Resolution(ConflictChoice.OVERWRITE)
        }
    }
}
