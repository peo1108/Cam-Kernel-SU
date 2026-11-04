package cam.su.kernel.files

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/** One entry of an archive; [name] is the inner path without leading or trailing slash. */
data class ArchiveEntry(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

interface ArchiveReader : Closeable {
    val entries: List<ArchiveEntry>
    fun open(name: String): InputStream
}

/**
 * Archives browsed like folders, as MT does. A path inside one is written
 * `/sdcard/a.zip!/dir/file`; the archive itself is `/sdcard/a.zip!/`.
 */
object Archives {
    const val SEP = "!/"

    private val ZIP = setOf("zip", "jar", "apk", "apks", "xapk", "apkm", "aar")
    private val TAR = setOf("tar", "tgz", "txz", "gz", "xz")

    fun isInside(path: String) = separator(path) >= 0

    /** [archive, inner]; inner is "" at the archive's root. */
    fun split(path: String): Pair<String, String> {
        val i = separator(path)
        require(i >= 0) { "not inside an archive: $path" }
        return path.substring(0, i) to path.substring(i + SEP.length).trim('/')
    }

    /** The first "!/" that follows an archive name; a folder may be called "Wow!" too. */
    private fun separator(path: String): Int {
        var from = 0
        while (true) {
            val i = path.indexOf(SEP, from)
            if (i < 0) return -1
            if (canBrowse(path.substring(0, i).substringAfterLast('/'))) return i
            from = i + 1
        }
    }

    fun join(archive: String, inner: String) = "$archive$SEP${inner.trim('/')}"

    /** Parent inside the archive, or the folder holding the archive when at its root. */
    fun parent(path: String): String {
        val (archive, inner) = split(path)
        if (inner.isEmpty()) return FsPath.parent(archive)
        return join(archive, inner.substringBeforeLast('/', ""))
    }

    fun canBrowse(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")
        if (ext in ZIP) return true
        return ext in TAR && (ext == "tar" || ext == "tgz" || ext == "txz" || lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz"))
    }

    /** Zip-format archives can be edited in place; tar ones are read-only. */
    fun isZip(name: String) = name.lowercase(Locale.ROOT).substringAfterLast('.', "") in ZIP

    fun isApkBundle(name: String) = name.lowercase(Locale.ROOT).substringAfterLast('.', "") in setOf("apks", "xapk", "apkm")

    fun open(file: File): ArchiveReader {
        val lower = file.name.lowercase(Locale.ROOT)
        return if (lower.substringAfterLast('.', "") in ZIP) ZipReader(file) else TarReader(file)
    }

    /** Direct children of [inner], with folders that only exist as path prefixes filled in. */
    fun list(reader: ArchiveReader, archive: String, inner: String): List<FileEntry> {
        val prefix = inner.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val files = LinkedHashMap<String, ArchiveEntry>()
        val dirs = LinkedHashMap<String, ArchiveEntry?>()
        for (e in reader.entries) {
            val name = e.name.trim('/')
            if (!name.startsWith(prefix) || name.length == prefix.length) continue
            val rest = name.substring(prefix.length)
            val head = rest.substringBefore('/')
            when {
                '/' in rest -> dirs.putIfAbsent(head, null) // only seen as a prefix so far
                e.isDir -> dirs[head] = e
                else -> files[head] = e
            }
        }
        return dirs.map { (name, e) ->
            entry(archive, prefix + name, name, true, 0, e?.mtime ?: 0)
        } + files.filterKeys { it !in dirs }.map { (name, e) ->
            entry(archive, prefix + name, name, false, e.size, e.mtime)
        }
    }

    /** Every entry at or under [inner] (a file, or a folder and its contents). */
    fun under(reader: ArchiveReader, inner: String): List<ArchiveEntry> {
        val name = inner.trim('/')
        return reader.entries.filter { val n = it.name.trim('/'); n == name || n.startsWith("$name/") }
    }

    private fun entry(archive: String, inner: String, name: String, dir: Boolean, size: Long, mtime: Long) = FileEntry(
        name = name,
        path = join(archive, inner),
        type = if (dir) FileEntry.TYPE_DIR else FileEntry.TYPE_FILE,
        size = size,
        mtime = mtime,
        mode = if (dir) FileMode.S_IFDIR or 0x1ED else FileMode.S_IFREG or 0x1A4,
        uid = 0,
        gid = 0,
        owner = "",
        group = "",
        context = null,
    )

    private class ZipReader(file: File) : ArchiveReader {
        // Old Windows zips store names in a local code page, which is not valid UTF-8: they
        // still open, read byte for byte as Latin-1 (names look odd but every entry works).
        private val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            if (e !is java.util.zip.ZipException && e !is IllegalArgumentException) throw e
            try {
                ZipFile(file, Charsets.ISO_8859_1)
            } catch (_: Exception) {
                throw e
            }
        }
        override val entries: List<ArchiveEntry> = zip.entries().asSequence()
            .map { ArchiveEntry(it.name.trim('/'), it.isDirectory, it.size.coerceAtLeast(0), it.time.coerceAtLeast(0)) }
            .filter { it.name.isNotEmpty() }
            .toList()

        override fun open(name: String): InputStream {
            val e = zip.getEntry(name) ?: zip.getEntry("$name/") ?: throw FsException(Errno.ENOENT, name)
            return zip.getInputStream(e)
        }

        override fun close() = zip.close()
    }

    /** Tar has no index: listing reads the whole stream, opening an entry reads up to it. */
    private class TarReader(private val file: File) : ArchiveReader {
        private fun stream(): TarArchiveInputStream {
            val raw = BufferedInputStream(FileInputStream(file))
            val lower = file.name.lowercase(Locale.ROOT)
            val decoded: InputStream = when {
                lower.endsWith(".gz") || lower.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                lower.endsWith(".xz") || lower.endsWith(".txz") -> XZCompressorInputStream(raw)
                else -> raw
            }
            return TarArchiveInputStream(decoded)
        }

        override val entries: List<ArchiveEntry> by lazy {
            stream().use { tar ->
                generateSequence { tar.nextEntry }
                    .map { ArchiveEntry(it.name.trim('/'), it.isDirectory, it.size, it.modTime.time) }
                    .filter { it.name.isNotEmpty() }
                    .toList()
            }
        }

        override fun open(name: String): InputStream {
            val tar = stream()
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.name.trim('/') == name) return tar
            }
            tar.close()
            throw FsException(Errno.ENOENT, name)
        }

        override fun close() = Unit
    }
}
