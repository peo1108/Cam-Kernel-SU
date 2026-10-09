package cam.su.kernel.ui.component.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Liquid glass replacement for miuix OverlayDropdownPreference (same parameters as used here): the
 * row opens a [GlassListPopup] instead of the opaque miuix dropdown, whose popup cannot be restyled.
 */
@Composable
fun GlassDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    maxHeight: Dp? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    val expanded = remember { mutableStateOf(false) }
    val current = items.getOrNull(selectedIndex).orEmpty()
    Box(modifier = modifier) {
        BasicComponent(
            title = title,
            summary = summary,
            startAction = startAction,
            endActions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = current,
                        color = if (enabled) colorScheme.onSurfaceVariantActions else colorScheme.disabledOnSecondaryVariant,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    DropdownArrowEndAction(colorScheme.onSurfaceVariantActions)
                }
            },
            onClick = { if (enabled) expanded.value = true },
            holdDownState = expanded.value,
            enabled = enabled,
        )
        GlassListPopup(
            show = expanded.value,
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            maxHeight = maxHeight,
            onDismissRequest = { expanded.value = false },
        ) {
            ListPopupColumn {
                items.forEachIndexed { index, text ->
                    DropdownImpl(
                        text = text,
                        optionSize = items.size,
                        isSelected = index == selectedIndex,
                        index = index,
                        onSelectedIndexChange = {
                            onSelectedIndexChange?.invoke(it)
                            expanded.value = false
                        },
                    )
                }
            }
        }
    }
}
