package cam.su.kernel.files

/**
 * Line diff for the compare screen, and for saving a file with mixed line endings: Myers'
 * O((N+M)D) algorithm on the lines between the common head and tail. Past [MAX_D] differences
 * it stops looking for the shortest script and reports the rest as one replaced block: still a
 * correct diff, so a file rewritten end to end costs no more than one barely touched.
 */
object LineDiff {
    enum class Op { EQUAL, DELETE, INSERT }

    /** [count] lines from a[[aStart]] (DELETE, EQUAL) and b[[bStart]] (INSERT, EQUAL). */
    data class Run(val op: Op, val aStart: Int, val bStart: Int, val count: Int)

    const val MAX_D = 1024

    fun diff(a: List<String>, b: List<String>, maxD: Int = MAX_D): List<Run> {
        // lines as ints: the inner loop compares numbers, not strings
        val ids = HashMap<String, Int>()
        val x = IntArray(a.size) { ids.getOrPut(a[it]) { ids.size } }
        val y = IntArray(b.size) { ids.getOrPut(b[it]) { ids.size } }
        var head = 0
        while (head < x.size && head < y.size && x[head] == y[head]) head++
        var tail = 0
        while (tail < x.size - head && tail < y.size - head && x[x.size - 1 - tail] == y[y.size - 1 - tail]) tail++

        val out = RunBuilder()
        out.add(Op.EQUAL, 0, 0, head)
        middle(x, head, x.size - tail, y, head, y.size - tail, maxD, out)
        out.add(Op.EQUAL, x.size - tail, y.size - tail, tail)
        return out.runs
    }

    private class RunBuilder {
        val runs = mutableListOf<Run>()

        fun add(op: Op, aStart: Int, bStart: Int, count: Int) {
            if (count <= 0) return
            val last = runs.lastOrNull()
            if (last != null && last.op == op && last.aStart + (if (op == Op.INSERT) 0 else last.count) == aStart &&
                last.bStart + (if (op == Op.DELETE) 0 else last.count) == bStart
            ) {
                runs[runs.lastIndex] = last.copy(count = last.count + count)
            } else {
                runs += Run(op, aStart, bStart, count)
            }
        }
    }

    private fun middle(x: IntArray, a0: Int, a1: Int, y: IntArray, b0: Int, b1: Int, maxD: Int, out: RunBuilder) {
        val n = a1 - a0
        val m = b1 - b0
        if (n == 0 || m == 0) {
            out.add(Op.DELETE, a0, b0, n)
            out.add(Op.INSERT, a1, b0, m)
            return
        }
        val limit = minOf(n + m, maxD)
        val offset = limit + 1
        val v = IntArray(2 * limit + 3)
        // v after each round d, for k in -d..d: what the walk back reads
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..limit) {
            for (k in -d..d step 2) {
                var px = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var py = px - k
                while (px < n && py < m && x[a0 + px] == y[b0 + py]) {
                    px++
                    py++
                }
                v[offset + k] = px
                if (px >= n && py >= m) {
                    found = d
                    break@search
                }
            }
            trace += v.copyOfRange(offset - d, offset + d + 1)
        }
        if (found < 0) {
            // too different to be worth the search: one replaced block
            out.add(Op.DELETE, a0, b0, n)
            out.add(Op.INSERT, a1, b0, m)
            return
        }
        // walk back from (n, m), collecting runs in reverse
        val back = mutableListOf<Run>()
        var px = n
        var py = m
        for (d in found downTo 1) {
            val prev = trace[d - 1] // k = -(d-1)..(d-1) at index k + d - 1
            fun at(k: Int) = prev[k + d - 1]
            val k = px - py
            val down = k == -d || (k != d && at(k - 1) < at(k + 1))
            val prevK = if (down) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            // one line inserted (down) or deleted (right), then a snake of equal lines to (px, py)
            val startX = if (down) prevX else prevX + 1
            val startY = if (down) prevY + 1 else prevY
            back += Run(Op.EQUAL, a0 + startX, b0 + startY, px - startX)
            back += Run(if (down) Op.INSERT else Op.DELETE, a0 + prevX, b0 + prevY, 1)
            px = prevX
            py = prevY
        }
        // round 0 is a snake from the start
        back += Run(Op.EQUAL, a0, b0, px)
        for (run in back.asReversed()) out.add(run.op, run.aStart, run.bStart, run.count)
    }
}

/** A row of the compare screen. */
sealed interface DiffRow {
    /** [aLine] and [bLine] are 1-based, 0 on the side without the line; [mark] is what changed in it. */
    data class Line(val op: LineDiff.Op, val aLine: Int, val bLine: Int, val text: String, val mark: IntRange? = null) : DiffRow

