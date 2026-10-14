package cam.su.kernel.ui.component.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * State of a [GlassMorphPopup]. Opening runs two phases: [split] (a droplet buds off the button and
 * tears away on a liquid neck) then [grow] (the droplet inflates into the menu). Closing reverses them.
 */
@Stable
class MorphPopupState {
    var expanded by mutableStateOf(false)
    internal var anchor by mutableStateOf(Rect.Zero)
    internal val split = Animatable(0f)
    internal val grow = Animatable(0f)

    /** True from opening until the droplet has merged back into its button. */
    val active: Boolean get() = expanded || split.value > 0f
}

@Composable
fun rememberMorphPopupState(): MorphPopupState = remember { MorphPopupState() }

/**
 * Marks the button a [GlassMorphPopup] buds off. The button stays: it swells toward the droplet while
 * it tears away and again when it merges back. Put [MorphIcon] inside so it turns into a close button.
 */
fun Modifier.morphAnchor(state: MorphPopupState): Modifier = this
    .onGloballyPositioned { state.anchor = it.boundsInWindow() }
    .graphicsLayer {
        val s = state.split.value.coerceIn(0f, 1f)
        val swell = 1f + 0.07f * sin(PI.toFloat() * s)
        scaleX = swell
        scaleY = swell
        // the droplet leaves from the bottom-start corner: swell toward it
        transformOrigin = TransformOrigin(1f, 0f)
    }

/** The button's icon, turning a quarter and becoming a close mark while its menu is open. */
@Composable
fun MorphIcon(state: MorphPopupState, tint: Color = colorScheme.onSurface, icon: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.graphicsLayer {
                val s = state.split.value.coerceIn(0f, 1f)
                rotationZ = 90f * s
                alpha = (1f - 2f * s).coerceIn(0f, 1f)
            },
        ) { icon() }
        Box(
            Modifier.graphicsLayer {
                val s = state.split.value.coerceIn(0f, 1f)
                rotationZ = -90f + 90f * s
                alpha = (2f * s - 1f).coerceIn(0f, 1f)
            },
        ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = tint) }
    }
}

/** Window areas a menu must keep clear of (the floating side and bottom bars). */
internal object MorphObstacles {
    val rects = mutableStateMapOf<Any, Rect>()
}

/** Registers this element as an area [GlassMorphPopup] menus must not cover. */
@Composable
fun Modifier.morphObstacle(): Modifier {
    val key = remember { Any() }
    DisposableEffect(key) { onDispose { MorphObstacles.rects.remove(key) } }
    return onGloballyPositioned { MorphObstacles.rects[key] = it.boundsInWindow() }
}

enum class MorphAlign {
    /** Icon buttons: the menu hangs from the button's bottom-start corner, beside it. */
    Corner,

    /** Row values (dropdowns): the menu hangs under the value, end-aligned with it. */
    End,
}

/**
 * iOS style menu that buds off its anchor button as a liquid droplet and inflates into a glass popup.
 * Tapping outside (or the button, now a close mark) folds it back into the button.
 */
@Composable
fun GlassMorphPopup(
    state: MorphPopupState,
    onDismissRequest: () -> Unit,
    align: MorphAlign = MorphAlign.Corner,
    liquid: Boolean = true,
    content: @Composable () -> Unit,
) {
    val hostVisible = remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(state.expanded) {
        if (state.expanded) {
            hostVisible.value = true
            // The phases overlap: the droplet starts inflating while it still flies, so there is no stop.
            coroutineScope {
                launch {
                    state.split.animateTo(1f, tween(170, easing = FastOutSlowInEasing))
                    // the neck has just torn
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                snapshotFlow { state.split.value }.first { it >= 0.55f }
                state.grow.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 520f, visibilityThreshold = 0.001f))
            }
        } else {
            coroutineScope {
                launch { state.grow.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = 0.001f)) }
                snapshotFlow { state.grow.value }.first { it <= 0.3f }
                state.split.animateTo(0f, tween(170, easing = FastOutSlowInEasing))
            }
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
        MorphLayer(state, onDismissRequest, align, liquid, content)
    }
}

/** One frame of the morph, in the popup host's coordinates. */
private class MorphFrame(
    val s: Float,
    val g: Float,
    val anchor: Rect,
    val target: Rect,
    val c1: Offset,
    val r1: Float,
    val c2: Offset,
    val r2: Float,
    val current: Rect,
    val radius: Float,
)

