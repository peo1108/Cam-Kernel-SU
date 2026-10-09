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
import cam.su.kernel.Cam
import cam.su.kernel.camApp
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.repository.HidingHistoryRepository
import cam.su.kernel.data.repository.HidingRulesRepository
import cam.su.kernel.data.repository.SavedHidingScan
import cam.su.kernel.data.repository.SettingsRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.hiding.HidingProbeService
import cam.su.kernel.ui.screen.hidingcheck.CheckedApp
import cam.su.kernel.ui.screen.hidingcheck.HidingCheckUiState
import cam.su.kernel.ui.screen.hidingcheck.HidingFix
import cam.su.kernel.ui.util.applyHidingFixes
import cam.su.kernel.ui.util.checkAttestationReport
import cam.su.kernel.ui.util.getHideBootloaderStatus
import cam.su.kernel.ui.util.launchApp
import cam.su.kernel.ui.util.listModules
import cam.su.kernel.ui.util.runAppHidingAudit
import cam.su.kernel.ui.util.runHidingAudit
import cam.su.kernel.ui.util.setHideBootloader
import cam.su.kernel.ui.util.toggleModule
import org.json.JSONArray

/**
 * The root hiding check page, for the whole device or, with [uid], for one app. Nothing
 * runs in the background: opening the page reads the saved scan and the switches, and
 * checks for newer rules at most every few hours; the audit runs when the user asks for it.
 */
