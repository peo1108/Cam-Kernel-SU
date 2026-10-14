package cam.su.kernel.ui.component.rebootlistpopup

import android.content.Context
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cam.su.kernel.Cam
import cam.su.kernel.R
import cam.su.kernel.ui.component.dialog.rememberConfirmDialog
import cam.su.kernel.ui.util.reboot

data class RebootListOption(
    @param:StringRes val labelRes: Int,
    val reason: String,
    val icon: ImageVector,
)

@Composable
fun getRebootListOption(): List<RebootListOption> {
    val pm = LocalContext.current.getSystemService(Context.POWER_SERVICE) as PowerManager?

    @Suppress("DEPRECATION")
    val isRebootingUserspaceSupported = pm?.isRebootingUserspaceSupported == true

    return buildList {
        add(RebootListOption(R.string.reboot, "", Icons.Rounded.RestartAlt))
        if (isRebootingUserspaceSupported) {
            add(RebootListOption(R.string.reboot_userspace, "userspace", Icons.Rounded.Refresh))
        }
        add(RebootListOption(R.string.reboot_soft, "soft_reboot", Icons.Rounded.Autorenew))
        add(RebootListOption(R.string.reboot_recovery, "recovery", Icons.Rounded.Build))
        add(RebootListOption(R.string.reboot_bootloader, "bootloader", Icons.Rounded.Lock))
        add(RebootListOption(R.string.reboot_download, "download", Icons.Rounded.Download))
        add(RebootListOption(R.string.reboot_edl, "edl", Icons.Rounded.Memory))
    }
}

/** Reboots on selection, but confirms first in jailbreak mode where a plain reboot drops root. */
@Composable
fun rememberRebootAction(): (String) -> Unit {
    val title = stringResource(R.string.reboot)
    val message = stringResource(R.string.jailbreak_reboot_warning)
    val confirmDialog = rememberConfirmDialog(onConfirm = { reboot() })

    return remember(title, message, confirmDialog) {
        { reason ->
            if (Cam.isLateLoadMode && reason.isEmpty()) {
                confirmDialog.showConfirm(title = title, content = message)
            } else {
                reboot(reason)
            }
        }
    }
}

@Composable
fun RebootListPopup() {
    RebootListPopupMiuix()
}
