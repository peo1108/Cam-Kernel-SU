package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineBufferTest {

    @Test
    fun cutsLongLinesAndJoinsBack() {
        val text = "ab\nxxxxx\n\nend"
        val buffer = LineBuffer(text, width = 2)
        assertEquals(listOf("ab", "xx", "xx", "x", "", "en", "d"), (0 until buffer.size).map { buffer[it].text })
        assertEquals(listOf(1, 2, 0, 0, 3, 4, 0), (0 until buffer.size).map { buffer.line(it) })
        assertEquals(4, buffer.lineCount)
        assertEquals(4, buffer.rowOfLine(3))
        assertEquals(text, buffer.text())
        assertEquals("a\nb\n", LineBuffer("a\nb\n").text())
        assertEquals(3, LineBuffer("a\nb\n").lineCount)
    }

    @Test
    fun enterSplitsAndUndoRestores() {
        val buffer = LineBuffer("one\ntwo")
        val edit = buffer.setRow(0, "o\nne")
        assertEquals("o\nne\ntwo", buffer.text())
        assertEquals(3, buffer.lineCount)
        buffer.apply(edit.inverse())
        assertEquals("one\ntwo", buffer.text())
        assertEquals(2, buffer.lineCount)
        buffer.apply(edit)
        assertEquals("o\nne\ntwo", buffer.text())
    }

    @Test
    fun backspaceAtStartJoins() {
        val buffer = LineBuffer("one\ntwo")
        val (edit, cursor) = buffer.joinUp(1, "two")!!
        assertEquals("onetwo", buffer.text())
        assertEquals(3, cursor)
        buffer.apply(edit.inverse())
        assertEquals("one\ntwo", buffer.text())
        assertNull(buffer.joinUp(0, "one"))
        // a soft row has no newline before it: the character before goes instead
        val cut = LineBuffer("abcd", width = 2)
        val (_, at) = cut.joinUp(1, "cd")!!
        assertEquals("acd", cut.text())
        assertEquals(1, at)
        assertEquals(1, cut.size)
    }

    @Test
    fun findWrapsBothWays() {
        val buffer = LineBuffer("foo\nbar foo\nbaz")
        assertEquals(1 to (4..6), buffer.find("foo", 0, 1, forward = true))
        assertEquals(0 to (0..2), buffer.find("foo", 1, 5, forward = true)) // wraps
        assertEquals(0 to (0..2), buffer.find("FOO", 1, 4, forward = false))
        assertEquals(1 to (4..6), buffer.find("foo", 0, 0, forward = false)) // wraps back
        assertNull(buffer.find("zzz", 0, 0, forward = true))
    }

    @Test
    fun replaceAllIsOneUndoStep() {
        val buffer = LineBuffer("a=1\nb=2\na=3")
        val (edit, count) = buffer.replaceAll("a=", "x=")!!
        assertEquals(2, count)
        assertEquals("x=1\nb=2\nx=3", buffer.text())
        buffer.apply(edit.inverse())
        assertEquals("a=1\nb=2\na=3", buffer.text())
        assertNull(buffer.replaceAll("zz", "y"))
    }

    @Test
    fun keepsSoftFlagWhenEditingCutRow() {
        val buffer = LineBuffer("abcd", width = 2)
        buffer.setRow(1, "cX")
        assertTrue(buffer[1].soft)
        assertEquals("abcX", buffer.text())
        assertFalse(buffer[0].soft)
    }
}
