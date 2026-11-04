package cam.su.kernel.ui.viewmodel

import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.Errno
import cam.su.kernel.files.FileChanges
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.Highlighter
import cam.su.kernel.files.LineBuffer
import cam.su.kernel.files.LineEnding
import cam.su.kernel.files.TextDoc
import cam.su.kernel.files.TextRows
import cam.su.kernel.files.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException

/** Why a file is open read-only (or not at all). */
enum class EditorLimit { NONE, ARCHIVE, BINARY, TOO_LARGE }

sealed interface EditorMessage {
    data object Saved : EditorMessage
    data class Failed(val errno: Int, val detail: String?) : EditorMessage
    data object NotFound : EditorMessage

    /** The text has characters [charset] cannot write. */
    data class Unmappable(val charset: String) : EditorMessage
}

/**
 * MT's text editor: load as root, edit, save atomically keeping the file's mode, owner and label.
 * Up to [MAX_EDIT] the text is one text field; above it, a [LineBuffer] edited one row at a time
 * ([buffer], [editRow]), since one field relayouts the whole text on every keystroke.
 */
class TextEditorViewModel : ViewModel() {
    var path by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<Int?>(null)
        private set
    var limit by mutableStateOf(EditorLimit.NONE)
        private set
    var doc by mutableStateOf<TextDoc?>(null)
        private set
    var value by mutableStateOf(TextFieldValue(""))
        private set
    var saved by mutableStateOf("")
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<EditorMessage?>(null)
    var lang by mutableStateOf(Highlighter.Lang.PLAIN)
        private set

    /** What a save writes: the file's own until "Save with encoding" or "Line ending" changes them. */
    var saveCharset by mutableStateOf("UTF-8")
        private set
    var saveEol by mutableStateOf(LineEnding.LF)
        private set

    /** Set for binary files and tar entries: shown read-only by the row viewer. */
    var rows by mutableStateOf<TextRows?>(null)
        private set

    /** Set for big files: edited one row at a time. */
    var buffer by mutableStateOf<LineBuffer?>(null)
        private set

    /** Bumped on every change of [buffer], which is not snapshot state itself. */
    var version by mutableIntStateOf(0)
        private set

    /** The row being edited (-1: none) and its field, which starts with [SENTINEL]. */
    var editRow by mutableIntStateOf(-1)
        private set
    var rowValue by mutableStateOf(TextFieldValue(SENTINEL))
        private set

    /** The current search match in [buffer]: row and range. */
    var match by mutableStateOf<Pair<Int, IntRange>?>(null)
        private set

    val readOnly get() = limit != EditorLimit.NONE

    private var currentId by mutableLongStateOf(0L)
    private var savedId by mutableLongStateOf(0L)

    // derived: read by the title, the back handler and the save button on every frame
    val textModified by derivedStateOf { doc != null && if (buffer != null) currentId != savedId else value.text != saved }
    val modified by derivedStateOf { textModified || (doc != null && (saveCharset != doc!!.charset || saveEol != doc!!.eol)) }
    val lineCount by derivedStateOf {
        version
        buffer?.lineCount ?: rows?.lineCount ?: (value.text.count { it == '\n' } + 1)
    }
    val highlight get() = value.text.length <= HIGHLIGHT_LIMIT

    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastPush = 0L

    private class Step(val id: Long, val edit: LineBuffer.Edit, val time: Long)

    private val rowUndo = ArrayDeque<Step>()
    private val rowRedo = ArrayDeque<Step>()
    private var nextId = 0L

    val canUndo get() = if (buffer != null) version >= 0 && rowUndo.isNotEmpty() else undo.isNotEmpty()
    val canRedo get() = if (buffer != null) version >= 0 && rowRedo.isNotEmpty() else redo.isNotEmpty()

    fun load(file: String) {
        if (path == file) return
        path = file
        lang = Highlighter.langFor(FsPath.name(file.substringAfterLast(Archives.SEP)))
        open(null)
    }