/**
 * Animation values are read only in layout and draw (never in composition), so a frame re-places and
 * redraws the menu without recomposing its rows: that is what keeps the morph smooth.
 */
@Composable
private fun MorphLayer(
    state: MorphPopupState,
    onDismissRequest: () -> Unit,
    align: MorphAlign,
    liquid: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val textStyles = MiuixTheme.textStyles
    val glassColors = colorScheme.copy(
        surfaceContainer = Color.Transparent,
        tertiaryContainer = colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    )
    val veil = colorScheme.surface.copy(alpha = 0.35f)
    val neckColor = colorScheme.surface.copy(alpha = 0.3f)
    val neck = if (liquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember { NeckShader() } else null
    val gap = with(density) { 8.dp.toPx() }
    val endRadius = with(density) { GlassDefaults.popupCorner.toPx() }
    val shift = with(density) { 6.dp.toPx() }

    fun frame(): MorphFrame {
        val s = state.split.value.coerceIn(0f, 1f)
        val g = state.grow.value
        val anchor = state.anchor.translate(-origin)
        val obstacles = MorphObstacles.rects.values.map { it.translate(-origin) }
        val target = placeMenu(anchor, contentSize, hostSize, obstacles, gap, align)
        // The button's circle (for a row value: a circle at its end) and the droplet budding off its rim.
        val r1 = anchor.minDimension / 2f
        val c1 = Offset(anchor.right - r1, anchor.center.y)
        val r2 = r1 * lerp(0.3f, 0.55f, s)
        val corner = target.nearestCorner(c1)
        val inward = Offset(if (corner.x > target.center.x) -1f else 1f, if (corner.y > target.center.y) -1f else 1f)
        val dropEnd = corner + inward * r2
        val toward = (dropEnd - c1).let { it / it.getDistance().coerceAtLeast(1f) }
        val c2 = lerp(c1 + toward * r1, dropEnd, s)
        val drop = Rect(c2, r2)
        // The droplet inflates into the menu.
        val current = Rect(
            lerp(drop.left, target.left, g),
            lerp(drop.top, target.top, g),
            lerp(drop.right, target.right, g),
            lerp(drop.bottom, target.bottom, g),
        )
        val radius = lerp(r2, endRadius, g.coerceIn(0f, 1f)).coerceIn(0f, current.minDimension / 2f)
        return MorphFrame(s, g, anchor, target, c1, r1, c2, r2, current, radius)
    }

    // The glass reads its corner at draw time, so the shape changes without recomposition.
    val bgShape = remember {
        RoundedCornerShape(object : CornerSize {
            override fun toPx(shapeSize: Size, density: Density) = frame().radius
        })
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInWindow()
                hostSize = it.size
            }
            .drawBehind {
                val f = frame()
                // light dim everywhere but the button, which now closes the menu
                val dim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(f.anchor, CornerRadius(f.r1)))
                }
                drawPath(dim, Color.Black.copy(alpha = 0.08f * f.s))
                // the liquid neck between button and droplet, fading as the droplet inflates
                if (neck != null && f.s > 0f && f.g < 0.05f) {
                    val a = neckColor.alpha * (f.s / 0.25f).coerceAtMost(1f) * (1f - f.g.coerceAtLeast(0f) / 0.05f)
                    neck.update(f.c1, f.r1, f.c2, f.r2, k = f.r1 * 0.9f, color = neckColor.copy(alpha = a))
                    drawRect(neck.brush)
                }
            }
            .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } },
    ) {
        Layout(
            content = {
                Box(
                    Modifier
                        .graphicsLayer {
                            // a clear droplet frosting over into the menu: fade the glass, never re-blur it
                            val f = frame()
                            alpha = (f.s / 0.25f).coerceIn(0f, 1f) * lerp(0.3f, 1f, f.g.coerceIn(0f, 1f))
                        }
                        .overlayGlass(bgShape)
                        // iOS menus are nearly opaque: a surface veil keeps busy rows behind unreadable
                        .drawBehind {
                            val g = state.grow.value.coerceIn(0f, 1f)
                            drawOutline(bgShape.createOutline(size, layoutDirection, this), veil.copy(alpha = veil.alpha * g))
                        },
                )
                Box(
                    Modifier
                        .onSizeChanged { contentSize = it }
                        // Taps between rows must not reach the dismiss layer.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .graphicsLayer {
                            val f = frame()
                            alpha = ((f.g - 0.3f) / 0.5f).coerceIn(0f, 1f)
                            translationY = -shift * (1f - f.g.coerceIn(0f, 1f))
                            clip = true
                            shape = WindowShape(f.current.translate(-f.target.topLeft), f.radius)
                        },
                ) {
                    // Rows paint surfaceContainer (selected: tertiaryContainer); clear them so the glass shows.
                    MiuixTheme(colors = glassColors, textStyles = textStyles) { content() }
                }
            },
        ) { measurables, constraints ->
            val f = frame()
            val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            val bg = measurables[0].measure(
                Constraints.fixed(f.current.width.roundToInt().coerceAtLeast(1), f.current.height.roundToInt().coerceAtLeast(1)),
            )
            val menu = measurables[1].measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (f.s > 0f) bg.place(f.current.left.roundToInt(), f.current.top.roundToInt())
                menu.place(f.target.left.roundToInt(), f.target.top.roundToInt())
            }
        }
    }
}

