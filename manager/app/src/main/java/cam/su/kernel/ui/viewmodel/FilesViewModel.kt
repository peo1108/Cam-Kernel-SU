package cam.su.kernel.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.camApp
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.ConflictChoice
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileOp
import cam.su.kernel.files.FileSort
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.OpKind
import cam.su.kernel.files.PanelNav
import cam.su.kernel.files.SelectionOps
import cam.su.kernel.files.SortBy
import cam.su.kernel.files.SortOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PanelUi(
    val nav: PanelNav,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** errno of the last failed listing; the list then stays empty. */
    val errorErrno: Int? = null,
    val selection: Set<String> = emptySet(),
    /** [total, available] bytes of the file system the folder is on. */
    val disk: LongArray? = null,
) {
    val folders get() = entries.count { it.isDir }
    val files get() = entries.size - folders
}

data class OpUi(
    val kind: OpKind,
    val done: Long = 0,
    val total: Long = 0,
    val current: String = "",
    val conflict: CamFileClient.OpEvent.Conflict? = null,
)

/** MD5, SHA-1, SHA-256 of [entry]; null while computing. */
data class ChecksumUi(val entry: FileEntry, val values: List<String>? = null)

/** What an APK says about itself, read from a cached copy. */
data class ApkInfoUi(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val signer: String?,
    val permissions: List<String>,
    val icon: android.graphics.drawable.Drawable?,
)

data class SearchUi(val root: String, val running: Boolean = true, val results: List<FileEntry> = emptyList())

/** The Properties dialog: [measure] fills in after the tree was walked. */
data class PropertiesUi(val entry: FileEntry, val measure: LongArray? = null)

sealed interface FilesMessage {
    data class Finished(val kind: OpKind, val items: Int, val skipped: Int) : FilesMessage
    data object Cancelled : FilesMessage
    data class Failed(val errno: Int, val detail: String?) : FilesMessage
    data object Done : FilesMessage
    data class Text(val text: String) : FilesMessage
}

/** One-shot work for the screen: hand a cached copy of a root file to another app. */
sealed interface FilesEvent {
    data class Open(val file: File, val mime: String) : FilesEvent
    data class Share(val files: List<File>) : FilesEvent
    /** A module zip to hand to the Flash screen. */
    data class Flash(val file: File) : FilesEvent
    data class Edit(val path: String) : FilesEvent
    data class Hex(val path: String) : FilesEvent
}

data class FilesUiState(
    val panels: List<PanelUi>,
    val active: Int = 0,
    val sort: SortOptions = SortOptions(),
    val op: OpUi? = null,
    val message: FilesMessage? = null,
    val properties: PropertiesUi? = null,
    val bookmarks: List<String> = emptyList(),
    val busy: Boolean = false,
    val checksum: ChecksumUi? = null,
    val apkInfo: ApkInfoUi? = null,
    val search: SearchUi? = null,
) {
    val activePanel get() = panels[active]
    val otherPanel get() = panels[1 - active]
}

class FilesViewModel : ViewModel() {
    private val prefs = camApp.getSharedPreferences("files", Context.MODE_PRIVATE)
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
        _uiState.update { it.copy(panels = listOf(b, a)) }
        prefs.edit {
            putString(KEY_PATH_0, b.nav.path)
            putString(KEY_PATH_1, a.nav.path)
        }
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

    fun restoreBookmarks() = saveBookmarks((DEFAULT_BOOKMARKS + _uiState.value.bookmarks).distinct())

    // --- single file actions ---

    fun create(name: String, folder: Boolean) = simple(refreshActive = true) {
        val path = FsPath.child(_uiState.value.activePanel.nav.path, name)
        if (folder) CamFileClient.mkdir(path) else CamFileClient.createFile(path)
    }

    fun rename(path: String, newName: String) = simple(refreshActive = true) {
        CamFileClient.rename(path, FsPath.child(FsPath.parent(path), newName))
    }

    /** Long-pressed Move: to a path typed by hand (a folder, or a new full name). */
    fun moveTo(path: String, target: String) = simple(refreshActive = true) {
        val dest = target.trim().let { if (it.startsWith("/")) it else FsPath.child(FsPath.parent(path), it) }
        val existing = CamFileClient.stat(dest)
        CamFileClient.rename(path, if (existing?.isDir == true) FsPath.child(dest, FsPath.name(path)) else dest)
    }

    fun symlinkInOther(path: String) = simple(refreshActive = true) {
        CamFileClient.symlink(path, FsPath.child(_uiState.value.otherPanel.nav.path, FsPath.name(path)))
    }

    fun chmod(path: String, mode: Int, recursive: Boolean) = simple(refreshActive = true) {
        CamFileClient.chmod(path, mode, recursive)
        reloadProperties(path)
    }

    fun chown(path: String, uid: Int, gid: Int, recursive: Boolean) = simple(refreshActive = true) {
        CamFileClient.chown(path, uid, gid, recursive)
        reloadProperties(path)
    }

    fun setContext(path: String, context: String, recursive: Boolean) = simple(refreshActive = true) {
        CamFileClient.setContext(path, context, recursive)
        reloadProperties(path)
    }

    fun showProperties(entry: FileEntry) {
        _uiState.update { it.copy(properties = PropertiesUi(entry)) }
        viewModelScope.launch {
            val measure = runCatching { CamFileClient.measure(entry.path) }.getOrNull() ?: return@launch
            _uiState.update { s -> s.copy(properties = s.properties?.takeIf { it.entry.path == entry.path }?.copy(measure = measure)) }
        }
    }