class HidingCheckViewModel(
    private val uid: Int? = null,
    private val history: HidingHistoryRepository = HidingHistoryRepository(scope = uid?.let { "uid_$it" }.orEmpty()),
    private val rulesRepo: HidingRulesRepository = HidingRulesRepository(),
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(HidingCheckUiState(app = uid?.let(::checkedApp)))
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
            val names = withContext(Dispatchers.IO) { moduleNames() }
            _uiState.update { it.copy(moduleNames = names) }
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
                val camd = uid == null && applyHidingFixes()
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

    /** Turns off a module a finding names; its mounts go away with the next boot. */
    fun disableModule(id: String) {
        viewModelScope.launch {
            val done = withContext(Dispatchers.IO) { toggleModule(id, false) }
            if (done) {
                ModuleListSignal.invalidate()
                _uiState.update { it.copy(disabledModules = it.disabledModules + id, rebootNeeded = true) }
            }
        }
    }

    fun checkAttestation() {
        if (_uiState.value.checkingAttestation) return
        _uiState.update { it.copy(checkingAttestation = true) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { checkAttestationReport() }
            _uiState.update { it.copy(attestation = report, checkingAttestation = false) }
        }
    }

    /** Starts the checked app, so its processes are there to look at. */
    fun launchCheckedApp() {
        val app = _uiState.value.app ?: return
        launchApp(app.packageName, app.uid / 100_000)
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

    /** One scan's result: what it found, and which views could not run. */
    private class AuditResult(val audit: HidingAudit?, val rootFailed: Boolean, val appFailed: Boolean, val running: Boolean? = null)

    private suspend fun audit(): AuditResult = if (uid != null) appAudit(uid) else deviceAudit()

    /**
     * camd's audit (the root view), the isolated probe's (the app view) and the app
     * profiles that keep module mounts, side by side.
     */
    private suspend fun deviceAudit(): AuditResult = coroutineScope {
        val rules = withContext(Dispatchers.IO) { rulesRepo.current() }
        val root = async(Dispatchers.IO) { runHidingAudit(rulesRepo.currentFile()) }
        val app = async { HidingProbeService.scan(camApp, rules) }
        val profiles = async(Dispatchers.IO) { profileAudit(null) }
        val (r, a) = root.await() to app.await()
        AuditResult(listOfNotNull(r, a, profiles.await()).reduceOrNull(HidingAudit::plus), r == null, a == null)
    }

    /** What the app's own processes see (through camd), and its profile's umount. */
    private suspend fun appAudit(uid: Int): AuditResult = withContext(Dispatchers.IO) {
        val result = runAppHidingAudit(uid, rulesRepo.currentFile())
            ?: return@withContext AuditResult(null, rootFailed = true, appFailed = false)
        val (audit, running) = result
        if (!running) return@withContext AuditResult(null, rootFailed = false, appFailed = false, running = false)
        AuditResult(listOfNotNull(audit, profileAudit(setOf(uid))).reduce(HidingAudit::plus), false, false, running = true)
    }

    /**
     * Apps without root whose profile keeps module mounts: they see modules even with kernel
     * umount on. When every such app does, the default profile is the cause. Only [uids]
     * when given; null when the app list is not loaded or kernel umount is off.
     */
    private fun profileAudit(uids: Set<Int>?): HidingAudit? {
        if (!settingsRepo.isKernelUmountEnabled()) return null
        val groups = SuperUserViewModel.apps
            .filter { uids == null || it.uid in uids }
            .groupBy { it.uid }
            .filterValues { apps -> apps.none { it.allowSu } }
        if (groups.isEmpty()) return null
        val keeping = groups.filterKeys { uid ->
            !(SuperUserViewModel.getGroupedApp(uid)?.shouldUmount ?: Cam.uidShouldUmount(uid))
        }
        val stats = mapOf("profileChecked" to 1)
        if (keeping.isEmpty()) return HidingAudit(emptyList(), 0, stats)
        val finding = if (uids == null && keeping.size == groups.size && groups.size > 1) {
            HidingAudit.Finding(id = "defaultProfileUmount", leak = true, items = emptyList(), fix = null)
        } else {
            HidingAudit.Finding(
                id = "profileUmount",
                leak = true,
                items = keeping.values.map { apps -> "${apps.first().label} (${apps.first().packageName})" }.sorted(),
                fix = null,
            )
        }
        return HidingAudit(listOf(finding), 0, stats)
    }

    private suspend fun record(result: AuditResult) {
        if (result.running != null) {
            _uiState.update { it.copy(app = it.app?.copy(running = result.running)) }
        }
        val audit = result.audit
        if (audit == null) {
            _uiState.update { it.copy(scanning = false, rootViewFailed = result.rootFailed, appViewFailed = result.appFailed) }
            return
        }
        val scan = SavedHidingScan(System.currentTimeMillis(), audit)
        withContext(Dispatchers.IO) { history.save(scan) }
        // the last scan, saved or from this visit, is the one to compare with
        previous = current
        current = scan
        _uiState.update { it.copy(scanning = false, rootViewFailed = result.rootFailed, appViewFailed = result.appFailed) }
        publish()
    }

    private fun publish() {
        val scan = current
        if (scan == null) {
            _uiState.update {
                it.copy(
                    scanTime = null,
                    previousTime = null,
                    findings = emptyList(),
                    gone = emptyList(),
                    ignored = emptyList(),
                    passed = emptyList(),
                )
            }
            return
        }
        val ignoredIds = history.ignored
        val (findings, gone, ignored) = HidingCheckUiState.compare(scan.audit, previous?.audit, ignoredIds)
        _uiState.update {
            it.copy(
                scanTime = scan.time,
                previousTime = previous?.time,
                findings = findings,
                gone = gone,
                ignored = ignored,
                passed = HidingCheckUiState.passed(scan.audit, ignoredIds),
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

    private companion object {
        fun checkedApp(uid: Int): CheckedApp {
            val primary = SuperUserViewModel.getGroupedApp(uid)?.primary
            return CheckedApp(uid = uid, label = primary?.label ?: uid.toString(), packageName = primary?.packageName.orEmpty())
        }

        /** Module id to name, from `camd module list`. */
        fun moduleNames(): Map<String, String> = runCatching {
            val array = JSONArray(listModules())
            (0 until array.length()).associate { i ->
                array.getJSONObject(i).let { it.getString("id") to it.optString("name") }
            }
        }.getOrDefault(emptyMap())
    }
}
