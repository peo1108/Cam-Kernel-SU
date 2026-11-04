package cam.su.kernel.ui.component.rebootlistpopup

import cam.su.kernel.ui.component.glass.GlassMenuColumn
import cam.su.kernel.ui.component.glass.GlassMenuDivider
import cam.su.kernel.ui.component.glass.GlassMenuItem
import cam.su.kernel.ui.component.glass.GlassMorphPopup
import cam.su.kernel.ui.component.glass.MorphIcon
import cam.su.kernel.ui.component.glass.morphAnchor
import cam.su.kernel.ui.component.glass.rememberMorphPopupState
import cam.su.kernel.ui.component.glass.GlassIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cam.su.kernel.R
import cam.su.kernel.ui.component.CamIsValid
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun RebootListPopupMiuix(
    modifier: Modifier = Modifier
) {
    val popup = rememberMorphPopupState()
    CamIsValid {
        val onReboot = rememberRebootAction()
        val icon: @Composable () -> Unit = {
            Icon(
                imageVector = MiuixIcons.Close2,
                contentDescription = stringResource(id = R.string.reboot),
                tint = colorScheme.onBackground
            )
        }
        GlassIconButton(
            modifier = modifier.morphAnchor(popup),
            onClick = { popup.expanded = true },
        ) { MorphIcon(popup) { icon() } }
        GlassMorphPopup(
            state = popup,
            onDismissRequest = { popup.expanded = false },
        ) {
            val rebootOptions = getRebootListOption()
            GlassMenuColumn {
                rebootOptions.forEachIndexed { idx, option ->
                    // the first entry is the everyday reboot; the rest boot into special modes
                    if (idx == 1) GlassMenuDivider()
                    GlassMenuItem(
                        text = stringResource(option.labelRes),
                        icon = option.icon,
                        onClick = {
                            popup.expanded = false
                            onReboot(option.reason)
                        },
                    )
                }
            }
        }
    }
}
