package cam.su.kernel.ui.component.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Whole page (background + content) of the current [GlassPage]. Only overlays may sample it:
 * they are drawn by the root popup host, outside the recorded page, so there is no feedback loop.
 */
val LocalGlassOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun Modifier.overlayGlass(shape: RoundedCornerShape, frost: Float = 1f): Modifier {
    val dark = isInDarkTheme()
    // frost < 1: clearer glass (a droplet), less tint and blur
    return glassSurface(
        shape = shape,
        tint = colorScheme.surface.copy(alpha = frost * if (dark) GlassDefaults.overlayTintDark else GlassDefaults.overlayTintLight),
        blur = GlassDefaults.dialogBlur * frost,
        lens = true,
        highlight = { if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight },
        refraction = GlassRefraction(GlassDefaults.dialogLensHeight, GlassDefaults.dialogLensAmount),
        source = LocalGlassOverlayBackdrop.current ?: LocalGlassBackdrop.current,
    )
}

/**
 * iOS style alert on liquid glass: centred, title centred and bold, pops in from a slight zoom and
 * fades out. Tapping the dimmed backdrop or Back calls [onDismissRequest]; buttons inside close it
 * through LocalDismissState, as with the miuix dialog it replaces.
 */
@Composable
fun GlassDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    insideMargin: DpSize? = null,
    content: @Composable () -> Unit,
) {
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(show) { visible.value = show }
    val textStyles = MiuixTheme.textStyles
    // Secondary (cancel) buttons paint secondaryVariant; make them translucent over the glass.
    val glassColors = colorScheme.copy(secondaryVariant = colorScheme.secondaryVariant.copy(alpha = 0.4f))
    val margin = insideMargin ?: DpSize(24.dp, 24.dp)
    val shape = RoundedCornerShape(GlassDefaults.dialogCorner)
    MiuixPopupUtils.PopupLayout(
        visible = visible,
        enterTransition = fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 520f), initialScale = 1.12f),
        exitTransition = fadeOut(tween(140)),
        enableWindowDim = false,
        enableBackHandler = false,
    ) {
        BackHandler(enabled = visible.value && onDismissRequest != null) { onDismissRequest?.invoke() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onDismissRequest?.invoke()
                }
                .safeDrawingPadding()
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.88f)
                    .heightIn(max = LocalWindowInfo.current.containerSize.height.let { with(LocalDensity.current) { it.toDp() } } * 0.85f)
                    .overlayGlass(shape)
                    // like iOS alerts, nearly opaque: lists behind must not show through
                    .background(colorScheme.surface.copy(alpha = 0.5f), shape)
                    // taps on the card must not reach the backdrop
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = margin.width, vertical = margin.height),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                if (title != null || summary != null) Spacer(Modifier.height(16.dp))
                MiuixTheme(colors = glassColors, textStyles = textStyles) {
                    CompositionLocalProvider(LocalDismissState provides { visible.value = false }) {
                        content()
                    }
                }
            }
        }
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
    morph: MorphPopupState? = null,
    morphAlign: MorphAlign = MorphAlign.Corner,
    content: @Composable () -> Unit,
) {
    // With a morph state the menu buds off the button marked with Modifier.morphAnchor.
    if (morph != null) {
        LaunchedEffect(show) { morph.expanded = show }
        GlassMorphPopup(
            state = morph,
            onDismissRequest = { onDismissRequest?.invoke() },
            align = morphAlign,
            liquid = morphAlign == MorphAlign.Corner,
            content = content,
        )
        return
    }
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
