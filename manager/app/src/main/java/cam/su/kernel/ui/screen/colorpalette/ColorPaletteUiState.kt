package cam.su.kernel.ui.screen.colorpalette

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import cam.su.kernel.ui.screen.settings.SettingsUiState
import cam.su.kernel.ui.theme.ColorMode

@Immutable
data class ColorPaletteUiState(
    val uiState: SettingsUiState,
    val currentColorMode: ColorMode,
    val currentPaletteStyle: PaletteStyle,
    val currentColorSpec: ColorSpec.SpecVersion,
)

@Immutable
data class ColorPaletteScreenActions(
    val onBack: () -> Unit,
    val onSetThemeMode: (Int) -> Unit,
    val onSetMiuixMonet: (Boolean) -> Unit,
    val onSetKeyColor: (Int) -> Unit,
    val onSetColorMode: (ColorMode) -> Unit,
    val onSetColorStyle: (String) -> Unit,
    val onSetColorSpec: (String) -> Unit,
    val onSetEnableBlur: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBar: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBarBlur: (Boolean) -> Unit,
    val onSetEnableNavigationBadge: (Boolean) -> Unit,
    val onSetEnablePredictiveBack: (Boolean) -> Unit,
    val onSetEnableSwipeDismiss: (Boolean) -> Unit,
    val onSetPagerInterceptionMode: (Int) -> Unit,
    val onSetPageScale: (Float) -> Unit,
    val onSetModuleDescriptionMaxLines: (Int) -> Unit,
    val onSetGlassBackgroundType: (Int) -> Unit,
    val onSetGlassBackgroundBlur: (Float) -> Unit,
    val onSetGlassBackgroundDim: (Float) -> Unit,
    val onPickGlassImage: (Uri) -> Unit,
    val onSetRoamingSlimes: (Boolean) -> Unit,
    val onSetRoamingSlimeCount: (Int) -> Unit,
    val onSetSlimeNightNap: (Boolean) -> Unit,
)
