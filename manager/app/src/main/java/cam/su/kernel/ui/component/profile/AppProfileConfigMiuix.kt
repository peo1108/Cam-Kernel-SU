package cam.su.kernel.ui.component.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import cam.su.kernel.Cam
import cam.su.kernel.CamNative
import cam.su.kernel.R
import cam.su.kernel.ui.component.miuix.EditText
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun AppProfileConfigMiuix(
    modifier: Modifier = Modifier,
    fixedName: Boolean,
    enabled: Boolean,
    profile: CamNative.Profile,
    onProfileChange: (CamNative.Profile) -> Unit,
) {
    Column(modifier = modifier) {
        if (!fixedName) {
            EditText(
                title = stringResource(R.string.profile_name),
                value = profile.name,
                onValueChange = { onProfileChange(profile.copy(name = it)) },
                enabled = enabled,
            )
        }

        SwitchPreference(
            title = stringResource(R.string.profile_umount_modules),
            summary = stringResource(R.string.profile_umount_modules_summary),
            checked = if (enabled) {
                profile.umountModules
            } else {
                Cam.isDefaultUmountModules()
            },
            enabled = enabled,
            onCheckedChange = {
                onProfileChange(
                    profile.copy(
                        umountModules = it,
                        nonRootUseDefault = false
                    )
                )
            }
        )
    }
}

@Preview
@Composable
private fun AppProfileConfigPreview() {
    var profile by remember { mutableStateOf(CamNative.Profile("")) }
    AppProfileConfigMiuix(fixedName = true, enabled = false, profile = profile) {
        profile = it
    }
}
