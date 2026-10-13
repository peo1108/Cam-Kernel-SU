package cam.su.kernel.files

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import cam.su.kernel.camApp
import cam.su.kernel.ui.util.CamCli
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Keeps one binding to [CamFileService]. Every call runs on [Dispatchers.IO] and fails with
 * [FsException] (errno from the root side; EACCES when there is no root).
 */
object CamFileClient {
    private const val TAG = "CamFileClient"
    private const val BIND_TIMEOUT_MS = 15_000L

    private val mutex = Mutex()

    @Volatile
    private var bound: ICamFileService? = null

    private val service: ICamFileService?
        get() = bound?.takeIf { it.asBinder().isBinderAlive }

    suspend fun listDir(path: String): List<FileEntry> = call { it.listDir(path).list }

    suspend fun stat(path: String): FileEntry? = call { it.stat(path) }

    suspend fun mkdir(path: String) = call { it.mkdir(path) }

    suspend fun createFile(path: String) = call { it.createFile(path) }

    suspend fun symlink(target: String, link: String) = call { it.symlink(target, link) }

    suspend fun rename(from: String, to: String) = call { it.rename(from, to) }

    suspend fun chmod(path: String, mode: Int, recursive: Boolean = false) =
        call { it.chmod(path, mode, recursive) }

    suspend fun chown(path: String, uid: Int, gid: Int, recursive: Boolean = false) =
        call { it.chown(path, uid, gid, recursive) }

    suspend fun setContext(path: String, context: String, recursive: Boolean = false) =
        call { it.setContext(path, context, recursive) }

    /** Whole file; meant for the editor, which caps the size before calling. */
    suspend fun readBytes(path: String): ByteArray = call { s ->
        s.openRead(path).use { pfd ->
            val bytes = FileInputStream(pfd.fileDescriptor).readBytes()
            pfd.checkError()
            bytes
        }
    }

    /**
     * Streams what [writer] produces into [path]; the file is only replaced if [writer]
     * returns normally and the root side commits.
     */
    suspend fun write(path: String, writer: (OutputStream) -> Unit) = call { s ->
        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        val producer = thread(name = "cam-file-write") {
            try {
                FileOutputStream(writeEnd.fileDescriptor).let(writer)
                writeEnd.close()
            } catch (e: Throwable) {
                runCatching { writeEnd.closeWithError(e.message ?: "write failed") }
            }
        }
        try {
            s.writeFile(path, readEnd)
        } finally {
            // Our copy of the read end must go, or a producer the root side stopped
            // reading from would block forever on a full pipe.
            readEnd.close()
            producer.join()
        }
    }

    private suspend fun <T> call(block: (ICamFileService) -> T): T = withContext(Dispatchers.IO) {
        val s = service ?: connect() ?: throw FsException(Errno.EACCES, detail = "no root")
        try {
            block(s)
        } catch (e: IllegalStateException) {
            throw FsException.fromRemote(e) ?: e
        }
    }

    private suspend fun connect(): ICamFileService? = mutex.withLock {
        service?.let { return it }
        if (!CamCli.SHELL.isRoot) {
            Log.i(TAG, "no root, CamFileService not bound")
            return null
        }
        val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { bind() }
        if (binder == null) {
            Log.w(TAG, "bind CamFileService timed out")
            return null
        }
        ICamFileService.Stub.asInterface(binder).also { bound = it }
    }

    private suspend fun bind(): IBinder = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && cont.isActive) cont.resume(binder)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "CamFileService disconnected")
                    bound = null
                }
            }
            val intent = Intent(camApp, CamFileService::class.java)
            val task = RootService.bindOrTask(intent, Shell.EXECUTOR, connection)
            task?.let { CamCli.SHELL.execTask(it) }
        }
    }
}