    /** "Reopen with encoding": reads the file again as [charset], dropping unsaved edits. */
    fun reopen(charset: String) {
        if (loading) return
        open(charset)
    }

    private fun open(charset: String?) {
        val file = path
        loading = true
        loadError = null
        viewModelScope.launch {
            try {
                val inArchive = Archives.isInside(file)
                if (!inArchive) {
                    val entry = CamFileClient.stat(file) ?: run {
                        loadError = Errno.ENOENT
                        return@launch
                    }
                    // a link's own size is the length of its target's name
                    if (entry.contentSize > MAX_OPEN) {
                        limit = EditorLimit.TOO_LARGE
                        return@launch
                    }
                }
                // capped as it is read too: an archive entry, or a /proc file that says 0 bytes
                val bytes = CamFileClient.readBytes(file, MAX_OPEN)
                val text = withContext(Dispatchers.Default) { TextDoc.decode(bytes, charset) }
                val readOnlyBecause = when {
                    // zips are edited in place; tar archives are read-only
                    inArchive && !Archives.isZip(Archives.split(file).first) -> EditorLimit.ARCHIVE
                    TextDoc.looksBinary(bytes) && !TextDoc.isWide(text.charset) -> EditorLimit.BINARY
                    else -> EditorLimit.NONE
                }
                val big = bytes.size > MAX_EDIT
                // one text field cannot lay out megabytes (or binary with no line breaks) smoothly
                rows = if (readOnlyBecause == EditorLimit.BINARY || (big && readOnlyBecause != EditorLimit.NONE)) {
                    withContext(Dispatchers.Default) { TextRows(text.text) }
                } else {
                    null
                }
                buffer = if (big && rows == null) withContext(Dispatchers.Default) { LineBuffer(text.text) } else null
                undo.clear()
                redo.clear()
                rowUndo.clear()
                rowRedo.clear()
                currentId = 0
                savedId = 0
                editRow = -1
                match = null
                version++
                limit = readOnlyBecause
                doc = text
                saveCharset = text.charset
                saveEol = text.eol
                // the big editor keeps its text in the buffer only
                saved = if (buffer != null) "" else text.text
                value = TextFieldValue(saved)
            } catch (e: FsException) {
                if (e.errno == Errno.EFBIG) limit = EditorLimit.TOO_LARGE else loadError = e.errno
            } finally {
                loading = false
            }
        }
    }

    /** "Save with encoding": the next save converts the text to [charset]. */
    fun convertTo(charset: String) {
        if (!readOnly) saveCharset = charset
    }

    fun setLineEnding(eol: LineEnding) {
        if (!readOnly) saveEol = eol
    }

    fun onValueChange(new: TextFieldValue) {
        if (readOnly) {
            value = value.copy(selection = new.selection)
            return
        }
        if (new.text != value.text) {
            // one undo step per burst of typing
            val now = SystemClock.uptimeMillis()
            if (now - lastPush > UNDO_GAP_MS || undo.isEmpty()) undo.addLast(value)
            if (undo.size > UNDO_DEPTH) undo.removeFirst()
            lastPush = now
            redo.clear()
        }
        value = new
    }

    /** Starts editing [row] of the big editor with the cursor at [offset]. */
    fun editAt(row: Int, offset: Int) {
        val b = buffer ?: return
        if (readOnly || row !in 0 until b.size) return
        val text = b[row].text
        editRow = row
        rowValue = TextFieldValue(SENTINEL + text, TextRange(offset.coerceIn(0, text.length) + 1))
        wantsFocus = true
    }

    /**
     * The edited row's field takes focus once per [editAt], not each time it scrolls back into
     * view: that would bring back a keyboard the user put away.
     */
    var wantsFocus = false

    /** Arrow up / down on a hardware keyboard: the same column of the next row. */
    fun moveRow(delta: Int) {
        val b = buffer ?: return
        val target = editRow + delta
        if (target !in 0 until b.size) return
        editAt(target, rowValue.selection.start - 1)
    }

