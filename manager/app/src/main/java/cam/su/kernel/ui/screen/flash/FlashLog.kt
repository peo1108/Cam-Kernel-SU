package cam.su.kernel.ui.screen.flash

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.listdetail.DetailButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * What a flash or a module's action shows, the same on its own page and beside the Module list:
 * this card, then [FlashLogCard]. It says what runs and how it went; once it has ended, what can be
 * done next: [onReboot] when [rebootLabel] is set, and [onDone].
 */
@Composable
internal fun FlashStatusCard(
    status: FlashingStatus,
    title: String,
    rebootLabel: String? = null,
    onReboot: () -> Unit = {},
    onDone: (() -> Unit)? = null,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                FlashingStatus.FLASHING -> InfiniteProgressIndicator(size = 24.dp)
                FlashingStatus.SUCCESS -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(24.dp))
                FlashingStatus.FAILED -> Icon(Icons.Rounded.Error, contentDescription = null, tint = colorScheme.error, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight(550),
                color = colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != FlashingStatus.FLASHING) {
                if (rebootLabel != null) {
                    Spacer(Modifier.width(12.dp))
                    DetailButton(icon = Icons.Rounded.RestartAlt, label = rebootLabel, onClick = onReboot)
                }
                if (onDone != null) {
                    Spacer(Modifier.width(if (rebootLabel != null) 8.dp else 12.dp))
                    DetailButton(icon = Icons.Rounded.CheckCircle, label = stringResource(R.string.module_install_done), onClick = onDone)
                }
            }
        }
    }
}

/**
 * The output of a flash or a module's action, following the last line. It scrolls inside its card,
 * which grows to the room left: following in code moves nothing the top bar would notice, so a whole
 * page scrolling that way would slide under a title that never collapses. [scrolling] is what a
 * finger's scroll needs to reach the bar.
 */
@Composable
internal fun ColumnScope.FlashLogCard(text: String, scrolling: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        // the new line is measured by the next frame
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).weight(1f, fill = false),
        insideMargin = PaddingValues(14.dp),
    ) {
        Text(
            text = text.ifEmpty { " " },
            modifier = Modifier.fillMaxWidth().then(scrolling).verticalScroll(scroll),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onSurface,
        )
    }
}
