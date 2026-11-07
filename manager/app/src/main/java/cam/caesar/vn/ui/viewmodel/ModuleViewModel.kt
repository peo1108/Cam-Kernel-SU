package cam.caesar.vn.ui.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cam.caesar.vn.Cam
import cam.caesar.vn.R
import cam.caesar.vn.data.model.Module
import cam.caesar.vn.data.model.ModuleUpdateInfo
import cam.caesar.vn.data.model.RepoModule
import cam.caesar.vn.data.repository.ModuleRepoRepositoryImpl
import cam.caesar.vn.data.repository.ModuleRepository
import cam.caesar.vn.data.repository.ModuleRepositoryImpl
import cam.caesar.vn.data.repository.SettingsRepository
import cam.caesar.vn.data.repository.SettingsRepositoryImpl
import cam.caesar.vn.camApp
import cam.caesar.vn.ui.component.SearchStatus
import cam.caesar.vn.ui.screen.module.ModuleConfirmDialogState
import cam.caesar.vn.ui.screen.module.ModuleConfirmRequest
import cam.caesar.vn.ui.screen.module.ModuleEffect
import cam.caesar.vn.ui.screen.module.ModuleUiState
import cam.caesar.vn.ui.util.PinyinUtil
import cam.caesar.vn.ui.util.hasMagisk
import cam.caesar.vn.ui.util.module.fetchModuleDetail
import cam.caesar.vn.ui.util.module.fetchReleaseDescriptionHtml
import okhttp3.Request
import java.text.Collator
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import cam.caesar.vn.ui.util.restoreModule as restoreModuleUtil
import cam.caesar.vn.ui.util.toggleModule as toggleModuleUtil
import cam.caesar.vn.ui.util.undoUninstallModule as undoUninstallModuleUtil
import cam.caesar.vn.ui.util.uninstallModule as uninstallModuleUtil

