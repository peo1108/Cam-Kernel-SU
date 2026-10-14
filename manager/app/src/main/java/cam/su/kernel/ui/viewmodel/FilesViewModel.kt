package cam.su.kernel.ui.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.R
import cam.su.kernel.camApp
import cam.su.kernel.files.AppView
import cam.su.kernel.files.Archives
import cam.su.kernel.files.Systemless
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

data class PanelUi(
    val nav: PanelNav,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    /** errno of the last failed listing; the list then stays empty. */
    val errorErrno: Int? = null,
    val selection: Set<String> = emptySet(),
    /** [total, available] bytes of the file system the folder is on. */
    val disk: LongArray? = null,
    /** The folder [entries] were listed from; it trails [nav] while the next one loads. */
    val loadedPath: String? = null,
) {
    // counted once per state, not on every recomposition of the header
    val folders by lazy(LazyThreadSafetyMode.PUBLICATION) { entries.count { it.isDir } }
    val files get() = entries.size - folders
}

data class OpUi(
    val kind: OpKind,
    val done: Long = 0,
    val total: Long = 0,
    val current: String = "",
    val conflict: CamFileClient.OpEvent.Conflict? = null,
)

/** MD5, SHA-1, SHA-256 of [entry]; null while computing. [token] tells one opening from the next. */
data class ChecksumUi(val entry: FileEntry, val token: Int, val values: List<String>? = null)

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

/** The systemless override dialog for one system file. */
data class OverrideUi(
    val entry: FileEntry,
    val target: String,
    val exists: Boolean,
    val metamodule: Boolean,
    /** a file with the same name in the other panel, offered as the replacement */
    val replacement: FileEntry?,
)

/**
 * The partition list: to back one up ([image] null), or to pick where [image] gets flashed.
 * [all] shows every partition, not just the boot chain.
 */
data class PartitionsUi(
    val list: List<cam.su.kernel.files.Partition>,
    val slot: String,
    val image: FileEntry? = null,
    val all: Boolean = false,
)

data class SearchUi(val root: String, val running: Boolean = true, val results: List<FileEntry> = emptyList())

/** The Properties dialog: [measure] fills in after the tree was walked. [token] tells one opening from the next. */
data class PropertiesUi(val entry: FileEntry, val token: Int = 0, val measure: LongArray? = null)

sealed interface FilesMessage {
    data class Finished(val kind: OpKind, val items: Int, val skipped: Int) : FilesMessage
    data object Cancelled : FilesMessage
    data class Failed(val errno: Int, val detail: String?) : FilesMessage
    data object Done : FilesMessage
    data class Text(val text: String) : FilesMessage

    /** Another copy/move/delete is still running: one at a time. */
    data object Busy : FilesMessage
}

