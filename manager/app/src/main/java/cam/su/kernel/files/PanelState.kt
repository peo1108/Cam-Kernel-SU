package cam.su.kernel.files

import java.util.Locale

enum class SortBy { NAME, SIZE, TIME, TYPE }

data class SortOptions(
    val by: SortBy = SortBy.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = true,
)

/** Folders first, then the chosen order; names compare like a person reads them (file2 < file10). */
object FileSort {
    fun apply(entries: List<FileEntry>, options: SortOptions): List<FileEntry> {
        val visible = if (options.showHidden) entries else entries.filterNot { it.name.startsWith(".") }
        val byName = Comparator<FileEntry> { a, b -> NaturalOrder.compare(a.name, b.name) }
        val order: Comparator<FileEntry> = when (options.by) {
            SortBy.NAME -> byName
            // a link sorts by the size it shows: its file's, not the length of the link
            SortBy.SIZE -> compareBy<FileEntry> { it.contentSize }.then(byName)
            SortBy.TIME -> compareBy<FileEntry> { it.mtime }.then(byName)
            SortBy.TYPE -> compareBy<FileEntry> { extension(it) }.then(byName)
        }
        val directed = if (options.descending) order.reversed() else order
        return visible.sortedWith(compareBy<FileEntry> { !it.isDir }.then(directed))
    }

    private fun extension(entry: FileEntry): String {
        if (entry.isDir) return ""
        val dot = entry.name.lastIndexOf('.')
        return if (dot > 0) entry.name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }
}

object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }
}

/** Where a panel is and how it got there. */
data class PanelNav(
    val path: String,
    val back: List<String> = emptyList(),
    val forward: List<String> = emptyList(),
) {
    fun go(to: String): PanelNav =
        if (to == path) this else PanelNav(to, (back + path).takeLast(MAX_HISTORY), emptyList())

    fun goBack(): PanelNav? = back.lastOrNull()?.let { PanelNav(it, back.dropLast(1), forward + path) }

    fun goForward(): PanelNav? = forward.lastOrNull()?.let { PanelNav(it, back + path, forward.dropLast(1)) }

    val parent: String?
        get() = when {
            path == "/" -> null
            Archives.isInside(path) -> Archives.parent(path)
            else -> FsPath.parent(path)
        }

    private companion object {
        const val MAX_HISTORY = 100
    }
}

object FileFormat {
    /** "/data/adb" → [("/", "/"), ("data", "/data"), ("adb", "/data/adb")] */
    fun breadcrumb(path: String): List<Pair<String, String>> {
        val crumbs = mutableListOf("/" to "/")
        var current = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current += "/$part"
            crumbs += part to current
        }
        return crumbs
    }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 100) "${value.toLong()} ${units[unit]}" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    /** `ls -l` style: "drwxr-x--x", with s/S and t/T for setuid, setgid and sticky. */
    fun permissions(mode: Int): String {
        val type = when (mode and FileMode.S_IFMT) {
            FileMode.S_IFDIR -> 'd'
            FileMode.S_IFLNK -> 'l'
            FileMode.S_IFREG -> '-'
            0x2000 -> 'c'
            0x6000 -> 'b'
            0x1000 -> 'p'
            0xC000 -> 's'
            else -> '?'
        }
        val sb = StringBuilder().append(type)
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (mode shr shift) and 7
            sb.append(if (bits and 4 != 0) 'r' else '-')
            sb.append(if (bits and 2 != 0) 'w' else '-')
            val exec = bits and 1 != 0
            val special = when (shift) {
                6 -> mode and 0x800 != 0 // setuid
                3 -> mode and 0x400 != 0 // setgid
                else -> mode and 0x200 != 0 // sticky
            }
            val letter = if (shift == 0) 't' else 's'
            sb.append(
                when {
                    special && exec -> letter
                    special -> letter.uppercaseChar()
                    exec -> 'x'
                    else -> '-'
                },
            )
        }
        return sb.toString()
    }

    fun octal(mode: Int): String = (mode and FileMode.PERM_MASK).toString(8).padStart(4, '0')
}

/** What a file is, for its icon and for what tapping it does. */
enum class FileKind { FOLDER, ARCHIVE, APK, IMAGE, DISK_IMAGE, TEXT, AUDIO, VIDEO, OTHER }

