package me.weishu.kernelsu.ui.viewmodel

import androidx.compose.runtime.Immutable
import me.weishu.kernelsu.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val enableNavigationBadge: Boolean,
    val enableSwipeDismiss: Boolean,
    val pagerInterceptionMode: Int,
    val moduleDescriptionMaxLines: Int = 5,
    val glassBackgroundType: Int = 0,
    val glassBackgroundBlur: Float = 0f,
    val glassBackgroundDim: Float = 0.2f,
    val glassImageVersion: Long = 0L,
    val roamingSlimes: Boolean = true,
    val roamingSlimeCount: Int = 0,
    val slimeNightNap: Boolean = true,
)
