package cam.su.kernel.ui.component.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cam.su.kernel.CamNative

@Composable
fun AppProfileConfig(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: CamNative.Profile,
    onProfileChange: (CamNative.Profile) -> Unit,
) {
    AppProfileConfigMiuix(
        modifier = modifier,
        fixedName = fixedName,
        enabled = enabled,
        profile = profile,
        onProfileChange = onProfileChange
    )
}

@Composable
fun RootProfileConfig(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean = true,
    profile: CamNative.Profile,
    onProfileChange: (CamNative.Profile) -> Unit,
) {
    RootProfileConfigMiuix(
        modifier = modifier,
        fixedName = fixedName,
        enabled = enabled,
        profile = profile,
        onProfileChange = onProfileChange
    )
}

@Composable
fun TemplateConfig(
    modifier: Modifier = Modifier,
    profile: CamNative.Profile,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (CamNative.Profile) -> Unit
) {
    TemplateConfigMiuix(
        modifier = modifier,
        profile = profile,
        onViewTemplate = onViewTemplate,
        onManageTemplate = onManageTemplate,
        onProfileChange = onProfileChange
    )
}
