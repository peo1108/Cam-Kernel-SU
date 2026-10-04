package me.weishu.kernelsu.ui.screen.susfs

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.util.SusfsConfig
import me.weishu.kernelsu.ui.util.SusfsState

@Immutable
data class SusfsSettingsUiState(
    val loading: Boolean = true,
    val state: SusfsState = SusfsState(),
    /** what ksud has saved */
    val saved: SusfsConfig = SusfsConfig(),
    /** what the page shows, saved with Save */
    val edited: SusfsConfig = SusfsConfig(),
    val saving: Boolean = false,
) {
    val available: Boolean
        get() = state.info != null

    val dirty: Boolean
        get() = edited != saved

    /** a CONFIG_KSU_SUSFS_* option the kernel was built with, without the prefix */
    fun has(feature: String): Boolean = state.info?.features?.contains(feature) == true
}

@Immutable
data class SusfsSettingsActions(
    val onBack: () -> Unit,
    val onUpdate: (SusfsConfig.() -> SusfsConfig) -> Unit,
    val onSave: () -> Unit,
    val onUseStockBuild: () -> Unit,
    val onShowLog: () -> Unit,
    val onPreviewBootconfig: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    val onReset: () -> Unit,
)
