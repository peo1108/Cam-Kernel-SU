package cam.su.kernel.files

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * EXTRACT: sources are paths inside one archive. COMPRESS: destDir is the zip to write.
 * BACKUP: sources is one partition name, destDir the image file. FLASH: sources is one image,
 * destDir the partition name. TRASH: sources go to the trash. RESTORE / PURGE: sources are trash
 * folders (Trash), put back or removed for good.
 */
enum class OpKind { COPY, MOVE, DELETE, EXTRACT, COMPRESS, BACKUP, FLASH, TRASH, RESTORE, PURGE }

/**
 * One copy/move/delete. [preserve] also carries owner and SELinux label over (mode bits are
 * always kept, like cp).
 */
data class FileOp(
    val kind: OpKind,
    val sources: List<String>,
    val destDir: String? = null,
    val preserve: Boolean = true,
)

/** What to do when the destination name is taken. OVERWRITE on folder-over-folder merges. */
enum class ConflictChoice { OVERWRITE, SKIP, RENAME }

data class Resolution(val choice: ConflictChoice, val applyAll: Boolean = false)

interface OpListener {
    fun onProgress(done: Long, total: Long, current: String) {}

    /** Blocks the operation until the user answers. */
    fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean): Resolution
}

class OpCancelledException : IOException("cancelled")

data class OpResult(val items: Int, val skipped: Int)

/**
 * Runs a [FileOp] on [fs]. Never follows symlinks (links are copied, moved and deleted as
 * links); a file being copied is written as `.name.camtmp` and renamed when complete, so a
 * cancel or an error never leaves a half file under the real name. Progress is bytes for
 * copy/move and entries for delete.
 */
