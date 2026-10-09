package cam.su.kernel.ui.component.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Whole page (background + content) of the current [GlassPage]. Only overlays may sample it:
 * they are drawn by the root popup host, outside the recorded page, so there is no feedback loop.
 */
val LocalGlassOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
private fun Modifier.overlayGlass(shape: RoundedCornerShape): Modifier {
    val dark = isInDarkTheme()
    return glassSurface(
        shape = shape,
        tint = colorScheme.surface.copy(alpha = if (dark) GlassDefaults.overlayTintDark else GlassDefaults.overlayTintLight),
        blur = GlassDefaults.dialogBlur,
        lens = true,
        highlight = { if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight },
        refraction = GlassRefraction(GlassDefaults.dialogLensHeight, GlassDefaults.dialogLensAmount),
        source = LocalGlassOverlayBackdrop.current ?: LocalGlassBackdrop.current,
    )
}

/** Liquid glass [OverlayDialog] with the parameters used in this app. */
@Composable
fun GlassDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    insideMargin: DpSize? = null,
    content: @Composable () -> Unit,
) {
    val modifier = Modifier.overlayGlass(RoundedCornerShape(GlassDefaults.dialogCorner))
    val textStyles = MiuixTheme.textStyles
    // Secondary (cancel) buttons paint secondaryVariant; make them translucent over the glass.
    val glassColors = colorScheme.copy(secondaryVariant = colorScheme.secondaryVariant.copy(alpha = 0.4f))
    val glassContent: @Composable () -> Unit = {
        MiuixTheme(colors = glassColors, textStyles = textStyles) { content() }
    }
    if (insideMargin != null) {
        OverlayDialog(
            show = show,
            modifier = modifier,
            title = title,
            summary = summary,
            backgroundColor = Color.Transparent,
            onDismissRequest = onDismissRequest,
            insideMargin = insideMargin,
            cornerRadius = GlassDefaults.dialogCorner,
            content = glassContent,
        )
    } else {
        OverlayDialog(
            show = show,
            modifier = modifier,
            title = title,
            summary = summary,
            backgroundColor = Color.Transparent,
            onDismissRequest = onDismissRequest,
            cornerRadius = GlassDefaults.dialogCorner,
            content = glassContent,
        )
    }
}

/** Liquid glass [OverlayListPopup] with the parameters used in this app. */
@Composable
fun GlassListPopup(
    show: Boolean,
    popupPositionProvider: PopupPositionProvider,
    alignment: PopupPositionProvider.Align,
    onDismissRequest: (() -> Unit)? = null,
    maxHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    // The popup container is drawn by the root popup host (call-site locals do not reach it), so
    // the glass is laid over its content area instead; the backdrop is resolved here, at the call site.
    val glass = Modifier.overlayGlass(RoundedCornerShape(GlassDefaults.popupCorner))
    val textStyles = MiuixTheme.textStyles
    val glassColors = colorScheme.copy(
        surfaceContainer = Color.Transparent,
        tertiaryContainer = colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    )
    OverlayListPopup(
        show = show,
        popupPositionProvider = popupPositionProvider,
        alignment = alignment,
        onDismissRequest = onDismissRequest,
        maxHeight = maxHeight,
        content = {
            // Rows paint surfaceContainer (selected: tertiaryContainer); clear them so the glass shows.
            MiuixTheme(colors = glassColors, textStyles = textStyles) {
                Box(glass) { content() }
            }
        },
    )
}

/** Liquid glass floating action button: a primary-tinted droplet. */
@Composable
fun GlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = colorScheme.primary,
    @Suppress("UNUSED_PARAMETER") shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    GlassIconButton(
        onClick = onClick,
        modifier = modifier,
        backgroundColor = containerColor,
        minWidth = GlassDefaults.fabSize,
        minHeight = GlassDefaults.fabSize,
    ) {
        Box(Modifier.defaultMinSize(GlassDefaults.fabSize, GlassDefaults.fabSize), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
