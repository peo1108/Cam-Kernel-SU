package cam.su.kernel.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDocTest {

    @Test
    fun keepsCrlfAndBomOnSave() {
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a=1\r\nb=2\r\n".toByteArray()
        val doc = TextDoc.decode(original)
        assertEquals("a=1\nb=2\n", doc.text)
        assertTrue(doc.crlf && doc.bom)
        assertArrayEquals(original, doc.encode(doc.text))
        assertEquals("a=1\r\nb=3\r\n", String(doc.encode("a=1\nb=3\n")).removePrefix("﻿"))
    }

    @Test
    fun viewerRowsCutLongLinesAndKeepLineNumbers() {
        val text = "ab\nxxxxx\n\nend"
        val rows = TextRows(text, width = 2)
        val shown = (0 until rows.size).map { rows.line(it) to text.substring(rows.start(it), rows.end(it)) }
        assertEquals(
            listOf(1 to "ab", 2 to "xx", 0 to "xx", 0 to "x", 3 to "", 4 to "en", 0 to "d"),
            shown,
        )
        assertEquals(4, rows.lineCount)
        assertEquals(2, rows.rowOf(6)) // the second "xx"
        assertEquals(0, rows.rowOf(0))
        assertEquals(6, rows.rowOf(text.length))
        // an emoji is never cut in half
        val emoji = "a😀b"
        val cut = TextRows(emoji, width = 2)
        assertEquals(listOf("a", "😀", "b"), (0 until cut.size).map { emoji.substring(cut.start(it), cut.end(it)) })
        // a text ending with a newline has an empty last line, as editors show it
        assertEquals(3, TextRows("a\nb\n").lineCount)
    }

    @Test
    fun lfStaysLf() {
        val doc = TextDoc.decode("x\ny\n".toByteArray())
        assertFalse(doc.crlf || doc.bom)
        assertEquals("x\nz\n", String(doc.encode("x\nz\n")))
    }

    @Test
    fun invalidUtf8FallsBackWithoutLosingBytes() {
        val bytes = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())
        val doc = TextDoc.decode(bytes)
        assertEquals("ISO-8859-1", doc.charset)
        assertArrayEquals(bytes, doc.encode(doc.text))
        assertEquals("UTF-8", TextDoc.decode("Tiếng Việt".toByteArray()).charset)
    }

    @Test
    fun binaryDetection() {
        assertTrue(TextDoc.looksBinary(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0, 1)))
        assertFalse(TextDoc.looksBinary("#!/system/bin/sh\n".toByteArray()))
    }

    private fun spanned(text: String, lang: Highlighter.Lang) =
        Highlighter.spans(text, lang).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun highlightsShell() {
        val spans = spanned("if [ \"\$A\" = 1 ]; then echo hi # note\nfi", Highlighter.Lang.SHELL)
        assertTrue("if" to SpanKind.KEYWORD in spans)
        assertTrue("\"\$A\"" to SpanKind.STRING in spans)
        assertTrue("# note" to SpanKind.COMMENT in spans)
        assertTrue("1" to SpanKind.NUMBER in spans)
        assertTrue("fi" to SpanKind.KEYWORD in spans)
        // a keyword inside a string or comment stays part of it
        assertFalse(spanned("echo \"if then\" # do", Highlighter.Lang.SHELL).any { it.second == SpanKind.KEYWORD && it.first == "then" })
    }

    @Test
    fun highlightsPropXmlJson() {
        val prop = spanned("# c\nid=cam\nversionCode=12\n[sec]", Highlighter.Lang.PROP)
        assertTrue("# c" to SpanKind.COMMENT in prop)
        assertTrue("id" to SpanKind.KEY in prop)
        assertTrue("12" to SpanKind.NUMBER in prop)
        assertTrue("[sec]" to SpanKind.TAG in prop)
        val xml = spanned("<a b=\"1\"><!-- x --></a>", Highlighter.Lang.XML)
        assertTrue("<!-- x -->" to SpanKind.COMMENT in xml)
        assertTrue("\"1\"" to SpanKind.STRING in xml)
        val json = spanned("{\"k\": \"v\", \"n\": 2}", Highlighter.Lang.JSON)
        assertTrue("\"k\"" to SpanKind.KEY in json)
        assertTrue("\"v\"" to SpanKind.STRING in json)
        assertTrue("2" to SpanKind.NUMBER in json)
    }

    @Test
    fun picksLanguageByName() {
        assertEquals(Highlighter.Lang.SHELL, Highlighter.langFor("service.sh"))
        assertEquals(Highlighter.Lang.SHELL, Highlighter.langFor("post-fs-data"))
        assertEquals(Highlighter.Lang.PROP, Highlighter.langFor("module.prop"))
        assertEquals(Highlighter.Lang.XML, Highlighter.langFor("packages.xml"))
        assertEquals(Highlighter.Lang.PLAIN, Highlighter.langFor("notes.txt"))
    }

    @Test
    fun searchWrapsAndCounts() {
        val text = "abc ABC abc"
        assertEquals(0..2, TextSearch.next(text, "abc", 0))
        assertEquals(4..6, TextSearch.next(text, "abc", 1))
        assertEquals(0..2, TextSearch.next(text, "abc", 9)) // wraps
        assertEquals(4..6, TextSearch.previous(text, "abc", 8))
        assertEquals(8..10, TextSearch.previous(text, "abc", 0)) // wraps back
        assertNull(TextSearch.next(text, "zzz", 0))
        assertEquals(3, TextSearch.count(text, "abc"))
        assertEquals("x x x", TextSearch.replaceAll(text, "abc", "x"))
        assertEquals(1, TextSearch.count(text, "ABC", ignoreCase = false))
    }

    @Test
    fun lineStarts() {
        val text = "a\nbb\nccc"
        assertEquals(0, TextSearch.lineStart(text, 1))
        assertEquals(2, TextSearch.lineStart(text, 2))
        assertEquals(5, TextSearch.lineStart(text, 3))
        assertEquals(text.length, TextSearch.lineStart(text, 9))
    }
}
