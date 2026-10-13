// FloatingBottomBar stood on its side (adapted, like it, from compose-miuix-ui's
// IosLiquidGlassNavigationBar, Apache 2.0).

package cam.su.kernel.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import cam.su.kernel.ui.component.liquid.InnerShadow
import cam.su.kernel.ui.component.liquid.LiquidSpecular
import cam.su.kernel.ui.component.liquid.innerShadow
import cam.su.kernel.ui.component.liquid.lens
import cam.su.kernel.ui.component.liquid.rememberCombinedBackdrop
import cam.su.kernel.ui.component.liquid.rememberGravityRotatedHighlight
import cam.su.kernel.ui.component.liquid.vibrancy
import cam.su.kernel.ui.component.miuix.animation.DampedDragAnimation
import cam.su.kernel.ui.component.miuix.animation.InteractiveHighlight
import cam.su.kernel.ui.theme.isInDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** The bottom bar's measures turned upright: its 64 dp thickness is this bar's width. */
internal val SIDE_BAR_WIDTH = 64.dp
private val INSET = 4.dp

/** The drop: 56 dp across like the bottom bar's, a little taller than wide. */
private val TAB_HEIGHT = 68.dp

val LocalFloatingSideBarTabScale = staticCompositionLocalOf { { 1f } }

