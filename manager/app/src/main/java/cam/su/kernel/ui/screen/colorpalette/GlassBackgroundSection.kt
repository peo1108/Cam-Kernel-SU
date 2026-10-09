package cam.su.kernel.ui.screen.colorpalette

import cam.su.kernel.ui.component.glass.GlassDropdownPreference
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.su.kernel.R
import cam.su.kernel.ui.component.glass.GlassBackgroundType
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.LocalGlassBackgroundState
import cam.su.kernel.ui.screen.settings.SettingsUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Miuix appearance settings for the liquid glass page background. */
@Composable
fun GlassBackgroundSection(
    uiState: SettingsUiState,
    actions: ColorPaletteScreenActions,
) {
    val type = uiState.glassBackgroundType
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) actions.onPickGlassImage(uri)
    }
    val types = listOf(
        GlassBackgroundType.WALLPAPER to stringResource(R.string.glass_background_wallpaper),
        GlassBackgroundType.GRADIENT to stringResource(R.string.glass_background_gradient),
        GlassBackgroundType.IMAGE to stringResource(R.string.glass_background_image),
    )

    GlassCard(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        GlassDropdownPreference(
            title = stringResource(R.string.glass_background),
            summary = if (type == GlassBackgroundType.WALLPAPER && LocalGlassBackgroundState.current.wallpaperFallback) {
                stringResource(R.string.glass_background_fallback)
            } else {
                null
            },
            items = types.map { it.second },
            startAction = {
                Icon(
                    Icons.Rounded.Wallpaper,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = stringResource(R.string.glass_background),
                    tint = colorScheme.onBackground,
                )
            },
            selectedIndex = types.indexOfFirst { it.first == type }.coerceAtLeast(0),
            onSelectedIndexChange = { index -> actions.onSetGlassBackgroundType(types[index].first) },
        )
        AnimatedVisibility(visible = type == GlassBackgroundType.IMAGE) {
            ArrowPreference(
                title = stringResource(R.string.glass_background_pick_image),
                startAction = {
                    Icon(
                        Icons.Rounded.Image,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.glass_background_pick_image),
                        tint = colorScheme.onBackground,
                    )
                },
                onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
        AnimatedVisibility(visible = type != GlassBackgroundType.GRADIENT) {
            androidx.compose.foundation.layout.Column {
                SliderPreference(
                    title = stringResource(R.string.glass_background_blur),
                    icon = { Icon(Icons.Rounded.BlurOn, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground) },
                    value = uiState.glassBackgroundBlur,
                    valueRange = 0f..40f,
                    label = { "${it.toInt()}dp" },
                    onValueChangeFinished = actions.onSetGlassBackgroundBlur,
                )
                SliderPreference(
                    title = stringResource(R.string.glass_background_dim),
                    icon = { Icon(Icons.Rounded.Brightness6, modifier = Modifier.padding(end = 6.dp), contentDescription = null, tint = colorScheme.onBackground) },
                    value = uiState.glassBackgroundDim,
                    valueRange = 0f..0.6f,
                    label = { "${(it * 100).toInt()}%" },
                    onValueChangeFinished = actions.onSetGlassBackgroundDim,
                )
            }
        }
    }
}

@Composable
private fun SliderPreference(
    title: String,
    icon: @Composable () -> Unit,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onValueChangeFinished: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    BasicComponent(
        title = title,
        startAction = icon,
        endActions = {
            Text(text = label(sliderValue), color = colorScheme.onSurfaceVariantActions)
        },
        bottomAction = {
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onValueChangeFinished(sliderValue) },
                valueRange = valueRange,
            )
        },
    )
}
