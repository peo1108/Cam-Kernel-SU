package cam.su.kernel.files

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.BitSet
import java.util.Locale

/** How lines end in a file; MIXED keeps each line's own ending on save. */
enum class LineEnding { LF, CRLF, MIXED }

/**
 * A text file as the editor holds it: always "\n" inside, with the file's own line endings, BOM
 * and charset put back on save, so editing one line of a CRLF file does not rewrite all. A file
 * with both endings keeps each unchanged line's own ending ([LineEnding.MIXED]).
 */
data class TextDoc(
    val text: String,
    val eol: LineEnding,
    val bom: Boolean,
    val charset: String,
    /** Bytes the charset could not read were replaced by U+FFFD: a save writes them changed. */
    val lossy: Boolean = false,
    /** For MIXED: the lines (0-based) that ended in CRLF. */
    private val crlfLines: BitSet? = null,
    /** For MIXED: most lines end in CRLF, so a new line does too. */
    private val mostlyCrlf: Boolean = false,
) {
    val crlf get() = eol == LineEnding.CRLF

    /**
     * The bytes to write for [edited]. Throws [CharacterCodingException] when [charset] cannot
     * write a character (Vietnamese in GBK), rather than writing '?' in its place.
     */
    fun encode(edited: String, eol: LineEnding = this.eol, charset: String = this.charset): ByteArray {
        val body = when (eol) {
            LineEnding.LF -> edited
            LineEnding.CRLF -> edited.replace("\n", "\r\n")
            LineEnding.MIXED -> keepEndings(edited)
        }.let { if (charset.equals(WINDOWS_1258, ignoreCase = true)) splitTones(it) else it }
        val out = Charset.forName(charset).newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(body))
        val bytes = ByteArray(out.remaining()).also { out.get(it) }
        val mark = if (bom) bomFor(charset) else null
        return if (mark != null) mark + bytes else bytes
    }

    /**
     * A line keeps the ending it had, found with [LineDiff] so moving lines around does not
     * matter; a line edited in place keeps the ending of the line it replaced. Only a new line
     * gets the ending most lines have, as does the old last line, which had none.
     */
    private fun keepEndings(edited: String): String {
        val before = text.split('\n')
        val after = edited.split('\n')
        fun had(line: Int) = if (line == before.lastIndex) mostlyCrlf else crlfLines?.get(line) == true
        val crlfAfter = BitSet()
        val runs = LineDiff.diff(before, after)
        runs.forEachIndexed { index, run ->
            // deleted lines right before inserted ones: the inserted ones are their new text
            val replaced = runs.getOrNull(index - 1)?.takeIf { run.op == LineDiff.Op.INSERT && it.op == LineDiff.Op.DELETE }
            for (i in 0 until run.count) {
                val crlf = when {
                    run.op == LineDiff.Op.DELETE -> continue
                    run.op == LineDiff.Op.EQUAL -> had(run.aStart + i)
                    replaced != null && i < replaced.count -> had(replaced.aStart + i)
                    else -> mostlyCrlf
                }
                if (crlf) crlfAfter.set(run.bStart + i)
            }
        }
        val sb = StringBuilder(edited.length + crlfAfter.cardinality())
        after.forEachIndexed { i, line ->
            if (i > 0) sb.append(if (crlfAfter[i - 1]) "\r\n" else "\n")
            sb.append(line)
        }
        return sb.toString()
    }

    companion object {
        private const val WINDOWS_1258 = "windows-1258"

        /** Grave, acute, tilde, hook above, dot below: the Vietnamese tones. */
        private const val TONES = "\u0300\u0301\u0303\u0309\u0323"

        /**
         * Windows-1258 has the vowels with circumflex, breve and horn but writes the tone as a
         * combining mark after them: one typed letter, e with circumflex and dot below, is saved
         * as e with circumflex, then the dot below.
         */
        private fun splitTones(text: String): String {
            val encoder = Charset.forName(WINDOWS_1258).newEncoder()
            val sb = StringBuilder(text.length + 16)
            for (c in text) {
                if (c.code < 0x80 || encoder.canEncode(c)) {
                    sb.append(c)
                    continue
                }
                val parts = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
                sb.append(Normalizer.normalize(parts.filterNot { it in TONES }, Normalizer.Form.NFC))
                sb.append(parts.filter { it in TONES })
            }
            return sb.toString()
        }

        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

        /**
         * What "Reopen with encoding" offers: the ones a rooted phone's files come in (Chinese
         * ROM configs in GBK, old Vietnamese text in Windows-1258...). Filtered by what the
         * runtime has; DeviceCheck checks Android has them all.
         */
        val CHARSETS = listOf(
            "UTF-8", "GBK", "GB18030", "Big5", "Shift_JIS", "EUC-KR", WINDOWS_1258, "windows-1252",
            "windows-1251", "KOI8-R", "ISO-8859-1", "UTF-16LE", "UTF-16BE",
        )

        fun charsets(): List<String> = CHARSETS.filter { runCatching { Charset.isSupported(it) }.getOrDefault(false) }

        /** UTF-16 text is half NUL bytes: not a sign of a binary file there. */
        fun isWide(charset: String) = charset.startsWith("UTF-16")

        private fun bomFor(charset: String): ByteArray? = when (charset) {
            "UTF-8" -> UTF8_BOM
            "UTF-16LE" -> UTF16LE_BOM
            "UTF-16BE" -> UTF16BE_BOM
            else -> null
        }

        private fun ByteArray.startsWith(prefix: ByteArray) =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        /** NUL bytes in the first 8 KB: an ELF, an image, a database... not text. */
        fun looksBinary(bytes: ByteArray): Boolean {
            val n = minOf(bytes.size, 8192)
            for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
            return false
        }

        /**
         * With no [charset]: a BOM's charset, else UTF-8 when the bytes are valid UTF-8, else
         * ISO-8859-1 so no byte is lost. With one, bytes it cannot read become U+FFFD ([lossy]).
         */
        fun decode(bytes: ByteArray, charset: String? = null): TextDoc {
            val marked = listOf("UTF-8" to UTF8_BOM, "UTF-16LE" to UTF16LE_BOM, "UTF-16BE" to UTF16BE_BOM)
                .firstOrNull { (name, mark) -> (charset == null || charset == name) && bytes.startsWith(mark) }
            val body = if (marked != null) bytes.copyOfRange(marked.second.size, bytes.size) else bytes
            val name = charset ?: marked?.first
            var lossy = false
            val (raw, used) = if (name != null) {
                try {
                    strict(body, name) to name
                } catch (_: CharacterCodingException) {
                    lossy = true
                    String(body, Charset.forName(name)) to name
                }
            } else {
                try {
                    strict(body, "UTF-8") to "UTF-8"
                } catch (_: CharacterCodingException) {
                    String(body, Charsets.ISO_8859_1) to "ISO-8859-1"
                }
            }
            var crlf = 0
            var lf = 0
            val crlfLines = BitSet()
            for (i in raw.indices) {
                if (raw[i] != '\n') continue
                if (i > 0 && raw[i - 1] == '\r') crlfLines.set(crlf + lf).also { crlf++ } else lf++
            }
            val eol = when {
                crlf == 0 -> LineEnding.LF
                lf == 0 -> LineEnding.CRLF
                else -> LineEnding.MIXED
            }
            val text = if (crlf > 0) raw.replace("\r\n", "\n") else raw
            return TextDoc(
                text = text,
                eol = eol,
                bom = marked != null,
                charset = used,
                lossy = lossy,
                crlfLines = crlfLines.takeIf { eol == LineEnding.MIXED },
                mostlyCrlf = crlf > lf,
            )
        }

        private fun strict(body: ByteArray, charset: String): String =
            Charset.forName(charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
    }
}

