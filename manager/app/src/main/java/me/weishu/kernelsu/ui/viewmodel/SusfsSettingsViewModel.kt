package me.weishu.kernelsu.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.weishu.kernelsu.ui.screen.susfs.SusfsSettingsUiState
import me.weishu.kernelsu.ui.util.SusfsConfig
import me.weishu.kernelsu.ui.util.SusfsSaveResult
import me.weishu.kernelsu.ui.util.loadSusfsState
import me.weishu.kernelsu.ui.util.saveSusfsConfig
import me.weishu.kernelsu.ui.util.stockKernelBuild

class SusfsSettingsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SusfsSettingsUiState())
    val uiState: StateFlow<SusfsSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Reload from ksud; unsaved edits are kept. */
    fun refresh() {
        viewModelScope.launch {
            val state = loadSusfsState()
            _uiState.update {
                val edited = if (it.loading || !it.dirty) state.config else it.edited
                it.copy(loading = false, state = state, saved = state.config, edited = edited)
            }
        }
    }

    fun update(change: SusfsConfig.() -> SusfsConfig) = _uiState.update { it.copy(edited = it.edited.change()) }

    fun replace(config: SusfsConfig) = _uiState.update { it.copy(edited = config) }

    fun useStockBuild() {
        viewModelScope.launch {
            val build = stockKernelBuild()
            if (build.isNotEmpty()) update { copy(unameVersion = build) }
        }
    }

    fun save(context: Context, onDone: (SusfsSaveResult) -> Unit) {
        if (_uiState.value.saving) return
        val config = _uiState.value.edited
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = saveSusfsConfig(context.applicationContext, config)
            _uiState.update {
                if (result.saved) {
                    it.copy(saving = false, saved = config, state = it.state.copy(config = config))
                } else {
                    it.copy(saving = false)
                }
            }
            // the running uname may have changed
            if (result.saved) refresh()
            onDone(result)
        }
    }
}
