package cam.su.kernel.ui.webui.file

import android.webkit.JavascriptInterface
import cam.su.kernel.ui.util.getRootShell
import com.topjohnwu.superuser.io.SuFile
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

const val TAG = "CamIO"

// The same camd-backed shell as ksu.exec; libsu's default shell would run plain `su`, which
// fails when su compatibility is turned off.
internal fun rootFile(path: String) = SuFile(path).apply { shell = getRootShell(true) }

/**
 * `ksu.io()` of one module WebUI. One each, not one for the process (upstream's KsuIO): module
 * WebUIs run side by side in this process (WebUIActivity is documentLaunchMode=intoExisting), and
 * closing one must not close the streams another has open, nor count them against its limit.
 */
class CamIO {
    internal val openInputStreams = ConcurrentHashMap<String, ManagedInputStream>()
    internal val openOutputStreams = ConcurrentHashMap<String, OutputStream>()

    private val fileOutputStream = FileOutputStreamInterface(this)
    private val fileInputStream = FileInputStreamInterface(this)
    private val randomAccessFile = RandomAccessFileInterface()

    @JavascriptInterface
    fun version(): Int = KSU_IO_VERSION

    @JavascriptInterface
    fun File(path: String): FileInterface = FileInterface(path, this)

    @JavascriptInterface
    fun FileInputStream(): FileInputStreamInterface = fileInputStream

    @JavascriptInterface
    fun FileOutputStream(): FileOutputStreamInterface = fileOutputStream

    @JavascriptInterface
    fun RandomAccessFile(): RandomAccessFileInterface = randomAccessFile

    /**
     * Check if we can open a new stream without exceeding resource limits.
     * Returns true if under limit, false otherwise.
     */
    internal fun canOpenStream(): Boolean {
        return openInputStreams.size + openOutputStreams.size < MAX_OPEN_STREAMS
    }

    fun destroy() {
        fileInputStream.closeAll()
        fileOutputStream.closeAll()
        randomAccessFile.closeAll()
    }

    companion object {
        private const val KSU_IO_VERSION = 1
        private const val MAX_OPEN_STREAMS = 50
    }
}