class ModuleViewModel(
    private val repo: ModuleRepository = ModuleRepositoryImpl(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    companion object {
        /** how long the module library list is reused for update checks */
        private const val LIBRARY_TTL_MS = 10 * 60 * 1000L
        private const val TAG = "ModuleViewModel"
    }

    private val _uiState = MutableStateFlow(ModuleUiState())
    val uiState: StateFlow<ModuleUiState> = _uiState.asStateFlow()

    // One-shot UI events (toast/snackbar): buffered Channel, never dropped/duplicated/overwritten
    private val _moduleEvent = Channel<ModuleEffect>(Channel.BUFFERED)
    val moduleEvent: Flow<ModuleEffect> = _moduleEvent.receiveAsFlow()

    private var libraryCache: Pair<Long, List<RepoModule>>? = null

    private val updateChecker = ModuleUpdateChecker(
        isEnabled = { settingsRepo.checkModuleUpdate },
        checkModule = { module -> checkUpdate(module) },
        fetchLibrary = { libraryModules() },
    )
    private val searchQuery = MutableStateFlow("")

    private var fetchJob: Job? = null
    /** bumped by each [fetchModuleList]; only the newest fetch may end the refresh */
    private var fetchGeneration = 0
    /** a fetch asked to check updates and none has finished doing so (one may have been cancelled) */
    private var pendingUpdateCheck = false

    var isNeedRefresh = false
        private set

    /**
     * The startup preload (MainScreen) and the Module page's first activation ran for this view
     * model. Kept here, not in saved composition state: they must run again when the process was
     * killed and this view model starts empty, and not when MainScreen comes back from a page.
     */
    var startupPreloadDone = false
        private set
    var pageActivated = false
        private set

    init {
        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
        viewModelScope.launch {
            updateChecker.results.collect { results ->
                _uiState.update { if (it.updateInfo == results) it else it.copy(updateInfo = results) }
            }
        }
        // the switch in Settings (or a config restore): off drops the results at once, on checks
        viewModelScope.launch {
            settingsRepo.checkModuleUpdateChanges.collectLatest { enabled ->
                val state = _uiState.value
                if (!enabled || state.hasLoaded) updateChecker.sync(state.modules)
            }
        }
        // another page changed modules or the conflict options
        viewModelScope.launch {
            ModuleListSignal.version.drop(1).collect {
                if (_uiState.value.hasLoaded) fetchModuleList(resort = false)
            }
        }
    }

    fun markNeedRefresh() {
        isNeedRefresh = true
    }

    fun markStartupPreloadDone() {
        startupPreloadDone = true
    }

    fun markPageActivated() {
        pageActivated = true
    }

    fun initializePreferences() {
        _uiState.update {
            it.copy(
                sortEnabledFirst = settingsRepo.moduleSortEnabledFirst,
                sortActionFirst = settingsRepo.moduleSortActionFirst,
            )
        }
        updateModuleList()
    }

    fun toggleSortActionFirst() {
        val newValue = !_uiState.value.sortActionFirst
        settingsRepo.moduleSortActionFirst = newValue
        _uiState.update { it.copy(sortActionFirst = newValue) }
        updateModuleList()
    }

    fun toggleSortEnabledFirst() {
        val newValue = !_uiState.value.sortEnabledFirst
        settingsRepo.moduleSortEnabledFirst = newValue
        _uiState.update { it.copy(sortEnabledFirst = newValue) }
        updateModuleList()
    }

    fun refreshEnvironmentState() {
        viewModelScope.launch {
            val magiskInstalled = withContext(Dispatchers.IO) { hasMagisk() }
            val isSafeMode = Cam.isSafeMode
            _uiState.update {
                it.copy(
                    magiskInstalled = magiskInstalled,
                    isSafeMode = isSafeMode,
                )
            }
        }
    }

    fun updateSearchStatus(status: SearchStatus) {
        val previous = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (previous.searchText != status.searchText) {
            searchQuery.value = status.searchText
        }
    }

    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }

    private fun filterModules(modules: List<Module>, text: String): List<Module> {
        if (text.isEmpty()) return emptyList()

        return modules.filter {
            it.id.contains(text, true) || it.name.contains(text, true) ||
                    it.description.contains(text, true) || it.author.contains(text, true) ||
                    PinyinUtil.toPinyin(it.name).contains(text, true)
        }
    }

    private suspend fun applySearchText(text: String) {
        _uiState.update {
            it.copy(
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchLoadingStatusFor(text)
                )
            )
        }

        if (text.isEmpty()) {
            updateModuleList()
            return
        }

        val result = withContext(Dispatchers.IO) {
            val state = _uiState.value
            filterModules(state.modules, text).sortedWith(moduleComparator(state))
        }

        _uiState.update {
            it.copy(
                searchResults = result,
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchResultStatusFor(text, result.isEmpty())
                )
            )
        }
    }

    private fun updateModuleList(resort: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val searchText = state.searchStatus.searchText
            val shorted = if (resort || state.moduleList.isEmpty()) {
                state.modules.sortedWith(moduleComparator(state))
            } else {
                // Order-preserving reload: keep order, refresh data, drop removed, append new (no re-sort on toggle/uninstall)
                val byId = state.modules.associateBy { it.id }
                val existingIds = state.moduleList.mapTo(HashSet()) { it.id }
                state.moduleList.mapNotNull { byId[it.id] } + state.modules.filter { it.id !in existingIds }
            }
            val searchResults = filterModules(shorted, searchText)

            _uiState.update {
                it.copy(
                    moduleList = shorted,
                    searchResults = searchResults,
                    searchStatus = it.searchStatus.copy(
                        resultStatus = searchResultStatusFor(searchText, searchResults.isEmpty())
                    )
                )
            }
        }
    }

    private fun moduleComparator(state: ModuleUiState): Comparator<Module> {
        return compareBy<Module>(
            {
                val executable = it.hasWebUi || it.hasActionScript
                when {
                    it.metamodule && it.enabled -> 0
                    state.sortEnabledFirst && state.sortActionFirst -> when {
                        it.enabled && executable -> 1
                        it.enabled -> 2
                        executable -> 3
                        else -> 4
                    }

                    state.sortEnabledFirst && !state.sortActionFirst -> if (it.enabled) 1 else 2
                    !state.sortEnabledFirst && state.sortActionFirst -> if (executable) 1 else 2
                    else -> 1
                }
            },
            { if (state.sortEnabledFirst) !it.enabled else 0 },
            { if (state.sortActionFirst) !(it.hasWebUi || it.hasActionScript) else 0 },
        ).thenBy(Collator.getInstance(Locale.getDefault()), Module::id)
    }

    suspend fun loadModuleList(resort: Boolean = true) {
        val parsedModules = withContext(Dispatchers.IO) {
            repo.getModules().getOrElse {
                Log.e(TAG, "fetchModuleList: ", it)
                emptyList()
            }
        }

        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    modules = parsedModules,
                )
            }
            // Trigger recalculation of moduleList
            updateModuleList(resort)
        }
    }

    fun fetchModuleList(checkUpdate: Boolean = false, resort: Boolean = true) {
        // A fetch this one replaces may have been asked to check updates, and a flash or action
        // (isNeedRefresh) can change versions: keep that check, it is not lost with the old job.
        if (checkUpdate || isNeedRefresh) pendingUpdateCheck = true
        isNeedRefresh = false
        fetchJob?.cancel()
        val generation = ++fetchGeneration
        _uiState.update { it.copy(isRefreshing = true) }
        fetchJob = viewModelScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()

                loadModuleList(resort)

                if (pendingUpdateCheck) {
                    syncModuleUpdateInfo(_uiState.value.modules)
                    pendingUpdateCheck = false
                }

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}, modules: ${_uiState.value.modules}")
            } finally {
                // a job cancelled by a newer fetch must not end that one's refresh
                if (generation == fetchGeneration) {
                    _uiState.update { it.copy(isRefreshing = false, hasLoaded = true) }
                }
            }
        }
    }

    /** Checks [modules] for updates (unless the setting is off: then the results are dropped). */
    suspend fun syncModuleUpdateInfo(modules: List<Module>) = updateChecker.sync(modules)

    fun requestUpdateConfirmation(module: Module, updateInfo: ModuleUpdateInfo) {
        viewModelScope.launch {
            val dialogState = buildUpdateConfirmDialogState(module, updateInfo)
            _uiState.update { it.copy(confirmDialogState = dialogState) }
        }
    }

    fun requestUninstallConfirmation(module: Module) {
        val res = camApp.resources
        _uiState.update {
            it.copy(
                confirmDialogState = ModuleConfirmDialogState(
                    request = ModuleConfirmRequest.Uninstall(module),
                    title = res.getString(R.string.module),
                    content = (if (module.metamodule) res.getString(R.string.metamodule_uninstall_confirm) else res.getString(R.string.module_uninstall_confirm)).format(
                        module.name
                    ),
                    confirm = res.getString(R.string.uninstall),
                    dismiss = res.getString(android.R.string.cancel),
                )
            )
        }
    }

    fun dismissConfirmRequest() {
        _uiState.update { it.copy(confirmDialogState = null) }
    }

    fun emitEffect(effect: ModuleEffect) {
        _moduleEvent.trySend(effect)
    }

    fun toggleModule(module: Module) {
        viewModelScope.launch {
            val res = camApp.resources
            val success = withContext(Dispatchers.IO) {
                toggleModuleUtil(module.id, !module.enabled)
            }
            if (success) {
                fetchModuleList(checkUpdate = true, resort = false)
                emitEffect(ModuleEffect.SnackBar(res.getString(R.string.reboot_to_apply)))
            } else {
                val message = if (module.enabled) R.string.module_failed_to_disable else R.string.module_failed_to_enable
                emitEffect(ModuleEffect.SnackBar(res.getString(message).format(module.name)))
            }
        }
    }

    /** Disables [id] if it is enabled; the conflict dialog offers this for every module involved. */
    fun disableModule(id: String) {
        val module = _uiState.value.modules.firstOrNull { it.id == id } ?: return
        if (module.enabled) toggleModule(module)
    }

    fun uninstallModule(module: Module) {
        viewModelScope.launch {
            val res = camApp.resources
            val success = withContext(Dispatchers.IO) {
                uninstallModuleUtil(module.id)
            }
            if (success) {
                fetchModuleList(checkUpdate = true, resort = false)
            }
            _uiState.update { it.copy(confirmDialogState = null) }
            emitEffect(
                ModuleEffect.SnackBar(
                    res.getString(
                        if (success) R.string.module_uninstall_success else R.string.module_uninstall_failed
                    ).format(module.name)
                )
            )
        }
    }

    fun restoreModule(module: Module) {
        viewModelScope.launch {
            val res = camApp.resources
            val success = withContext(Dispatchers.IO) { restoreModuleUtil(module.id) }
            if (success) fetchModuleList(resort = false)
            emitEffect(
                ModuleEffect.SnackBar(
                    res.getString(
                        if (success) R.string.module_restore_success else R.string.module_restore_failed,
                        module.name,
                    )
                )
            )
        }
    }

    fun undoUninstallModule(module: Module) {
        viewModelScope.launch {
            val res = camApp.resources
            val success = withContext(Dispatchers.IO) {
                undoUninstallModuleUtil(module.id)
            }
            if (success) {
                fetchModuleList(checkUpdate = true, resort = false)
            }
            emitEffect(
                ModuleEffect.SnackBar(
                    res.getString(
                        if (success) R.string.module_undo_uninstall_success else R.string.module_undo_uninstall_failed
                    ).format(module.name)
                )
            )
        }
    }

    private suspend fun buildUpdateConfirmDialogState(
        module: Module,
        updateInfo: ModuleUpdateInfo,
    ): ModuleConfirmDialogState {
        val res = camApp.resources
        val changelogUrl = updateInfo.changelog

        var changelog = ""
        var html = false

        if (changelogUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                if (changelogUrl.startsWith("#") && changelogUrl.contains('@')) {
                    val parts = changelogUrl.substring(1).split('@', limit = 2)
                    if (parts.size == 2) {
                        fetchReleaseDescriptionHtml(parts[0], parts[1])?.let {
                            changelog = it
                            html = true
                        }
                    }
                }

                if (changelog.isBlank()) {
                    // an error page (404, 500) is not a changelog: fall back like an empty one
                    changelog = runCatching {
                        camApp.okhttpClient.newCall(
                            Request.Builder().url(changelogUrl).build()
                        ).execute().use { response ->
                            if (response.isSuccessful) response.body.string() else ""
                        }
                    }.getOrDefault("")
                }
            }
        }

        if (changelog.isBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val latestTag = fetchModuleDetail(module.id)?.latestTag.orEmpty()
                    if (latestTag.isNotBlank()) {
                        fetchReleaseDescriptionHtml(module.id, latestTag)?.let {
                            changelog = it
                            html = true
                        }
                    }
                }
            }
        }

        return ModuleConfirmDialogState(
            request = ModuleConfirmRequest.Update(
                module = module,
                downloadUrl = updateInfo.downloadUrl,
                fileName = "${module.name}-${updateInfo.version}.zip",
            ),
            title = if (changelog.isNotBlank()) res.getString(R.string.module_changelog) else res.getString(R.string.module_update),
            content = changelog.ifBlank { res.getString(R.string.module_start_downloading).format(module.name) },
            markdown = changelog.isNotBlank() && !html,
            html = html,
            confirm = res.getString(R.string.module_update),
        )
    }

    /** The library's modules, fetched at most every [LIBRARY_TTL_MS]; empty when it cannot be read. */
    private suspend fun libraryModules(): List<RepoModule> {
        val now = SystemClock.elapsedRealtime()
        libraryCache?.let { (at, modules) -> if (now - at < LIBRARY_TTL_MS) return modules }
        val modules = withTimeoutOrNull(8_000L.milliseconds) { ModuleRepoRepositoryImpl().fetchModules().getOrNull() }
            ?: return libraryCache?.second.orEmpty()
        libraryCache = now to modules
        return modules
    }

    private suspend fun checkUpdate(m: Module): ModuleUpdateInfo {
        return repo.checkUpdate(m).getOrDefault(ModuleUpdateInfo.Empty)
    }
}
