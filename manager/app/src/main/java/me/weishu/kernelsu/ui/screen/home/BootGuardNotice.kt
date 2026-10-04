package me.weishu.kernelsu.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import me.weishu.kernelsu.ui.component.miuix.WarningCard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Card plus dialog for the modules ksud's boot guard disabled after failed boots. */
@Composable
fun BootGuardNotice(state: HomeUiState, actions: HomeActions) {
    val ids = state.bootGuard.autoDisabled
    var showDialog by rememberSaveable { mutableStateOf(false) }

    WarningCard(
        message = stringResource(R.string.boot_guard_notice, ids.size),
        onClick = { showDialog = true },
    )

    GlassDialog(
        show = showDialog && ids.isNotEmpty(),
        title = stringResource(R.string.boot_guard_dialog_title),
        summary = stringResource(R.string.boot_guard_dialog_summary),
        onDismissRequest = { showDialog = false },
    ) {
        Column {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ids.forEach { id ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = id, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        TextButton(
                            text = stringResource(R.string.boot_guard_reenable),
                            onClick = { actions.onReenableModule(id) },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
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
