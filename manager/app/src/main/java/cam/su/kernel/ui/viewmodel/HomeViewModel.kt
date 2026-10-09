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
import cam.su.kernel.Ksu
import cam.su.kernel.Natives
import cam.su.kernel.data.repository.SettingsRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.getKernelVersion
import cam.su.kernel.ksuApp
import cam.su.kernel.ui.screen.home.HomeUiState
import cam.su.kernel.ui.screen.home.SystemInfo
import cam.su.kernel.ui.screen.home.getManagerVersion
import cam.su.kernel.ui.util.checkNewVersion
import cam.su.kernel.ui.util.clearBootGuard
import cam.su.kernel.ui.util.getBootGuardStatus
import cam.su.kernel.ui.util.getSELinuxStatusRaw
import cam.su.kernel.ui.util.module.LatestVersionInfo
import cam.su.kernel.ui.util.resolveDeviceName
import cam.su.kernel.ui.util.rootAvailable
import cam.su.kernel.ui.util.toggleModule

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) {
                val state = buildState()
                if (state.isManager && state.isRootAvailable) state.copy(bootGuard = getBootGuardStatus()) else state
            }
            _uiState.update { baseState }
            if (baseState.checkUpdateEnabled) {
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
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
        val isManager = Ksu.isAvailable
        val ksuVersion = if (isManager) Ksu.version else null
        val kernelUAPIVersion = if (isManager) Ksu.kernelUAPIVersion else null
        val managerUAPIVersion = Natives.managerUAPIVersion
        val lkmMode = ksuVersion?.let { if (kernelVersion.isGKI()) Ksu.isLkmMode else null }
        val isRootAvailable = rootAvailable()
        val managerVersion = getManagerVersion(ksuApp)

        return HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isLkmBundled = lkmMode == true && Ksu.isLkmBundled,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = Ksu.isPrBuild,
            requiresNewKernel = isManager && Natives.managerUAPIVersion > Ksu.kernelUAPIVersion,
            requiresNewManager = isManager && Natives.managerUAPIVersion < Ksu.kernelUAPIVersion,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            isSafeMode = Ksu.isSafeMode,
            isLateLoadMode = Ksu.isLateLoadMode,
            checkUpdateEnabled = settingsRepo.checkUpdate,
            latestVersionInfo = LatestVersionInfo(),
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
