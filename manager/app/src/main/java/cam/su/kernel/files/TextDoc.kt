package cam.su.kernel.files

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * A text file as the editor holds it: always "\n" inside, with the file's own line ending,
 * BOM and charset put back on save, so editing one line of a CRLF file does not rewrite all.
 */
data class TextDoc(
    val text: String,
    val crlf: Boolean,
    val bom: Boolean,
    val charset: String,
) {
    fun encode(edited: String): ByteArray {
        val body = if (crlf) edited.replace("\n", "\r\n") else edited
        val bytes = body.toByteArray(Charset.forName(charset))
        return if (bom) UTF8_BOM + bytes else bytes
    }

    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** NUL bytes in the first 8 KB: an ELF, an image, a database... not text. */
        fun looksBinary(bytes: ByteArray): Boolean {
            val n = minOf(bytes.size, 8192)
            for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
            return false
        }

        /** UTF-8 when the bytes are valid UTF-8, else ISO-8859-1 so no byte is lost. */
        fun decode(bytes: ByteArray): TextDoc {
            val bom = bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]
            val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
            val (raw, charset) = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString() to "UTF-8"
            } catch (_: CharacterCodingException) {
                String(body, Charsets.ISO_8859_1) to "ISO-8859-1"
            }
            val crlf = raw.contains("\r\n")
            return TextDoc(if (crlf) raw.replace("\r\n", "\n") else raw, crlf, bom, charset)
        }
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
