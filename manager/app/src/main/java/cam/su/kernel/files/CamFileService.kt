package cam.su.kernel.files

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.topjohnwu.superuser.ipc.RootService
import rikka.parcelablelist.ParcelableListSlice
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
    private val ops = ConcurrentHashMap<Int, RunningOp>()

    private class RunningOp {
        val cancelled = AtomicBoolean(false)
        val answers = LinkedBlockingQueue<Resolution>()
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
            val input = fs.openRead(path)
            val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
            thread(name = "cam-file-read") {
                try {
                    input.use { it.copyTo(FileOutputStream(writeEnd.fileDescriptor)) }
                    writeEnd.close()
                } catch (e: IOException) {
                    runCatching { writeEnd.closeWithError(e.message ?: "read failed") }
                }
            }
            readEnd
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
        val engine = FileOpEngine(OsBackend, listener) { running.cancelled.get() }
        val (result, errno, error) = try {
            Triple(engine.run(op), 0, null)
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
