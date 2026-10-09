package me.weishu.kernelsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.data.repository.SettingsRepository
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.screen.features.FeaturesUiState
import me.weishu.kernelsu.ui.util.applyHidingFixes
import me.weishu.kernelsu.ui.util.checkAttestationReport
import me.weishu.kernelsu.ui.util.clearBootGuard
import me.weishu.kernelsu.ui.util.getBootGuardStatus
import me.weishu.kernelsu.ui.util.getHideBootloaderStatus
import me.weishu.kernelsu.ui.util.listModuleConflicts
import me.weishu.kernelsu.ui.util.runHidingAudit
import me.weishu.kernelsu.ui.util.setBootGuardConfig
import me.weishu.kernelsu.ui.util.setHideBootloader as writeHideBootloader
import me.weishu.kernelsu.ui.util.toggleModule

class FeaturesViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FeaturesUiState(
            conflictDetection = settingsRepo.conflictDetection,
            conflictWarnOnFlash = settingsRepo.conflictWarnOnFlash,
            conflictIncludeProps = settingsRepo.conflictIncludeProps,
        )
    )
    val uiState: StateFlow<FeaturesUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val detection = settingsRepo.conflictDetection
            val (bootGuard, conflicts) = withContext(Dispatchers.IO) {
                getBootGuardStatus() to if (detection) listModuleConflicts() else emptyList()
            }
            val hideBootloader = withContext(Dispatchers.IO) { getHideBootloaderStatus() }
            _uiState.update {
                it.copy(
                    bootGuard = bootGuard,
                    hideBootloader = hideBootloader,
                    allConflicts = conflicts,
                    conflictDetection = detection,
                    conflictWarnOnFlash = settingsRepo.conflictWarnOnFlash,
                    conflictIncludeProps = settingsRepo.conflictIncludeProps,
                    scanning = false,
                )
            }
        }
    }

    fun setBootGuardEnabled(enabled: Boolean) {
        _uiState.update { it.copy(bootGuard = it.bootGuard.copy(enabled = enabled)) }
        applyBootGuard { setBootGuardConfig(enabled = enabled) }
    }

    /** [failures] is the number of failed boots tolerated (1..4); ksud stores the attempt that triggers. */
    fun setBootGuardFailures(failures: Int) {
        val threshold = failures.coerceIn(1, 4) + 1
        _uiState.update { it.copy(bootGuard = it.bootGuard.copy(threshold = threshold)) }
        applyBootGuard { setBootGuardConfig(threshold = threshold) }
    }

    fun setBootGuardDisableAll(disableAll: Boolean) {
        _uiState.update { it.copy(bootGuard = it.bootGuard.copy(disableAll = disableAll)) }
        applyBootGuard { setBootGuardConfig(disableAll = disableAll) }
    }

    fun reenableModule(id: String) = applyBootGuard { toggleModule(id, true).also { ModuleListSignal.invalidate() } }

    fun dismissBootGuard() = applyBootGuard { clearBootGuard() }

    fun setConflictDetection(enabled: Boolean) {
        settingsRepo.conflictDetection = enabled
        _uiState.update { it.copy(conflictDetection = enabled) }
        ModuleListSignal.invalidate()
        if (enabled) refresh()
    }

    fun setConflictWarnOnFlash(enabled: Boolean) {
        settingsRepo.conflictWarnOnFlash = enabled
        _uiState.update { it.copy(conflictWarnOnFlash = enabled) }
    }

    fun setConflictIncludeProps(enabled: Boolean) {
        settingsRepo.conflictIncludeProps = enabled
        _uiState.update { it.copy(conflictIncludeProps = enabled) }
        ModuleListSignal.invalidate()
    }

    fun rescanConflicts() = refresh()

    fun setHideBootloader(enabled: Boolean) {
        _uiState.update { it.copy(hideBootloader = it.hideBootloader.copy(enabled = enabled)) }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                writeHideBootloader(enabled)
                getHideBootloaderStatus()
            }
            _uiState.update { it.copy(hideBootloader = status) }
        }
    }

    fun checkAttestation() {
        _uiState.update { it.copy(checkingAttestation = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { checkAttestationReport() }
            _uiState.update {
                it.copy(
                    attestation = report.info,
                    chainSize = report.chainSize,
                    revoked = report.revoked,
                    attestationChecked = true,
                    checkingAttestation = false,
                )
            }
        }
    }

    fun runAudit() {
        _uiState.update { it.copy(auditing = true) }
        viewModelScope.launch {
            val audit = withContext(Dispatchers.IO) { runHidingAudit() }
            _uiState.update { it.copy(audit = audit, auditRun = true, auditing = false) }
        }
    }

    /** Turns on every suggested fix, then audits again so the card shows what is left. */
    fun applyAuditFixes() {
        _uiState.update { it.copy(auditing = true) }
        viewModelScope.launch {
            val (rebootNeeded, audit, hideBootloader) = withContext(Dispatchers.IO) {
                Triple(applyHidingFixes(), runHidingAudit(), getHideBootloaderStatus())
            }
            _uiState.update {
                it.copy(
                    audit = audit,
                    auditRun = true,
                    auditing = false,
                    auditRebootNeeded = rebootNeeded,
                    hideBootloader = hideBootloader,
                )
            }
        }
    }

    /** Run a ksud command, then read the boot guard back so the page shows what ksud stored. */
    private fun applyBootGuard(command: () -> Boolean) {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                command()
                getBootGuardStatus()
            }
            _uiState.update { it.copy(bootGuard = status) }
        }
    }
}
