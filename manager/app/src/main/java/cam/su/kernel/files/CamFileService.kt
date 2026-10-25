package cam.su.kernel.files

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.topjohnwu.superuser.ipc.RootService
import rikka.parcelablelist.ParcelableListSlice
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * File access for the Files tab, in the root process (uid 0). Kept apart from
 * CamRootService, which only carries kernel supercalls.
 */
class CamFileService : RootService() {

    private val fs = RootFs(OsBackend)
    private val nextOpId = AtomicInteger(1)
    private val searchCancelled = AtomicBoolean(false)

    /** Bumped by cancelReads(): a measure or checksum started before it stops. */
    private val readGeneration = AtomicInteger()
    private val ops = ConcurrentHashMap<Int, RunningOp>()
    private val scripts = ConcurrentHashMap<Int, ScriptRunner>()
    private val thumbnailer by lazy { Thumbnailer(this) }

    /** inotify on the folders the two panels show (watch()). */
    private val watchers = mutableListOf<android.os.FileObserver>()

    private fun stopWatchers() = synchronized(watchers) {
        watchers.forEach { it.stopWatching() }
        watchers.clear()
    }

    private companion object {
        const val WATCH_MASK = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
            android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
            android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.ATTRIB or
            android.os.FileObserver.DELETE_SELF or android.os.FileObserver.MOVE_SELF
    }

    private class RunningOp {
        val cancelled = AtomicBoolean(false)
        val answers = LinkedBlockingQueue<Resolution>()
    }

    override fun onCreate() {
        super.onCreate()
        // The root process inherits umask 077 from su: new folders would be 0700, files 0600.
        android.system.Os.umask(0x12) // 022
    }

    override fun onBind(intent: Intent): IBinder = Stub()

