package cam.su.kernel.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FileChanges
import cam.su.kernel.files.FsException
import cam.su.kernel.files.ScriptOutput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One run of a script (MT's "Execute"): its live output and how it ended. */
class ScriptViewModel : ViewModel() {
    var lines by mutableStateOf<List<String>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set

    /** Exit code once it ended; -1 when the root process died under it. */
    var exitCode by mutableStateOf<Int?>(null)
        private set
    var error by mutableStateOf<FsException?>(null)
        private set

    private var path = ""
    private var output = ScriptOutput()
    private var handle: CamFileClient.ScriptHandle? = null
    private var job: Job? = null

    fun start(file: String) {
        if (path == file) return
        path = file
        run()
    }

    fun run() {
        if (running) return
        output = ScriptOutput()
        lines = emptyList()
        exitCode = null
        error = null
        inputClosed = false
        running = true
        job = viewModelScope.launch {
            val h = try {
                CamFileClient.runScript(path)
            } catch (e: FsException) {
                error = e
                running = false
                return@launch
            }
            handle = h
            var dirty = false
            // output can come in thousands of small pieces: redraw at most every 80 ms
            val painter = launch {
                while (true) {
                    if (dirty) {
                        dirty = false
                        lines = output.snapshot()
                    }
                    delay(PAINT_INTERVAL_MS)
                }
            }
            h.events.collect { event ->
                when (event) {
                    is CamFileClient.ScriptEvent.Output -> {
                        output.append(event.text)
                        dirty = true
                    }

                    is CamFileClient.ScriptEvent.Exit -> exitCode = event.code
                }
            }
            painter.cancel()
            lines = output.snapshot()
            running = false
            handle = null
            // a script usually changes files: the Files tab reloads
            FileChanges.notify(path)
        }
    }

    /** EOF was sent: nothing more can be typed in this run. */
    var inputClosed by mutableStateOf(false)
        private set

    /**
     * One typed line to the script. It is shown in the output as a terminal echoes it: a pipe has
     * no echo of its own, and a prompt printed without a newline ("Name: ") gets the answer after it.
     */
    fun send(line: String) {
        val h = handle ?: return
        output.append(line + "\n")
        lines = output.snapshot()
        viewModelScope.launch {
            try {
                h.input(line + "\n")
            } catch (e: FsException) {
                error = e
            }
        }
    }

    fun endInput() {
        val h = handle ?: return
        inputClosed = true
        viewModelScope.launch { runCatching { h.endInput() } }
    }

    fun stop() {
        val h = handle ?: return
        viewModelScope.launch { runCatching { h.stop() } }
    }

    fun text() = output.text()

    override fun onCleared() {
        // leaving the screen ends the run (it asks first while the script runs)
        handle?.let { h -> cam.su.kernel.files.FileOpProgress.scope.launch { runCatching { h.stop() } } }
        super.onCleared()
    }

    private companion object {
        const val PAINT_INTERVAL_MS = 80L
    }
}