    /** [count] equal lines left out, from a[[aStart]] and b[[bStart]]. */
    data class Fold(val aStart: Int, val bStart: Int, val count: Int) : DiffRow
}

object DiffView {
    data class Summary(val added: Int, val deleted: Int, val changes: Int)

    fun summary(runs: List<LineDiff.Run>): Summary {
        var changes = 0
        var previousEqual = true
        for (run in runs) {
            val equal = run.op == LineDiff.Op.EQUAL
            if (!equal && previousEqual) changes++
            previousEqual = equal
        }
        return Summary(
            added = runs.filter { it.op == LineDiff.Op.INSERT }.sumOf { it.count },
            deleted = runs.filter { it.op == LineDiff.Op.DELETE }.sumOf { it.count },
            changes = changes,
        )
    }

    /**
     * The changes with [context] equal lines around each; longer equal stretches fold into one
     * [DiffRow.Fold] unless [all], or unless their first hidden line (a index) is in [expanded].
     * A deleted line followed by an inserted one has the part that changed marked in both.
     */
    fun rows(
        a: List<String>,
        b: List<String>,
        runs: List<LineDiff.Run>,
        context: Int = 3,
        expanded: Set<Int> = emptySet(),
        all: Boolean = false,
    ): List<DiffRow> {
        val out = mutableListOf<DiffRow>()
        fun equal(from: Int, to: Int, run: LineDiff.Run) {
            for (i in from until to) out += DiffRow.Line(LineDiff.Op.EQUAL, run.aStart + i + 1, run.bStart + i + 1, a[run.aStart + i])
        }
        var i = 0
        while (i < runs.size) {
            val run = runs[i]
            when (run.op) {
                LineDiff.Op.EQUAL -> {
                    val lead = if (i == 0) 0 else context
                    val trail = if (i == runs.lastIndex) 0 else context
                    val hidden = run.count - lead - trail
                    if (all || hidden <= 1 || (run.aStart + lead) in expanded) {
                        equal(0, run.count, run)
                    } else {
                        equal(0, lead, run)
                        out += DiffRow.Fold(run.aStart + lead, run.bStart + lead, hidden)
                        equal(run.count - trail, run.count, run)
                    }
                }
                LineDiff.Op.DELETE -> {
                    val insert = runs.getOrNull(i + 1)?.takeIf { it.op == LineDiff.Op.INSERT }
                    val marks = (0 until run.count).map { k ->
                        insert?.takeIf { k < it.count }?.let { mark(a[run.aStart + k], b[it.bStart + k]) }
                    }
                    for (k in 0 until run.count) {
                        out += DiffRow.Line(LineDiff.Op.DELETE, run.aStart + k + 1, 0, a[run.aStart + k], marks[k]?.first)
                    }
                    if (insert != null) {
                        for (k in 0 until insert.count) {
                            out += DiffRow.Line(LineDiff.Op.INSERT, 0, insert.bStart + k + 1, b[insert.bStart + k], marks.getOrNull(k)?.second)
                        }
                        i++
                    }
                }
                LineDiff.Op.INSERT -> for (k in 0 until run.count) {
                    out += DiffRow.Line(LineDiff.Op.INSERT, 0, run.bStart + k + 1, b[run.bStart + k])
                }
            }
            i++
        }
        return out
    }

    /** What differs between two versions of a line: the part between their common ends. */
    fun mark(x: String, y: String): Pair<IntRange?, IntRange?>? {
        var head = 0
        while (head < x.length && head < y.length && x[head] == y[head]) head++
        var tail = 0
        while (tail < x.length - head && tail < y.length - head && x[x.length - 1 - tail] == y[y.length - 1 - tail]) tail++
        // never between the two halves of a surrogate pair
        if (head > 0 && Character.isHighSurrogate(x[head - 1])) head--
        if (tail > 0 && Character.isLowSurrogate(x[x.length - tail])) tail--
        // nothing in common: the whole line changed, marking it says nothing more
        if (head == 0 && tail == 0) return null
        fun range(s: String) = (head until s.length - tail).takeIf { !it.isEmpty() }
        return range(x) to range(y)
    }

    /** Rows where a block of changes starts, for the next / previous buttons. */
    fun changeStarts(rows: List<DiffRow>): List<Int> = rows.indices.filter { i ->
        val row = rows[i]
        row is DiffRow.Line && row.op != LineDiff.Op.EQUAL &&
            (i == 0 || (rows[i - 1] as? DiffRow.Line)?.op.let { it == null || it == LineDiff.Op.EQUAL })
    }
}