    /**
     * Every failure goes back as an errno. A RuntimeException that binder cannot carry (a bad
     * archive makes ZipFile throw several kinds) would otherwise be rethrown in this process.
     */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e.toRemote()
    } catch (e: IOException) {
        throw FsException(Errno.EIO, detail = e.message ?: e.javaClass.simpleName).toRemote()
    } catch (e: RuntimeException) {
        throw FsException(Errno.EIO, detail = e.message ?: e.javaClass.simpleName).toRemote()
    }

    private inner class Stub : ICamFileService.Stub() {
        override fun listDir(path: String): ParcelableListSlice<FileEntry> =
            call { ParcelableListSlice(fs.listDir(path)) }

        override fun stat(path: String): FileEntry? = call { fs.stat(path) }

        override fun statFs(path: String): LongArray = call { fs.statFs(path) }

        override fun checksums(path: String): Array<String> = call {
            val generation = readGeneration.get()
            fs.checksums(path) { readGeneration.get() != generation }.toTypedArray()
        }

        override fun readRange(path: String, offset: Long, length: Int): ByteArray =
            call { fs.readRange(path, offset, length.coerceIn(0, 1024 * 1024)) }

        override fun search(root: String, pattern: String, content: String?, limit: Int): ParcelableListSlice<FileEntry> = call {
            searchCancelled.set(false)
            ParcelableListSlice(fs.search(root, pattern, content, limit) { searchCancelled.get() })
        }

        override fun cancelSearch() = searchCancelled.set(true)

        override fun appProcesses(): Array<String> = call {
            File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
                runCatching {
                    val uid = android.system.Os.stat(dir.path).st_uid
                    if (uid % 100_000 < 10_000) return@runCatching null
                    val name = File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                    if (name.isEmpty()) null else "${dir.name}\t$uid\t$name"
                }.getOrNull()
            }.toTypedArray()
        }

        override fun listArchive(archive: String, inner: String): ParcelableListSlice<FileEntry> = call {
            Archives.open(File(FsPath.normalize(archive))).use { ParcelableListSlice(Archives.list(it, archive, inner)) }
        }

        override fun openArchiveEntry(archive: String, inner: String): ParcelFileDescriptor = call {
            val reader = Archives.open(File(FsPath.normalize(archive)))
            val input = try {
                reader.open(inner.trim('/'))
            } catch (e: Exception) {
                reader.close()
                throw e
            }
            pipeFrom(input) { reader.close() }
        }

        override fun archiveRename(archive: String, from: String, to: String) = call {
            checkFree(archive, to)
            editZip(archive) { ZipEditor.Edits(renames = mapOf(from.trim('/') to to.trim('/'))) }
        }

        override fun archiveMkdir(archive: String, inner: String) = call {
            checkFree(archive, inner)
            editZip(archive) { ZipEditor.Edits(mkdirs = listOf(inner)) }
        }

        override fun archiveWrite(archive: String, inner: String, source: ParcelFileDescriptor) = call {
            source.use { pfd ->
                val bytes = FileInputStream(pfd.fileDescriptor).readBytes()
                pfd.checkError()
                editZip(archive) {
                    ZipEditor.Edits(adds = listOf(ZipEditor.Addition(inner.trim('/'), bytes.size.toLong()) { bytes.inputStream() }))
                }
            }
        }

        override fun measure(path: String): LongArray = call {
            val generation = readGeneration.get()
            fs.measure(path) { readGeneration.get() != generation }
        }

        override fun cancelReads() {
            readGeneration.incrementAndGet()
        }

        override fun runScript(path: String, callback: IScriptCallback): Int = call {
            val script = FsPath.normalize(path)
            if (!FileMode.isRegular(OsBackend.stat(script).mode)) throw FsException(Errno.EINVAL, script, "not a regular file: $script")
            val id = nextOpId.getAndIncrement()
            lateinit var death: IBinder.DeathRecipient
            val runner = ScriptRunner(
                script,
                onOutput = { runCatching { callback.onOutput(id, it) } },
                onExit = { code ->
                    scripts.remove(id)
                    runCatching { callback.asBinder().unlinkToDeath(death, 0) }
                    runCatching { callback.onExit(id, code) }
                },
            )
            // the app going away stops what it started
            death = IBinder.DeathRecipient { runner.stop() }
            try {
                callback.asBinder().linkToDeath(death, 0)
            } catch (_: android.os.RemoteException) {
                throw FsException(Errno.EIO, script, "the app is gone")
            }
            scripts[id] = runner
            try {
                runner.start()
            } catch (e: Exception) {
                scripts.remove(id)
                runCatching { callback.asBinder().unlinkToDeath(death, 0) }
                throw e
            }
            id
        }

        override fun partitions(): Array<String> = call {
            PartitionOps.list().map { "${it.name}\t${it.size}" }.toTypedArray()
        }

        override fun slotSuffix(): String = PartitionOps.slot()

        override fun trashList(): Array<String> = call {
            Trash(OsBackend).list().map { "${it.root}	${it.id}	${it.original}	${it.deletedAt}	${it.size}	${it.isDir}" }.toTypedArray()
        }

        override fun watch(dirs: Array<String>, callback: IWatchCallback) = call {
            stopWatchers()
            synchronized(watchers) {
                for (dir in dirs.map(FsPath::normalize).distinct()) {
                    if (!File(dir).isDirectory) continue
                    watchers += object : android.os.FileObserver(File(dir), WATCH_MASK) {
                        override fun onEvent(event: Int, path: String?) {
                            try {
                                callback.onChanged(dir)
                            } catch (_: android.os.RemoteException) {
                                // the app is gone: nobody to tell any more
                                stopWatchers()
                            }
                        }
                    }.also { it.startWatching() }
                }
            }
        }

        override fun thumbnail(path: String, size: Int): ByteArray? = call {
            thumbnailer.make(FsPath.normalize(path), size.coerceIn(16, 512))
        }

        override fun openFd(path: String): ParcelFileDescriptor = call {
            val file = FsPath.normalize(path)
            if (!FileMode.isRegular(OsBackend.stat(file).mode)) throw FsException(Errno.EINVAL, file, "not a regular file: $file")
            ParcelFileDescriptor.open(File(file), ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun stopScript(id: Int) {
            scripts[id]?.stop()
        }

        override fun writeScript(id: Int, text: String) = call {
            (scripts[id] ?: throw FsException(Errno.EPIPE, detail = "the script ended")).input(text)
        }

        override fun closeScriptInput(id: Int) {
            scripts[id]?.endInput()
        }

        override fun mkdir(path: String) = call { fs.mkdir(path) }

        override fun createFile(path: String) = call { fs.createFile(path) }

        override fun symlink(target: String, link: String) = call { fs.symlink(target, link) }

        override fun rename(from: String, to: String) = call { fs.rename(from, to) }

        override fun chmod(path: String, mode: Int, recursive: Boolean) =
            call { fs.chmod(path, mode, recursive) }

        override fun chown(path: String, uid: Int, gid: Int, recursive: Boolean) =
            call { fs.chown(path, uid, gid, recursive) }

        override fun setContext(path: String, context: String, recursive: Boolean) =
            call { fs.setContext(path, context, recursive) }

        override fun repairShared(path: String) = call { fs.repairShared(path) }

        override fun openRead(path: String): ParcelFileDescriptor = call {
            // Opened here so a missing file or a directory fails the call itself.
            pipeFrom(fs.openRead(path)) {}
        }

        override fun startOp(
            kind: Int,
            sources: Array<String>,
            destDir: String?,
            preserve: Boolean,
            callback: IFileOpCallback,
        ): Int {
            val opKind = OpKind.entries.getOrNull(kind) ?: throw FsException(Errno.EINVAL).toRemote()
            val op = FileOp(opKind, sources.toList(), destDir, preserve)
            val id = nextOpId.getAndIncrement()
            val running = RunningOp()
            ops[id] = running
            // The app going away cancels what it started.
            val death = IBinder.DeathRecipient { running.cancelled.set(true) }
            runCatching { callback.asBinder().linkToDeath(death, 0) }
            thread(name = "cam-file-op-$id") {
                try {
                    runOp(id, op, running, callback)
                } finally {
                    ops.remove(id)
                    runCatching { callback.asBinder().unlinkToDeath(death, 0) }
                }
            }
            return id
        }

        override fun cancelOp(id: Int) {
            ops[id]?.cancelled?.set(true)
        }

        override fun resolveConflict(id: Int, choice: Int, applyAll: Boolean) {
            val resolved = ConflictChoice.entries.getOrNull(choice) ?: ConflictChoice.SKIP
            ops[id]?.answers?.offer(Resolution(resolved, applyAll))
        }

        override fun writeFile(path: String, source: ParcelFileDescriptor) = call {
            source.use { pfd ->
                // checkError() turns a writer that gave up halfway into an IOException,
                // so a cut-off stream never replaces the file.
                fs.writeAtomic(path, FileInputStream(pfd.fileDescriptor)) { pfd.checkError() }
            }
        }
    }

    /** Copying (or moving) into a zip: add the files, then drop the sources on a move. */
    private fun intoZip(op: FileOp, listener: OpListener, cancelled: () -> Boolean): OpResult {
        val (archive, inner) = Archives.split(op.destDir!!)
        if (!Archives.isZip(archive)) throw FsException(Errno.EROFS, archive, "only zip archives can be edited")
        val editor = ZipEditor(OsBackend, listener, cancelled)
        val (adds, dirs) = editor.additionsFrom(op.sources, inner)
        val result = editor.apply(FsPath.normalize(archive), ZipEditor.Edits(adds = adds, mkdirs = dirs))
        if (op.kind == OpKind.MOVE && result.skipped == 0) {
            FileOpEngine(OsBackend, listener, cancelled).run(FileOp(OpKind.DELETE, op.sources))
        }
        return result
    }

    /** Rewrites a zip archive; replacing an entry with the same name is what the caller wants here. */
    private fun editZip(archive: String, edits: () -> ZipEditor.Edits) {
        val path = FsPath.normalize(archive)
        if (!Archives.isZip(path)) throw FsException(Errno.EROFS, path, "only zip archives can be edited")
        ZipEditor(OsBackend).apply(path, edits())
    }

    /** EEXIST when [inner] is a file or folder in [archive] already (read from its index, no rewrite). */
    private fun checkFree(archive: String, inner: String) {
        val path = FsPath.normalize(archive)
        if (!Archives.isZip(path)) throw FsException(Errno.EROFS, path, "only zip archives can be edited")
        val names = Archives.open(File(path)).use { reader -> reader.entries.map { it.name } }
        if (ZipEditor.taken(names, inner)) throw FsException(Errno.EEXIST, inner.trim('/'))
    }

    /** Streams [input] into a reliable pipe from a thread; returns the read end. */
    private fun pipeFrom(input: java.io.InputStream, onDone: () -> Unit): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "cam-file-read") {
            try {
                input.use { it.copyTo(FileOutputStream(writeEnd.fileDescriptor), 256 * 1024) }
                writeEnd.close()
            } catch (e: Exception) {
                // not only IOException: a broken archive throws others, and an uncaught one
                // here would take the whole root process (and CamRootService) down
                runCatching { writeEnd.closeWithError(e.message ?: e.javaClass.simpleName) }
            } finally {
                runCatching { onDone() }
            }
        }
        return readEnd
    }

    private fun runOp(id: Int, op: FileOp, running: RunningOp, callback: IFileOpCallback) {
        val listener = object : OpListener {
            override fun onProgress(done: Long, total: Long, current: String) {
                runCatching { callback.onProgress(id, done, total, current) }
            }

            override fun onConflict(src: String, dst: String, srcIsDir: Boolean, dstIsDir: Boolean): Resolution {
                running.answers.clear()
                callback.onConflict(id, src, dst, srcIsDir, dstIsDir)
                while (!running.cancelled.get()) {
                    running.answers.poll(200, TimeUnit.MILLISECONDS)?.let { return it }
                }
                throw OpCancelledException()
            }
        }
        val cancelled = { running.cancelled.get() }
        val (result, errno, error) = try {
            val result = when (op.kind) {
                OpKind.EXTRACT -> {
                    val archive = Archives.split(op.sources.first()).first
                    Archives.open(File(FsPath.normalize(archive))).use { reader ->
                        ArchiveOps(OsBackend, listener, cancelled)
                            .extract(reader, op.sources.map { Archives.split(it).second }, op.destDir ?: throw FsException(Errno.EINVAL))
                    }
                }

                OpKind.TRASH -> Trash(OsBackend).put(op.sources, listener, cancelled)

                OpKind.RESTORE -> Trash(OsBackend).restore(op.sources.single(), listener, cancelled)

                OpKind.PURGE -> Trash(OsBackend).purge(op.sources, listener, cancelled)

                OpKind.BACKUP -> PartitionOps(listener, cancelled)
                    .backup(op.sources.single(), op.destDir ?: throw FsException(Errno.EINVAL))

                // sources: the image, then where the partition is backed up first (never optional)
                OpKind.FLASH -> PartitionOps(listener, cancelled).flash(
                    op.sources.first(),
                    op.destDir ?: throw FsException(Errno.EINVAL),
                    op.sources.getOrNull(1) ?: throw FsException(Errno.EINVAL, detail = "flash needs a backup path"),
                )

                OpKind.COMPRESS -> ArchiveOps(OsBackend, listener, cancelled)
                    .compress(op.sources, op.destDir ?: throw FsException(Errno.EINVAL))

                OpKind.INSTALL -> ApkInstaller.install(FsPath.normalize(op.sources.single()), OsBackend, listener, cancelled)

                // deleting inside a zip: rewrite it without those entries
                OpKind.DELETE if Archives.isInside(op.sources.first()) -> {
                    val archive = Archives.split(op.sources.first()).first
                    if (!Archives.isZip(archive)) throw FsException(Errno.EROFS, archive, "only zip archives can be edited")
                    ZipEditor(OsBackend, listener, cancelled)
                        .apply(FsPath.normalize(archive), ZipEditor.Edits(deletes = op.sources.map { Archives.split(it).second }.toSet()))
                }

                OpKind.COPY if op.destDir?.let(Archives::isInside) == true -> intoZip(op, listener, cancelled)

                OpKind.MOVE if op.destDir?.let(Archives::isInside) == true -> intoZip(op, listener, cancelled)

                else -> FileOpEngine(OsBackend, listener, cancelled).run(op)
            }
            Triple(result, 0, null)
        } catch (_: OpCancelledException) {
            Triple(null, -1, null)
        } catch (e: FsException) {
            Triple(null, e.errno, e.message)
        } catch (e: Exception) {
            Triple(null, Errno.EIO, e.message ?: e.javaClass.simpleName)
        }
        runCatching { callback.onDone(id, result?.items ?: 0, result?.skipped ?: 0, errno, error) }
    }
}
