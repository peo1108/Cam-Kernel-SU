package cam.su.kernel.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import cam.su.kernel.camApp
import cam.su.kernel.files.FileChanges
import cam.su.kernel.files.FileOpProgress
import cam.su.kernel.files.TerminalSetup
import cam.su.kernel.ui.util.getCamDaemonPath
import cam.su.kernel.ui.util.getRootShell
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/**
 * One root terminal: a PTY session that outlives the view (rotation, keyboard), ended when the
 * screen goes away. The shell is root, so this app cannot signal it: ending goes through root.
 */
class TerminalViewModel : ViewModel() {
    var session: TerminalSession? = null
        private set
    private var view = WeakReference<TerminalView>(null)

    var title by mutableStateOf("")
        private set

    /** Exit status once the shell ended. */
    var exited by mutableStateOf<Int?>(null)
        private set

    /** The extra keys row's CTRL and ALT: held for the next key, as on Termux. */
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    private var cwd = ""

    /** Set by the screen: puts the theme's colors back after the shell reset the palette. */
    var onColorsReset: (() -> Unit)? = null

    fun attach(terminal: TerminalView) {
        view = WeakReference(terminal)
    }

    fun start(dir: String, script: String?) {
        if (session != null) return
        cwd = dir
        val rc = File(camApp.cacheDir, "terminal").apply { mkdirs() }.resolve("rc-${System.nanoTime()}.sh")
        rc.writeText(TerminalSetup.rc(dir, script, File(TerminalSetup.BUSYBOX).exists()))
        rc.setReadable(true, false)
        val camd = getCamDaemonPath()
        session = TerminalSession(
            camd,
            "/",
            arrayOf(camd, "debug", "su", "-g"),
            TerminalSetup.environment(System.getenv(), rc.path, System.getenv("PATH")),
            TRANSCRIPT_ROWS,
            client,
        )
    }

    val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            view.get()?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            title = changedSession.title.orEmpty()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            exited = finishedSession.exitStatus
            view.get()?.onScreenUpdated()
            // a script run here usually changed files
            FileChanges.notify(cwd)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val cm = camApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text.orEmpty()))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cm = camApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(camApp)?.toString() ?: return
            session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            onColorsReset?.invoke() ?: view.get()?.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            view.get()?.setTerminalCursorBlinkerState(state, true)
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "$tag: $message")
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(TAG, "$tag: $message")
        }

        override fun logInfo(tag: String?, message: String?) = Unit

        override fun logDebug(tag: String?, message: String?) = Unit

        override fun logVerbose(tag: String?, message: String?) = Unit

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "$tag: $message", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(TAG, tag, e)
        }
    }

    /** The clipboard into the terminal, as a paste (bracketed when the program asked for it). */
    fun paste() {
        val cm = camApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(camApp)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    /** Ctrl+C: SIGINT to what runs in the foreground, through the terminal itself. */
    fun interrupt() {
        session?.write(byteArrayOf(3), 0, 1)
    }

    override fun onCleared() {
        val s = session
        if (s != null && s.isRunning) {
            // The shell runs as root: signal from root (this uid cannot). Not just its process
            // group: mksh puts each background job in a group of its own, but all of them keep
            // the terminal's session id (field 6 of /proc/<pid>/stat, the 4th after the name).
            val pid = s.pid
            val each = "for d in /proc/[0-9]*; do [ \"\$(sed 's/.*) //' \$d/stat 2>/dev/null | cut -d' ' -f4)\" = $pid ] && kill -%s \${d#/proc/} 2>/dev/null; done"
            FileOpProgress.scope.launch(Dispatchers.IO) {
                runCatching { getRootShell().newJob().add(each.format("HUP"), "sleep 1", each.format("KILL")).exec() }
            }
        }
        File(camApp.cacheDir, "terminal").listFiles()?.forEach { it.delete() }
        super.onCleared()
    }

    private companion object {
        const val TAG = "CamTerminal"
        const val TRANSCRIPT_ROWS = 5000
    }
}
