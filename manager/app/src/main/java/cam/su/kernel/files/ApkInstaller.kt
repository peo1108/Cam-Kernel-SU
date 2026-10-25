package cam.su.kernel.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Installs an APK, or a split bundle (.apks / .xapk / .apkm), as root through `pm` install
 * sessions, as MT's "Install" does. Runs in the root process.
 */
object ApkInstaller {
    private val SESSION = Regex("""\[(\d+)]""")
    private const val PROGRESS_INTERVAL_NANOS = 100_000_000L

    /** The session id in `pm install-create` output ("Success: created install session [123]"). */
    fun parseSession(output: String): Int? = SESSION.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** Inner paths of the APKs in a bundle; base.apk first so pm sees the base before splits. */
    fun bundleApks(entries: List<ArchiveEntry>): List<ArchiveEntry> = entries
        .filter { !it.isDir && it.name.endsWith(".apk", ignoreCase = true) && '/' !in it.name }
        .sortedBy { if (it.name.equals("base.apk", true)) 0 else 1 }

    /** OBB files an .xapk carries under Android/obb/<package>/. */
    fun bundleObbs(entries: List<ArchiveEntry>): List<ArchiveEntry> =
        entries.filter { !it.isDir && it.name.startsWith("Android/obb/") }

    /**
     * Installs [path]; pm's failure ("Failure [INSTALL_FAILED_...]") is thrown as [FsException]
     * carrying that text. [listener] gets the bytes handed to pm, then total 0 while it commits;
     * [cancelled] is checked while streaming (a commit cannot be stopped). The result's skipped
     * count is the OBB files of an .xapk that failed to copy (the app is in all the same).
     */
    fun install(
        path: String,
        fs: FsBackend,
        listener: OpListener? = null,
        cancelled: () -> Boolean = { false },
    ): OpResult {
        val file = File(path)
        val progress = Progress(listener, cancelled)
        if (!Archives.isApkBundle(file.name)) {
            val size = fs.stat(path).size
            progress.total = size
            session(listOf(Triple("base.apk", size) { fs.openRead(path) }), progress)
            return OpResult(1, 0)
        }
        Archives.open(file).use { reader ->
            val apks = bundleApks(reader.entries)
            if (apks.isEmpty()) throw FsException(Errno.EINVAL, path, "Failure: no APK inside ${file.name}")
            val obbs = bundleObbs(reader.entries)
            progress.total = apks.sumOf { it.size } + obbs.sumOf { it.size }
            session(apks.map { e -> Triple(e.name, e.size) { reader.open(e.name) } }, progress)
            // The app is in; OBB data failing to copy leaves it installed, so it is only reported.
            val failed = obbs.count { obb ->
                runCatching {
                    val target = File("/storage/emulated/0/${obb.name}")
                    target.parentFile?.mkdirs()
                    reader.open(obb.name).use { input -> target.outputStream().use { progress.copy(input, it, obb.name) } }
                }.isFailure
            }
            return OpResult(1, failed)
        }
    }

    /** Bytes streamed so far, told to the listener a few times a second. */
    private class Progress(val listener: OpListener?, val cancelled: () -> Boolean) {
        var total = 0L
        var done = 0L
        private var lastEmit = 0L

        fun copy(input: InputStream, out: OutputStream, name: String) {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (cancelled()) throw OpCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastEmit >= PROGRESS_INTERVAL_NANOS) {
                    lastEmit = now
                    listener?.onProgress(done, total, name)
                }
            }
            listener?.onProgress(done, total, name)
        }
    }

    private fun session(parts: List<Triple<String, Long, () -> InputStream>>, progress: Progress) {
        val created = pm("install-create", "-r", "-S", parts.sumOf { it.second }.toString())
        val id = parseSession(created) ?: throw failure(created.ifBlank { "Failure: install-create" })
        try {
            parts.forEachIndexed { i, (name, size, open) ->
                val out = pm("install-write", "-S", size.toString(), id.toString(), "${i}_$name", "-") { stdin ->
                    open().use { progress.copy(it, stdin, name) }
                }
                if (!out.startsWith("Success")) throw failure(out.ifBlank { "Failure: install-write $name" })
            }
        } catch (e: Exception) {
            pm("install-abandon", id.toString())
            throw if (e is FsException || e is OpCancelledException) e else failure("Failure: ${e.message}")
        }
        // pm checks and optimizes the app now, with nothing to report on the way
        progress.listener?.onProgress(progress.done, 0, "")
        val result = pm("install-commit", id.toString())
        if (!result.startsWith("Success")) throw failure(result.ifBlank { "Failure: install-commit" })
    }

    private fun failure(pmOutput: String) = FsException(Errno.EINVAL, detail = pmOutput)

    private fun pm(vararg args: String, stdin: ((OutputStream) -> Unit)? = null): String {
        val process = ProcessBuilder(listOf("pm") + args).redirectErrorStream(true).start()
        try {
            if (stdin != null) {
                process.outputStream.use(stdin)
            } else {
                process.outputStream.close()
            }
        } catch (e: Exception) {
            // cancelled, or the source failed to read: pm must not sit waiting for the rest
            process.destroy()
            throw e
        }
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
    }
}
