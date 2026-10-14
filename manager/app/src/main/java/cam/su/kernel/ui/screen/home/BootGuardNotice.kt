package cam.su.kernel.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.su.kernel.R
import cam.su.kernel.ui.component.glass.GlassDialog
import cam.su.kernel.ui.component.miuix.WarningCard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/** Card plus dialog for what camd's boot guard turned off after failed boots. */
@Composable
fun BootGuardNotice(state: HomeUiState, actions: HomeActions) {
    val status = state.bootGuard
    var showDialog by rememberSaveable { mutableStateOf(false) }

    WarningCard(
        message = stringResource(R.string.boot_guard_notice, status.autoDisabled.size + status.initrcDisabled.size),
        onClick = { showDialog = true },
    )

    GlassDialog(
        show = showDialog && status.hasNotice,
        title = stringResource(R.string.boot_guard_dialog_title),
        summary = stringResource(R.string.boot_guard_dialog_summary),
        onDismissRequest = { showDialog = false },
    ) {
        Column {
            BootGuardReport(
                status = status,
                onReenableModule = actions.onReenableModule,
                onReenableInitrc = actions.onReenableInitrc,
                onReenableOthers = actions.onReenableOtherModules,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.close),
                    onClick = { showDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.boot_guard_dismiss),
                    onClick = {
                        showDialog = false
                        actions.onDismissBootGuard()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
