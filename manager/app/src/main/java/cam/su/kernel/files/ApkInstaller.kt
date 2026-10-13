package cam.su.kernel.files

import java.io.File
import java.io.InputStream

/**
 * Installs an APK, or a split bundle (.apks / .xapk / .apkm), as root through `pm` install
 * sessions, as MT's "Install" does. Runs in the root process.
 */
object ApkInstaller {
    private val SESSION = Regex("""\[(\d+)]""")

    /** The session id in `pm install-create` output ("Success: created install session [123]"). */
    fun parseSession(output: String): Int? = SESSION.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** Inner paths of the APKs in a bundle; base.apk first so pm sees the base before splits. */
    fun bundleApks(entries: List<ArchiveEntry>): List<ArchiveEntry> = entries
        .filter { !it.isDir && it.name.endsWith(".apk", ignoreCase = true) && '/' !in it.name }
        .sortedBy { if (it.name.equals("base.apk", true)) 0 else 1 }

    /** OBB files an .xapk carries under Android/obb/<package>/. */
    fun bundleObbs(entries: List<ArchiveEntry>): List<ArchiveEntry> =
        entries.filter { !it.isDir && it.name.startsWith("Android/obb/") }

    /** Returns pm's last line ("Success" or the failure) for the caller to show. */
    fun install(path: String, fs: FsBackend): String {
        val file = File(path)
        if (!Archives.isApkBundle(file.name)) {
            val size = fs.stat(path).size
            return session(listOf(Triple("base.apk", size) { fs.openRead(path) }))
        }
        Archives.open(file).use { reader ->
            val apks = bundleApks(reader.entries)
            if (apks.isEmpty()) return "Failure: no APK inside ${file.name}"
            val result = session(apks.map { e -> Triple(e.name, e.size) { reader.open(e.name) } })
            if (!result.startsWith("Success")) return result
            // The app is in; OBB data failing to copy leaves it installed, so it is only reported.
            val failed = bundleObbs(reader.entries).count { obb ->
                runCatching {
                    val target = File("/storage/emulated/0/${obb.name}")
                    target.parentFile?.mkdirs()
                    reader.open(obb.name).use { input -> target.outputStream().use { input.copyTo(it) } }
                }.isFailure
            }
            return if (failed == 0) result else "$result (OBB: $failed failed)"
        }
    }

    private fun session(parts: List<Triple<String, Long, () -> InputStream>>): String {
        val total = parts.sumOf { it.second }
        val created = pm("install-create", "-r", "-S", total.toString())
        val id = parseSession(created) ?: return created.ifBlank { "Failure: install-create" }
        try {
            parts.forEachIndexed { i, (name, size, open) ->
                val out = pm("install-write", "-S", size.toString(), id.toString(), "${i}_$name", "-", stdin = open)
                if (!out.startsWith("Success")) {
                    pm("install-abandon", id.toString())
                    return out.ifBlank { "Failure: install-write $name" }
                }
            }
        } catch (e: Exception) {
            pm("install-abandon", id.toString())
            return "Failure: ${e.message}"
        }
        return pm("install-commit", id.toString()).ifBlank { "Failure: install-commit" }
    }

    private fun pm(vararg args: String, stdin: (() -> InputStream)? = null): String {
        val process = ProcessBuilder(listOf("pm") + args).redirectErrorStream(true).start()
        if (stdin != null) {
            process.outputStream.use { out -> stdin().use { it.copyTo(out) } }
        } else {
            process.outputStream.close()
        }
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
    }
}
