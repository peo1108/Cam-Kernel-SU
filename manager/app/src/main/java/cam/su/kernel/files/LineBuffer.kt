package cam.su.kernel.files

/**
 * The text of the big-file editor: one row per line, edited one row at a time, so only the rows
 * on screen are ever laid out. A long line is cut every [width] characters into soft rows (no
 * newline between them), like [TextRows], so no row is megabytes long. Every change replaces a
 * range of rows and returns the [Edit] that made it; [apply] of its [Edit.inverse] undoes it.
 */
class LineBuffer(text: String, width: Int = TextRows.ROW_WIDTH) {
    /** [soft]: the rest of the line above, with no newline between them. */
    data class Row(val text: String, val soft: Boolean = false)

    data class Edit(val start: Int, val removed: List<Row>, val added: List<Row>) {
        fun inverse() = Edit(start, added, removed)
    }

    private val rows = ArrayList<Row>()

    /** Row to 1-based line (0 for a soft row), and line to its first row; rebuilt after a change of rows. */
    private var lineOfRow: IntArray? = null
    private var rowOfLine: IntArray? = null

    init {
        require(width > 1)
        var pos = 0
        while (true) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            var start = pos
            var soft = false
            while (nl - start > width) {
                var cut = start + width
                if (Character.isHighSurrogate(text[cut - 1])) cut--
                rows += Row(text.substring(start, cut), soft)
                soft = true
                start = cut
            }
            rows += Row(text.substring(start, nl), soft)
            if (nl >= text.length) break
            pos = nl + 1
        }
    }

    val size: Int get() = rows.size

    operator fun get(row: Int): Row = rows[row]

    /** The rows as they are now, to join off the main thread while editing goes on. */
    fun snapshot(): List<Row> = ArrayList(rows)

    fun text(): String = join(rows)

    private fun index() {
        if (lineOfRow != null) return
        val lines = IntArray(rows.size)
        var starts = IntArray(256)
        var n = 0
        for (i in rows.indices) {
            if (rows[i].soft && i > 0) continue
            if (n == starts.size) starts = starts.copyOf(n * 2)
            starts[n++] = i
            lines[i] = n
        }
        lineOfRow = lines
        rowOfLine = starts.copyOf(n)
    }

    /** 1-based line [row] starts, 0 when it is the rest of a cut line. */
    fun line(row: Int): Int {
        index()
        return lineOfRow!![row]
    }

    val lineCount: Int
        get() {
            index()
            return rowOfLine!!.size
        }

    /** First row of 1-based [line], clamped to the text. */
    fun rowOfLine(line: Int): Int {
        index()
        val starts = rowOfLine!!
        return starts[(line - 1).coerceIn(0, starts.size - 1)]
    }

    fun apply(edit: Edit) {
        val end = edit.start + edit.removed.size
        val range = rows.subList(edit.start, end)
        val sameShape = edit.removed.size == edit.added.size && edit.removed.indices.all { edit.removed[it].soft == edit.added[it].soft }
        range.clear()
        range.addAll(edit.added)
        if (!sameShape) {
            lineOfRow = null
            rowOfLine = null
        }
    }

    /** [row] now reads [content]; each newline in it starts a new line. */
    fun setRow(row: Int, content: String): Edit {
        val parts = content.split('\n')
        val added = listOf(Row(parts[0], rows[row].soft)) + parts.drop(1).map { Row(it) }
        return Edit(row, listOf(rows[row]), added).also(::apply)
    }

    /**
     * Backspace at the start of [row], which now reads [content]: it joins the row above, losing
     * the newline between them, or for a soft row the character before. Returns the edit and
     * where the cursor goes in the joined row; null on the first row.
     */
    fun joinUp(row: Int, content: String): Pair<Edit, Int>? {
        if (row <= 0) return null
        val above = rows[row - 1]
        val current = rows[row]
        var head = above.text
        if (current.soft && head.isNotEmpty()) {
            val cut = head.length - if (head.length >= 2 && Character.isLowSurrogate(head.last())) 2 else 1
            head = head.substring(0, cut)
        }
        val edit = Edit(row - 1, listOf(above, current), listOf(Row(head + content, above.soft)))
        apply(edit)
        return edit to head.length
    }

    /**
     * Next (or previous) match of [query] from column [col] of [row], wrapping around; a match
     * across the cut of a long line is not found. Returns the row and the match in it.
     */
    fun find(query: String, row: Int, col: Int, forward: Boolean, ignoreCase: Boolean = true): Pair<Int, IntRange>? {
        if (query.isEmpty() || rows.isEmpty()) return null
        val n = rows.size
        val first = row.coerceIn(0, n - 1)
        for (step in 0..n) {
            val r = if (forward) (first + step) % n else ((first - step) % n + n) % n
            val text = rows[r].text
            val i = when {
                step == 0 && forward -> text.indexOf(query, col.coerceIn(0, text.length), ignoreCase)
                step == 0 -> if (col <= 0) -1 else text.lastIndexOf(query, col - 1, ignoreCase)
                step == n && forward -> text.indexOf(query, 0, ignoreCase).takeIf { it in 0 until col }
                step == n -> text.lastIndexOf(query, text.length, ignoreCase).takeIf { it >= col }
                forward -> text.indexOf(query, 0, ignoreCase)
                else -> text.lastIndexOf(query, text.length, ignoreCase)
            } ?: -1
            if (i >= 0) return r to (i until i + query.length)
        }
        return null
    }

    /** Replaces every match; one edit over the rows from the first changed to the last. */
    fun replaceAll(query: String, replacement: String, ignoreCase: Boolean = true): Pair<Edit, Int>? {
        if (query.isEmpty()) return null
        var count = 0
        var first = -1
        var last = -1
        val changed = HashMap<Int, Row>()
        for (i in rows.indices) {
            val text = rows[i].text
            if (!text.contains(query, ignoreCase)) continue
            count += TextSearch.count(text, query, ignoreCase)
            changed[i] = rows[i].copy(text = text.replace(query, replacement, ignoreCase))
            if (first < 0) first = i
            last = i
        }
        if (first < 0) return null
        val removed = rows.subList(first, last + 1).toList()
        val added = (first..last).map { changed[it] ?: rows[it] }
        return Edit(first, removed, added).also(::apply) to count
    }

    companion object {
        fun join(rows: List<Row>): String {
            val sb = StringBuilder(rows.sumOf { it.text.length + 1 })
            rows.forEachIndexed { i, row ->
                if (i > 0 && !row.soft) sb.append('\n')
                sb.append(row.text)
            }
            return sb.toString()
        }
    }
}