/** One-shot work for the screen: hand a cached copy of a root file to another app. */
sealed interface FilesEvent {
    data class Open(val uri: android.net.Uri, val mime: String) : FilesEvent
    data class Share(val uris: List<android.net.Uri>) : FilesEvent
    /** A module zip to hand to the Flash screen. */
    data class Flash(val file: File) : FilesEvent
    data class Edit(val path: String) : FilesEvent
    data class Hex(val path: String) : FilesEvent
    data class Compare(val left: String, val right: String) : FilesEvent
    data class Run(val path: String) : FilesEvent
    data class Terminal(val cwd: String) : FilesEvent
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
    val override: OverrideUi? = null,
    /** running apps offered by "Browse as an app": (pid, uid, process) */
    val processes: List<Triple<Int, Int, String>>? = null,
    /** pid → process name of the apps browsed as */
    val appNames: Map<Int, String> = emptyMap(),
    val partitions: PartitionsUi? = null,
    /** The trash dialog's list; null while it is closed. */
    val trash: List<cam.su.kernel.files.TrashItem>? = null,
) {
    val activePanel get() = panels[active]
    val otherPanel get() = panels[1 - active]
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

    private var started = false
    private var sortJob: Job? = null

    /** Tells a dialog's opening from the next one, so a late answer for an old one is dropped. */
    private var dialogToken = 0

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
        val token = ++dialogToken
        _uiState.update { it.copy(properties = PropertiesUi(entry, token)) }
        viewModelScope.launch {
            val measure = try {
                CamFileClient.measure(entry.path)
            } catch (_: FsException) {
                return@launch
            }
            _uiState.update { s -> s.copy(properties = s.properties?.let { if (it.token == token) it.copy(measure = measure) else it }) }
        }
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

    fun showChecksum(entry: FileEntry) {
        val token = ++dialogToken
        _uiState.update { it.copy(checksum = ChecksumUi(entry, token)) }
        viewModelScope.launch {
            try {
                val values = CamFileClient.checksums(entry.path)
                _uiState.update { s -> s.copy(checksum = s.checksum?.let { if (it.token == token) it.copy(values = values) else it }) }
            } catch (e: FsException) {
                // closed meanwhile (that cancelled it): nothing to report
                if (_uiState.value.checksum?.token != token) return@launch
                _uiState.update { it.copy(checksum = null, message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun hideChecksum() {
        val computing = _uiState.value.checksum?.values == null
        _uiState.update { it.copy(checksum = null) }
        if (computing) cancelReads()
    }

    /** A closed Properties or Checksum dialog stops its walk or hash on the root side. */
    private fun cancelReads() {
        viewModelScope.launch { runCatching { CamFileClient.cancelReads() } }
    }

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

    // --- systemless overrides (needs a metamodule to mount modules) ---

    fun showOverride(entry: FileEntry) {
        viewModelScope.launch {
            try {
                val target = Systemless.target(entry.path)
                val other = _uiState.value.otherPanel.entries.firstOrNull { it.name == entry.name && !it.isDir && it.path != entry.path }
                val ui = OverrideUi(
                    entry = entry,
                    target = target,
                    exists = CamFileClient.stat(target) != null,
                    metamodule = CamFileClient.stat(Systemless.METAMODULE_LINK) != null,
                    replacement = other,
                )
                _uiState.update { it.copy(override = ui) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun hideOverride() = _uiState.update { it.copy(override = null) }

    /** Copies the system file into the module (once) and opens the copy in the editor. */
    fun overrideEdit() {
        val o = _uiState.value.override ?: return
        hideOverride()
        viewModelScope.launch {
            try {
                prepareOverride(o.target)
                if (o.exists) {
                    events.send(FilesEvent.Edit(o.target))
                } else {
                    runOp(FileOp(OpKind.COPY, listOf(o.entry.path), FsPath.parent(o.target))) {
                        viewModelScope.launch { events.send(FilesEvent.Edit(o.target)) }
                    }
                }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    /** Puts the same-named file from the other panel in place of the system one. */
    fun overrideReplace() {
        val o = _uiState.value.override ?: return
        val replacement = o.replacement ?: return
        hideOverride()
        viewModelScope.launch {
            try {
                prepareOverride(o.target)
                runOp(FileOp(OpKind.COPY, listOf(replacement.path), FsPath.parent(o.target), preserve = false))
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun overrideRemove() {
        val o = _uiState.value.override ?: return
        hideOverride()
        runOp(FileOp(OpKind.DELETE, listOf(o.target)))
    }

    private suspend fun prepareOverride(target: String) {
        mkdirs(FsPath.parent(target))
        val prop = FsPath.child(Systemless.MODULE, "module.prop")
        if (CamFileClient.stat(prop) == null) CamFileClient.write(prop) { it.write(Systemless.MODULE_PROP.toByteArray()) }
    }

    private suspend fun mkdirs(path: String) {
        if (path == "/" || CamFileClient.stat(path) != null) return
        mkdirs(FsPath.parent(path))
        CamFileClient.mkdir(path)
    }

    // --- browse as an app (/proc/<pid>/root) ---

    fun chooseApp() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(processes = CamFileClient.appProcesses()) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun hideProcesses() = _uiState.update { it.copy(processes = null) }

    // --- partitions: back up any, flash the boot chain ---

    /** The partition list; with [image], to choose where it gets flashed. */
    fun showPartitions(image: FileEntry? = null) {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val (list, slot) = CamFileClient.partitions()
                if (list.isEmpty()) throw FsException(Errno.ENOENT, detail = "/dev/block/by-name")
                _uiState.update { it.copy(partitions = PartitionsUi(cam.su.kernel.files.Partitions.ordered(list, slot), slot, image)) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun showAllPartitions() = _uiState.update { it.copy(partitions = it.partitions?.copy(all = true)) }

    fun hidePartitions() = _uiState.update { it.copy(partitions = null) }

    /** Into /sdcard/CamBackup/<name>_<time>.img; the active panel opens there when it is done. */
    fun backupPartition(name: String) {
        hidePartitions()
        val dest = cam.su.kernel.files.Partitions.backupPath(name, System.currentTimeMillis())
        viewModelScope.launch {
            try {
                mkdirs(FsPath.parent(dest))
                runOp(FileOp(OpKind.BACKUP, listOf(name), dest)) { navigateActive(FsPath.parent(dest)) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    /** What the flash dialog shows before it lets the flash go. */
    data class FlashCheck(val problem: cam.su.kernel.files.ImageProblem?, val room: Boolean)

    /** Reads the image header and the free space; the root side checks both again before writing. */
    suspend fun checkFlash(image: FileEntry, partition: cam.su.kernel.files.Partition): FlashCheck {
        val header = runCatching { CamFileClient.readRange(image.path, 0, cam.su.kernel.files.Partitions.HEADER_SIZE) }.getOrNull()
        val free = runCatching { CamFileClient.statFs(FsPath.parent(cam.su.kernel.files.Partitions.BACKUP_DIR))[1] }.getOrNull()
        return FlashCheck(
            problem = header?.let { cam.su.kernel.files.Partitions.checkImage(partition.name, it) },
            room = free == null || free >= cam.su.kernel.files.Partitions.backupRoom(partition.size),
        )
    }

    /**
     * The partition as it is goes to /sdcard/CamBackup first (the root side refuses to flash
     * without that path); the active panel opens there when it is done.
     */
    fun flashPartition(image: FileEntry, name: String) {
        hidePartitions()
        val backup = cam.su.kernel.files.Partitions.backupPath(name, System.currentTimeMillis())
        viewModelScope.launch {
            try {
                mkdirs(FsPath.parent(backup))
                runOp(FileOp(OpKind.FLASH, listOf(image.path, backup), name)) { navigateActive(FsPath.parent(backup)) }
            } catch (e: FsException) {
                _uiState.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun viewAsApp(pid: Int, name: String) {
        _uiState.update { it.copy(processes = null, appNames = it.appNames + (pid to name)) }
        navigateActive(AppView.root(pid))
    }

    fun hideProperties() {
        val measuring = _uiState.value.properties?.measure == null
        _uiState.update { it.copy(properties = null) }
        if (measuring) cancelReads()
    }

    private suspend fun reloadProperties(path: String) {
        if (_uiState.value.properties?.entry?.path != path) return
        CamFileClient.stat(path)?.let { fresh -> _uiState.update { it.copy(properties = it.properties?.copy(entry = fresh)) } }
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

        const val SEARCH_LIMIT = 500
        const val RELOAD_DELAY_MS = 400L
    }
}
