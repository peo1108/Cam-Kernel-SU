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
            SortBy.SIZE -> compareBy<FileEntry> { it.size }.then(byName)
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

    val parent: String? get() = if (path == "/") null else FsPath.parent(path)

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
