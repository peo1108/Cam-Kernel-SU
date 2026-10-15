package cam.su.kernel.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.R
import cam.su.kernel.camApp
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.ConflictChoice
import cam.su.kernel.files.Errno
import cam.su.kernel.files.FileChanges
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileOp
import cam.su.kernel.files.FileOpProgress
import cam.su.kernel.files.FileOpService
import cam.su.kernel.files.FileSort
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.PanelNav
import cam.su.kernel.files.SelectionOps
import cam.su.kernel.files.SortBy
import cam.su.kernel.files.SortOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the view model lends the helpers that run some of its dialogs ([FilesInspector], [FilesSystemTools]). */
internal interface FilesHost {
    val state: MutableStateFlow<FilesUiState>
    val scope: CoroutineScope
    fun runOp(op: FileOp, then: (() -> Unit)? = null)
    fun navigateActive(path: String)
    suspend fun send(event: FilesEvent)
}

class FilesViewModel : ViewModel() {
    private val prefs = camApp.getSharedPreferences(cam.su.kernel.files.Bookmarks.PREFS, Context.MODE_PRIVATE)
    private val raw = arrayOf<List<FileEntry>>(emptyList(), emptyList())
    private val loadJobs = arrayOfNulls<Job>(2)
    private var opHandle: CamFileClient.OpHandle? = null
    private val events = Channel<FilesEvent>(Channel.BUFFERED)
    val eventFlow: Flow<FilesEvent> = events.receiveAsFlow()

    private val _uiState = MutableStateFlow(
        FilesUiState(
            panels = listOf(
                PanelUi(PanelNav(prefs.getString(KEY_PATH_0, null) ?: home(0))),
                PanelUi(PanelNav(prefs.getString(KEY_PATH_1, null) ?: home(1))),
            ),
            active = prefs.getInt(KEY_ACTIVE, 0).coerceIn(0, 1),
            sort = SortOptions(
                by = SortBy.entries.getOrElse(prefs.getInt(KEY_SORT_BY, 0)) { SortBy.NAME },
                descending = prefs.getBoolean(KEY_SORT_DESC, false),
                showHidden = prefs.getBoolean(KEY_SHOW_HIDDEN, true),
            ),
            bookmarks = loadBookmarks(),
        ),
    )
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val host = object : FilesHost {
        override val state get() = _uiState
        override val scope get() = viewModelScope
        override fun runOp(op: FileOp, then: (() -> Unit)?) = this@FilesViewModel.runOp(op, then)
        override fun navigateActive(path: String) = this@FilesViewModel.navigateActive(path)
        override suspend fun send(event: FilesEvent) = events.send(event)
    }

    /** Properties, checksums, APK info and search. */
    val inspector = FilesInspector(host)

    /** Systemless overrides, browsing as an app, partitions. */
    val system = FilesSystemTools(host)

    private var started = false
    private var sortJob: Job? = null

    /** The folders inotify watches now (both panels, archives left out). */
    private var watched = emptyList<String>()
    private val reloadJobs = arrayOfNulls<Job>(2)

    init {
        // the editor saved a file: whichever panel shows it reloads
        viewModelScope.launch { FileChanges.changed.collect { if (started) refresh() } }
        // Another app (or a shell) changed a folder a panel shows. A copy of hundreds of files
        // sends hundreds of events: one reload per panel, 400 ms after the first.
        viewModelScope.launch {
            CamFileClient.dirChanges.collect { dir ->
                _uiState.value.panels.forEachIndexed { i, p ->
                    if (p.nav.path == dir && reloadJobs[i]?.isActive != true) {
                        reloadJobs[i] = viewModelScope.launch {
                            delay(RELOAD_DELAY_MS)
                            load(i)
                        }
                    }
                }
            }
        }
    }

