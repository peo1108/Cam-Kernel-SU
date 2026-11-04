package cam.su.kernel.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cam.su.kernel.camApp
import cam.su.kernel.data.repository.isSoftRebootPreferred
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import cam.su.kernel.hiding.ModuleRiskHit
import cam.su.kernel.ui.bunker.BunkerSignals
import cam.su.kernel.ui.screen.flash.FlashIt
import cam.su.kernel.ui.screen.flash.FlashingStatus
import cam.su.kernel.ui.screen.flash.flashIt
import cam.su.kernel.ui.screen.flash.saveLog
import cam.su.kernel.ui.util.FlashResult
import cam.su.kernel.ui.util.checkModuleRisk
import cam.su.kernel.ui.util.getFileName
import cam.su.kernel.ui.util.module.ModuleProp
import cam.su.kernel.ui.util.module.readModulePropFromZip
import cam.su.kernel.ui.util.reboot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

enum class ModuleInstallStage { Picking, Flashing, Finished }

/** A zip picked for installing; what it holds shows up as soon as it has been read. */
@Immutable
data class ZipPick(
    /** its path, or the Uri a system file picker gave */
    val key: String,
    val name: String,
    val file: File? = null,
    val loading: Boolean = true,
    val prop: ModuleProp? = null,
    val risk: ModuleRiskHit? = null,
    /** the risk gate would refuse it */
    val blocked: Boolean = false,
    val failed: Boolean = false,
) {
    val installable: Boolean get() = !loading && file != null && prop != null && !blocked
}

@Immutable
data class ModuleInstallUi(
    val open: Boolean = false,
    val stage: ModuleInstallStage = ModuleInstallStage.Picking,
    val path: String = ModuleInstallViewModel.DOWNLOADS,
    val entries: List<FileEntry> = emptyList(),
    val listing: Boolean = false,
    val errno: Int? = null,
    val picks: List<ZipPick> = emptyList(),
    val log: String = "",
    val status: FlashingStatus = FlashingStatus.FLASHING,
    val showReboot: Boolean = false,
) {
    val flashing: Boolean get() = stage == ModuleInstallStage.Flashing
}

/** What the browser lists: folders first by name, then zips, the newest on top (just downloaded). */
internal fun installEntries(entries: List<FileEntry>): List<FileEntry> {
    val folders = entries.filter { it.isDir }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val zips = entries.filter { !it.isDir && it.name.endsWith(".zip", ignoreCase = true) }.sortedByDescending { it.mtime }
    return folders + zips
}

/** One line of a flash's output added to what the log shows: a terminal's clear command starts it over. */
internal fun appendFlashOutput(log: String, line: String): String =
    if (line.startsWith(CLEAR_SCREEN)) line.removePrefix(CLEAR_SCREEN) + "\n" else log + line + "\n"

private const val CLEAR_SCREEN = "\u001b[H\u001b[J"

/**
 * Installing modules beside the Module list: finding the zips, what they hold, the flash. It lives
 * here and not in the pane, which goes when the tablet turns upright: a flash must go on then.
 */
class ModuleInstallViewModel : ViewModel() {
    private val prefs = camApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(ModuleInstallUi(path = prefs.getString(KEY_PATH, null) ?: DOWNLOADS))
    val uiState: StateFlow<ModuleInstallUi> = _uiState.asStateFlow()

    // the shell's lines come on the main thread, what the risk gate prints on the flash's own
    private val logContent = StringBuilder()
    private val cacheRoot = File(camApp.cacheDir, "module_install")

    // a folder per opening, so clearing the last one cannot take a zip just picked
    private var cacheSession = 0
    private var nextCacheFile = 0
    private val cacheDir get() = File(cacheRoot, cacheSession.toString())
    private val readJobs = HashMap<String, Job>()
    private var listJob: Job? = null

    fun open() {
        _uiState.update { it.copy(open = true) }
        list()
    }

    /**
     * Leaves the pane, unless a flash is running; returns the modules that went in. Their zips are
     * gone after this, and the list has the modules by now.
     */
    fun close(): List<String> {
        val state = _uiState.value
        if (state.flashing) return emptyList()
        val installed = if (state.stage == ModuleInstallStage.Finished && state.status == FlashingStatus.SUCCESS) {
            state.picks.filter { it.installable }.mapNotNull { it.prop?.id }
        } else {
            emptyList()
        }
        readJobs.values.forEach { it.cancel() }
        readJobs.clear()
        logContent.clear()
        _uiState.update {
            it.copy(open = false, stage = ModuleInstallStage.Picking, picks = emptyList(), log = "", showReboot = false, status = FlashingStatus.FLASHING)
        }
        val old = cacheDir
        cacheSession++
        viewModelScope.launch(Dispatchers.IO) { old.deleteRecursively() }
        return installed
    }

    fun navigate(path: String) {
        _uiState.update { it.copy(path = path) }
        prefs.edit { putString(KEY_PATH, path) }
        list()
    }

