package me.weishu.kernelsu.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.screen.gki.BuildsState
import me.weishu.kernelsu.ui.screen.gki.GkiInstallUiState
import me.weishu.kernelsu.ui.screen.gki.GkiMethod
import me.weishu.kernelsu.ui.screen.gki.GkiSource
import me.weishu.kernelsu.ui.util.SfsBuild
import me.weishu.kernelsu.ui.util.fetchSfsBuilds
import me.weishu.kernelsu.ui.util.getAk3Backups
import me.weishu.kernelsu.ui.util.loadGkiStatus

class GkiInstallViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GkiInstallUiState())
    val uiState: StateFlow<GkiInstallUiState> = _uiState.asStateFlow()

    private var buildsJob: Job? = null

    init {
        refresh()
    }

    /** Status and backups change after a flash or restore; the build list is kept. */
    fun refresh() {
        viewModelScope.launch {
            val status = loadGkiStatus()
            val backups = getAk3Backups()
            _uiState.update { it.copy(status = status, backups = backups) }
            if (_uiState.value.builds !is BuildsState.Loaded) loadBuilds()
        }
    }

    fun loadBuilds() {
        val kmi = _uiState.value.status.kmi
        if (kmi.isBlank()) return
        buildsJob?.cancel()
        buildsJob = viewModelScope.launch {
            _uiState.update { it.copy(builds = BuildsState.Loading) }
            val result = try {
                BuildsState.Loaded(fetchSfsBuilds(kmi))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BuildsState.Failed(e.message ?: e.javaClass.simpleName)
            }
            _uiState.update { state ->
                // the newest build is the default until the user picks another
                val build = state.build ?: (result as? BuildsState.Loaded)?.builds?.firstOrNull()
                state.copy(builds = result, build = build)
            }
        }
    }

    fun selectMethod(method: GkiMethod) = _uiState.update { it.copy(method = method) }

    fun selectSource(source: GkiSource) = _uiState.update { it.copy(source = source) }

    fun selectBuild(build: SfsBuild) = _uiState.update { it.copy(build = build) }

    fun selectLocalZip(uri: Uri, name: String) = _uiState.update { it.copy(localZip = uri, localZipName = name) }

    fun toggleAdvanced() = _uiState.update { it.copy(advancedShown = !it.advancedShown) }

    fun toggleRestore() = _uiState.update { it.copy(restoreShown = !it.restoreShown) }

    fun setBackupBoot(enabled: Boolean) = _uiState.update { it.copy(backupBoot = enabled) }
}