    /** Points inotify at the two panels' folders when they changed. */
    private fun updateWatch() {
        val dirs = _uiState.value.panels.map { it.nav.path }.filter { !Archives.isInside(it) }.distinct()
        if (dirs == watched) return
        watched = dirs
        viewModelScope.launch {
            try {
                CamFileClient.watch(dirs)
            } catch (_: FsException) {
                // asked again with the next listing (the root service may be restarting)
                watched = emptyList()
            }
        }
    }

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
        if (_uiState.value.active == index) return
        _uiState.update { it.copy(active = index) }
        prefs.edit { putInt(KEY_ACTIVE, index) }
    }

    // --- navigation (MT: back key = up, ‹ › = history) ---

    fun navigate(panel: Int, path: String) = setNav(panel, _uiState.value.panels[panel].nav.go(path))

    fun navigateActive(path: String) = navigate(_uiState.value.active, path)

    /** The path typed into the address bar: a folder, a file (its folder opens) or a path in an archive. */
    fun goToPath(input: String) {
        val raw = input.trim().let { if (it.length > 1) it.trimEnd('/') else it }
        if (Archives.isInside(raw + "/")) {
            navigateActive(if (Archives.isInside(raw)) raw else "$raw/")
            return
        }
        viewModelScope.launch {
            val path = try {
                FsPath.normalize(raw)
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, raw)) }
                return@launch
            }
            val entry = try {
                CamFileClient.stat(path)
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, path)) }
                return@launch
            }
            when {
                entry == null -> _uiState.update { it.copy(message = FilesMessage.Failed(cam.su.kernel.files.Errno.ENOENT, path)) }
                entry.isDir -> navigateActive(path)
                else -> navigateActive(FsPath.parent(path))
            }
        }
    }

    /** Into an archive as if it were a folder. */
    fun browseArchive(panel: Int, entry: FileEntry) = navigate(panel, Archives.join(entry.path, ""))

    fun goUp(panel: Int = _uiState.value.active) {
        _uiState.value.panels[panel].nav.parent?.let { navigate(panel, it) }
    }

    fun historyBack() {
        val state = _uiState.value
        state.activePanel.nav.goBack()?.let { setNav(state.active, it) }
    }

    fun historyForward() {
        val state = _uiState.value
        state.activePanel.nav.goForward()?.let { setNav(state.active, it) }
    }

    /** ⇄: the other panel goes to this panel's folder. */
    fun syncOther() {
        val state = _uiState.value
        navigate(1 - state.active, state.activePanel.nav.path)
    }

    fun swapPanels() {
        val state = _uiState.value
        val (a, b) = state.panels
        raw.reverse()
        loadJobs.forEach { it?.cancel() }
        _uiState.update { it.copy(panels = listOf(b, a)) }
        prefs.edit {
            putString(KEY_PATH_0, b.nav.path)
            putString(KEY_PATH_1, a.nav.path)
        }
        // a listing still on its way was for the other side: ask again
        _uiState.value.panels.forEachIndexed { i, p -> if (p.loading) load(i) }
    }

    /** System back: drop the selection, else go up; false at "/" so the tab can be left. */
    fun back(): Boolean {
        val state = _uiState.value
        if (state.activePanel.selection.isNotEmpty()) {
            clearSelection(state.active)
            return true
        }
        val parent = state.activePanel.nav.parent ?: return false
        navigate(state.active, parent)
        return true
    }

    fun setHome() {
        val state = _uiState.value
        prefs.edit { putString(if (state.active == 0) KEY_HOME_0 else KEY_HOME_1, state.activePanel.nav.path) }
        _uiState.update { it.copy(message = FilesMessage.Done) }
    }

    fun goHome() = navigateActive(home(_uiState.value.active))

    // --- selection ---

    fun toggleSelection(panel: Int, path: String) = updatePanel(panel) {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun selectAll(panel: Int = _uiState.value.active) = updatePanel(panel) { p -> p.copy(selection = p.entries.map { it.path }.toSet()) }

    fun selectRange() = updatePanel(_uiState.value.active) { it.copy(selection = SelectionOps.range(it.entries, it.selection)) }

    fun invertSelection() = updatePanel(_uiState.value.active) { it.copy(selection = SelectionOps.invert(it.entries, it.selection)) }

    fun selectSameType() = updatePanel(_uiState.value.active) { it.copy(selection = SelectionOps.sameType(it.entries, it.selection)) }

    fun clearSelection(panel: Int = _uiState.value.active) = updatePanel(panel) { it.copy(selection = emptySet()) }

    /** What a menu on [entry] acts on: the whole selection when [entry] is part of it. */
    fun targetsFor(panel: Int, entry: FileEntry): List<String> {
        val selection = _uiState.value.panels[panel].selection
        return if (entry.path in selection) selection.toList() else listOf(entry.path)
    }

    // --- sort / view ---

    fun setSort(by: SortBy, descending: Boolean) = applySort(_uiState.value.sort.copy(by = by, descending = descending))

    fun toggleHidden() = applySort(_uiState.value.sort.let { it.copy(showHidden = !it.showHidden) })

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // --- bookmarks ---

    fun addBookmark(path: String) {
        val list = _uiState.value.bookmarks
        if (path in list) return
        saveBookmarks(list + path)
        _uiState.update { it.copy(message = FilesMessage.Done) }
    }

    fun removeBookmark(path: String) = saveBookmarks(_uiState.value.bookmarks - path)

    fun restoreBookmarks() = saveBookmarks((cam.su.kernel.files.Bookmarks.DEFAULTS + _uiState.value.bookmarks).distinct())

    // --- single file actions ---

    fun create(name: String, folder: Boolean) = simple(refreshActive = true) {
        val dir = _uiState.value.activePanel.nav.path
        if (Archives.isInside(dir)) {
            val (archive, inner) = Archives.split(dir)
            val path = Archives.join(archive, "$inner/$name".trim('/'))
            // archiveWrite replaces what is there (the editor saves with it): a new file must not
            if (CamFileClient.listDir(dir).any { it.name == name }) throw FsException(Errno.EEXIST, path)
            if (folder) CamFileClient.archiveMkdir(path) else CamFileClient.archiveWrite(path, ByteArray(0))
            return@simple
        }
        val path = FsPath.child(dir, name)
        if (folder) CamFileClient.mkdir(path) else CamFileClient.createFile(path)
    }

    fun rename(path: String, newName: String) = simple(refreshActive = true) {
        if (Archives.isInside(path)) {
            val inner = Archives.split(path).second
            CamFileClient.archiveRename(path, (inner.substringBeforeLast('/', "") + "/" + newName).trim('/'))
            return@simple
        }
        CamFileClient.rename(path, FsPath.child(FsPath.parent(path), newName))
    }

    /**
     * Long-pressed Move: to a path typed by hand. Into a folder it is the move job (any file
     * system, conflicts asked, progress); to a new full name it is a rename.
     */
    fun moveTo(path: String, target: String) = simple(refreshActive = true) {
        val dest = FsPath.normalize(target.trim().let { if (it.startsWith("/")) it else FsPath.child(FsPath.parent(path), it) })
        if (CamFileClient.stat(dest)?.isDir == true) {
            runOp(FileOp(OpKind.MOVE, listOf(path), dest))
            return@simple
        }
        try {
            CamFileClient.rename(path, dest)
        } catch (e: FsException) {
            // rename(2) cannot cross file systems; a folder as the target can
            if (e.errno == Errno.EXDEV) throw FsException(Errno.EXDEV, dest, camApp.getString(R.string.files_error_xdev_hint))
            throw e
        }
    }

    fun symlinkInOther(path: String) = simple(refreshActive = true) {
        CamFileClient.symlink(path, FsPath.child(_uiState.value.otherPanel.nav.path, FsPath.name(path)))
    }

    fun chmod(path: String, mode: Int, recursive: Boolean) = simple(refreshActive = true) {
        CamFileClient.chmod(path, mode, recursive)
        inspector.reloadProperties(path)
    }

    fun chown(path: String, uid: Int, gid: Int, recursive: Boolean) = simple(refreshActive = true) {
        CamFileClient.chown(path, uid, gid, recursive)
        inspector.reloadProperties(path)
    }

    fun setContext(path: String, context: String, recursive: Boolean) = simple(refreshActive = true) {
        CamFileClient.setContext(path, context, recursive)
        inspector.reloadProperties(path)
    }

    fun edit(entry: FileEntry) {
        viewModelScope.launch { events.send(FilesEvent.Edit(entry.path)) }
    }

    fun hex(entry: FileEntry) {
        viewModelScope.launch { events.send(FilesEvent.Hex(entry.path)) }
    }

    fun compare(left: String, right: String) {
        viewModelScope.launch { events.send(FilesEvent.Compare(left, right)) }
    }

    /** A root terminal in the active panel's folder (an archive's own folder when inside one). */
    fun openTerminal() {
        val path = _uiState.value.activePanel.nav.path
        val dir = if (Archives.isInside(path)) FsPath.parent(Archives.split(path).first) else path
        viewModelScope.launch { events.send(FilesEvent.Terminal(dir)) }
    }

    fun runScript(entry: FileEntry) {
        viewModelScope.launch { events.send(FilesEvent.Run(entry.path)) }
    }

    /**
     * Opens a file with another app. Shared storage and system files are handed over as the
     * descriptor root opened (RootFileProvider: instant, seekable, nothing copied); the rest is
     * copied to the cache first.
     */
    fun openWith(entry: FileEntry, mime: String) = handOver(listOf(entry)) { uris -> FilesEvent.Open(uris.single(), mime) }

    fun share(entries: List<FileEntry>) =
        handOver(entries.filter { !it.isDir && it.type != FileEntry.TYPE_OTHER && !(it.isLink && it.linkBroken) }) { FilesEvent.Share(it) }

    private fun handOver(entries: List<FileEntry>, event: (List<android.net.Uri>) -> FilesEvent) {
        if (entries.isEmpty()) return
        if (entries.all(cam.su.kernel.files.RootFileProvider::canStream)) {
            viewModelScope.launch { events.send(event(entries.map(cam.su.kernel.files.RootFileProvider::uriFor))) }
            return
        }
        cached(entries, copyAll = false) { files ->
            event(
                entries.zip(files).map { (e, f) ->
                    if (cam.su.kernel.files.RootFileProvider.canStream(e)) {
                        cam.su.kernel.files.RootFileProvider.uriFor(e)
                    } else {
                        androidx.core.content.FileProvider.getUriForFile(camApp, "${camApp.packageName}.fileprovider", f)
                    }
                },
            )
        }
    }

    /**
     * Copies [entries] into the cache; [event] gets a file per entry. Without [copyAll], entries
     * that are streamed instead are not copied (their file stays empty and unused).
     */
    private fun cached(entries: List<FileEntry>, copyAll: Boolean = true, event: (List<File>) -> FilesEvent) {
        if (entries.isEmpty()) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val dir = File(camApp.cacheDir, "files_open").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val files = entries.map { e ->
                    File(dir, e.name).also { if (copyAll || !cam.su.kernel.files.RootFileProvider.canStream(e)) CamFileClient.copyToLocal(e.path, it) }
                }
                events.send(event(files))
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    // --- operations ---

    fun copy(sources: List<String>, move: Boolean, destDir: String = _uiState.value.otherPanel.nav.path) {
        if (sources.isEmpty()) return
        val intoArchive = Archives.isInside(destDir)
        // Out of an archive, copying is extracting; archive to archive is not supported.
        if (Archives.isInside(sources.first())) {
            if (intoArchive) {
                _uiState.update { it.copy(message = FilesMessage.Failed(cam.su.kernel.files.Errno.EINVAL, null)) }
                return
            }
            return extract(sources, destDir)
        }
        if (intoArchive && !Archives.isZip(Archives.split(destDir).first)) {
            _uiState.update { it.copy(message = FilesMessage.Failed(cam.su.kernel.files.Errno.EROFS, null)) }
            return
        }
        runOp(FileOp(if (move) OpKind.MOVE else OpKind.COPY, sources, destDir))
    }

    /** [sources] are paths inside one archive, or archives themselves (extracted whole). */
    fun extract(sources: List<String>, destDir: String = _uiState.value.otherPanel.nav.path) {
        val inside = sources.map { if (Archives.isInside(it)) it else Archives.join(it, "") }
        inside.groupBy { Archives.split(it).first }.values.forEach { runOp(FileOp(OpKind.EXTRACT, it, destDir)) }
    }

    fun compress(sources: List<String>, zipName: String) {
        if (sources.isEmpty()) return
        runOp(FileOp(OpKind.COMPRESS, sources, FsPath.child(FsPath.parent(sources.first()), zipName)))
    }

    /** Installs an APK or split bundle as root (pm install session). */
    fun install(entry: FileEntry) {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = try {
                FilesMessage.Text(CamFileClient.installPackage(entry.path))
            } catch (e: FsException) {
                FilesMessage.Failed(e.errno, e.message)
            }
            _uiState.update { it.copy(busy = false, message = message) }
        }
    }

    /** "Install as module": the Flash screen installs it like one picked in the Module tab. */
    fun flashModule(entry: FileEntry) = cached(listOf(entry)) { FilesEvent.Flash(it.single()) }

    /** Long-pressed Copy: a copy next to the original ("name (1)"). */
    fun copyHere(sources: List<String>) = copy(sources, move = false, destDir = FsPath.parent(sources.first()))

    /**
     * Delete: into the trash, unless [permanent] is asked for, or the items are inside an archive
     * (the archive is rewritten) or already in the trash.
     */
    fun delete(sources: List<String>, permanent: Boolean = false) {
        if (sources.isEmpty()) return
        runOp(FileOp(if (permanent || !canTrash(sources)) OpKind.DELETE else OpKind.TRASH, sources))
    }

    /** Whether Delete on [sources] can go to the trash at all (the dialog says so). */
    fun canTrash(sources: List<String>): Boolean {
        val trash = cam.su.kernel.files.Trash(cam.su.kernel.files.OsBackend)
        return sources.isNotEmpty() && !Archives.isInside(sources.first()) && sources.none(trash::isInTrash)
    }

    // --- trash ---

    fun showTrash() {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val list = CamFileClient.trashList()
                _uiState.update { it.copy(trash = list) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun hideTrash() = _uiState.update { it.copy(trash = null) }

    fun restoreTrash(item: cam.su.kernel.files.TrashItem) {
        hideTrash()
        runOp(FileOp(OpKind.RESTORE, listOf(item.dir))) { showTrash() }
    }

    fun purgeTrash(items: List<cam.su.kernel.files.TrashItem>) {
        if (items.isEmpty()) return
        hideTrash()
        runOp(FileOp(OpKind.PURGE, items.map { it.dir })) { showTrash() }
    }

    // FileOpProgress.scope, not viewModelScope: the notification's Cancel works after the screen is gone
    fun cancelOp() {
        val handle = opHandle ?: return
        FileOpProgress.scope.launch { runCatching { handle.cancel() } }
    }

    fun resolveConflict(choice: ConflictChoice, applyAll: Boolean) {
        val handle = opHandle ?: return
        _uiState.update { it.copy(op = it.op?.copy(conflict = null)) }
        FileOpProgress.scope.launch { runCatching { handle.resolve(choice, applyAll) } }
    }

    /** [then] runs after the operation finished without error. */
    private fun runOp(op: FileOp, then: (() -> Unit)? = null) {
        if (_uiState.value.op != null || FileOpProgress.state.value != null) {
            _uiState.update { it.copy(message = FilesMessage.Busy) }
            return
        }
        _uiState.update { it.copy(op = OpUi(op.kind)) }
        // a foreground service keeps the app, and the root process running the job, alive
        FileOpProgress.update(FileOpProgress.State(op.kind, 0, 0, ""))
        FileOpProgress.onCancel = { cancelOp() }
        FileOpService.start(camApp)
        // followed outside viewModelScope: the job must still end (notification, refresh) if
        // this screen goes away while it runs
        FileOpProgress.scope.launch {
            val handle = try {
                CamFileClient.startOp(op)
            } catch (e: FsException) {
                FileOpProgress.onCancel = null
                FileOpProgress.update(null)
                _uiState.update { it.copy(op = null, message = FilesMessage.Failed(e.errno, e.message)) }
                return@launch
            }
            opHandle = handle
            handle.events.collect { event ->
                when (event) {
                    is CamFileClient.OpEvent.Progress -> {
                        FileOpProgress.update(FileOpProgress.State(op.kind, event.done, event.total, event.current))
                        _uiState.update {
                            it.copy(op = it.op?.copy(done = event.done, total = event.total, current = event.current))
                        }
                    }

                    is CamFileClient.OpEvent.Conflict -> _uiState.update { it.copy(op = it.op?.copy(conflict = event)) }

                    is CamFileClient.OpEvent.Done -> {
                        opHandle = null
                        FileOpProgress.onCancel = null
                        FileOpProgress.update(null)
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
                        if (event.ok) then?.invoke()
                    }
                }
            }
        }
    }

    /** Runs a quick root call; errors become a message, success reloads. */
    private fun simple(refreshActive: Boolean, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
            if (refreshActive) refresh()
        }
    }

    // --- internals ---

    private fun home(panel: Int) =
        prefs.getString(if (panel == 0) KEY_HOME_0 else KEY_HOME_1, null) ?: if (panel == 0) DEFAULT_PATH_0 else DEFAULT_PATH_1

    private fun loadBookmarks(): List<String> = cam.su.kernel.files.Bookmarks.load(prefs)

    private fun saveBookmarks(list: List<String>) {
        cam.su.kernel.files.Bookmarks.save(prefs, list)
        _uiState.update { it.copy(bookmarks = list) }
    }

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
            val disk = if (Archives.isInside(path)) null else runCatching { CamFileClient.statFs(path) }.getOrNull()
            val list = result.getOrDefault(emptyList())
            val sort = _uiState.value.sort
            // thousands of names sort in tens of milliseconds: not on the main thread
            val sorted = withContext(Dispatchers.Default) { FileSort.apply(list, sort) }
            if (_uiState.value.panels[panel].nav.path != path) return@launch
            raw[panel] = list
            updateWatch()
            val entries = if (_uiState.value.sort == sort) sorted else FileSort.apply(list, _uiState.value.sort)
            val paths = entries.mapTo(HashSet()) { it.path }
            updatePanel(panel) { p ->
                p.copy(
                    entries = entries,
                    loading = false,
                    errorErrno = (result.exceptionOrNull() as? FsException)?.errno,
                    selection = p.selection.filterTo(HashSet()) { it in paths },
                    disk = disk,
                    loadedPath = path,
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
        _uiState.update { it.copy(sort = sort) }
        sortJob?.cancel()
        val lists = raw.copyOf()
        sortJob = viewModelScope.launch {
            val sorted = withContext(Dispatchers.Default) { lists.map { FileSort.apply(it, sort) } }
            _uiState.update { state ->
                state.copy(
                    panels = state.panels.mapIndexed { i, p ->
                        // a listing that arrived meanwhile was already sorted the new way
                        if (raw[i] !== lists[i]) return@mapIndexed p
                        val paths = sorted[i].mapTo(HashSet()) { it.path }
                        p.copy(entries = sorted[i], selection = p.selection.filterTo(HashSet()) { it in paths })
                    },
                )
            }
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
        const val KEY_HOME_0 = "home_0"
        const val KEY_HOME_1 = "home_1"
        const val KEY_ACTIVE = "active"
        const val KEY_SORT_BY = "sort_by"
        const val KEY_SORT_DESC = "sort_desc"
        const val KEY_SHOW_HIDDEN = "show_hidden"

        const val RELOAD_DELAY_MS = 400L
    }
}
