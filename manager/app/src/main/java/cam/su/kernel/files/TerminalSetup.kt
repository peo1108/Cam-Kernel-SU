package cam.su.kernel.files

/**
 * What the Files tab's terminal starts with. The shell is `camd debug su -g`: root in the global
 * mount namespace, ending in an interactive `sh` (mksh) on the PTY. mksh runs the file named by
 * $ENV before its first prompt, which is where the terminal goes to its folder and, for "Run as
 * root", runs the script the way modules run (busybox ash), shows its exit code and stays open.
 */
object TerminalSetup {
    const val BUSYBOX = "/data/adb/cam/bin/busybox"

    /** Single quotes for sh, a quote inside closed, escaped and reopened. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun rc(cwd: String, script: String?, busybox: Boolean): String = buildString {
        appendLine("cd ${quote(cwd)} 2>/dev/null")
        // toybox ls only colors when asked
        appendLine("alias ls='ls --color=auto'")
        if (script != null) {
            val shell = if (busybox) "ASH_STANDALONE=1 $BUSYBOX sh" else "sh"
            appendLine("KSU=true $shell ${quote(script)}")
            // the exit code, green or red, then a prompt to look around or run it again
            appendLine("rc=\$?; if [ \$rc -eq 0 ]; then c=32; else c=31; fi")
            appendLine("printf '\\n\\033[%sm[exit %s]\\033[0m\\n' \$c \$rc; unset rc c")
        }
    }

    /** The environment: the app's own, plus what a terminal and a module script expect. */
    fun environment(base: Map<String, String>, rcFile: String, path: String?): Array<String> {
        val env = LinkedHashMap(base)
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["HOME"] = "/"
        env["ENV"] = rcFile
        env["KSU"] = "true"
        env["PATH"] = listOfNotNull(path?.takeIf { it.isNotEmpty() } ?: "/system/bin:/system/xbin", "/data/adb/cam/bin").joinToString(":")
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }
}