    fun up() {
        val path = _uiState.value.path
        if (path != "/") navigate(FsPath.parent(path))
    }

    private fun list() {
        val path = _uiState.value.path
        listJob?.cancel()
        _uiState.update { it.copy(listing = true) }
        listJob = viewModelScope.launch {
            val result = try {
                Result.success(CamFileClient.listDir(path))
            } catch (e: FsException) {
                Result.failure(e)
            }
            val entries = withContext(Dispatchers.Default) { installEntries(result.getOrDefault(emptyList())) }
            _uiState.update { state ->
                if (state.path != path) return@update state
                state.copy(entries = entries, listing = false, errno = (result.exceptionOrNull() as? FsException)?.errno)
            }
        }
    }

    /** A tap on a zip: picks it, or takes it back. */
    fun toggle(entry: FileEntry) {
        if (_uiState.value.picks.any { it.key == entry.path }) {
            remove(entry.path)
        } else {
            add(entry.path, entry.name) { target -> CamFileClient.copyToLocal(entry.path, target) }
        }
    }

    /** What a system file picker gave, for what the browser cannot reach (Drive, a USB stick). */
    fun addUris(uris: List<Uri>) {
        for (uri in uris) {
            // a provider may refuse even this: the copy below says so on the card
            val name = runCatching { uri.getFileName(camApp) }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "module.zip"
            add(uri.toString(), name) { target ->
                withContext(Dispatchers.IO) {
                    camApp.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw IOException("cannot open $uri")
                }
            }
        }
    }

    fun remove(key: String) {
        readJobs.remove(key)?.cancel()
        _uiState.update { state -> state.copy(picks = state.picks.filterNot { it.key == key }) }
    }

    private fun add(key: String, name: String, copy: suspend (File) -> Unit) {
        if (_uiState.value.picks.any { it.key == key }) return
        _uiState.update { it.copy(picks = it.picks + ZipPick(key, name)) }
        readJobs[key] = viewModelScope.launch {
            val read = try {
                val target = File(cacheDir.apply { mkdirs() }, "${nextCacheFile++}.zip")
                copy(target)
                withContext(Dispatchers.IO) { readPick(key, name, target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a file picker's provider can throw nearly anything: the card says it cannot read it
                Log.w("ModuleInstall", "cannot read $key", e)
                ZipPick(key, name, loading = false, failed = true)
            }
            _uiState.update { state -> state.copy(picks = state.picks.map { if (it.key == key) read else it }) }
        }
    }

    // the same check the flash makes, to show it before the flash starts
    private fun readPick(key: String, name: String, file: File): ZipPick {
        val prop = readModulePropFromZip(file)
        val risk = if (prop != null) checkModuleRisk(file) else null
        return ZipPick(key, name, file, loading = false, prop = prop, risk = risk?.hit, blocked = risk?.block == true)
    }

    fun install() {
        val state = _uiState.value
        if (state.stage != ModuleInstallStage.Picking || state.picks.any { it.loading }) return
        val uris = state.picks.filter { it.installable }.mapNotNull { pick -> pick.file?.let(Uri::fromFile) }
        if (uris.isEmpty()) return
        synchronized(logContent) { logContent.clear() }
        _uiState.update { it.copy(stage = ModuleInstallStage.Flashing, log = "", status = FlashingStatus.FLASHING, showReboot = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                flashIt(
                    FlashIt.FlashModules(uris),
                    onStdout = { line ->
                        synchronized(logContent) { logContent.append(line).append("\n") }
                        _uiState.update { it.copy(log = appendFlashOutput(it.log, line)) }
                    },
                    onStderr = { line -> synchronized(logContent) { logContent.append(line).append("\n") } },
                )
            } catch (e: IOException) {
                // the zip is gone from the cache (the system cleared it) or the disk is full
                Log.w("ModuleInstall", "cannot flash", e)
                FlashResult(1, e.message.orEmpty(), false)
            }
            // on the main thread: the shell's last lines are queued there, and go first
            withContext(Dispatchers.Main) {
                _uiState.update { ui ->
                    ui.copy(
                        stage = ModuleInstallStage.Finished,
                        status = if (result.code == 0) FlashingStatus.SUCCESS else FlashingStatus.FAILED,
                        showReboot = result.showReboot,
                        log = if (result.code == 0) ui.log else ui.log + "Error code: ${result.code}.\n ${result.err} Please save and check the log.\n",
                    )
                }
                if (result.code == 0) BunkerSignals.lock()
            }
        }
    }

    fun saveLog(showMessage: (String) -> Unit) {
        saveLog(StringBuilder(synchronized(logContent) { logContent.toString() }), viewModelScope, showMessage)()
    }

    fun reboot() {
        viewModelScope.launch(Dispatchers.IO) { reboot(if (isSoftRebootPreferred()) "soft_reboot" else "") }
    }

    companion object {
        const val DOWNLOADS = "/storage/emulated/0/Download"
        const val STORAGE = "/storage/emulated/0"
        private const val PREFS = "module_install"
        private const val KEY_PATH = "path"
    }
}
