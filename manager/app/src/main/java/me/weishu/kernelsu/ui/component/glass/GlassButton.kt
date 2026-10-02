package me.weishu.kernelsu.ui.component.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.component.liquid.LiquidSpecular
import me.weishu.kernelsu.ui.component.liquid.rememberGravityRotatedHighlight
import me.weishu.kernelsu.ui.component.miuix.animation.InteractiveHighlight
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import kotlin.math.min

/**
 * Small liquid glass control surface (droplet buttons, search field): thin tint so the lens
 * refraction shows, a specular rim that follows device tilt, depth lens with slight dispersion.
 */
@Composable
fun Modifier.liquidControl(shape: RoundedCornerShape, tint: Color = GlassDefaults.dropletTint()): Modifier {
    val rim = rememberGravityRotatedHighlight(LiquidSpecular, extraDegrees = -45f)
    return this
        .dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = 12.dp,
                color = Color.Black,
                alpha = if (isInDarkTheme()) 0.25f else 0.1f,
            ),
        )
        .glassSurface(
            shape = shape,
            tint = tint,
            blur = 2.dp,
            lens = true,
            highlight = { rim.value },
            refraction = GlassRefraction(
                height = GlassDefaults.dropletLensHeight,
                amount = GlassDefaults.dropletLensAmount,
                depth = true,
                chromaticAberration = GlassDefaults.dropletChromaticAberration,
            ),
        )
}

/**
 * Liquid glass "droplet" replacement for the miuix IconButton: same parameters, drawn as a
 * glass circle (or rounded rect when [cornerRadius] is set) that swells and lights up under the
 * finger while pressed. A specified [backgroundColor] tints the glass with that color.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp? = null,
    minHeight: Dp = GlassDefaults.dropletSize,
    minWidth: Dp = GlassDefaults.dropletSize,
    content: @Composable () -> Unit,
) {
    val shape = if (cornerRadius != null) RoundedCornerShape(cornerRadius) else RoundedCornerShape(percent = 50)
    val tint = if (backgroundColor.isSpecified) {
        backgroundColor.copy(alpha = min(backgroundColor.alpha, GlassDefaults.coloredCardTint))
    } else {
        GlassDefaults.dropletTint()
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed || holdDownState) GlassDefaults.dropletPressScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "dropletScale",
    )
    // The touch glow is an AGSL shader: API 33+ only.
    val animationScope = rememberCoroutineScope()
    val touchLight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember(animationScope) { InteractiveHighlight(animationScope) }
    } else {
        null
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidControl(shape, tint)
            .then(if (touchLight != null) touchLight.modifier.then(touchLight.gestureModifier) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
