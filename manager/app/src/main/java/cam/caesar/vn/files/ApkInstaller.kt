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
    private const val STORAGE = "/storage/emulated/0"
    private const val OBB_DIR = "Android/obb/"

    /** The session id in `pm install-create` output ("Success: created install session [123]"). */
    fun parseSession(output: String): Int? = SESSION.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** Inner paths of the APKs in a bundle; base.apk first so pm sees the base before splits. */
    fun bundleApks(entries: List<ArchiveEntry>): List<ArchiveEntry> = entries
        .filter { !it.isDir && it.name.endsWith(".apk", ignoreCase = true) && '/' !in it.name }
        .sortedBy { if (it.name.equals("base.apk", true)) 0 else 1 }

    /** The OBB files of a bundle that are copied, each with where it goes, and how many are not. */
    class Obbs(val copies: List<Pair<ArchiveEntry, String>>, val refused: Int)

    /**
     * The files an .xapk carries under Android/obb/: those in [pkg]'s own folder, and a count of
     * the rest (another app's folder, no folder, a name that climbs out), which are not copied.
     * With [pkg] null (the bundle's manifest did not read) none is copied.
     */
    fun bundleObbs(entries: List<ArchiveEntry>, pkg: String?): Obbs {
        val copies = mutableListOf<Pair<ArchiveEntry, String>>()
        var refused = 0
        for (e in entries) {
            if (e.isDir || !e.name.startsWith(OBB_DIR)) continue
            val target = pkg?.let { obbTarget(e.name, it) }
            if (target != null) copies += e to target else refused++
        }
        return Obbs(copies, refused)
    }

    /**
     * Where the bundle's [name] is copied, or null unless it is a file inside Android/obb/[pkg]/.
     * The copy runs as root: a name that climbs out with ".." would write anywhere (/data/adb),
     * and one in another app's folder would overwrite that app's data.
     */
    fun obbTarget(name: String, pkg: String): String? {
        val parts = name.split('/')
        if (parts.size < 4 || parts[0] != "Android" || parts[1] != "obb" || parts[2] != pkg || '\u0000' in name) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return "$STORAGE/$name"
    }

    /**
     * Copies [obbs] out of [reader] with [copy] and returns how many did not make it. Each one is
     * written to a temp file renamed over the old one, so a failure or a cancel never leaves a
     * cut-off OBB, nor takes away the working one an update of the same game replaces. A cancel
     * stops there; the rest count as not copied.
     */
    internal fun copyObbs(
        reader: ArchiveReader,
        obbs: List<Pair<ArchiveEntry, String>>,
        fs: FsBackend,
        copy: (InputStream, OutputStream, String) -> Unit,
    ): Int {
        var skipped = 0
        for ((i, obb) in obbs.withIndex()) {
            val (entry, target) = obb
            try {
                mkdirs(fs, FsPath.parent(target))
                writeReplacing(fs, target, entry.size) { out -> reader.open(entry.name).use { copy(it, out, entry.name) } }
            } catch (e: Exception) {
                if (e is OpCancelledException) return skipped + obbs.size - i
                skipped++
            }
        }
        return skipped
    }

    /**
     * Writes [size] bytes from [body] to [target]. No owner, mode or label copied over
     * (RootFs.writeAtomic does): FUSE decides those here. Beside the old file when the disk has
     * room for both; when it has not (a big OBB of the same name, a nearly full disk) over the old
     * one in place, as before the temp file: the app is installed already and wants the new data,
     * and a failure then leaves no OBB rather than a cut one. When not even that fits, the old
     * file stays as it is.
     */
    private fun writeReplacing(fs: FsBackend, target: String, size: Long, body: (OutputStream) -> Unit) {
        val old = try {
            fs.lstat(target).takeIf { FileMode.isRegular(it.mode) }
        } catch (e: FsException) {
            if (e.errno == Errno.ENOENT) null else throw e
        }
        val free = fs.statFs(FsPath.parent(target))[1]
        if (free < size) {
            if (old == null || free + old.size < size) throw FsException(Errno.ENOSPC, target)
            try {
                fs.openWrite(target).use(body)
            } catch (e: Throwable) {
                runCatching { fs.remove(target) }
                throw e
            }
            return
        }
        val tmp = FsPath.child(FsPath.parent(target), ".${FsPath.name(target)}${RootFs.TMP_SUFFIX}")
        if (exists(fs, tmp)) fs.remove(tmp)
        fs.createFile(tmp, 0x1B0) // 0660
        try {
            fs.openWrite(tmp).use(body)
            fs.rename(tmp, target)
        } catch (e: Throwable) {
            runCatching { fs.remove(tmp) }
            throw e
        }
    }

    private fun mkdirs(fs: FsBackend, dir: String) {
        val missing = ArrayDeque<String>()
        var p = dir
        while (p != "/" && !exists(fs, p)) {
            missing.addFirst(p)
            p = FsPath.parent(p)
        }
        missing.forEach { fs.mkdir(it, 0x1F8) } // 0770
    }

    private fun exists(fs: FsBackend, path: String) = try {
        fs.lstat(path)
        true
    } catch (e: FsException) {
        if (e.errno == Errno.ENOENT) false else throw e
    }

    /** The package of [bundle]'s [apk], from its manifest; null when that does not read. A cancel is thrown. */
    private fun packageOf(bundle: File, apk: ArchiveEntry, cancelled: () -> Boolean): String? = try {
        ApkManifest.packageNameInBundle(bundle, apk.name, cancelled)
    } catch (e: Exception) {
        if (e is OpCancelledException) throw e
        null
    }

    /**
     * Installs [path]; pm's failure ("Failure [INSTALL_FAILED_...]") is thrown as [FsException]
     * carrying that text. [listener] gets the bytes handed to pm, then total 0 while it commits;
     * [cancelled] is checked while streaming (a commit cannot be stopped). The result's skipped
     * count is the OBB files of an .xapk that were not copied: outside the app's own folder, one
     * failed, or the copy was cancelled after the commit (the app is in all the same).
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
            // OBB data goes only to the folder of the package pm installs, as its manifest says
            val hasObbs = reader.entries.any { !it.isDir && it.name.startsWith(OBB_DIR) }
            val obbs = bundleObbs(reader.entries, if (hasObbs) packageOf(file, apks.first(), cancelled) else null)
            progress.total = apks.sumOf { it.size } + obbs.copies.sumOf { it.first.size }
            session(apks.map { e -> Triple(e.name, e.size) { reader.open(e.name) } }, progress)
            // The app is in; OBB data failing to copy leaves it installed, so it is only reported.
            // A cancel now stops the copying but cannot take the app back: it is reported the
            // same way, as the OBB files that are not there.
            return OpResult(1, obbs.refused + copyObbs(reader, obbs.copies, fs, progress::copy))
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