/**
 * Rows of the read-only viewer used for big and binary files: one per line, and a long line cut
 * every [width] characters, so no row is ever laid out whole when it is megabytes long.
 */
class TextRows(private val text: String, width: Int = ROW_WIDTH) {
    private val starts: IntArray

    /** 1-based line number of each row; 0 for the rest of a cut line. */
    private val lines: IntArray

    init {
        require(width > 1)
        var s = IntArray(256)
        var l = IntArray(256)
        var n = 0
        fun add(start: Int, line: Int) {
            if (n == s.size) {
                s = s.copyOf(n * 2)
                l = l.copyOf(n * 2)
            }
            s[n] = start
            l[n] = line
            n++
        }
        var line = 1
        var pos = 0
        while (true) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            var start = pos
            add(start, line)
            while (nl - start > width) {
                var cut = start + width
                // never between the two halves of a surrogate pair
                if (Character.isHighSurrogate(text[cut - 1])) cut--
                start = cut
                add(start, 0)
            }
            if (nl >= text.length) break
            pos = nl + 1
            line++
        }
        starts = s.copyOf(n)
        lines = l.copyOf(n)
    }

    val size: Int get() = starts.size

    /** Lines in the text (the viewer's status line). */
    val lineCount: Int get() = lines.last { it > 0 }

    fun start(row: Int) = starts[row]

    /** End of [row], its newline left out. */
    fun end(row: Int): Int = when {
        row + 1 >= starts.size -> text.length
        lines[row + 1] == 0 -> starts[row + 1]
        else -> starts[row + 1] - 1
    }

    fun line(row: Int) = lines[row]

    /** The row holding [offset]. */
    fun rowOf(offset: Int): Int {
        val i = starts.binarySearch(offset.coerceIn(0, text.length))
        return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
    }

    companion object {
        const val ROW_WIDTH = 2000
    }
}

