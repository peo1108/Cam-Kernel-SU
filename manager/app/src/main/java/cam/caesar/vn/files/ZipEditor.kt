package cam.su.kernel.files

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Edits a zip in place, as MT does: the archive is rewritten into `.name.camtmp` next to it and
 * renamed over the original only when complete (the original keeps its mode, owner and label).
 * Untouched entries are copied with their compression method; STORED ones (resources.arsc,
 * native libraries in an APK) stay STORED. An edited APK loses its signature.
 */
class ZipEditor(
    private val fs: FsBackend,
    private val listener: OpListener? = null,
    private val isCancelled: () -> Boolean = { false },
) {
    /** A file to put at [name] inside the archive. */
    class Addition(val name: String, val size: Long, val open: () -> InputStream)

    class Edits(
        /** inner paths to drop, folders with everything under them */
        val deletes: Set<String> = emptySet(),
        /** inner path (file or folder) to its new inner path */
        val renames: Map<String, String> = emptyMap(),
        val adds: List<Addition> = emptyList(),
        val mkdirs: List<String> = emptyList(),
    )

    /** Bytes of the archive read so far plus bytes of additions written: progress for big rewrites. */
    private var done = 0L
    private var total = 0L
    private var lastEmit = 0L
    private var emitted = false

    fun apply(archive: String, edits: Edits): OpResult {
        // an empty name would be the archive's root: never "delete everything"
        val deletes = edits.deletes.map { it.trim('/') }.filter { it.isNotEmpty() }
        val adds = edits.adds.associateBy { it.name.trim('/') }.toMutableMap()
        val renamedAdds = mutableMapOf<String, Addition>() // added under a new name (Keep both)
        var choice: ConflictChoice? = null
        var skipped = 0
        var items = 0
        total = fs.stat(archive).size + edits.adds.sumOf { it.size }
        val written = HashSet<String>()
        val renamedTo = HashSet<String>()

        RootFs(fs).writeAtomicWith(archive, beforeCommit = { checkCancel() }) { raw ->
            // ZipOutputStream and ZipInputStream move 512 bytes at a time: one syscall each without a buffer
            val buffered = BufferedOutputStream(raw, BUFFER_SIZE)
            val out = ZipOutputStream(buffered)
            val source = Counting(fs.openRead(archive))
            ZipInputStream(BufferedInputStream(source, BUFFER_SIZE)).use { input ->
                while (true) {
                    checkCancel()
                    val entry = input.nextEntry ?: break
                    val isDir = entry.isDirectory
                    val oldName = entry.name.trim('/')
                    if (oldName.isEmpty() || deletes.any { under(oldName, it) }) continue
                    val name = rename(oldName, edits.renames)
                    if (!isDir && name in adds) {
                        val answer = choice ?: (listener?.onConflict(name, name, srcIsDir = false, dstIsDir = false) ?: Resolution(ConflictChoice.OVERWRITE)).let {
                            checkCancel()
                            if (it.applyAll) choice = it.choice
                            it.choice
                        }
                        when (answer) {
                            ConflictChoice.OVERWRITE -> continue // the addition replaces it
                            ConflictChoice.SKIP -> {
                                adds.remove(name)?.let {
                                    skipped++
                                    total -= it.size
                                }
                            }
                            ConflictChoice.RENAME -> adds.remove(name)?.let { renamedAdds[name] = it }
                        }
                    }
                    val key = if (isDir) "$name/" else name
                    if (!written.add(key)) {
                        // Renamed onto a name that is taken: refuse (nothing is replaced) rather
                        // than silently drop one of the two. Plain duplicates are dropped as before.
                        if (name != oldName || key in renamedTo) throw FsException(Errno.EEXIST, archive, "already exists: $name")
                        continue
                    }
                    if (name != oldName) renamedTo += key
                    copyEntry(input, entry, key, out)
                    emit(source.count, name)
                }
            }
            done = source.count
            for (dir in edits.mkdirs.map { it.trim('/') }.filter { it.isNotEmpty() }) {
                if (written.add("$dir/")) {
                    out.putNextEntry(ZipEntry("$dir/"))
                    out.closeEntry()
                    items++
                }
            }
            for ((name, add) in adds) {
                if (!written.add(name)) continue
                writeAddition(out, name, add)
                items++
            }
            for ((name, add) in renamedAdds) {
                val unique = uniqueName(name, written)
                written.add(unique)
                writeAddition(out, unique, add)
                items++
            }
            out.finish()
            buffered.flush()
        }
        listener?.onProgress(total, total, "")
        return OpResult(items + deletes.size + edits.renames.size, skipped)
    }

    /** Disk files and folders as additions under [destInner], symlinks left out. */
    fun additionsFrom(sources: List<String>, destInner: String): Pair<List<Addition>, List<String>> {
        val adds = mutableListOf<Addition>()
        val dirs = mutableListOf<String>()
        val prefix = destInner.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        for (src in sources.map(FsPath::normalize)) {
            val base = FsPath.parent(src)
            val pending = ArrayDeque(listOf(src))
            while (pending.isNotEmpty()) {
                val path = pending.removeLast()
                val st = fs.lstat(path)
                val name = prefix + path.removePrefix(base).trim('/')
                when {
                    FileMode.isDir(st.mode) -> {
                        dirs += name
                        fs.list(path).forEach { pending.addLast(FsPath.child(path, it)) }
                    }
                    FileMode.isRegular(st.mode) -> adds += Addition(name, st.size) { fs.openRead(path) }
                }
            }
        }
        return adds to dirs
    }

    private fun copyEntry(input: ZipInputStream, entry: ZipEntry, name: String, out: ZipOutputStream) {
        val copy = ZipEntry(name).apply { if (entry.time > 0) time = entry.time }
        if (entry.isDirectory) {
            out.putNextEntry(copy)
            out.closeEntry()
            return
        }
        if (entry.method == ZipEntry.STORED) {
            copy.method = ZipEntry.STORED
            if (entry.size >= 0 && entry.crc >= 0) {
                // The local header carried size and CRC (it always does for STORED): stream it,
                // a stored video or native library can be far bigger than memory.
                copy.size = entry.size
                copy.compressedSize = entry.size
                copy.crc = entry.crc
                out.putNextEntry(copy)
                input.copyTo(out, BUFFER_SIZE)
            } else {
                // STORED needs size and CRC before the data: buffer it
                val bytes = ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
                copy.size = bytes.size.toLong()
                copy.compressedSize = bytes.size.toLong()
                copy.crc = CRC32().apply { update(bytes) }.value
                out.putNextEntry(copy)
                out.write(bytes)
            }
        } else {
            out.putNextEntry(copy)
            input.copyTo(out, BUFFER_SIZE)
        }
        out.closeEntry()
    }

    private fun writeAddition(out: ZipOutputStream, name: String, add: Addition) {
        out.putNextEntry(ZipEntry(name))
        add.open().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancel()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                emit(done, name)
            }
        }
        out.closeEntry()
    }

    private fun emit(progress: Long, current: String) {
        val now = System.nanoTime()
        if (emitted && now - lastEmit < EMIT_INTERVAL_NS) return
        emitted = true
        lastEmit = now
        listener?.onProgress(progress.coerceAtMost(total), total, current)
    }

    private fun checkCancel() {
        if (isCancelled()) throw OpCancelledException()
    }

    /** Counts what was read from the original archive. */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val EMIT_INTERVAL_NS = 100_000_000L

        fun under(name: String, root: String): Boolean {
            val r = root.trim('/')
            return r.isEmpty() || name == r || name.startsWith("$r/")
        }

        fun rename(name: String, renames: Map<String, String>): String {
            for ((from, to) in renames) {
                val f = from.trim('/')
                if (name == f || name.startsWith("$f/")) return to.trim('/') + name.removePrefix(f)
            }
            return name
        }

        /** "dir/a.txt" taken → "dir/a (1).txt". */
        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dir = name.substringBeforeLast('/', "")
            val file = name.substringAfterLast('/')
            val dot = file.lastIndexOf('.')
            val (base, ext) = if (dot > 0) file.substring(0, dot) to file.substring(dot) else file to ""
            var n = 1
            while (true) {
                val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$base ($n)$ext"
                if (candidate !in taken) return candidate
                n++
            }
        }

        /** Whether [name] (a file or folder inside the archive, by [names]) is already taken. */
        fun taken(names: Collection<String>, name: String): Boolean {
            val n = name.trim('/')
            return n.isNotEmpty() && names.any { val e = it.trim('/'); e == n || e.startsWith("$n/") }
        }
    }
}