@Composable
fun ColumnScope.FloatingSideBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalFloatingSideBarTabScale.current
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
                onClick {
                    onClick()
                    true
                }
            }
            .onKeyEvent { event ->
                val activationKey = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
                if (activationKey) {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .fillMaxWidth()
            .height(TAB_HEIGHT)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * [FloatingBottomBar] stood on its side for landscape tablets, with the same water drop: the glass
 * pill (vibrancy, blur, lens), and the selection a drop that bulges when pressed, can be dragged
 * up and down, stretches with its speed and refracts the tabs into the accent color under it.
 */
@Composable
fun FloatingSideBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    isBlurEnabled: Boolean = true,
    content: @Composable ColumnScope.((Int) -> Unit) -> Unit,
) {
    val isInDark = isInDarkTheme()
    val pillShape = remember { CircleShape }
    val accentColor = MiuixTheme.colorScheme.primary
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainer
    val containerColor = if (isBlurEnabled) surfaceContainer.copy(0.4f) else surfaceContainer

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()

    val tabHeightPx = with(density) { TAB_HEIGHT.toPx() }
    val insetPx = with(density) { INSET.toPx() }
    val totalHeightPx = tabHeightPx * tabsCount + insetPx * 2

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx, totalHeightPx) {
        derivedStateOf {
            val fraction = (offsetAnimation.value / totalHeightPx).fastCoerceIn(-1f, 1f)
            rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val haptic = LocalHapticFeedback.current
    val onSelectedUpdated by rememberUpdatedState<(Int) -> Unit> { index ->
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onSelected(index)
    }

    fun indexAt(positionY: Float): Int =
        ((positionY - insetPx) / tabHeightPx).toInt().coerceIn(0, tabsCount - 1)

    val dampedDragAnimation = remember(animationScope, tabsCount, density) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset -> offset.y in 0f..totalHeightPx },
            onDragStarted = { position -> updateValue(indexAt(position.y).toFloat()) },
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onSelectedUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
            },
            onDrag = { _, dragAmount ->
                if (dragAmount.y != 0f) {
                    updateValue((targetValue + dragAmount.y / tabHeightPx).coerceIn(0f, (tabsCount - 1).toFloat()))
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.y) }
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }
    }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        if (currentIndex != index) {
            currentIndex = index
            onSelectedUpdated(index)
        }
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    val interactiveHighlight = remember(animationScope, dampedDragAnimation, tabHeightPx) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(size.width / 2f, insetPx + (dampedDragAnimation.value + 0.5f) * tabHeightPx + panelOffset)
            },
        )
    }

    val baseHighlight = rememberGravityRotatedHighlight(LiquidSpecular, extraDegrees = -45f)
    val pillHighlight = rememberGravityRotatedHighlight(LiquidSpecular, extraDegrees = 0f)
    val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val innerWidth = SIDE_BAR_WIDTH - INSET * 2
    val barHeight = TAB_HEIGHT * tabsCount + INSET * 2

    Box(modifier = modifier.width(SIDE_BAR_WIDTH).height(barHeight), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .selectableGroup()
                .graphicsLayer { translationY = panelOffset }
                .dropShadow(
                    shape = pillShape,
                    shadow = Shadow(radius = 10.dp, color = Color.Black, alpha = if (isInDark) 0.2f else 0.1f),
                )
                .then(
                    if (isBlurEnabled) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                padding = maxOf(padding, 40.dp.toPx())
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(refractionHeight = 24.dp.toPx(), refractionAmount = 24.dp.toPx())
                            },
                            highlight = { baseHighlight.value.copy(alpha = 0.75f) },
                            layerBlock = {
                                val s = lerp(1f, 1f + 16.dp.toPx() / size.height.coerceAtLeast(1f), dampedDragAnimation.pressProgress)
                                scaleX = s
                                scaleY = s
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                    } else {
                        Modifier.background(containerColor, pillShape)
                    },
                )
                .then(if (isBlurEnabled) interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier) else Modifier)
                .then(dampedDragAnimation.modifier)
                .width(SIDE_BAR_WIDTH)
                .height(barHeight)
                .padding(INSET),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompositionLocalProvider(LocalContentColor provides tabContentColor) {
                content(::activateTab)
            }
        }

        if (isBlurEnabled) {
            // the tabs again in the accent color, unseen: what the drop refracts
            CompositionLocalProvider(
                LocalFloatingSideBarTabScale provides { lerp(1f, 1.2f, dampedDragAnimation.pressProgress) },
                LocalContentColor provides accentColor,
            ) {
                Column(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationY = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(refractionHeight = 24.dp.toPx(), refractionAmount = 24.dp.toPx())
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight.modifier)
                        .width(innerWidth)
                        .height(barHeight)
                        .padding(vertical = INSET),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = { content(::activateTab) },
                )
            }
            // the drop
            Box(
                Modifier
                    .padding(top = INSET)
                    .graphicsLayer { translationY = dampedDragAnimation.value * tabHeightPx + panelOffset }
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { pillShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                refractionHeight = 10.dp.toPx() * progress,
                                refractionAmount = 14.dp.toPx() * progress,
                                depthEffect = true,
                                chromaticAberration = 0.5f,
                            )
                        },
                        highlight = { pillHighlight.value.copy(alpha = dampedDragAnimation.pressProgress) },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            // stretched along the way it moves, as the bottom bar's is sideways
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleY /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleX *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(
                                color = if (!isInDark) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f),
                                alpha = 1f - progress,
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        },
                    )
                    .innerShadow(shape = pillShape) {
                        InnerShadow(
                            radius = 8.dp * dampedDragAnimation.pressProgress,
                            color = Color.Black.copy(alpha = 0.15f),
                            alpha = dampedDragAnimation.pressProgress,
                        )
                    }
                    .width(innerWidth)
                    .height(TAB_HEIGHT),
            )
        } else {
            // no backdrop effects: a tinted drop with the tabs in accent inside it
            Box(
                Modifier
                    .padding(top = INSET)
                    .graphicsLayer { translationY = dampedDragAnimation.value * tabHeightPx + panelOffset }
                    .clip(pillShape)
                    .background(accentColor.copy(alpha = 0.15f), pillShape)
                    .width(innerWidth)
                    .height(TAB_HEIGHT),
                contentAlignment = Alignment.TopCenter,
            ) {
                CompositionLocalProvider(LocalContentColor provides accentColor) {
                    Column(
                        Modifier
                            .clearAndSetSemantics {}
                            .wrapContentHeight(align = Alignment.Top, unbounded = true)
                            .requiredHeight(TAB_HEIGHT * tabsCount)
                            .width(innerWidth)
                            .graphicsLayer { translationY = -dampedDragAnimation.value * tabHeightPx },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = { content(::activateTab) },
                    )
                }
            }
        }
    }
}
