package cam.su.kernel.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.camApp
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.ConflictChoice
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileOp
import cam.su.kernel.files.FileSort
import cam.su.kernel.files.FsException
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.PanelNav
import cam.su.kernel.files.SortBy
import cam.su.kernel.files.SortOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PanelUi(
    val nav: PanelNav,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** errno of the last failed listing; the list then stays empty. */
    val errorErrno: Int? = null,
    val selection: Set<String> = emptySet(),
)

data class OpUi(
    val kind: OpKind,
    val done: Long = 0,
    val total: Long = 0,
    val current: String = "",
    val conflict: CamFileClient.OpEvent.Conflict? = null,
)

sealed interface FilesMessage {
    data class Finished(val kind: OpKind, val items: Int, val skipped: Int) : FilesMessage
    data object Cancelled : FilesMessage
    data class Failed(val errno: Int, val detail: String?) : FilesMessage
    data object NotYet : FilesMessage
}

data class FilesUiState(
    val panels: List<PanelUi>,
    val active: Int = 0,
    val sort: SortOptions = SortOptions(),
    val op: OpUi? = null,
    val message: FilesMessage? = null,
) {
    val activePanel get() = panels[active]
    val otherPanel get() = panels[1 - active]
}

class FilesViewModel : ViewModel() {
    private val prefs = camApp.getSharedPreferences("files", Context.MODE_PRIVATE)
    private val raw = arrayOf<List<FileEntry>>(emptyList(), emptyList())
    private val loadJobs = arrayOfNulls<Job>(2)
    private var opHandle: CamFileClient.OpHandle? = null