class FileOpEngine(
    private val fs: FsBackend,
    private val listener: OpListener,
    private val isCancelled: () -> Boolean = { false },
) {
    private class Target(val path: String, val existing: StatInfo?)

    private var total = 0L
    private var done = 0L
    private var items = 0
    private var skipped = 0
    private var preserve = true
    private val skippedPaths = HashSet<String>()
    private var fileChoice: ConflictChoice? = null
    private var dirChoice: ConflictChoice? = null
    private var lastEmit = 0L
    private var emitted = false

    fun run(op: FileOp): OpResult {
        preserve = op.preserve
        val sources = op.sources.map(FsPath::normalize).distinct()
        if (op.kind != OpKind.COPY) sources.forEach(::checkNotProtected)
        when (op.kind) {
            OpKind.EXTRACT, OpKind.COMPRESS, OpKind.BACKUP, OpKind.FLASH, OpKind.TRASH, OpKind.RESTORE, OpKind.PURGE ->
                throw FsException(Errno.EINVAL, detail = "archive and partition jobs run elsewhere")

            OpKind.DELETE -> {
                total = sources.sumOf(::countEntries)
                sources.forEach { deleteTree(it, counted = true) }
            }

            OpKind.COPY, OpKind.MOVE -> {
                val dest = FsPath.normalize(op.destDir ?: throw FsException(Errno.EINVAL, detail = "no destination"))
                if (!FileMode.isDir(fs.stat(dest).mode)) throw FsException(Errno.ENOTDIR, dest)
                val realDest = fs.realpath(dest)
                for (src in sources) {
                    if (FileMode.isDir(fs.lstat(src).mode) && FsPath.isSameOrInside(realDest, fs.realpath(src))) {
                        throw FsException(Errno.EINVAL, src, "cannot put a folder inside itself: $src")
                    }
                }
                total = sources.sumOf(::treeSize)
                for (src in sources) {
                    val dst = FsPath.child(dest, FsPath.name(src))
                    if (op.kind == OpKind.COPY) copyTop(src, dst) else moveTop(src, dst)
                }
            }
        }
        emit("", force = true)
        return OpResult(items, skipped)
    }

    // --- copy ---

    private fun copyTop(src: String, dst: String) {
        val st = fs.lstat(src)
        // Copy into its own folder: a new name, nothing to ask.
        val target = if (src == dst) Target(uniqueName(dst), null) else resolve(src, st, dst)
        if (target == null) skip(src, st) else copyNode(src, st, target)
    }

    private fun copyNode(src: String, st: StatInfo, target: Target) {
        checkCancel()
        val dst = target.path
        val existing = target.existing
        when {
            FileMode.isDir(st.mode) -> {
                val merge = existing != null && FileMode.isDir(existing.mode)
                if (!merge) {
                    if (existing != null) fs.remove(dst)
                    fs.mkdir(dst, 0x1C0) // 0700 until the real mode is set below
                }
                for (name in fs.list(src)) {
                    val child = FsPath.child(src, name)
                    val childSt = fs.lstat(child)
                    val childTarget = resolve(child, childSt, FsPath.child(dst, name))
                    if (childTarget == null) skip(child, childSt) else copyNode(child, childSt, childTarget)
                }
                if (!merge) copyMetadata(src, st, dst)
            }

            existing != null && FileMode.isDir(existing.mode) -> {
                deleteTree(dst, counted = false)
                copyNode(src, st, Target(dst, null))
                return
            }

            FileMode.isLink(st.mode) -> {
                if (existing != null) fs.remove(dst)
                try {
                    fs.symlink(fs.readlink(src), dst)
                } catch (e: FsException) {
                    // FUSE storage (/sdcard) has no symlinks: the link is skipped, not the whole job.
                    if (e.errno !in NO_LINKS) throw e
                    skip(src, st)
                    return
                }
                if (preserve) bestEffort { fs.lchown(dst, st.uid, st.gid) }
            }

            FileMode.isRegular(st.mode) -> copyFile(src, st, dst)

            else -> {
                skip(src, st) // device node, fifo, socket
                return
            }
        }
        items++
    }

    private fun copyFile(src: String, st: StatInfo, dst: String) {
        val tmp = tempFor(dst)
        lstatOrNull(tmp)?.let { if (FileMode.isRegular(it.mode)) fs.remove(tmp) }
        fs.createFile(tmp, 0x180) // 0600
        try {
            fs.openRead(src).use { input -> fs.openWrite(tmp).use { output -> pump(input, output, src) } }
            copyMetadata(src, st, tmp)
            fs.rename(tmp, dst)
        } catch (e: Throwable) {
            runCatching { fs.remove(tmp) }
            throw e
        }
    }

    private fun pump(input: InputStream, output: OutputStream, path: String) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            checkCancel()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            emit(path)
        }
    }

    /** Metadata is best effort: FUSE storage refuses chown and labels, the data still counts. */
    private fun copyMetadata(src: String, st: StatInfo, dst: String) {
        if (preserve) bestEffort { fs.lchown(dst, st.uid, st.gid) }
        bestEffort { fs.chmod(dst, st.mode and FileMode.PERM_MASK) }
        if (preserve) fs.getContext(src)?.let { bestEffort { fs.setContext(dst, it) } }
    }

    // --- move ---

    private fun moveTop(src: String, dst: String) {
        if (src == dst) return
        val st = fs.lstat(src)
        val target = resolve(src, st, dst)
        if (target == null) skip(src, st) else moveNode(src, st, target)
    }

    /** Returns false when something under [src] was skipped and so stays behind. */
    private fun moveNode(src: String, st: StatInfo, target: Target): Boolean {
        checkCancel()
        val dst = target.path
        val existing = target.existing
        if (existing != null && FileMode.isDir(st.mode) && FileMode.isDir(existing.mode)) {
            var clean = true
            for (name in fs.list(src)) {
                val child = FsPath.child(src, name)
                val childSt = fs.lstat(child)
                val childTarget = resolve(child, childSt, FsPath.child(dst, name))
                clean = if (childTarget == null) {
                    skip(child, childSt)
                    false
                } else {
                    moveNode(child, childSt, childTarget) && clean
                }
            }
            if (clean) fs.remove(src)
            items++
            return clean
        }
        if (existing != null) {
            if (FileMode.isDir(existing.mode)) deleteTree(dst, counted = false)
            else if (FileMode.isDir(st.mode)) fs.remove(dst)
        }
        val size = treeSize(src)
        try {
            fs.rename(src, dst)
            done += size
            items++
            emit(src)
            return true
        } catch (e: FsException) {
            if (e.errno != Errno.EXDEV) throw e
        }
        // Another file system: copy everything, and only then drop what was copied.
        copyNode(src, st, Target(dst, null))
        return deleteMoved(src)
    }

    /** Deletes [path] except what the copy skipped (and the folders holding it); false when something stayed. */
    private fun deleteMoved(path: String): Boolean {
        checkCancel()
        if (path in skippedPaths) return false
        val st = fs.lstat(path)
        var clean = true
        if (FileMode.isDir(st.mode)) {
            for (name in fs.list(path)) clean = deleteMoved(FsPath.child(path, name)) && clean
        }
        if (clean) fs.remove(path)
        return clean
    }

    // --- delete ---

    private fun deleteTree(path: String, counted: Boolean) {
        checkCancel()
        val st = fs.lstat(path)
        if (FileMode.isDir(st.mode)) {
            for (name in fs.list(path)) deleteTree(FsPath.child(path, name), counted)
        }
        fs.remove(path)
        if (counted) {
            done++
            items++
            emit(path)
        }
    }

    // --- helpers ---

    /** Null means skip. */
    private fun resolve(src: String, st: StatInfo, dst: String): Target? {
        val existing = lstatOrNull(dst) ?: return Target(dst, null)
        val srcDir = FileMode.isDir(st.mode)
        val dstDir = FileMode.isDir(existing.mode)
        val bothDirs = srcDir && dstDir
        val choice = (if (bothDirs) dirChoice else fileChoice) ?: run {
            val answer = listener.onConflict(src, dst, srcDir, dstDir)
            checkCancel()
            if (answer.applyAll) {
                if (bothDirs) dirChoice = answer.choice else fileChoice = answer.choice
            }
            answer.choice
        }
        return when (choice) {
            ConflictChoice.SKIP -> null
            ConflictChoice.RENAME -> Target(uniqueName(dst), null)
            ConflictChoice.OVERWRITE -> Target(dst, existing)
        }
    }

    private fun skip(path: String, st: StatInfo) {
        skipped++
        skippedPaths += path
        done += if (FileMode.isDir(st.mode)) treeSize(path) else if (FileMode.isRegular(st.mode)) st.size else 0
        emit(path)
    }

    /** "a.txt" → "a (1).txt", "dir" → "dir (1)", first free one. */
    fun uniqueName(path: String): String {
        val dir = FsPath.parent(path)
        val name = FsPath.name(path)
        val dot = name.lastIndexOf('.')
        val isDir = lstatOrNull(path)?.let { FileMode.isDir(it.mode) } ?: false
        val (base, ext) = if (dot > 0 && !isDir) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 1
        while (true) {
            val candidate = FsPath.child(dir, "$base ($n)$ext")
            if (lstatOrNull(candidate) == null) return candidate
            n++
        }
    }

    private fun treeSize(path: String): Long {
        val st = fs.lstat(path)
        return when {
            FileMode.isDir(st.mode) -> fs.list(path).sumOf { treeSize(FsPath.child(path, it)) }
            FileMode.isRegular(st.mode) -> st.size
            else -> 0
        }
    }

    private fun countEntries(path: String): Long {
        val st = fs.lstat(path)
        if (!FileMode.isDir(st.mode)) return 1
        return 1 + fs.list(path).sumOf { countEntries(FsPath.child(path, it)) }
    }

    /** "/" and the folders right under it (/data, /system, /sdcard...) are never moved or deleted. */
    private fun checkNotProtected(path: String) {
        if (path == "/" || FsPath.parent(path) == "/") throw FsException(Errno.EPERM, path, "protected: $path")
    }

    private fun checkCancel() {
        if (isCancelled()) throw OpCancelledException()
    }

    private fun emit(current: String, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && emitted && now - lastEmit < EMIT_INTERVAL_NS) return
        emitted = true
        lastEmit = now
        listener.onProgress(done, total, current)
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (_: FsException) {
        }
    }

    private fun lstatOrNull(path: String): StatInfo? = try {
        fs.lstat(path)
    } catch (e: FsException) {
        if (e.errno == Errno.ENOENT) null else throw e
    }

    private fun tempFor(path: String) = FsPath.child(FsPath.parent(path), ".${FsPath.name(path)}${RootFs.TMP_SUFFIX}")

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val EMIT_INTERVAL_NS = 100_000_000L
        val NO_LINKS = setOf(Errno.ENOSYS, Errno.EPERM, Errno.EOPNOTSUPP)
    }
}