/** One colored run for the editor. */
data class Span(val start: Int, val end: Int, val kind: SpanKind)

enum class SpanKind { COMMENT, STRING, KEYWORD, NUMBER, KEY, TAG, VARIABLE }

/**
 * Small regex highlighter for what gets edited on a rooted phone: shell scripts, .prop / .rc /
 * .conf files, XML and JSON. Not a parser; good enough to read by.
 *
 * Android compiles these with ICU, which rejects a bare `]` or `}` the JVM accepts (so the JVM
 * tests pass anyway): escape them. DeviceCheck runs every language on a device.
 */
object Highlighter {
    enum class Lang { SHELL, PROP, XML, JSON, PLAIN }

    fun langFor(name: String): Lang {
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")
        return when {
            ext == "sh" || ext == "rc" || ext == "bash" || ('.' !in lower && lower.isNotEmpty()) -> Lang.SHELL
            ext in setOf("prop", "conf", "cfg", "ini", "properties", "toml") -> Lang.PROP
            ext in setOf("xml", "html", "svg") -> Lang.XML
            ext == "json" -> Lang.JSON
            else -> Lang.PLAIN
        }
    }

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
        "function", "return", "export", "local", "readonly", "exit", "break", "continue", "set", "unset",
        "echo", "source", "trap", "shift", "true", "false", "on", "service", "import", "mkdir", "chmod",
        "chown", "write", "start", "stop", "setprop",
    )

    private val shellRules = listOf(
        Regex("""(?m)(^|[ \t;])#.*$""") to SpanKind.COMMENT,
        Regex(""""(?:\\.|[^"\\])*"|'[^']*'""") to SpanKind.STRING,
        Regex("""\$\{[^\}]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\$[0-9@#?*!$-]""") to SpanKind.VARIABLE,
        Regex("""\b[0-9]+\b""") to SpanKind.NUMBER,
    )
    private val shellWord = Regex("""\b[a-z_]+\b""")

    private val propRules = listOf(
        Regex("""(?m)^[ \t]*[#;].*$""") to SpanKind.COMMENT,
        Regex("""(?m)^[ \t]*\[[^\]\n]*\]""") to SpanKind.TAG,
        Regex("""(?m)^[ \t]*[^=\n#;\[]+(?==)""") to SpanKind.KEY,
        Regex("""\b(?:true|false)\b|\b[0-9]+\b""") to SpanKind.NUMBER,
    )

    private val xmlRules = listOf(
        Regex("""<!--[\s\S]*?-->""") to SpanKind.COMMENT,
        Regex(""""[^"]*"|'[^']*'""") to SpanKind.STRING,
        Regex("""</?[A-Za-z_][\w:.-]*|/?>""") to SpanKind.TAG,
        Regex("""\b[A-Za-z_][\w:.-]*(?==)""") to SpanKind.KEY,
    )

    private val jsonRules = listOf(
        Regex(""""(?:\\.|[^"\\])*"(?=\s*:)""") to SpanKind.KEY,
        Regex(""""(?:\\.|[^"\\])*"""") to SpanKind.STRING,
        Regex("""\b(?:true|false|null)\b|-?\b[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\b""") to SpanKind.NUMBER,
    )

    /** Non-overlapping spans in order; a rule earlier in the list wins over later ones. */
    fun spans(text: String, lang: Lang): List<Span> {
        val rules = when (lang) {
            Lang.SHELL -> shellRules
            Lang.PROP -> propRules
            Lang.XML -> xmlRules
            Lang.JSON -> jsonRules
            Lang.PLAIN -> return emptyList()
        }
        val taken = BooleanArray(text.length)
        val out = mutableListOf<Span>()
        fun claim(start: Int, end: Int, kind: SpanKind) {
            if (start >= end) return
            for (i in start until end) if (taken[i]) return
            for (i in start until end) taken[i] = true
            out += Span(start, end, kind)
        }
        for ((regex, kind) in rules) {
            for (m in regex.findAll(text)) {
                var start = m.range.first
                // the comment rule also matched the space before '#'
                if (kind == SpanKind.COMMENT && lang == Lang.SHELL) start = text.indexOf('#', start)
                claim(start, m.range.last + 1, kind)
            }
        }
        if (lang == Lang.SHELL) {
            for (m in shellWord.findAll(text)) if (m.value in SHELL_KEYWORDS) claim(m.range.first, m.range.last + 1, SpanKind.KEYWORD)
        }
        return out.sortedBy { it.start }
    }
}

