package cam.su.kernel.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.FileFormat
import cam.su.kernel.ui.util.TransferRate
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * How far a job got and how long it still needs: the bar, "42%   12.3 MB / 27.0 MB", then
 * "8.1 MB/s · about 3 s left" and [label] (the file being worked on). [total] 0 or less: the bar
 * runs without an end and only [label] shows. [bytes] false: [done] and [total] count items.
 */
@Composable
fun ProgressDetails(
    done: Long,
    total: Long,
    modifier: Modifier = Modifier,
    bytes: Boolean = true,
    label: String? = null,
) {
    val known = total > 0
    val rate = remember { TransferRate() }
    var speed by remember { mutableStateOf<Double?>(null) }
    var left by remember { mutableStateOf<Long?>(null) }
    val currentDone by rememberUpdatedState(done)
    val currentTotal by rememberUpdatedState(total)
    // sampled on a clock, not on each report: a stalled job must show its speed dropping
    LaunchedEffect(rate) {
        while (true) {
            rate.add(currentDone, System.nanoTime())
            speed = rate.perSecond()
            left = rate.secondsLeft(currentDone, currentTotal)
            delay(SAMPLE_MS)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = if (known) (done.toFloat() / total).coerceIn(0f, 1f) else null,
        )
        if (known) {
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    text = "${(done * 100 / total).coerceIn(0, 100)}%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (bytes) "${FileFormat.size(done)} / ${FileFormat.size(total)}" else "$done / $total",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            val parts = buildList {
                speed?.takeIf { bytes }?.let { add(stringResource(R.string.progress_speed, FileFormat.size(it.toLong()))) }
                add(left?.let { timeLeft(it) } ?: stringResource(R.string.progress_estimating))
            }
            Text(
                text = parts.joinToString(" · "),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!label.isNullOrEmpty()) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(top = if (known) 4.dp else 10.dp),
            )
        }
    }
}

@Composable
private fun timeLeft(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.progress_left_seconds, seconds.coerceAtLeast(1))
    seconds < 3600 -> stringResource(R.string.progress_left_minutes, seconds / 60, seconds % 60)
    else -> stringResource(R.string.progress_left_hours, seconds / 3600, seconds % 3600 / 60)
}

private const val SAMPLE_MS = 500L
