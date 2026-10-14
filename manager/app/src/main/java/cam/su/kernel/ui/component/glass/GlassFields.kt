package cam.su.kernel.ui.component.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** iOS pull to refresh shows only its spinner: no "pull" / "release" captions. */
val IosRefreshTexts = listOf("", "", "", "")

private val IosRed = Color(0xFFFF3B30)

/**
 * iOS text field: a rounded grey fill with no focus ring, [label] as the placeholder, and a round
 * clear button while it is focused and holds text. [isError] draws a thin red outline instead.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine || maxLines == 1,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        textStyle = TextStyle(fontSize = 17.sp, color = colorScheme.onSurface),
        cursorBrush = SolidColor(colorScheme.primary),
        modifier = modifier.iosField(enabled, isError),
        decorationBox = { inner ->
            IosFieldDecoration(value.isEmpty(), label, enabled, interaction, { onValueChange("") }, trailingIcon, inner)
        },
    )
}

/** [GlassTextField] for callers that track the selection ([TextFieldValue]). */
@Composable
fun GlassTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine || maxLines == 1,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        textStyle = TextStyle(fontSize = 17.sp, color = colorScheme.onSurface),
        cursorBrush = SolidColor(colorScheme.primary),
        modifier = modifier.iosField(enabled, isError),
        decorationBox = { inner ->
            IosFieldDecoration(value.text.isEmpty(), label, enabled, interaction, { onValueChange(TextFieldValue("")) }, trailingIcon, inner)
        },
    )
}

@Composable
private fun Modifier.iosField(enabled: Boolean, isError: Boolean): Modifier {
    val fill = if (isInDarkTheme()) Color(0x3D767680) else Color(0x24767680)
    val shape = RoundedCornerShape(12.dp)
    return alpha(if (enabled) 1f else 0.5f)
        .background(fill, shape)
        .then(if (isError) Modifier.border(1.dp, IosRed.copy(alpha = 0.7f), shape) else Modifier)
}

@Composable
private fun IosFieldDecoration(
    empty: Boolean,
    label: String,
    enabled: Boolean,
    interaction: MutableInteractionSource,
    onClear: () -> Unit,
    trailingIcon: (@Composable () -> Unit)?,
    inner: @Composable () -> Unit,
) {
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .padding(start = 14.dp, end = if (trailingIcon == null) 8.dp else 0.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (empty && label.isNotEmpty()) {
                Text(label, fontSize = 17.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 1)
            }
            inner()
        }
        AnimatedVisibility(
            visible = enabled && focused && !empty,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) {
            Row {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(colorScheme.onSurface.copy(alpha = 0.28f), CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
        if (trailingIcon != null) trailingIcon()
    }
}
