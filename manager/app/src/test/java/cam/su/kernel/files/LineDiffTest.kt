package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LineDiffTest {

    /** Rebuilds b from a and the runs, checking every EQUAL line really is equal. */
    private fun apply(a: List<String>, b: List<String>, runs: List<LineDiff.Run>): List<String> {
        val out = mutableListOf<String>()
        var ai = 0
        var bi = 0
        for (run in runs) {
            when (run.op) {
                LineDiff.Op.EQUAL -> {
                    assertEquals(ai, run.aStart)
                    assertEquals(bi, run.bStart)
                    for (i in 0 until run.count) assertEquals(a[ai + i], b[bi + i])
                    out += a.subList(ai, ai + run.count)
                    ai += run.count
                    bi += run.count
                }
                LineDiff.Op.DELETE -> {
                    assertEquals(ai, run.aStart)
                    ai += run.count
                }
                LineDiff.Op.INSERT -> {
                    assertEquals(bi, run.bStart)
                    out += b.subList(bi, bi + run.count)
                    bi += run.count
                }
            }
        }
        assertEquals(a.size, ai)
        return out
    }

    private fun edits(runs: List<LineDiff.Run>) = runs.filter { it.op != LineDiff.Op.EQUAL }.sumOf { it.count }

    @Test
    fun shortestScriptForSmallChanges() {
        val a = "a b c d e f".split(' ')
        val b = "a c d x e f g".split(' ')
        val runs = LineDiff.diff(a, b)
        assertEquals(b, apply(a, b, runs))
        assertEquals(3, edits(runs)) // -b +x +g
        assertEquals(
            listOf(
                LineDiff.Run(LineDiff.Op.EQUAL, 0, 0, 1),
                LineDiff.Run(LineDiff.Op.DELETE, 1, 1, 1),
                LineDiff.Run(LineDiff.Op.EQUAL, 2, 1, 2),
                LineDiff.Run(LineDiff.Op.INSERT, 4, 3, 1),
                LineDiff.Run(LineDiff.Op.EQUAL, 4, 4, 2),
                LineDiff.Run(LineDiff.Op.INSERT, 6, 6, 1),
            ),
            runs,
        )
    }

    @Test
    fun emptyAndIdentical() {
        assertEquals(listOf(LineDiff.Run(LineDiff.Op.EQUAL, 0, 0, 2)), LineDiff.diff(listOf("x", "y"), listOf("x", "y")))
        assertEquals(listOf(LineDiff.Run(LineDiff.Op.INSERT, 0, 0, 2)), LineDiff.diff(emptyList(), listOf("x", "y")))
        assertEquals(listOf(LineDiff.Run(LineDiff.Op.DELETE, 0, 0, 1)), LineDiff.diff(listOf("x"), emptyList()))
        assertEquals(emptyList<LineDiff.Run>(), LineDiff.diff(emptyList(), emptyList()))
    }

    @Test
    fun randomEditsRoundTrip() {
        val random = Random(42)
        repeat(300) {
            val a = List(random.nextInt(0, 40)) { "l" + random.nextInt(0, 8) }
            val b = a.toMutableList()
            repeat(random.nextInt(0, 10)) {
                when (random.nextInt(3)) {
                    0 -> if (b.isNotEmpty()) b.removeAt(random.nextInt(b.size))
                    1 -> b.add(random.nextInt(b.size + 1), "n" + random.nextInt(0, 8))
                    else -> if (b.isNotEmpty()) b[random.nextInt(b.size)] = "c" + random.nextInt(0, 8)
                }
            }
            assertEquals(b, apply(a, b, LineDiff.diff(a, b)))
            // past the limit it gives up on the shortest script, never on a correct one
            assertEquals(b, apply(a, b, LineDiff.diff(a, b, maxD = 2)))
        }
    }

    @Test
    fun foldsLongEqualStretchesAndMarksChangedPart() {
        val a = (1..20).map { "line $it" }
        val b = a.toMutableList().also { it[9] = "line 10 changed" }
        val runs = LineDiff.diff(a, b)
        val rows = DiffView.rows(a, b, runs, context = 2)
        val folds = rows.filterIsInstance<DiffRow.Fold>()
        assertEquals(listOf(DiffRow.Fold(0, 0, 7), DiffRow.Fold(12, 12, 8)), folds)
        val changed = rows.filterIsInstance<DiffRow.Line>().filter { it.op != LineDiff.Op.EQUAL }
        assertEquals(listOf(10 to 0, 0 to 10), changed.map { it.aLine to it.bLine })
        assertNull(changed[0].mark) // "line 10" has nothing the new one lacks
        assertEquals(7..14, changed[1].mark) // " changed"
        assertEquals(DiffView.Summary(added = 1, deleted = 1, changes = 1), DiffView.summary(runs))
        assertEquals(listOf(3), DiffView.changeStarts(rows))
        // an expanded fold, or all, shows its lines
        assertEquals(1, DiffView.rows(a, b, runs, context = 2, expanded = setOf(0)).count { it is DiffRow.Fold })
        assertTrue(DiffView.rows(a, b, runs, context = 2, all = true).none { it is DiffRow.Fold })
    }

    @Test
    fun markKeepsSurrogatePairsWhole() {
        val (x, y) = DiffView.mark("a\uD83D\uDE00b", "a\uD83D\uDE01b")!!
        assertEquals(1..2, x)
        assertEquals(1..2, y)
        assertNull(DiffView.mark("abc", "xyz"))
    }
}
