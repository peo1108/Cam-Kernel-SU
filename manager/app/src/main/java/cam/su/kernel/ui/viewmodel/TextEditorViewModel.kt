package cam.su.kernel.ui.viewmodel

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.Highlighter
import cam.su.kernel.files.TextDoc
import cam.su.kernel.files.TextSearch
import kotlinx.coroutines.launch

/** Why a file is open read-only (or not at all). */
enum class EditorLimit { NONE, ARCHIVE, BINARY, LARGE, TOO_LARGE }

sealed interface EditorMessage {
    data object Saved : EditorMessage
    data class Failed(val errno: Int, val detail: String?) : EditorMessage
    data object NotFound : EditorMessage
}

/** MT's text editor: load as root, edit, save atomically keeping the file's mode, owner and label. */
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

    val readOnly get() = limit != EditorLimit.NONE
    val modified get() = doc != null && value.text != saved
    val highlight get() = value.text.length <= HIGHLIGHT_LIMIT

    private val undo = ArrayDeque<TextFieldValue>()
    private val redo = ArrayDeque<TextFieldValue>()
    private var lastPush = 0L
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun load(file: String) {
        if (path == file) return
        path = file
        lang = Highlighter.langFor(FsPath.name(file.substringAfterLast(Archives.SEP)))
        viewModelScope.launch {
            try {
                val inArchive = Archives.isInside(file)
                val size = if (inArchive) 0L else CamFileClient.stat(file)?.size ?: run {
                    loadError = cam.su.kernel.files.Errno.ENOENT
                    return@launch
                }
                if (size > MAX_OPEN) {
                    limit = EditorLimit.TOO_LARGE
                    return@launch
                }
                val bytes = CamFileClient.readBytes(file)
                val text = TextDoc.decode(bytes)
                limit = when {
                    inArchive -> EditorLimit.ARCHIVE
                    TextDoc.looksBinary(bytes) -> EditorLimit.BINARY
                    bytes.size > MAX_EDIT -> EditorLimit.LARGE
                    else -> EditorLimit.NONE
                }
                doc = text
                saved = text.text
                value = TextFieldValue(text.text)
            } catch (e: FsException) {
                loadError = e.errno
            } finally {
                loading = false
            }
        }
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

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(value)
        value = previous
        lastPush = 0
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(value)
        value = next
        lastPush = 0
    }

    /** Selects the next (or previous) match; returns it so the screen can scroll to it. */
    fun find(query: String, forward: Boolean): IntRange? {
        val text = value.text
        val range = if (forward) {
            TextSearch.next(text, query, value.selection.max)
        } else {
            TextSearch.previous(text, query, value.selection.min)
        } ?: return null
        value = value.copy(selection = TextRange(range.first, range.last + 1))
        return range
    }

    fun replace(query: String, replacement: String): IntRange? {
        val sel = value.selection
        val current = value.text.substring(sel.min, sel.max)
        if (query.isNotEmpty() && current.equals(query, ignoreCase = true) && !readOnly) {
            val text = value.text.replaceRange(sel.min, sel.max, replacement)
            onValueChange(TextFieldValue(text, TextRange(sel.min + replacement.length)))
        }
        return find(query, forward = true)
    }

    fun replaceAll(query: String, replacement: String): Int {
        if (readOnly) return 0
        val n = TextSearch.count(value.text, query)
        if (n > 0) onValueChange(TextFieldValue(TextSearch.replaceAll(value.text, query, replacement)))
        return n
    }

    fun goToLine(line: Int): Int {
        val offset = TextSearch.lineStart(value.text, line)
        value = value.copy(selection = TextRange(offset))
        return offset
    }

    fun save(then: () -> Unit = {}) {
        val current = doc ?: return
        if (readOnly || saving) return
        val text = value.text
        saving = true
        viewModelScope.launch {
            try {
                val bytes = current.encode(text)
                CamFileClient.write(path) { it.write(bytes) }
                saved = text
                message = EditorMessage.Saved
                then()
            } catch (e: FsException) {
                message = EditorMessage.Failed(e.errno, e.message)
            } finally {
                saving = false
            }
        }
    }

    private companion object {
        const val MAX_OPEN = 16L * 1024 * 1024
        const val MAX_EDIT = 4 * 1024 * 1024
        const val HIGHLIGHT_LIMIT = 512 * 1024
        const val UNDO_GAP_MS = 700L
        const val UNDO_DEPTH = 200
    }
}
