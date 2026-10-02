package me.weishu.kernelsu.ui.component.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import me.weishu.kernelsu.ui.component.liquid.lens
import me.weishu.kernelsu.ui.component.liquid.vibrancy
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlin.math.min

/**
 * Liquid glass replacement for the miuix [Card]: same parameters, but the container samples the
 * page background through [LocalGlassBackdrop]. A non-null [colors] tints the glass with its
 * container color instead of the neutral surface tint.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDefaults.cardCorner,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors? = null,
    lens: Boolean = true,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = colors?.color?.let { it.copy(alpha = min(it.alpha, GlassDefaults.coloredCardTint)) }
        ?: GlassDefaults.cardTint()
    val cardColors = CardDefaults.defaultColors(
        color = Color.Transparent,
        contentColor = colors?.contentColor ?: CardDefaults.defaultColors().contentColor,
    )
    val glassModifier = modifier.glassSurface(
        shape = RoundedCornerShape(cornerRadius),
        tint = tint,
        blur = GlassDefaults.cardBlur,
        lens = lens,
    )
    if (onClick == null && onLongPress == null) {
        Card(
            modifier = glassModifier,
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            colors = cardColors,
            content = content,
        )
    } else {
        Card(
            modifier = glassModifier,
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            colors = cardColors,
            pressFeedbackType = pressFeedbackType,
            showIndication = showIndication,
            onClick = onClick,
            onLongPress = onLongPress,
            content = content,
        )
    }
}

/** [GlassCard] without lens refraction, for items of long scrolling lists. */
@Composable
fun GlassListCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassDefaults.cardCorner,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors? = null,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = GlassCard(
    modifier = modifier,
    cornerRadius = cornerRadius,
    insideMargin = insideMargin,
    colors = colors,
    lens = false,
    pressFeedbackType = pressFeedbackType,
    showIndication = showIndication,
    onClick = onClick,
    onLongPress = onLongPress,
    content = content,
)

/**
 * Draws a glass surface sampling [LocalGlassBackdrop]; plain tinted background when there is none.
 * [highlight] overrides the default small specular rim; [refraction] overrides the card lens.
 */
@Composable
fun Modifier.glassSurface(
    shape: RoundedCornerShape,
    tint: Color,
    blur: Dp,
    lens: Boolean,
    highlight: (() -> Highlight)? = null,
    refraction: GlassRefraction? = null,
    source: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
    val backdrop = source ?: return this.background(tint, shape)
    val dark = isInDarkTheme()
    val rim = highlight ?: { if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight }
    val r = refraction ?: GlassRefraction(GlassDefaults.cardLensHeight, GlassDefaults.cardLensAmount)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blur.toPx(), blur.toPx())
            if (lens) {
                lens(
                    refractionHeight = r.height.toPx(),
                    refractionAmount = r.amount.toPx(),
                    depthEffect = r.depth,
                    chromaticAberration = r.chromaticAberration,
                )
            }
        },
        highlight = { rim() },
        onDrawSurface = { drawRect(tint) },
    )
}

/** Lens settings for [glassSurface]. */
data class GlassRefraction(
    val height: Dp,
    val amount: Dp,
    val depth: Boolean = false,
    val chromaticAberration: Float = 0f,
)
