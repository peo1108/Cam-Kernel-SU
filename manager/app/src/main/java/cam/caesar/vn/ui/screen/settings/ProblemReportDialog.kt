package cam.caesar.vn.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.caesar.vn.BuildConfig
import cam.caesar.vn.R
import cam.caesar.vn.account.Account
import cam.caesar.vn.account.OrderException
import cam.caesar.vn.account.ProblemReport
import cam.caesar.vn.ui.component.CamToast
import cam.caesar.vn.ui.component.glass.GlassDialog
import cam.caesar.vn.ui.component.glass.GlassTextField
import cam.caesar.vn.ui.util.collectProblemReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.io.File

private sealed interface ReportUi {
    data object Compose : ReportUi
    data object Sending : ReportUi
    data class Sent(val code: String) : ReportUi
    data object NotLinked : ReportUi
}

/**
 * Settings → "Report a problem to Cam" (docs/CAM_CHANGES.md row 98): the customer writes what went
 * wrong, the Manager gathers what Cam needs (ProblemReportCollect.kt) and sends it to caesarvn.xyz
 * with the account token; the answer is a code the customer passes on, and Cam reads the report
 * in /quan-tri. Needs a linked account ([onOpenAccount] opens that page).
 */
@Composable
fun ProblemReportDialog(show: Boolean, onDismissRequest: () -> Unit, onOpenAccount: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<ReportUi>(ReportUi.Compose) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorText = stringResource(R.string.account_error)
    val slowText = stringResource(R.string.report_slow_down)

    LaunchedEffect(show) {
        if (show) {
            ui = if (Account.isLinked()) ReportUi.Compose else ReportUi.NotLinked
            error = null
        }
    }

    fun send() = scope.launch {
        error = null
        ui = ReportUi.Sending
        val result = runCatching {
            withContext(Dispatchers.IO) {
                collectProblemReport(context, note).also {
                    // debug builds keep what was sent, to check it on the device
                    if (BuildConfig.DEBUG) File(context.cacheDir, "problem_report.json").writeText(it.toJson())
                }
            }
        }
            .fold({ Account.sendReport(it) }, { Result.failure(it) })
        result
            .onSuccess { code ->
                note = ""
                ui = ReportUi.Sent(code)
            }
            .onFailure { e ->
                when ((e as? OrderException)?.code) {
                    "sign_in" -> ui = ReportUi.NotLinked
                    "slow_down" -> {
                        error = slowText
                        ui = ReportUi.Compose
                    }
                    else -> {
                        error = errorText
                        ui = ReportUi.Compose
                    }
                }
            }
    }

    GlassDialog(
        show = show,
        title = stringResource(R.string.report_title),
        // gathering takes a while: the dialog stays until the answer comes
        onDismissRequest = if (ui == ReportUi.Sending) null else onDismissRequest,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { Text(it, color = colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            when (val state = ui) {
                ReportUi.Compose -> {
                    Text(stringResource(R.string.report_what), color = colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                    GlassTextField(
                        value = note,
                        onValueChange = { if (it.length <= ProblemReport.NOTE_MAX) note = it },
                        label = stringResource(R.string.report_note_hint),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                }
                ReportUi.Sending -> Text(
                    stringResource(R.string.report_sending),
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                is ReportUi.Sent -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.report_sent), textAlign = TextAlign.Center)
                    Text(state.code, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colorScheme.primary)
                    Text(stringResource(R.string.report_sent_hint), color = colorScheme.onSurfaceVariantSummary, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                ReportUi.NotLinked -> Text(
                    stringResource(R.string.report_not_linked),
                    color = colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                if (ui != ReportUi.Sending) {
                    TextButton(text = stringResource(R.string.close), onClick = onDismissRequest, modifier = Modifier.weight(1f))
                }
                val primary: Pair<String, () -> Unit>? = when (val state = ui) {
                    ReportUi.Compose -> stringResource(R.string.report_send) to { send(); Unit }
                    is ReportUi.Sent -> stringResource(R.string.report_copy) to {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("report", state.code))
                        CamToast.show(context, R.string.early_copied)
                    }
                    ReportUi.NotLinked -> stringResource(R.string.report_open_account) to {
                        onDismissRequest()
                        onOpenAccount()
                    }
                    ReportUi.Sending -> null
                }
                if (primary != null) {
                    Spacer(Modifier.width(20.dp))
                    TextButton(text = primary.first, onClick = primary.second, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
                if (ui == ReportUi.Sending) {
                    TextButton(text = stringResource(R.string.report_sending_button), onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
