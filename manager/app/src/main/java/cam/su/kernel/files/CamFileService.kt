package cam.su.kernel.files

import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.topjohnwu.superuser.ipc.RootService
import rikka.parcelablelist.ParcelableListSlice
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * File access for the Files tab, in the root process (uid 0). Kept apart from
 * CamRootService, which only carries kernel supercalls.
 */
class CamFileService : RootService() {

    private val fs = RootFs(OsBackend)

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

        override fun writeFile(path: String, source: ParcelFileDescriptor) = call {
            source.use { pfd ->
                // checkError() turns a writer that gave up halfway into an IOException,
                // so a cut-off stream never replaces the file.
                fs.writeAtomic(path, FileInputStream(pfd.fileDescriptor)) { pfd.checkError() }
            }
        }
    }
}
