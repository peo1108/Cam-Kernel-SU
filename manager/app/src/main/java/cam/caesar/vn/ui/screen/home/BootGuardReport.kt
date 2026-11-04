package cam.su.kernel.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.data.model.BootGuardCulprit
import cam.su.kernel.data.model.BootGuardReason
import cam.su.kernel.data.model.BootGuardStatus
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * What the boot guard turned off after the failed boots: the likely causes first (why, and the log
 * line that names them), then the other modules, which are most likely fine to turn back on.
 */
@Composable
fun BootGuardReport(
    status: BootGuardStatus,
    onReenableModule: (String) -> Unit,
    onReenableInitrc: (String) -> Unit,
    onReenableOthers: () -> Unit,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val culpritModules = status.culpritModules
    val others = status.autoDisabled.filterNot { it in culpritModules }
    Column(modifier.padding(padding)) {
        Label(stringResource(R.string.boot_guard_culprits))
        if (status.culprits.isEmpty()) {
            Text(
                text = stringResource(R.string.boot_guard_no_culprit),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        status.culprits.forEach { culprit ->
            CulpritRow(
                culprit = culprit,
                onReenable = { culprit.initrcName?.let(onReenableInitrc) ?: onReenableModule(culprit.id) },
            )
        }
        if (others.isNotEmpty()) {
            Label(stringResource(R.string.boot_guard_others))
            others.forEach { id ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = id, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        text = stringResource(R.string.boot_guard_reenable),
                        onClick = { onReenableModule(id) },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            if (others.size > 1) {
                TextButton(
                    text = stringResource(R.string.boot_guard_reenable_others, others.size),
                    onClick = onReenableOthers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight(600),
        color = colorScheme.onSurfaceVariantActions,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun CulpritRow(culprit: BootGuardCulprit, onReenable: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = culprit.initrcName?.let { "initrc.d/$it" } ?: culprit.id,
                fontSize = 16.sp,
                fontWeight = FontWeight(600),
                color = colorScheme.error,
            )
            val reasons = culprit.reasons.map { stringResource(it.label()) }
            if (reasons.isNotEmpty()) {
                Text(
                    text = reasons.joinToString(" · "),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            culprit.evidence?.let { line ->
                Text(
                    text = line,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        TextButton(
            text = stringResource(R.string.boot_guard_reenable),
            onClick = onReenable,
        )
    }
}

private fun BootGuardReason.label(): Int = when (this) {
    BootGuardReason.Changed -> R.string.boot_guard_reason_changed
    BootGuardReason.InitRc -> R.string.boot_guard_reason_init_rc
    BootGuardReason.InitrcD -> R.string.boot_guard_reason_initrc_d
    BootGuardReason.Log -> R.string.boot_guard_reason_log
}
