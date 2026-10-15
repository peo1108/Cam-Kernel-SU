package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.ui.component.glass.GlassDialog
import cam.su.kernel.ui.component.glass.LocalGlassOverlayBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun DialogButtons(onDismiss: () -> Unit, confirm: String, enabled: Boolean = true, onConfirm: () -> Unit) {
    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(text = stringResource(android.R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm,
            enabled = enabled,
            onClick = {
                onConfirm()
                onDismiss()
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
internal fun ListAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, round: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BitBox(checked, round = round) { onChange(!checked) }
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
internal fun BitBox(checked: Boolean, round: Boolean = false, onToggle: () -> Unit) {
    val shape = if (round) CircleShape else RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (checked) colorScheme.primary else Color.Transparent)
            .border(1.5.dp, if (checked) colorScheme.primary else colorScheme.onSurfaceVariantSummary, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** Glass overlay inside glass pages, a plain window dialog otherwise (as DialogMiuix does). */
@Composable
fun FilesDialog(show: Boolean, title: String?, onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    val body: @Composable () -> Unit = { Column(modifier = Modifier.fillMaxWidth()) { content() } }
    if (LocalGlassOverlayBackdrop.current != null) {
        GlassDialog(show = show, title = title, onDismissRequest = onDismiss, content = body)
    } else {
        WindowDialog(show = show, title = title, onDismissRequest = onDismiss, content = body)
    }
}
