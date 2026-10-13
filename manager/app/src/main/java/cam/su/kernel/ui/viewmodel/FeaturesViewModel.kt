package cam.su.kernel.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cam.su.kernel.adb.WirelessAdb
import cam.su.kernel.camApp
import cam.su.kernel.data.repository.HidingHistoryRepository
import cam.su.kernel.data.repository.SettingsRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.ui.screen.features.FeaturesUiState
import cam.su.kernel.ui.util.checkAttestationReport
import cam.su.kernel.ui.util.clearBootGuard
import cam.su.kernel.ui.util.getBootGuardStatus
import cam.su.kernel.ui.util.getHideBootloaderStatus
import cam.su.kernel.ui.util.listModuleConflicts
import cam.su.kernel.ui.util.setBootGuardConfig
import cam.su.kernel.ui.util.setHideBootloader as writeHideBootloader
import cam.su.kernel.ui.util.toggleModule

class FeaturesViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl(),
    private val hidingHistory: HidingHistoryRepository = HidingHistoryRepository(),
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
            // the saved result only: the check itself runs on its own page, when asked
            val (hidingScan, ignored) = withContext(Dispatchers.IO) {
                hidingHistory.load().first to hidingHistory.ignored
            }
            val hidingFindings = hidingScan?.audit?.findings.orEmpty().filter { it.id !in ignored }
            val adb = withContext(Dispatchers.IO) { WirelessAdb.status() }
            _uiState.update {
                it.copy(
                    bootGuard = bootGuard,
                    hideBootloader = hideBootloader,
                    allConflicts = conflicts,
                    conflictDetection = detection,
                    conflictWarnOnFlash = settingsRepo.conflictWarnOnFlash,
                    conflictIncludeProps = settingsRepo.conflictIncludeProps,
                    hidingScanTime = hidingScan?.time,
                    hidingLeaks = hidingFindings.count { f -> f.leak },
                    hidingFindings = hidingFindings.size,
                    wirelessAdb = adb,
                    wifiAddress = WirelessAdb.wifiAddress(camApp),
                    adbTimeout = WirelessAdb.timeoutMinutes(camApp),
                    scanning = false,
                )
            }
        }
    }

    fun setBootGuardEnabled(enabled: Boolean) {
        _uiState.update { it.copy(bootGuard = it.bootGuard.copy(enabled = enabled)) }
        applyBootGuard { setBootGuardConfig(enabled = enabled) }
    }

    /** [failures] is the number of failed boots tolerated (1..4); camd stores the attempt that triggers. */
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

    fun setWirelessAdb(enabled: Boolean) {
        if (_uiState.value.adbBusy) return
        _uiState.update { it.copy(adbBusy = true) }
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                if (enabled) WirelessAdb.enable(camApp) else WirelessAdb.disable(camApp)
            }
            _uiState.update { it.copy(wirelessAdb = status, wifiAddress = WirelessAdb.wifiAddress(camApp), adbBusy = false) }
        }
    }

    fun setAdbTimeout(minutes: Int) {
        WirelessAdb.setTimeout(camApp, minutes, on = _uiState.value.wirelessAdb.on)
        _uiState.update { it.copy(adbTimeout = minutes) }
    }

    /** Run a camd command, then read the boot guard back so the page shows what camd stored. */
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