    private val _uiState = MutableStateFlow(
        FilesUiState(
            panels = listOf(
                PanelUi(PanelNav(prefs.getString(KEY_PATH_0, null) ?: DEFAULT_PATH_0)),
                PanelUi(PanelNav(prefs.getString(KEY_PATH_1, null) ?: DEFAULT_PATH_1)),
            ),
            active = prefs.getInt(KEY_ACTIVE, 0).coerceIn(0, 1),
            sort = SortOptions(
                by = SortBy.entries.getOrElse(prefs.getInt(KEY_SORT_BY, 0)) { SortBy.NAME },
                descending = prefs.getBoolean(KEY_SORT_DESC, false),
                showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, true),
            ),
        ),
    )
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private var started = false

    /** Loads both panels the first time the tab is shown. */
    fun start() {
        if (started) return
        started = true
        load(0)
        load(1)
    }

    fun refresh() {
        load(0)
        load(1)
    }

    fun setActive(index: Int) {
        _uiState.update { it.copy(active = index) }
        prefs.edit { putInt(KEY_ACTIVE, index) }
    }

    fun navigate(panel: Int, path: String) = setNav(panel, _uiState.value.panels[panel].nav.go(path))

    fun open(panel: Int, entry: FileEntry) {
        if (entry.isDir) {
            navigate(panel, entry.path)
        } else {
            // Viewers and the editor come with the next tasks of the plan.
            _uiState.update { it.copy(message = FilesMessage.NotYet) }
        }
    }

    fun goUp(panel: Int) {
        _uiState.value.panels[panel].nav.parent?.let { navigate(panel, it) }
    }

    /** True when it moved; false lets back leave the tab. */
    fun back(): Boolean {
        val state = _uiState.value
        if (state.activePanel.selection.isNotEmpty()) {
            clearSelection(state.active)
            return true
        }
        val previous = state.activePanel.nav.goBack() ?: return false
        setNav(state.active, previous)
        return true
    }

    fun canGoBack(): Boolean = _uiState.value.let { it.activePanel.selection.isNotEmpty() || it.activePanel.nav.back.isNotEmpty() }

    fun toggleSelection(panel: Int, path: String) = updatePanel(panel) {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun selectAll(panel: Int) = updatePanel(panel) { p -> p.copy(selection = p.entries.map { it.path }.toSet()) }

    fun clearSelection(panel: Int) = updatePanel(panel) { it.copy(selection = emptySet()) }

    fun setSort(by: SortBy) {
        val current = _uiState.value.sort
        // Picking the same order again flips the direction, like a table header.
        val next = if (current.by == by) current.copy(descending = !current.descending) else current.copy(by = by, descending = false)
        applySort(next)
    }

    fun toggleHidden() = applySort(_uiState.value.sort.let { it.copy(showHidden = !it.showHidden) })

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // --- operations ---

    fun copySelection(move: Boolean) {
        val state = _uiState.value
        val sources = state.activePanel.selection.toList()
        if (sources.isEmpty()) return
        runOp(FileOp(if (move) OpKind.MOVE else OpKind.COPY, sources, state.otherPanel.nav.path))
    }

    fun deleteSelection() {
        val sources = _uiState.value.activePanel.selection.toList()
        if (sources.isEmpty()) return
        runOp(FileOp(OpKind.DELETE, sources))
    }

    fun cancelOp() {
        val handle = opHandle ?: return
        viewModelScope.launch { runCatching { handle.cancel() } }
    }

    fun resolveConflict(choice: ConflictChoice, applyAll: Boolean) {
        val handle = opHandle ?: return
        _uiState.update { it.copy(op = it.op?.copy(conflict = null)) }
        viewModelScope.launch { runCatching { handle.resolve(choice, applyAll) } }
    }

    private fun runOp(op: FileOp) {
        if (_uiState.value.op != null) return
        _uiState.update { it.copy(op = OpUi(op.kind)) }
        viewModelScope.launch {
            val handle = try {
                CamFileClient.startOp(op)
            } catch (e: FsException) {
                _uiState.update { it.copy(op = null, message = FilesMessage.Failed(e.errno, e.message)) }
                return@launch
            }
            opHandle = handle
            handle.events.collect { event ->
                when (event) {
                    is CamFileClient.OpEvent.Progress -> _uiState.update {
                        it.copy(op = it.op?.copy(done = event.done, total = event.total, current = event.current))
                    }

                    is CamFileClient.OpEvent.Conflict -> _uiState.update { it.copy(op = it.op?.copy(conflict = event)) }

                    is CamFileClient.OpEvent.Done -> {
                        opHandle = null
                        val message = when {
                            event.ok -> FilesMessage.Finished(op.kind, event.items, event.skipped)
                            event.cancelled -> FilesMessage.Cancelled
                            else -> FilesMessage.Failed(event.errno, event.error)
                        }
                        _uiState.update { state ->
                            state.copy(
                                op = null,
                                message = message,
                                panels = state.panels.map { it.copy(selection = emptySet()) },
                            )
                        }
                        refresh()
                    }
                }
            }
        }
    }

    // --- internals ---

    private fun setNav(panel: Int, nav: PanelNav) {
        updatePanel(panel) { it.copy(nav = nav, selection = emptySet()) }
        prefs.edit { putString(if (panel == 0) KEY_PATH_0 else KEY_PATH_1, nav.path) }
        load(panel)
    }

    private fun load(panel: Int) {
        loadJobs[panel]?.cancel()
        val path = _uiState.value.panels[panel].nav.path
        updatePanel(panel) { it.copy(loading = true) }
        loadJobs[panel] = viewModelScope.launch {
            val result = try {
                Result.success(CamFileClient.listDir(path))
            } catch (e: FsException) {
                Result.failure(e)
            }
            if (_uiState.value.panels[panel].nav.path != path) return@launch
            raw[panel] = result.getOrDefault(emptyList())
            val sort = _uiState.value.sort
            updatePanel(panel) { p ->
                val entries = FileSort.apply(raw[panel], sort)
                val paths = entries.mapTo(HashSet()) { it.path }
                p.copy(
                    entries = entries,
                    loading = false,
                    errorErrno = (result.exceptionOrNull() as? FsException)?.errno,
                    selection = p.selection.filterTo(HashSet()) { it in paths },
                )
            }
        }
    }

    private fun applySort(sort: SortOptions) {
        prefs.edit {
            putInt(KEY_SORT_BY, sort.by.ordinal)
            putBoolean(KEY_SORT_DESC, sort.descending)
            putBoolean(KEY_SHOW_HIDDEN, sort.showHidden)
        }
        _uiState.update { state ->
            state.copy(
                sort = sort,
                panels = state.panels.mapIndexed { i, p -> p.copy(entries = FileSort.apply(raw[i], sort)) },
            )
        }
    }

    private fun updatePanel(panel: Int, block: (PanelUi) -> PanelUi) = _uiState.update { state ->
        state.copy(panels = state.panels.mapIndexed { i, p -> if (i == panel) block(p) else p })
    }

    private companion object {
        const val DEFAULT_PATH_0 = "/storage/emulated/0"
        const val DEFAULT_PATH_1 = "/data/adb"
        const val KEY_PATH_0 = "path_0"
        const val KEY_PATH_1 = "path_1"
        const val KEY_ACTIVE = "active"
        const val KEY_SORT_BY = "sort_by"
        const val KEY_SORT_DESC = "sort_desc"
        const val KEY_SHOW_HIDDEN = "show_hidden"
    }
}
