package cam.su.kernel.files

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Extract from and compress to archives, writing through [fs] like [FileOpEngine]: files land as
 * `.name.camtmp` and are renamed when complete, conflicts are asked the same way.
 */
class ArchiveOps(
    private val fs: FsBackend,
    private val listener: OpListener,
    private val isCancelled: () -> Boolean = { false },
) {
    private var done = 0L
    private var total = 0L
    private var items = 0
    private var skipped = 0
    private var choice: ConflictChoice? = null
    private var lastEmit = 0L
    private var emitted = false

    /**
     * Extracts [inners] (files or folders inside the archive) into [destDir], each keeping its
     * own name: `a/b/c` lands as `destDir/c`, a folder `a/b` as `destDir/b/...`.
     */
    fun extract(reader: ArchiveReader, inners: List<String>, destDir: String): OpResult {
        val dest = FsPath.normalize(destDir)
        if (!FileMode.isDir(fs.stat(dest).mode)) throw FsException(Errno.ENOTDIR, dest)
        val work = inners.flatMap { inner ->
            val base = inner.trim('/').substringBeforeLast('/', "")
            val chosen = if (inner.trim('/').isEmpty()) reader.entries else Archives.under(reader, inner)
            chosen.map { it to it.name.trim('/').removePrefix(base).trim('/') }
        }
        total = work.filter { !it.first.isDir }.sumOf { it.first.size }
        for ((entry, relative) in work) {
            checkCancel()
            // zip slip: "../" (or an absolute name) must never leave the destination
            val target = FsPath.normalize("$dest/$relative")
            if (target == dest || !FsPath.isSameOrInside(target, dest)) throw FsException(Errno.EINVAL, entry.name, "unsafe path in archive: ${entry.name}")
            mkdirs(FsPath.parent(target), dest)
            if (entry.isDir) {
                mkdirs(target, dest)
                continue
            }
            val out = resolve(target) ?: run {
                skipped++
                done += entry.size
                null
            } ?: continue
            writeFile(out) { output -> reader.open(entry.name).use { input -> pump(input, output, entry.name) } }
            items++
        }
        emit("", force = true)
        return OpResult(items, skipped)
    }

    /** Zips [sources] (files and folders, symlinks not followed and left out) into [zipPath]. */
    fun compress(sources: List<String>, zipPath: String): OpResult {
        val target = resolve(FsPath.normalize(zipPath)) ?: return OpResult(0, 1)
        val work = mutableListOf<Pair<String, String>>() // path to entry name
        for (src in sources.map(FsPath::normalize)) {
            val base = FsPath.parent(src)
            val pending = ArrayDeque(listOf(src))
            while (pending.isNotEmpty()) {
                val path = pending.removeLast()
                val st = fs.lstat(path)
                val name = path.removePrefix(base).trim('/')
                when {
                    FileMode.isDir(st.mode) -> {
                        work += path to "$name/"
                        fs.list(path).sorted().reversed().forEach { pending.addLast(FsPath.child(path, it)) }
                    }

                    FileMode.isRegular(st.mode) -> {
                        work += path to name
                        total += st.size
                    }

                    else -> skipped++
                }
            }
        }
        writeFile(target) { output ->
            val zip = ZipOutputStream(output)
            for ((path, name) in work) {
                checkCancel()
                zip.putNextEntry(ZipEntry(name).apply { time = fs.lstat(path).mtime })
                if (!name.endsWith("/")) {
                    fs.openRead(path).use { pump(it, zip, path) }
                    items++
                }
                zip.closeEntry()
            }
            zip.finish()
        }
        emit("", force = true)
        return OpResult(items, skipped)
    }

    private fun mkdirs(path: String, root: String) {
        if (path == root) return
        val st = try {
            fs.lstat(path)
        } catch (e: FsException) {
            if (e.errno != Errno.ENOENT) throw e
            null
        }
        if (st != null) {
            if (!FileMode.isDir(st.mode)) throw FsException(Errno.ENOTDIR, path)
            return
        }
        mkdirs(FsPath.parent(path), root)
        fs.mkdir(path, 0x1ED)
    }

    /** Where to write [path], asking when it is taken; null to skip. */
    private fun resolve(path: String): String? {
        val exists = try {
            fs.lstat(path)
            true
        } catch (e: FsException) {
            if (e.errno != Errno.ENOENT) throw e
            false
        }
        if (!exists) return path
        val answer = choice ?: listener.onConflict(path, path, srcIsDir = false, dstIsDir = false).let {
            checkCancel()
            if (it.applyAll) choice = it.choice
            it.choice
        }
        return when (answer) {
            ConflictChoice.SKIP -> null
            ConflictChoice.OVERWRITE -> path
            ConflictChoice.RENAME -> FileOpEngine(fs, listener).uniqueName(path)
        }
    }

    private fun writeFile(path: String, body: (java.io.OutputStream) -> Unit) {
        val tmp = FsPath.child(FsPath.parent(path), ".${FsPath.name(path)}${RootFs.TMP_SUFFIX}")
        try {
            fs.remove(tmp)
        } catch (_: FsException) {
        }
        fs.createFile(tmp, 0x1A4)
        try {
            fs.openWrite(tmp).use(body)
            fs.chmod(tmp, 0x1A4)
            fs.rename(tmp, path)
        } catch (e: Throwable) {
            runCatching { fs.remove(tmp) }
            throw e
        }
    }

    private fun pump(input: java.io.InputStream, output: java.io.OutputStream, current: String) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            checkCancel()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            emit(current)
        }
    }

    private fun checkCancel() {
        if (isCancelled()) throw OpCancelledException()
    }

    private fun emit(current: String, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && emitted && now - lastEmit < 100_000_000L) return
        emitted = true
        lastEmit = now
        listener.onProgress(done, total, current)
    }
}
