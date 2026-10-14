package cam.su.kernel.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun mixedEndingsKeepEachLinesOwn() {
        val original = "a\r\nb\nc\r\nd\r\ne".toByteArray()
        val doc = TextDoc.decode(original)
        assertEquals(LineEnding.MIXED, doc.eol)
        assertEquals("a\nb\nc\nd\ne", doc.text)
        assertArrayEquals(original, doc.encode(doc.text))
        // b edited keeps its LF; a line added after d gets CRLF (most lines)
        assertEquals("a\r\nB\nc\r\nd\r\nnew\r\ne", String(doc.encode("a\nB\nc\nd\nnew\ne")))
        // the old last line had no ending: a line added after it gets the usual one
        assertEquals("a\r\nb\nc\r\nd\r\ne\r\nmore", String(doc.encode("a\nb\nc\nd\ne\nmore")))
        // a line moved keeps its ending too
        assertEquals("b\na\r\nc\r\nd\r\ne", String(doc.encode("b\na\nc\nd\ne")))
        // or all one way
        assertEquals("a\nb\nc\nd\ne", String(doc.encode(doc.text, LineEnding.LF)))
        assertEquals("a\r\nb\r\nc\r\nd\r\ne", String(doc.encode(doc.text, LineEnding.CRLF)))
    }

    @Test
    fun reopensInAnotherCharsetAndConverts() {
        val gbk = "中文=1\n".toByteArray(charset("GBK"))
        // GBK bytes are not valid UTF-8: the guess falls back to Latin-1
        assertEquals("ISO-8859-1", TextDoc.decode(gbk).charset)
        val doc = TextDoc.decode(gbk, "GBK")
        assertEquals("中文=1\n", doc.text)
        assertFalse(doc.lossy)
        assertArrayEquals(gbk, doc.encode(doc.text))
        assertEquals("中文=1\n", String(doc.encode(doc.text, charset = "UTF-8"), Charsets.UTF_8))
        // Windows-1258 writes Vietnamese as a base letter and a combining tone mark
        val vi = "Tiếng Việt".toByteArray(charset("windows-1258"))
        assertEquals("Tiếng Việt", TextDoc.decode(vi, "windows-1258").text.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) })
        // typed (precomposed) Vietnamese saves too: the tones become combining marks
        val typed = TextDoc.decode(vi, "windows-1258").encode("Tiếng Việt")
        assertArrayEquals(vi, typed)
        // bytes the charset cannot read are marked lossy
        assertTrue(TextDoc.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41), "UTF-8").lossy)
    }

    @Test
    fun refusesCharactersTheCharsetCannotWrite() {
        val doc = TextDoc.decode("a\n".toByteArray(charset("GBK")), "GBK")
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { doc.encode("Tiếng Việt") }
        // Latin-1 (the fallback) cannot hold Vietnamese either
        val latin = TextDoc.decode(byteArrayOf('a'.code.toByte(), 0xFF.toByte()))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { latin.encode("ệ") }
    }

    @Test
    fun utf16WithBomIsText() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\r\nb".toByteArray(Charsets.UTF_16LE)
        val doc = TextDoc.decode(bytes)
        assertEquals("UTF-16LE", doc.charset)
        assertTrue(doc.bom && doc.crlf)
        assertEquals("a\nb", doc.text)
        assertArrayEquals(bytes, doc.encode(doc.text))
        assertTrue(TextDoc.isWide(doc.charset))
        assertTrue("GBK" in TextDoc.charsets() && "windows-1258" in TextDoc.charsets())
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
