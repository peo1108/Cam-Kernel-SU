package cam.su.kernel.ui.viewmodel

import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cam.su.kernel.BuildConfig
import cam.su.kernel.Cam
import cam.su.kernel.CamNative
import cam.su.kernel.data.repository.SettingsRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.data.repository.UpdateRepository
import cam.su.kernel.data.repository.UpdateRepositoryImpl
import cam.su.kernel.getKernelVersion
import cam.su.kernel.camApp
import cam.su.kernel.ui.screen.home.HomeUiState
import cam.su.kernel.ui.screen.home.SystemInfo
import cam.su.kernel.ui.screen.home.getManagerVersion
import cam.su.kernel.ui.util.clearBootGuard
import cam.su.kernel.ui.util.getBootGuardStatus
import cam.su.kernel.ui.util.getSELinuxStatusRaw
import cam.su.kernel.ui.util.resolveDeviceName
import cam.su.kernel.ui.util.rootAvailable
import cam.su.kernel.ui.util.toggleModule
import cam.su.kernel.update.UpdateInstaller

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl(),
    private val updateRepo: UpdateRepository = UpdateRepositoryImpl(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            UpdateInstaller.state.collect { state -> _uiState.update { it.copy(installState = state) } }
        }
    }

    fun startUpdate() {
        val update = _uiState.value.update ?: return
        UpdateInstaller.start(camApp, update)
    }

    fun installWithSystemInstaller() {
        UpdateInstaller.installWithSystemInstaller(camApp)
    }

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) {
                val state = buildState()
                if (state.isManager && state.isRootAvailable) state.copy(bootGuard = getBootGuardStatus()) else state
            }
            _uiState.update { baseState.copy(update = it.update, installState = it.installState) }
            if (baseState.checkUpdateEnabled) {
                val update = updateRepo.fetchLatest().getOrNull()
                _uiState.update { it.copy(update = update) }
            }
        }
    }

    fun reenableModule(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { toggleModule(id, true) }
            ModuleListSignal.invalidate()
            refresh()
        }
    }

    fun dismissBootGuard() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { clearBootGuard() }
            refresh()
        }
    }

    private fun buildState(): HomeUiState {
        val kernelVersion = getKernelVersion()
        val isManager = Cam.isAvailable
        val camVersion = if (isManager) Cam.version else null
        val kernelUAPIVersion = if (isManager) Cam.kernelUAPIVersion else null
        val managerUAPIVersion = CamNative.managerUAPIVersion
        val lkmMode = camVersion?.let { if (kernelVersion.isGKI()) Cam.isLkmMode else null }
        val isRootAvailable = rootAvailable()
        val managerVersion = getManagerVersion(camApp)

        return HomeUiState(
            kernelVersion = kernelVersion,
            camVersion = camVersion,
            lkmMode = lkmMode,
            isLkmBundled = lkmMode == true && Cam.isLkmBundled,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = Cam.isPrBuild,
            requiresNewKernel = isManager && CamNative.managerUAPIVersion > Cam.kernelUAPIVersion,
            requiresNewManager = isManager && CamNative.managerUAPIVersion < Cam.kernelUAPIVersion,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            isSafeMode = Cam.isSafeMode,
            isLateLoadMode = Cam.isLateLoadMode,
            checkUpdateEnabled = settingsRepo.checkUpdate,
            currentManagerVersionCode = managerVersion.versionCode,
            systemInfo = SystemInfo(
                kernelVersion = Os.uname().release,
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",
                deviceModel = resolveDeviceName(),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = getSELinuxStatusRaw(),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
        )
    }
}
