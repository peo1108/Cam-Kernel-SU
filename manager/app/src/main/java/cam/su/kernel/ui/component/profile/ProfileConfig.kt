package cam.su.kernel.ui.component.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cam.su.kernel.Natives

@Composable
fun AppProfileConfig(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
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
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
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
    profile: Natives.Profile,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (Natives.Profile) -> Unit
) {
    TemplateConfigMiuix(
        modifier = modifier,
        profile = profile,
        onViewTemplate = onViewTemplate,
        onManageTemplate = onManageTemplate,
        onProfileChange = onProfileChange
    )
}
