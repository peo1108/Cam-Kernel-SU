package cam.su.kernel.files

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * Runs a shell script as root for the Files tab (MT's "Execute"), in the root process. The script
 * gets the environment module scripts get from camd (busybox ash standalone, KSU=true, Cam's bin
 * folder on PATH) so a module script behaves here as it will at boot, and its own session, so
 * Stop ends whatever it started too.
 */
class ScriptRunner(
    private val path: String,
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    @Volatile
    private var process: Process? = null

    /** The script's pid, which is also its process group (setsid); 0 until it is known. */
    @Volatile
    private var pid = 0

    @Volatile
    private var stopped = false

    fun start() {
        val busybox = File(BUSYBOX).takeIf { it.canExecute() }?.path
        val builder = ProcessBuilder(command(path, busybox))
            .directory(File(FsPath.parent(path)))
            .redirectErrorStream(true)
        builder.environment().putAll(environment(builder.environment()["PATH"]))
        val p = builder.start()
        process = p
        // no input: a script waiting on stdin reads EOF instead of hanging
        runCatching { p.outputStream.close() }
        val reader = InputStreamReader(p.inputStream, Charsets.UTF_8)
        val output = thread(name = "cam-script-out") {
            try {
                // first line: the pid the wrapper echoed before it became the script
                val first = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0 || c == '\n'.code) break
                    first.append(c.toChar())
                }
                pid = first.toString().trim().toIntOrNull() ?: 0
                if (stopped) kill()
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    onOutput(String(buffer, 0, n))
                }
            } catch (_: Exception) {
                // closed below, or the process went away
            }
        }
        thread(name = "cam-script-wait") {
            val code = p.waitFor()
            // A daemon the script left in the background keeps the pipe open: give the output a
            // moment to drain, then stop reading instead of waiting for the daemon.
            output.join(EXIT_GRACE_MS)
            if (output.isAlive) runCatching { p.inputStream.close() }
            onExit(code)
        }
    }

    /** SIGTERM to the whole session, SIGKILL to what is left a moment later. */
    fun stop() {
        stopped = true
        kill()
        thread(name = "cam-script-kill") {
            Thread.sleep(KILL_DELAY_MS)
            kill(OsConstants.SIGKILL)
            process?.destroyForcibly()
        }
    }

    private fun kill(signal: Int = OsConstants.SIGTERM) {
        val group = pid
        if (group > 0) runCatching { Os.kill(-group, signal) }
        if (signal == OsConstants.SIGTERM) process?.destroy()
    }

    companion object {
        const val BUSYBOX = "/data/adb/cam/bin/busybox"
        private const val BIN_DIR = "/data/adb/cam/bin"
        private const val EXIT_GRACE_MS = 300L
        private const val KILL_DELAY_MS = 800L

        /**
         * `sh -c` prints its pid, then becomes `setsid <interpreter> <script>`: setsid keeps the
         * pid (the wrapper is not a group leader), so that pid names the script's process group.
         */
        fun command(script: String, busybox: String?): List<String> {
            val interpreter = if (busybox != null) listOf(busybox, "sh") else listOf("/system/bin/sh")
            return listOf("/system/bin/sh", "-c", "echo \$\$; exec /system/bin/setsid \"\$0\" \"\$@\"") + interpreter + script
        }

        /** What camd gives module scripts (module.rs, get_common_script_envs). */
        fun environment(path: String?): Map<String, String> = mapOf(
            "ASH_STANDALONE" to "1",
            "KSU" to "true",
            "PATH" to listOfNotNull(path?.takeIf { it.isNotEmpty() } ?: "/system/bin:/system/xbin", BIN_DIR).joinToString(":"),
        )
    }
}
