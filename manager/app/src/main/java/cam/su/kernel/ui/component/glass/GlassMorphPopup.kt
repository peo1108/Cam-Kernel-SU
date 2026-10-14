package cam.su.kernel.ui.component.glass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import kotlin.math.roundToInt

/**
 * State of a [GlassMorphPopup]: where its anchor button sits and how far the popup has grown out of it
 * (0 = folded into the button, 1 = open).
 */
@Stable
class MorphPopupState {
    var expanded by mutableStateOf(false)
    internal var anchor by mutableStateOf(Rect.Zero)
    internal val progress = Animatable(0f)

    /** True from opening until the popup has shrunk back into its anchor: the anchor is hidden meanwhile. */
    val active: Boolean get() = expanded || progress.value > 0f
}

@Composable
fun rememberMorphPopupState(): MorphPopupState = remember { MorphPopupState() }

/**
 * Buttons of one [GlassButtonGroup] share its glass pill: their menus grow out of the whole pill,
 * and the pill hides while any of them is open.
 */
internal class MorphGroup {
    val members = mutableStateListOf<MorphPopupState>()
    var bounds = Rect.Zero
}

internal val LocalMorphGroup = staticCompositionLocalOf<MorphGroup?> { null }

/** Marks the button a [GlassMorphPopup] grows out of; the button is hidden while the popup stands in for it. */
@Composable
fun Modifier.morphAnchor(state: MorphPopupState): Modifier {
    val group = LocalMorphGroup.current
    if (group != null) {
        DisposableEffect(group, state) {
            state.anchor = group.bounds
            group.members.add(state)
            onDispose { group.members.remove(state) }
        }
        return this
    }
    return this
        .onGloballyPositioned { state.anchor = it.boundsInWindow() }
        .graphicsLayer { alpha = anchorAlpha(state.progress.value) }
}

/** Applied by [GlassButtonGroup]: tracks the pill for its members' menus and hides it while one is open. */
internal fun Modifier.morphGroup(group: MorphGroup): Modifier = this
    .onGloballyPositioned { coordinates ->
        group.bounds = coordinates.boundsInWindow()
        group.members.forEach { it.anchor = group.bounds }
    }
    .graphicsLayer { alpha = anchorAlpha(group.members.maxOfOrNull { it.progress.value } ?: 0f) }

/**
 * Near the button the real button and the morphing shape cross-fade, so neither pops in or out
 * (a two-button pill would otherwise jump from the one icon on the shape to both).
 */
private const val CROSS_FADE = 0.2f

private fun anchorAlpha(progress: Float) = (1f - progress / CROSS_FADE).coerceIn(0f, 1f)

/**
 * iOS style menu: the anchor button itself grows into the glass popup and shrinks back into it on
 * dismiss. [anchorIcon] is drawn on the morphing shape so the button seems to turn into the menu.
 */
@Composable
fun GlassMorphPopup(
    state: MorphPopupState,
    onDismissRequest: () -> Unit,
    anchorIcon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val hostVisible = remember { mutableStateOf(false) }
    LaunchedEffect(state.expanded) {
        if (state.expanded) {
            hostVisible.value = true
            state.progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f, visibilityThreshold = 0.001f))
        } else {
            state.progress.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 520f, visibilityThreshold = 0.001f))
            hostVisible.value = false
        }
    }
    MiuixPopupUtils.PopupLayout(
        visible = hostVisible,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableWindowDim = false,
        enableBackHandler = false,
    ) {
        BackHandler(enabled = state.expanded) { onDismissRequest() }
        // The menu is composed at its button's call site; a group there must not claim anchors inside it.
        CompositionLocalProvider(LocalMorphGroup provides null) {
            MorphLayer(state, onDismissRequest, anchorIcon, content)
        }
    }
}

@Composable
private fun MorphLayer(
    state: MorphPopupState,
    onDismissRequest: () -> Unit,
    anchorIcon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val p = state.progress.value
    val textStyles = MiuixTheme.textStyles
    val glassColors = colorScheme.copy(
        surfaceContainer = Color.Transparent,
        tertiaryContainer = colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    )

    val anchor = state.anchor.translate(-origin)
    val margin = with(density) { 12.dp.toPx() }
    // The menu opens over its button, sharing the button's top-end corner, kept on screen.
    val target = if (contentSize == IntSize.Zero) {
        anchor
    } else {
        val w = contentSize.width.toFloat()
        val h = contentSize.height.toFloat()
        val left = (anchor.right - w).coerceIn(margin, (hostSize.width - margin - w).coerceAtLeast(margin))
        val top = anchor.top.coerceIn(margin, (hostSize.height - margin - h).coerceAtLeast(margin))
        Rect(left, top, left + w, top + h)
    }
    val current = Rect(
        lerp(anchor.left, target.left, p),
        lerp(anchor.top, target.top, p),
        lerp(anchor.right, target.right, p),
        lerp(anchor.bottom, target.bottom, p),
    )
    val startRadius = anchor.minDimension / 2f
    val endRadius = with(density) { GlassDefaults.popupCorner.toPx() }
    val radius = lerp(startRadius, endRadius, p.coerceIn(0f, 1f)).coerceAtMost(current.minDimension / 2f)
    val contentAlpha = ((p - 0.25f) / 0.6f).coerceIn(0f, 1f)
    val iconAlpha = (1f - p / 0.35f).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInWindow()
                hostSize = it.size
            }
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.12f * state.progress.value.coerceIn(0f, 1f))) }
            .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } },
    ) {
        Layout(
            modifier = Modifier.graphicsLayer { alpha = (state.progress.value / CROSS_FADE).coerceIn(0f, 1f) },
            content = {
                val bgShape = RoundedCornerShape(with(density) { radius.toDp() })
                // iOS menus are nearly opaque: a surface veil over the glass keeps busy rows behind unreadable.
                Box(Modifier.overlayGlass(bgShape).background(colorScheme.surface.copy(alpha = 0.35f), bgShape))
                Box(
                    Modifier
                        .onSizeChanged { contentSize = it }
                        // Taps between rows must not reach the dismiss layer.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .graphicsLayer {
                            alpha = contentAlpha
                            clip = true
                            shape = WindowShape(current.translate(-target.topLeft), radius)
                        },
                ) {
                    // Rows paint surfaceContainer (selected: tertiaryContainer); clear them so the glass shows.
                    MiuixTheme(colors = glassColors, textStyles = textStyles) { content() }
                }
                Box(Modifier.graphicsLayer { alpha = iconAlpha }, contentAlignment = Alignment.Center) {
                    anchorIcon()
                }
            },
        ) { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            val bg = measurables[0].measure(
                Constraints.fixed(current.width.roundToInt().coerceAtLeast(1), current.height.roundToInt().coerceAtLeast(1)),
            )
            val menu = measurables[1].measure(loose)
            val icon = measurables[2].measure(
                Constraints.fixed(anchor.width.roundToInt().coerceAtLeast(0), anchor.height.roundToInt().coerceAtLeast(0)),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                bg.place(current.left.roundToInt(), current.top.roundToInt())
                menu.place(target.left.roundToInt(), target.top.roundToInt())
                // The button's icon rides the shape's top-end corner, where the button was.
                icon.place((current.right - anchor.width).roundToInt(), current.top.roundToInt())
            }
        }
    }
}

/** Clips the menu to the part the morphing shape has uncovered so far. */
private class WindowShape(private val window: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(window, CornerRadius(radius)))

    override fun equals(other: Any?) = other is WindowShape && other.window == window && other.radius == radius

    override fun hashCode() = 31 * window.hashCode() + radius.hashCode()
}
