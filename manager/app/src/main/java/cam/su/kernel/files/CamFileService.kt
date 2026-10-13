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
    private val ops = ConcurrentHashMap<Int, RunningOp>()

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

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e.toRemote()
    } catch (e: IOException) {
        throw FsException(Errno.EIO, detail = e.message).toRemote()
    }

    private inner class Stub : ICamFileService.Stub() {
        override fun listDir(path: String): ParcelableListSlice<FileEntry> =
            call { ParcelableListSlice(fs.listDir(path)) }

        override fun stat(path: String): FileEntry? = call { fs.stat(path) }

        override fun statFs(path: String): LongArray = call { fs.statFs(path) }

        override fun checksums(path: String): Array<String> = call { fs.checksums(path).toTypedArray() }

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
            editZip(archive) { ZipEditor.Edits(renames = mapOf(from.trim('/') to to.trim('/'))) }
        }

        override fun archiveMkdir(archive: String, inner: String) = call {
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

        override fun installPackage(path: String): String = call { ApkInstaller.install(FsPath.normalize(path), OsBackend) }

        override fun measure(path: String): LongArray = call { fs.measure(path) }

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

    /** Streams [input] into a reliable pipe from a thread; returns the read end. */
    private fun pipeFrom(input: java.io.InputStream, onDone: () -> Unit): ParcelFileDescriptor {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "cam-file-read") {
            try {
                input.use { it.copyTo(FileOutputStream(writeEnd.fileDescriptor)) }
                writeEnd.close()
            } catch (e: IOException) {
                runCatching { writeEnd.closeWithError(e.message ?: "read failed") }
            } finally {
                onDone()
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

                OpKind.COMPRESS -> ArchiveOps(OsBackend, listener, cancelled)
                    .compress(op.sources, op.destDir ?: throw FsException(Errno.EINVAL))

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
