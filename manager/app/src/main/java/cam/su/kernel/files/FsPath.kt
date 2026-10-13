package cam.su.kernel.files

/** Absolute path helpers. ".." is resolved by text, the way a file manager shows it. */
object FsPath {
    fun normalize(path: String): String {
        if (!path.startsWith("/") || '\u0000' in path) throw FsException(Errno.EINVAL, path)
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun parent(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    fun name(path: String): String = path.substringAfterLast('/')

    fun child(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"

    /** True for a name that can be created inside a directory as is. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
            name.toByteArray().size <= 255

    fun isSameOrInside(path: String, root: String): Boolean =
        root == "/" || path == root || path.startsWith("$root/")
}