    /**
     * The edited row's field changed. A newline in it splits the row (Enter, or pasted lines);
     * the [SENTINEL] gone means backspace at the start of the row, which joins it to the one above.
     */
    fun onRowChange(row: Int, new: TextFieldValue) {
        val b = buffer ?: return
        // the field of a row just left can still report a change: it is not this row's
        if (readOnly || row != editRow || row !in 0 until b.size) return
        if (!new.text.startsWith(SENTINEL)) {
            val joined = b.joinUp(row, new.text)
            if (joined == null) {
                if (new.text != b[row].text) record(b.setRow(row, new.text), coalesce = true)
                rowValue = TextFieldValue(SENTINEL + b[row].text, TextRange(1))
                return
            }
            record(joined.first, coalesce = false)
            editAt(row - 1, joined.second)
            return
        }
        val content = new.text.substring(1)
        if ('\n' in content) {
            val tail = content.length - (new.selection.end - 1).coerceIn(0, content.length)
            val edit = b.setRow(row, content)
            record(edit, coalesce = false)
            val last = row + edit.added.size - 1
            editAt(last, b[last].text.length - tail)
            return
        }
        if (content != b[row].text) record(b.setRow(row, content), coalesce = true)
        // the cursor never goes before the sentinel, so backspace there always deletes it
        rowValue = new.copy(selection = TextRange(new.selection.start.coerceAtLeast(1), new.selection.end.coerceAtLeast(1)))
    }

    private fun record(edit: LineBuffer.Edit, coalesce: Boolean) {
        val now = SystemClock.uptimeMillis()
        val top = rowUndo.lastOrNull()
        // typing in one row: one undo step per burst, never across a save
        if (coalesce && top != null && !saving && top.id != savedId && now - top.time < UNDO_GAP_MS && top.edit.start == edit.start &&
            top.edit.added.size == 1 && edit.removed.size == 1 && edit.added.size == 1
        ) {
            rowUndo[rowUndo.lastIndex] = Step(top.id, LineBuffer.Edit(edit.start, top.edit.removed, edit.added), now)
        } else {
            rowUndo.addLast(Step(++nextId, edit, now))
            if (rowUndo.size > UNDO_DEPTH) rowUndo.removeFirst()
        }
        rowRedo.clear()
        currentId = rowUndo.last().id
        match = null
        version++
    }

    fun undo() {
        val b = buffer
        if (b != null) {
            val step = rowUndo.removeLastOrNull() ?: return
            b.apply(step.edit.inverse())
            rowRedo.addLast(step)
            afterRowUndo(step.edit.start, step.edit.removed.size)
            return
        }
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(value)
        value = previous
        lastPush = 0
    }

    fun redo() {
        val b = buffer
        if (b != null) {
            val step = rowRedo.removeLastOrNull() ?: return
            b.apply(step.edit)
            rowUndo.addLast(step)
            afterRowUndo(step.edit.start, step.edit.added.size)
            return
        }
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(value)
        value = next
        lastPush = 0
    }

    /** After an undo or redo of rows from [start]: the cursor goes to the end of what changed. */
    private fun afterRowUndo(start: Int, count: Int) {
        val b = buffer ?: return
        currentId = rowUndo.lastOrNull()?.id ?: 0
        match = null
        version++
        if (editRow >= 0) {
            val row = (start + count - 1).coerceIn(0, b.size - 1)
            editAt(row, b[row].text.length)
        }
    }

    /**
     * Selects the next (or previous) match. Returns where to scroll: an offset in the text, or
     * for the big editor a row.
     */
    fun find(query: String, forward: Boolean): Int? {
        val b = buffer
        if (b != null) {
            val (row, col) = match?.let { (r, range) -> r to if (forward) range.last + 1 else range.first }
                ?: if (editRow >= 0) editRow to rowValue.selection.start - 1 else 0 to 0
            val found = b.find(query, row, col, forward) ?: return null
            editRow = -1
            match = found
            return found.first
        }
        val text = value.text
        val range = if (forward) {
            TextSearch.next(text, query, value.selection.max)
        } else {
            TextSearch.previous(text, query, value.selection.min)
        } ?: return null
        value = value.copy(selection = TextRange(range.first, range.last + 1))
        return range.first
    }

