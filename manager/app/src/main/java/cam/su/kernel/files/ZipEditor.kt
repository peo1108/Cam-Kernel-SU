package cam.su.kernel.files

import java.io.ByteArrayOutputStream
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

    private var done = 0L
    private var total = 0L

    fun apply(archive: String, edits: Edits): OpResult {
        val adds = edits.adds.associateBy { it.name.trim('/') }.toMutableMap()
        val renamedAdds = mutableMapOf<String, Addition>() // added under a new name (Keep both)
        var choice: ConflictChoice? = null
        var skipped = 0
        var items = 0
        total = edits.adds.sumOf { it.size }
        val written = HashSet<String>()

        RootFs(fs).writeAtomicWith(archive, beforeCommit = { checkCancel() }) { raw ->
            val out = ZipOutputStream(raw)
            ZipInputStream(fs.openRead(archive)).use { input ->
                while (true) {
                    checkCancel()
                    val entry = input.nextEntry ?: break
                    val isDir = entry.isDirectory
                    val oldName = entry.name.trim('/')
                    if (oldName.isEmpty() || edits.deletes.any { under(oldName, it) }) continue
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
                    if (!written.add(if (isDir) "$name/" else name)) continue
                    copyEntry(input, entry, if (isDir) "$name/" else name, out)
                }
            }
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
        }
        listener?.onProgress(total, total, "")
        return OpResult(items + edits.deletes.size + edits.renames.size, skipped)
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
            // STORED needs size and CRC before the data: buffer it
            val bytes = ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            copy.method = ZipEntry.STORED
            copy.size = bytes.size.toLong()
            copy.compressedSize = bytes.size.toLong()
            copy.crc = CRC32().apply { update(bytes) }.value
            out.putNextEntry(copy)
            out.write(bytes)
        } else {
            out.putNextEntry(copy)
            input.copyTo(out)
        }
        out.closeEntry()
    }

    private fun writeAddition(out: ZipOutputStream, name: String, add: Addition) {
        out.putNextEntry(ZipEntry(name))
        add.open().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkCancel()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                listener?.onProgress(done, total, name)
            }
        }
        out.closeEntry()
    }

    private fun checkCancel() {
        if (isCancelled()) throw OpCancelledException()
    }

    companion object {
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
    }
}
