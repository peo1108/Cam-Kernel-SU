package cam.su.kernel.ui.component.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/*
 * iOS style menu content for GlassListPopup / GlassMorphPopup: rows with a check column, an icon and
 * large text, thin inset separators between groups, and an optional row of icon-over-label actions.
 */

/** Destructive rows (delete, reboot to EDL...) are red, as on iOS. */
private val MenuRed = Color(0xFFFF3B30)

@Composable
fun GlassMenuColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 180.dp, max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null,
    checked: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    submenu: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color = if (destructive) MenuRed else colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (pressed) colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 17.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
        }
        // the check sits at the end: a leading check column leaves every unchecked menu with a gap
        if (checked) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Check, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        if (submenu) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

/** Thin inset separator between groups of rows. */
@Composable
fun GlassMenuDivider() {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(0.7.dp)
            .background(colorScheme.onSurface.copy(alpha = 0.12f)),
    )
}

/** The top row of a context menu: icon over label buttons sharing the width (Copy, Move, Share). */
@Composable
fun GlassMenuActions(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), content = content)
}

@Composable
fun RowScope.GlassMenuAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .weight(1f)
            .background(if (pressed) colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
