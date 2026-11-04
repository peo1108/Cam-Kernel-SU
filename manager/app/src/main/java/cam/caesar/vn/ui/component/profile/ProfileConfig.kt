package cam.caesar.vn.ui.component.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cam.caesar.vn.CamNative

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
    rulesLoading: Boolean = false,
    onProfileChange: (CamNative.Profile) -> Unit,
) {
    RootProfileConfigMiuix(
        modifier = modifier,
        fixedName = fixedName,
        enabled = enabled,
        profile = profile,
        rulesLoading = rulesLoading,
        onProfileChange = onProfileChange
    )
}

@Composable
fun TemplateConfig(
    modifier: Modifier = Modifier,
    profile: CamNative.Profile,
    templates: List<String>?,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (CamNative.Profile) -> Unit
) {
    TemplateConfigMiuix(
        modifier = modifier,
        profile = profile,
        templates = templates,
        onViewTemplate = onViewTemplate,
        onManageTemplate = onManageTemplate,
        onProfileChange = onProfileChange
    )
}
