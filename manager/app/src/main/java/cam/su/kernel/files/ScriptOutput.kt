package cam.su.kernel.files

/**
 * What a script printed, as terminal lines: "\r" starts its line over (progress counters), ANSI
 * colors are dropped, and only the last [maxLines] lines are kept.
 */
class ScriptOutput(private val maxLines: Int = 5000) {
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()
    private var carriage = false

    /** Lines dropped from the top to stay under [maxLines]. */
    var dropped = 0
        private set

    fun append(text: String) {
        for (c in ANSI.replace(text, "")) {
            when (c) {
                '\n' -> {
                    lines.addLast(current.toString())
                    current.setLength(0)
                    carriage = false
                    while (lines.size >= maxLines) {
                        lines.removeFirst()
                        dropped++
                    }
                }
                '\r' -> carriage = true
                else -> {
                    if (carriage) {
                        current.setLength(0)
                        carriage = false
                    }
                    current.append(c)
                }
            }
        }
    }

    /** Every kept line, the unfinished last one included when it has text. */
    fun snapshot(): List<String> = if (current.isEmpty()) lines.toList() else lines + current.toString()

    fun text(): String = snapshot().joinToString("\n")

    private companion object {
        val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    }
}