    fun replace(query: String, replacement: String): Int? {
        if (readOnly) return find(query, forward = true)
        val b = buffer
        if (b != null) {
            val (row, range) = match ?: return find(query, forward = true)
            val text = b[row].text
            if (query.isNotEmpty() && range.last < text.length && text.substring(range.first, range.last + 1).equals(query, ignoreCase = true)) {
                record(b.setRow(row, text.replaceRange(range.first, range.last + 1, replacement)), coalesce = false)
                match = row to (range.first until range.first + replacement.length)
            }
            return find(query, forward = true)
        }
        val sel = value.selection
        val current = value.text.substring(sel.min, sel.max)
        if (query.isNotEmpty() && current.equals(query, ignoreCase = true)) {
            val text = value.text.replaceRange(sel.min, sel.max, replacement)
            onValueChange(TextFieldValue(text, TextRange(sel.min + replacement.length)))
        }
        return find(query, forward = true)
    }

    fun replaceAll(query: String, replacement: String): Int {
        if (readOnly) return 0
        val b = buffer
        if (b != null) {
            val (edit, n) = b.replaceAll(query, replacement) ?: return 0
            record(edit, coalesce = false)
            if (editRow >= 0) editAt(editRow.coerceAtMost(b.size - 1), 0)
            return n
        }
        val n = TextSearch.count(value.text, query)
        if (n > 0) onValueChange(TextFieldValue(TextSearch.replaceAll(value.text, query, replacement)))
        return n
    }

    /** Where 1-based [line] starts: an offset in the text, or for the big editor a row. */
    fun goToLine(line: Int): Int {
        buffer?.let { b ->
            val row = b.rowOfLine(line)
            if (!readOnly) editAt(row, 0)
            return row
        }
        val offset = TextSearch.lineStart(value.text, line)
        value = value.copy(selection = TextRange(offset))
        return offset
    }

    fun save(then: () -> Unit = {}) {
        val current = doc ?: return
        if (readOnly || saving) return
        val b = buffer
        val text = value.text
        val snapshot = b?.snapshot()
        val id = currentId
        val charset = saveCharset
        val eol = saveEol
        saving = true
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    current.encode(if (snapshot != null) LineBuffer.join(snapshot) else text, eol, charset)
                }
                if (Archives.isInside(path)) CamFileClient.archiveWrite(path, bytes) else CamFileClient.write(path) { it.write(bytes) }
                // what was written is the new baseline: its charset, and for MIXED its endings now
                val written = withContext(Dispatchers.Default) { TextDoc.decode(bytes, charset) }
                doc = written
                saveCharset = written.charset
                saveEol = written.eol
                if (b != null) savedId = id else saved = text
                message = EditorMessage.Saved
                FileChanges.notify(path)
                then()
            } catch (_: CharacterCodingException) {
                message = EditorMessage.Unmappable(charset)
            } catch (e: FsException) {
                message = EditorMessage.Failed(e.errno, e.message)
            } finally {
                saving = false
            }
        }
    }

    companion object {
        /** First character of the edited row's field: deleting it is backspace at the row's start. */
        const val SENTINEL = "\u200B"

        private const val MAX_OPEN = 16 * 1024 * 1024

        /**
         * Above this a file opens in the row editor: one text field relayouts the whole text on
         * every keystroke (900 KB took over a second a frame on a Y700).
         */
        private const val MAX_EDIT = 128 * 1024

        /** The highlighter runs on every keystroke over the whole text. */
        private const val HIGHLIGHT_LIMIT = 100_000
        private const val UNDO_GAP_MS = 700L
        private const val UNDO_DEPTH = 200
    }
}
