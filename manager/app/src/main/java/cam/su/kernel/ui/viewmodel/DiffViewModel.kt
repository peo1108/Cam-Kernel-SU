package cam.su.kernel.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.DiffRow
import cam.su.kernel.files.DiffView
import cam.su.kernel.files.Errno
import cam.su.kernel.files.FsException
import cam.su.kernel.files.LineDiff
import cam.su.kernel.files.LineEnding
import cam.su.kernel.files.TextDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the compare screen shows a message instead of lines. */
sealed interface DiffOutcome {
    data object Identical : DiffOutcome

    /** The same lines; the bytes differ in charset, BOM or line endings ([left] / [right] say how). */
    data class SameText(val left: String, val right: String) : DiffOutcome
    data object Binary : DiffOutcome
    data object TooLarge : DiffOutcome
    data class Failed(val errno: Int) : DiffOutcome
}

/** Compares two text files line by line, read as root (either may be inside an archive). */
class DiffViewModel : ViewModel() {
    var left by mutableStateOf("")
        private set
    var right by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var outcome by mutableStateOf<DiffOutcome?>(null)
        private set
    var rows by mutableStateOf<List<DiffRow>>(emptyList())
        private set
    var changeStarts by mutableStateOf<List<Int>>(emptyList())
        private set
    var summary by mutableStateOf<DiffView.Summary?>(null)
        private set

    /** Every line, or only the changes with a few lines around them. */
    var all by mutableStateOf(false)
        private set

    private var a: List<String> = emptyList()
    private var b: List<String> = emptyList()
    private var runs: List<LineDiff.Run> = emptyList()
    private var expanded: Set<Int> = emptySet()

    fun load(leftPath: String, rightPath: String) {
        if (left == leftPath && right == rightPath) return
        left = leftPath
        right = rightPath
        loading = true
        viewModelScope.launch {
            try {
                val tooBig = listOf(leftPath, rightPath).any { path ->
                    !Archives.isInside(path) && (CamFileClient.stat(path)?.contentSize ?: throw FsException(Errno.ENOENT, path)) > MAX_BYTES
                }
                if (tooBig) {
                    outcome = DiffOutcome.TooLarge
                    return@launch
                }
                val x = CamFileClient.readBytes(leftPath, MAX_BYTES)
                val y = CamFileClient.readBytes(rightPath, MAX_BYTES)
                outcome = withContext(Dispatchers.Default) { compare(x, y) }
            } catch (e: FsException) {
                outcome = if (e.errno == Errno.EFBIG) DiffOutcome.TooLarge else DiffOutcome.Failed(e.errno)
            } finally {
                loading = false
            }
        }
    }

    private fun compare(x: ByteArray, y: ByteArray): DiffOutcome? {
        if (x.contentEquals(y)) return DiffOutcome.Identical
        val docX = TextDoc.decode(x)
        val docY = TextDoc.decode(y)
        val binary = { bytes: ByteArray, doc: TextDoc -> TextDoc.looksBinary(bytes) && !TextDoc.isWide(doc.charset) }
        if (binary(x, docX) || binary(y, docY)) return DiffOutcome.Binary
        a = lines(docX.text)
        b = lines(docY.text)
        if (a == b) return DiffOutcome.SameText(describe(docX), describe(docY))
        runs = LineDiff.diff(a, b)
        expanded = emptySet()
        rebuild()
        return null
    }

    /** The newline that ends a file ends its last line; it does not start an empty one. */
    private fun lines(text: String) = text.split('\n').let { if (text.endsWith('\n')) it.dropLast(1) else it }

    private fun describe(doc: TextDoc) = listOfNotNull(
        doc.charset,
        "BOM".takeIf { doc.bom },
        when (doc.eol) {
            LineEnding.LF -> "LF"
            LineEnding.CRLF -> "CRLF"
            LineEnding.MIXED -> "LF+CRLF"
        },
    ).joinToString(" ")

    private fun rebuild() {
        val built = DiffView.rows(a, b, runs, CONTEXT, expanded, all)
        rows = built
        changeStarts = DiffView.changeStarts(built)
        summary = DiffView.summary(runs)
    }

    /** Right becomes left: the diff read the other way. */
    fun swap() {
        if (loading) return
        val l = left
        left = right
        right = l
        if (outcome != null) {
            (outcome as? DiffOutcome.SameText)?.let { outcome = DiffOutcome.SameText(it.right, it.left) }
            return
        }
        val x = a
        a = b
        b = x
        loading = true
        viewModelScope.launch {
            val swapped = withContext(Dispatchers.Default) { LineDiff.diff(a, b) }
            runs = swapped
            expanded = emptySet()
            rebuild()
            loading = false
        }
    }

    fun toggleAll() {
        all = !all
        rebuild()
    }

    fun expand(fold: DiffRow.Fold) {
        expanded = expanded + fold.aStart
        rebuild()
    }

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val CONTEXT = 3
    }
}
