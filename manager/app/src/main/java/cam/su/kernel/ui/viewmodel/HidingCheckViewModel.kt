package cam.su.kernel.ui.viewmodel

import android.system.OsConstants
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.repository.HidingHistoryRepository
import cam.su.kernel.data.repository.SavedHidingScan
import cam.su.kernel.data.repository.SettingsRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.ui.screen.hidingcheck.HidingCheckUiState
import cam.su.kernel.ui.screen.hidingcheck.HidingFix
import cam.su.kernel.ui.util.applyHidingFixes
import cam.su.kernel.ui.util.getHideBootloaderStatus
import cam.su.kernel.ui.util.runHidingAudit
import cam.su.kernel.ui.util.setHideBootloader

/**
 * The root hiding check page. Nothing runs in the background: opening the page only reads
 * the saved scan and the switches; the audit runs when the user asks for it.
 */
class HidingCheckViewModel(
    private val history: HidingHistoryRepository = HidingHistoryRepository(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(HidingCheckUiState())
    val uiState: StateFlow<HidingCheckUiState> = _uiState.asStateFlow()

    /** what [HidingCheckUiState.findings] shows, and what it was compared with */
    private var current: SavedHidingScan? = null
    private var previous: SavedHidingScan? = null

    fun load() {
        viewModelScope.launch {
            val (last, before) = withContext(Dispatchers.IO) { history.load() }
            current = last
            previous = before
            publish()
            refreshFixes()
        }
    }

    fun scan() {
        if (_uiState.value.scanning) return
        _uiState.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val audit = withContext(Dispatchers.IO) { runHidingAudit() }
            record(audit)
        }
    }

    /** Turns on every fix the audit offers, then scans again so the page shows what is left. */
    fun applyFixes() {
        if (_uiState.value.scanning) return
        _uiState.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val (rebootNeeded, audit) = withContext(Dispatchers.IO) { applyHidingFixes() to runHidingAudit() }
            if (rebootNeeded) _uiState.update { it.copy(rebootNeeded = true) }
            record(audit)
            refreshFixes()
        }
    }

    fun setFix(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val rebootNeeded = withContext(Dispatchers.IO) {
                when (name) {
                    "kernelUmount" -> {
                        if (settingsRepo.setKernelUmountEnabled(enabled)) settingsRepo.execCamdFeatureSave()
                        false
                    }
                    "selinuxHide" -> {
                        val status = settingsRepo.setSelinuxHideEnabled(enabled)
                        settingsRepo.execCamdFeatureSave()
                        status == -OsConstants.EAGAIN
                    }
                    "hideBootloader" -> {
                        setHideBootloader(enabled)
                        false
                    }
                    else -> false
                }
            }
            if (rebootNeeded) _uiState.update { it.copy(rebootNeeded = true) }
            refreshFixes()
        }
    }

    fun ignore(id: String) {
        history.ignored = history.ignored + id
        publish()
    }

    fun restore(id: String) {
        history.ignored = history.ignored - id
        publish()
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { history.clear() }
            current = null
            previous = null
            publish()
        }
    }

    private suspend fun record(audit: HidingAudit?) {
        if (audit == null) {
            _uiState.update { it.copy(scanning = false, scanFailed = true) }
            return
        }
        val scan = SavedHidingScan(System.currentTimeMillis(), audit)
        withContext(Dispatchers.IO) { history.save(scan) }
        // the last scan, saved or from this visit, is the one to compare with
        previous = current
        current = scan
        _uiState.update { it.copy(scanning = false, scanFailed = false) }
        publish()
    }

    private fun publish() {
        val scan = current
        if (scan == null) {
            _uiState.update {
                it.copy(scanTime = null, previousTime = null, findings = emptyList(), gone = emptyList(), ignored = emptyList())
            }
            return
        }
        val (findings, gone, ignored) = HidingCheckUiState.compare(scan.audit, previous?.audit, history.ignored)
        _uiState.update {
            it.copy(
                scanTime = scan.time,
                previousTime = previous?.time,
                findings = findings,
                gone = gone,
                ignored = ignored,
            )
        }
    }

    private suspend fun refreshFixes() {
        val (umount, selinux, bootloader) = withContext(Dispatchers.IO) {
            Triple(
                HidingFix(settingsRepo.isKernelUmountEnabled(), settingsRepo.getKernelUmountStatus() == "supported"),
                HidingFix(settingsRepo.isSelinuxHideEnabled(), settingsRepo.getSelinuxHideStatus() == "supported"),
                // camd answers for itself: an empty status means camd is not there
                getHideBootloaderStatus().let { HidingFix(it.enabled, it.props.isNotEmpty() || it.enabled) },
            )
        }
        _uiState.update { it.copy(kernelUmount = umount, selinuxHide = selinux, hideBootloader = bootloader) }
    }
}