    fun edit(entry: FileEntry) {
        viewModelScope.launch { events.send(FilesEvent.Edit(entry.path)) }
    }

    fun hex(entry: FileEntry) {
        viewModelScope.launch { events.send(FilesEvent.Hex(entry.path)) }
    }

    fun showChecksum(entry: FileEntry) {
        _uiState.update { it.copy(checksum = ChecksumUi(entry)) }
        viewModelScope.launch {
            try {
                val values = CamFileClient.checksums(entry.path)
                _uiState.update { s -> s.copy(checksum = s.checksum?.takeIf { it.entry.path == entry.path }?.copy(values = values)) }
            } catch (e: FsException) {
                _uiState.update { it.copy(checksum = null, message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun hideChecksum() = _uiState.update { it.copy(checksum = null) }

    /** Reads the APK (or the base APK inside a bundle) like the installer would, without installing. */
    fun showApkInfo(entry: FileEntry) {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val source = if (Archives.isApkBundle(entry.name)) {
                    val inside = CamFileClient.listDir(Archives.join(entry.path, "")).filter { it.name.endsWith(".apk", true) }
                    (inside.firstOrNull { it.name.equals("base.apk", true) } ?: inside.firstOrNull())?.path
                        ?: throw FsException(cam.su.kernel.files.Errno.ENOENT, entry.path)
                } else {
                    entry.path
                }
                val file = File(camApp.cacheDir, "apk_info.apk")
                CamFileClient.copyToLocal(source, file)
                val info = readApk(file) ?: throw FsException(cam.su.kernel.files.Errno.EINVAL, entry.name, "not a valid APK")
                _uiState.update { it.copy(apkInfo = info) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun hideApkInfo() = _uiState.update { it.copy(apkInfo = null) }

    @Suppress("DEPRECATION")
    private fun readApk(file: File): ApkInfoUi? {
        val pm = camApp.packageManager
        val flags = android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { cert ->
            java.security.MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
        }
        return ApkInfoUi(
            label = app.loadLabel(pm).toString(),
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            signer = signer,
            permissions = info.requestedPermissions?.toList().orEmpty(),
            icon = runCatching { app.loadIcon(pm) }.getOrNull(),
        )
    }

    /** MT's search: from the active folder down, by name and optionally by content. */
    fun search(pattern: String, content: String) {
        val root = _uiState.value.activePanel.nav.path
        if (Archives.isInside(root)) return
        _uiState.update { it.copy(search = SearchUi(root)) }
        viewModelScope.launch {
            val results = try {
                CamFileClient.search(root, pattern, content.ifEmpty { null }, SEARCH_LIMIT)
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
                emptyList()
            }
            _uiState.update { s -> s.copy(search = s.search?.copy(running = false, results = results)) }
        }
    }

    fun stopSearch() {
        viewModelScope.launch { runCatching { CamFileClient.cancelSearch() } }
    }

    fun closeSearch() {
        if (_uiState.value.search?.running == true) stopSearch()
        _uiState.update { it.copy(search = null) }
    }

    /** A search hit: its folder opens in the active panel. */
    fun reveal(entry: FileEntry) {
        closeSearch()
        navigateActive(FsPath.parent(entry.path))
    }

    fun hideProperties() = _uiState.update { it.copy(properties = null) }

    private suspend fun reloadProperties(path: String) {
        if (_uiState.value.properties?.entry?.path != path) return
        CamFileClient.stat(path)?.let { fresh -> _uiState.update { it.copy(properties = it.properties?.copy(entry = fresh)) } }
    }

    /** Opens a file with another app: root files are copied to the cache first. */
    fun openWith(entry: FileEntry, mime: String) = cached(listOf(entry)) { files -> FilesEvent.Open(files.single(), mime) }

    fun share(entries: List<FileEntry>) = cached(entries.filter { !it.isDir }) { FilesEvent.Share(it) }

    private fun cached(entries: List<FileEntry>, event: (List<File>) -> FilesEvent) {
        if (entries.isEmpty()) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val dir = File(camApp.cacheDir, "files_open").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val files = entries.map { e -> File(dir, e.name).also { CamFileClient.copyToLocal(e.path, it) } }
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
        // Out of an archive, copying is extracting (archives are read-only here).
        if (Archives.isInside(sources.first())) return extract(sources, destDir)
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

    fun delete(sources: List<String>) {
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

    private fun loadBookmarks(): List<String> {
        if (!prefs.contains(KEY_BOOKMARKS)) return DEFAULT_BOOKMARKS
        return prefs.getString(KEY_BOOKMARKS, "").orEmpty().split('\n').filter { it.isNotEmpty() }
    }

    private fun saveBookmarks(list: List<String>) {
        prefs.edit { putString(KEY_BOOKMARKS, list.joinToString("\n")) }
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
            val disk = runCatching { CamFileClient.statFs(path) }.getOrNull()
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
                    disk = disk,
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
        const val KEY_HOME_0 = "home_0"
        const val KEY_HOME_1 = "home_1"
        const val KEY_ACTIVE = "active"
        const val KEY_SORT_BY = "sort_by"
        const val KEY_SORT_DESC = "sort_desc"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_BOOKMARKS = "bookmarks"

        const val SEARCH_LIMIT = 500

        val DEFAULT_BOOKMARKS = listOf(
            "/data/adb/modules",
            "/data/adb/cam",
            "/data/adb/service.d",
            "/data/adb/post-fs-data.d",
            "/data/local/tmp",
        )
    }
}