/** Find / replace for the editor; plain text, case-insensitive unless asked. */
object TextSearch {
    fun next(text: String, query: String, from: Int, ignoreCase: Boolean = true): IntRange? {
        if (query.isEmpty()) return null
        val i = text.indexOf(query, from.coerceIn(0, text.length), ignoreCase).let { if (it < 0) text.indexOf(query, 0, ignoreCase) else it }
        return if (i < 0) null else i until i + query.length
    }

    fun previous(text: String, query: String, before: Int, ignoreCase: Boolean = true): IntRange? {
        if (query.isEmpty()) return null
        val start = (before - 1).coerceAtLeast(-1)
        val i = if (start < 0) -1 else text.lastIndexOf(query, start, ignoreCase)
        val found = if (i < 0) text.lastIndexOf(query, text.length, ignoreCase) else i
        return if (found < 0) null else found until found + query.length
    }

    fun count(text: String, query: String, ignoreCase: Boolean = true): Int {
        if (query.isEmpty()) return 0
        var n = 0
        var i = text.indexOf(query, 0, ignoreCase)
        while (i >= 0) {
            n++
            i = text.indexOf(query, i + query.length, ignoreCase)
        }
        return n
    }

    fun replaceAll(text: String, query: String, replacement: String, ignoreCase: Boolean = true): String =
        if (query.isEmpty()) text else text.replace(query, replacement, ignoreCase)

    /** Offset where 1-based [line] starts, clamped to the text. */
    fun lineStart(text: String, line: Int): Int {
        var current = 1
        var i = 0
        while (current < line) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) return text.length
            i = nl + 1
            current++
        }
        return i
    }
}
