package cam.su.kernel.ui.viewmodel

import android.system.OsConstants
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cam.su.kernel.camApp
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.repository.HidingHistoryRepository
import cam.su.kernel.data.repository.HidingRulesRepository
import cam.su.kernel.data.repository.SavedHidingScan
import cam.su.kernel.data.repository.SettingsRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.hiding.HidingProbeService
import cam.su.kernel.ui.screen.hidingcheck.HidingCheckUiState
import cam.su.kernel.ui.screen.hidingcheck.HidingFix
import cam.su.kernel.ui.util.applyHidingFixes
import cam.su.kernel.ui.util.getHideBootloaderStatus
import cam.su.kernel.ui.util.runHidingAudit
import cam.su.kernel.ui.util.setHideBootloader

/**
 * The root hiding check page. Nothing runs in the background: opening the page reads the
 * saved scan and the switches, and checks for newer rules at most every few hours; the
 * audit runs when the user asks for it.
 */
class HidingCheckViewModel(
    private val history: HidingHistoryRepository = HidingHistoryRepository(),
    private val rulesRepo: HidingRulesRepository = HidingRulesRepository(),
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
            checkRules(force = false)
        }
    }

    /** The user asked: check the repo now, whenever it was last asked. */
    fun checkUpdates() {
        viewModelScope.launch { checkRules(force = true) }
    }

    private suspend fun checkRules(force: Boolean) {
        if (_uiState.value.checkingRules) return
        // show what is at hand while the repo answers
        val known = _uiState.value.rules ?: withContext(Dispatchers.IO) { rulesRepo.status() }
        _uiState.update { it.copy(checkingRules = true, rules = known) }
        val status = withContext(Dispatchers.IO) { rulesRepo.check(force) }
        _uiState.update { it.copy(checkingRules = false, rules = status) }
    }

    fun scan() {
        if (_uiState.value.scanning) return
        _uiState.update { it.copy(scanning = true) }
        viewModelScope.launch { record(audit()) }
    }

    /**
     * Turns on every fix the findings ask for, camd's and the app view's, then scans again
     * so the page shows what is left.
     */
    fun applyFixes() {
        if (_uiState.value.scanning) return
        val pending = _uiState.value.pendingFixes
        _uiState.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val rebootNeeded = withContext(Dispatchers.IO) {
                // camd covers its own findings; the app view can ask for one camd did not see
                val camd = applyHidingFixes()
                val rest = pending.map { writeFix(it, true) }
                camd || rest.any { it }
            }
            if (rebootNeeded) _uiState.update { it.copy(rebootNeeded = true) }
            refreshFixes()
            record(audit())
        }
    }

    fun setFix(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val rebootNeeded = withContext(Dispatchers.IO) { writeFix(name, enabled) }
            if (rebootNeeded) _uiState.update { it.copy(rebootNeeded = true) }
            refreshFixes()
        }
    }

    /** Switches one fix; true when it takes full effect only after a reboot. */
    private fun writeFix(name: String, enabled: Boolean): Boolean = when (name) {
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

    /** camd's audit (the root view) and the isolated probe's (the app view), side by side. */
    private suspend fun audit(): Pair<HidingAudit?, HidingAudit?> = coroutineScope {
        val root = async(Dispatchers.IO) { runHidingAudit() }
        val app = async { HidingProbeService.scan(camApp, withContext(Dispatchers.IO) { rulesRepo.current() }) }
        root.await() to app.await()
    }

    private suspend fun record(result: Pair<HidingAudit?, HidingAudit?>) {
        val (root, app) = result
        val audit = when {
            root != null && app != null -> root + app
            else -> root ?: app
        }
        if (audit == null) {
            _uiState.update { it.copy(scanning = false, rootViewFailed = true, appViewFailed = true) }
            return
        }
        val scan = SavedHidingScan(System.currentTimeMillis(), audit)
        withContext(Dispatchers.IO) { history.save(scan) }
        // the last scan, saved or from this visit, is the one to compare with
        previous = current
        current = scan
        _uiState.update { it.copy(scanning = false, rootViewFailed = root == null, appViewFailed = app == null) }
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