/**
 * Where the open menu sits: below the anchor ([MorphAlign.Corner]: hanging from its bottom-start
 * corner; [MorphAlign.End]: end-aligned), above it when there is no room below, never over an obstacle.
 */
private fun placeMenu(
    anchor: Rect,
    size: IntSize,
    host: IntSize,
    obstacles: List<Rect>,
    gap: Float,
    align: MorphAlign,
): Rect {
    if (size == IntSize.Zero || host == IntSize.Zero) return anchor
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    var right = when (align) {
        MorphAlign.Corner -> anchor.left + anchor.width * 0.25f
        MorphAlign.End -> anchor.right
    }
    var top = anchor.bottom + gap
    if (top + h > host.height - gap && anchor.top - gap - h >= gap) top = anchor.top - gap - h
    var rect = Rect(right - w, top, right, top + h)
    // slide sideways off any bar it would cover, to whichever side has room
    obstacles.forEach { bar ->
        if (rect.overlaps(bar)) {
            right = if (bar.left - gap - w >= gap) bar.left - gap else bar.right + gap + w
            rect = Rect(right - w, rect.top, right, rect.bottom)
        }
    }
    val left = rect.left.coerceIn(gap, (host.width - gap - w).coerceAtLeast(gap))
    val t = rect.top.coerceIn(gap, (host.height - gap - h).coerceAtLeast(gap))
    return Rect(left, t, left + w, t + h)
}

private fun Rect.nearestCorner(p: Offset): Offset =
    listOf(topLeft, topRight, bottomLeft, bottomRight).minBy { hypot(it.x - p.x, it.y - p.y) }

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/** Clips the menu to the part the inflating droplet has uncovered so far. */
private class WindowShape(private val window: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(window, CornerRadius(radius)))

    override fun equals(other: Any?) = other is WindowShape && other.window == window && other.radius == radius

    override fun hashCode() = 31 * window.hashCode() + radius.hashCode()
}

/**
 * Metaball neck: the smooth union of the button and droplet circles minus both circles (they draw
 * themselves), so only the liquid bridge is painted. It thins and tears as the circles part.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class NeckShader {
    private val shader = RuntimeShader(
        """
        uniform float2 c1;
        uniform float r1;
        uniform float2 c2;
        uniform float r2;
        uniform float k;
        layout(color) uniform half4 color;
        half4 main(float2 p) {
            float d1 = length(p - c1) - r1;
            float d2 = length(p - c2) - r2;
            float h = clamp(0.5 + 0.5 * (d2 - d1) / k, 0.0, 1.0);
            float d = mix(d2, d1, h) - k * h * (1.0 - h);
            float inside = clamp(0.5 - d, 0.0, 1.0);
            float outsideCircles = clamp(min(d1, d2) + 0.5, 0.0, 1.0);
            return color * (inside * outsideCircles);
        }
        """.trimIndent(),
    )
    val brush = ShaderBrush(shader)

    fun update(c1: Offset, r1: Float, c2: Offset, r2: Float, k: Float, color: Color) {
        shader.setFloatUniform("c1", c1.x, c1.y)
        shader.setFloatUniform("r1", r1)
        shader.setFloatUniform("c2", c2.x, c2.y)
        shader.setFloatUniform("r2", r2)
        shader.setFloatUniform("k", k)
        shader.setColorUniform("color", color.toArgb())
    }
}
