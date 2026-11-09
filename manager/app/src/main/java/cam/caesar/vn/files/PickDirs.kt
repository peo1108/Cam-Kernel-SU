package cam.caesar.vn.files

import java.util.Locale

/** Where the in-app file pickers (module zips, keybox) start and jump to. */
object PickDirs {
    const val DOWNLOADS = "/storage/emulated/0/Download"
    const val STORAGE = "/storage/emulated/0"
}

/**
 * What an in-app picker lists: folders first by name, then files ending in [extension], the newest
 * on top (just downloaded).
 */
fun pickEntries(entries: List<FileEntry>, extension: String): List<FileEntry> {
    val folders = entries.filter { it.isDir }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val files = entries.filter { !it.isDir && it.name.endsWith(extension, ignoreCase = true) }.sortedByDescending { it.mtime }
    return folders + files
}
