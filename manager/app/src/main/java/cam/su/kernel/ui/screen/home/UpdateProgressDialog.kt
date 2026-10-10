package cam.su.kernel.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cam.su.kernel.R
import cam.su.kernel.update.UpdateFailure
import cam.su.kernel.update.UpdateState
import cam.su.kernel.update.UpdateStep
import cam.su.kernel.update.formatMegabytes
import cam.su.kernel.update.step
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Download -> verify -> install, shown after "Update" is tapped. Hiding it keeps the work going
 * (the Home card still shows the state); on success Android kills the app mid-install.
 */
@Composable
fun UpdateProgressDialog(
    show: Boolean,
    versionName: String,
    state: UpdateState,
    onRetry: () -> Unit,
    onSystemInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.update_progress_title, versionName),
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                StepRow(current = state.step, failed = state is UpdateState.Failed)
                Spacer(Modifier.padding(top = 16.dp))
                if (state !is UpdateState.Failed) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        // pm install reports no percentage, so verify/install run indeterminate
                        progress = (state as? UpdateState.Downloading)?.let { it.percent / 100f },
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                }
                Text(
                    text = statusText(state),
                    color = if (state is UpdateState.Failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state is UpdateState.Installing) {
                    Text(
                        text = stringResource(R.string.update_installing_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    if (state is UpdateState.Failed) {
                        TextButton(
                            text = stringResource(R.string.close),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        val install = state.reason == UpdateFailure.INSTALL
                        TextButton(
                            text = stringResource(if (install) R.string.update_use_system_installer else R.string.update_retry),
                            onClick = if (install) onSystemInstall else onRetry,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.update_hide),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun StepRow(current: UpdateStep?, failed: Boolean) {
    val labels = listOf(
        UpdateStep.DOWNLOAD to R.string.update_step_download,
        UpdateStep.VERIFY to R.string.update_step_verify,
        UpdateStep.INSTALL to R.string.update_step_install,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, (step, label) ->
            val position = current?.ordinal ?: -1
            val color = when {
                step.ordinal < position -> MiuixTheme.colorScheme.primary
                step.ordinal == position && failed -> MiuixTheme.colorScheme.error
                step.ordinal == position -> MiuixTheme.colorScheme.onSurface
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            val mark = if (step.ordinal < position) "✓ " else "${index + 1}. "
            Text(
                text = mark + stringResource(label),
                color = color,
                fontWeight = if (step.ordinal == position) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun statusText(state: UpdateState): String = when (state) {
    is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.percent) +
        if (state.downloaded > 0) "\n" + formatMegabytes(state.downloaded, state.total) else ""
    UpdateState.Verifying -> stringResource(R.string.update_verifying)
    UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> when (state.reason) {
        UpdateFailure.DOWNLOAD -> stringResource(R.string.update_failed_download)
        UpdateFailure.CHECKSUM -> stringResource(R.string.update_failed_checksum)
        UpdateFailure.PACKAGE -> stringResource(R.string.update_failed_package)
        UpdateFailure.SIGNATURE -> stringResource(R.string.update_failed_signature)
        UpdateFailure.VERSION -> stringResource(R.string.update_failed_version)
        UpdateFailure.INSTALL -> stringResource(R.string.update_failed_install, state.detail.orEmpty())
    }
    UpdateState.Idle -> ""
}
