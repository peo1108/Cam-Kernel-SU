package me.weishu.kernelsu.ui.component.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Glass card with an icon/title/summary row that expands to [content] when tapped.
 * A card that is not [enabled] is dimmed, cannot be tapped and has no arrow.
 */
@Composable
fun GlassExpandableCard(
    icon: ImageVector,
    title: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "ExpandableCardArrow",
    )
    GlassListCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier.alpha(0.55f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = colorScheme.onBackground, modifier = Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = title, fontWeight = FontWeight(550), color = colorScheme.onSurface)
                    if (summary != null) {
                        Text(text = summary, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 1)
                    }
                }
                if (enabled) {
                    val layoutDirection = LocalLayoutDirection.current
                    Image(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                rotationZ = arrowRotation
                            }
                            .size(width = 10.dp, height = 16.dp),
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantActions),
                    )
                }
            }
            if (header != null) {
                Spacer(Modifier.height(12.dp))
                header()
            }
        }
        AnimatedVisibility(
            visible = expanded && enabled,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) + fadeOut(),
        ) {
            Column(Modifier.padding(bottom = 8.dp), content = content)
        }
    }
}