object FileKinds {
    private val byExtension = buildMap {
        listOf("zip", "jar", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2", "zst", "lz4").forEach { put(it, FileKind.ARCHIVE) }
        listOf("apk", "apks", "xapk", "apkm").forEach { put(it, FileKind.APK) }
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "svg").forEach { put(it, FileKind.IMAGE) }
        listOf("img", "bin", "iso", "lz4img").forEach { put(it, FileKind.DISK_IMAGE) }
        listOf(
            "txt", "sh", "prop", "rc", "xml", "json", "conf", "cfg", "ini", "log", "md", "kt", "java",
            "c", "h", "cpp", "py", "js", "ts", "html", "css", "yml", "yaml", "toml", "csv", "list", "te",
        ).forEach { put(it, FileKind.TEXT) }
        listOf("mp3", "flac", "ogg", "wav", "m4a", "aac", "opus").forEach { put(it, FileKind.AUDIO) }
        listOf("mp4", "mkv", "webm", "avi", "mov", "3gp").forEach { put(it, FileKind.VIDEO) }
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    fun of(entry: FileEntry): FileKind {
        if (entry.isDir) return FileKind.FOLDER
        // devices, fifos, sockets: nothing to open
        if (entry.type == FileEntry.TYPE_OTHER || entry.isLink && entry.linkBroken) return FileKind.OTHER
        byExtension[extension(entry.name)]?.let { return it }
        // Extensionless files under the usual script/config folders are text.
        return if (extension(entry.name).isEmpty() && entry.contentSize < 1024 * 1024) FileKind.TEXT else FileKind.OTHER
    }

    /**
     * Offered "Run as root": a .sh file, or a regular file with an execute bit (module scripts
     * often have no extension). Not inside an archive, not a link to who knows what.
     */
    fun isScript(entry: FileEntry): Boolean {
        if (entry.isDir || Archives.isInside(entry.path)) return false
        if (entry.isLink) return !entry.linkBroken && extension(entry.name) == "sh"
        if (entry.type != FileEntry.TYPE_FILE) return false
        return extension(entry.name) == "sh" || entry.mode and 0x49 != 0
    }

    fun mime(entry: FileEntry): String = when (of(entry)) {
        FileKind.APK -> "application/vnd.android.package-archive"
        FileKind.TEXT -> "text/plain"
        FileKind.ARCHIVE -> if (extension(entry.name) == "zip") "application/zip" else "application/octet-stream"
        FileKind.IMAGE -> "image/*"
        FileKind.AUDIO -> "audio/*"
        FileKind.VIDEO -> "video/*"
        else -> "*/*"
    }
}

/** MT's selection bar: select range, invert, same type. Paths in, paths out. */
object SelectionOps {
    /** Everything between the first and the last selected entry, in list order. */
    fun range(entries: List<FileEntry>, selected: Set<String>): Set<String> {
        val indices = entries.indices.filter { entries[it].path in selected }
        if (indices.isEmpty()) return selected
        return selected + entries.subList(indices.first(), indices.last() + 1).map { it.path }
    }

    fun invert(entries: List<FileEntry>, selected: Set<String>): Set<String> =
        entries.map { it.path }.filterTo(HashSet()) { it !in selected }

    /** Every entry of a kind already selected: folders, or files with a selected extension. */
    fun sameType(entries: List<FileEntry>, selected: Set<String>): Set<String> {
        val chosen = entries.filter { it.path in selected }
        val folders = chosen.any { it.isDir }
        val extensions = chosen.filter { !it.isDir }.mapTo(HashSet()) { FileKinds.extension(it.name) }
        return selected + entries.filter { e ->
            if (e.isDir) folders else FileKinds.extension(e.name) in extensions
        }.map { it.path }
    }
}

/** File name patterns for search: `*` and `?` wildcards; a plain word matches anywhere in the name. */
object SearchPattern {
    fun toRegex(pattern: String): Regex {
        val p = pattern.trim()
        if (p.isEmpty()) return Regex("")
        if ('*' !in p && '?' !in p) return Regex(Regex.escape(p), RegexOption.IGNORE_CASE)
        val body = p.map {
            when (it) {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(it.toString())
            }
        }.joinToString("")
        return Regex("^$body$", RegexOption.IGNORE_CASE)
    }
}

/**
 * Systemless overrides: a file under a read-only partition is replaced by a copy in one shared
 * module, the way modules replace files (`/vendor/x` lives at `system/vendor/x` in a module).
 * Modules are only mounted by a metamodule, so nothing happens without one.
 */
object Systemless {
    const val MODULE = "/data/adb/modules/cam_file_override"
    const val METAMODULE_LINK = "/data/adb/metamodule"
    private val ROOTS = listOf("/system", "/vendor", "/product", "/system_ext", "/odm")

    val MODULE_PROP = """
        id=cam_file_override
        name=Cam file overrides
        version=1
        versionCode=1
        author=Cam Kernel SU
        description=Files replaced from the Files tab (systemless).
    """.trimIndent() + "\n"

    fun canOverride(path: String) = ROOTS.any { path.startsWith("$it/") }

    fun target(path: String): String =
        "$MODULE/system" + if (path.startsWith("/system/")) path.removePrefix("/system") else path
}

/**
 * Shared storage is served by the media provider (FUSE) from a disk folder: /storage/emulated/<user>/x
 * is /data/media/<user>/x there.
 */
object SharedStorage {
    private val EMULATED = Regex("""^/storage/emulated/(\d+)(/.+)$""")

    /**
     * The disk path of [path], for a folder or file inside a user's shared storage; null for the
     * user's root, for Android/ (app folders, owned by each app and counted in its quota) and for
     * anywhere else.
     */
    fun lower(path: String): String? {
        var p = FsPath.normalize(path)
        for (alias in listOf("/sdcard", "/storage/self/primary")) {
            if (p == alias || p.startsWith("$alias/")) p = "/storage/emulated/0" + p.removePrefix(alias)
        }
        val m = EMULATED.find(p) ?: return null
        val inner = m.groupValues[2]
        if (inner == "/Android" || inner.startsWith("/Android/")) return null
        return "/data/media/${m.groupValues[1]}$inner"
    }
}

/** "Browse as an app": its mount namespace seen through /proc/<pid>/root. */
object AppView {
    private val PREFIX = Regex("""^/proc/(\d+)/root(/.*)?$""")

    fun root(pid: Int) = "/proc/$pid/root"

    /** (pid, path inside the app's view) when [path] goes through an app's root. */
    fun parse(path: String): Pair<Int, String>? =
        PREFIX.find(path)?.let { it.groupValues[1].toInt() to it.groupValues[2].ifEmpty { "/" } }
}
