package cam.su.kernel.ui.component.listdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.component.bottombar.LocalNavSideInsets
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Superuser, Module and the su log put their list and the picked item's detail side by side once
 * the page is this wide (a landscape tablet: 1221 dp beside the side bar on the TB323FU, 1103 dp at
 * the largest page scale). Narrower, items open in place or on their own page as before.
 */
const val LIST_DETAIL_MIN_WIDTH_DP = 900f

/** The Superuser and Module lists beside a detail. */
val ListPaneWidth = 440.dp

/** The su log's list is wider: its rows carry whole command lines. */
val SulogListPaneWidth = 560.dp

/** From this width a detail lays its cards out in two columns. */
val DetailTwoColumnWidth = 640.dp

fun showListDetail(pageWidthDp: Float): Boolean = pageWidthDp >= LIST_DETAIL_MIN_WIDTH_DP

/** The window's width in the app's dp, so the page scale setting counts. */
@Composable
fun windowWidthDp(): Float = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density

/** Whether the main pages show list and detail side by side; MainScreen sets it from its width. */
val LocalListDetail = compositionLocalOf { false }

/** The window edges whose insets the current pane keeps clear of: both, or only the one it touches. */
val LocalPaneSides = staticCompositionLocalOf { WindowInsetsSides.Horizontal }

/**
 * Horizontal insets for a page's content: system bars, the cutout and the side bar's column, on the
 * edges this page (or pane of it) touches.
 */
@Composable
fun pageHorizontalInsets(): WindowInsets =
    WindowInsets.systemBars.add(WindowInsets.displayCutout).add(LocalNavSideInsets.current).only(LocalPaneSides.current)

/**
 * With [split], the list at the start, [listWidth] wide plus the insets on that edge, and the
 * detail filling the rest, a hairline between them; each pane keeps clear of its own edge only
 * ([LocalPaneSides]). Without, the list alone: it stays at the same spot either way, so turning
 * the tablet keeps its scroll position and search. [motion] draws a picked row's icon and name
 * flying into the detail, over both panes.
 */
@Composable
fun ListDetailLayout(
    split: Boolean,
    listWidth: Dp,
    motion: PaneMotion?,
    flightParts: (Any) -> Map<FlightPart, @Composable () -> Unit> = { emptyMap() },
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val startInset = WindowInsets.systemBars.add(WindowInsets.displayCutout).add(LocalNavSideInsets.current)
        .only(WindowInsetsSides.Start).asPaddingValues().calculateStartPadding(LocalLayoutDirection.current)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // turning the tablet splits or joins the panes: that is the split's own motion (PaneMotion)
    val latestParts by rememberUpdatedState(flightParts)
    SideEffect { motion?.applySplit(split) { latestParts(it) } }

    // the list's width, the divider's place and the detail's width come from the layout pass, so
    // the list narrowing during a split relays out the list only: the detail keeps its final width
    val listTarget = { full: Int, density: Density -> with(density) { (listWidth + startInset).roundToPx() }.coerceAtMost(full) }
    Box(Modifier.fillMaxSize()) {
        Box(
            if (split) {
                Modifier.layout { measurable, constraints ->
                    val full = constraints.maxWidth
                    val width = motion?.listWidth(full, listTarget(full, this)) ?: listTarget(full, this)
                    val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                    layout(width, constraints.maxHeight) { placeable.placeRelative(0, 0) }
                }
            } else {
                Modifier.fillMaxSize()
            }
        ) {
            CompositionLocalProvider(
                LocalPaneSides provides if (split) WindowInsetsSides.Start else WindowInsetsSides.Horizontal,
                content = list,
            )
        }
        if (split) {
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val full = constraints.maxWidth
                        val x = motion?.listWidth(full, listTarget(full, this)) ?: listTarget(full, this)
                        val placeable = measurable.measure(constraints.copy(minWidth = 0))
                        layout(full, constraints.maxHeight) { placeable.placeRelative(x, 0) }
                    }
                    .padding(top = top + 12.dp, bottom = 12.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // draws down from the top while the panes split
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        scaleY = motion?.dividerScale() ?: 1f
                    }
                    .background(colorScheme.dividerLine)
            )
            Box(
                Modifier
                    .layout { measurable, constraints ->
                        val full = constraints.maxWidth
                        val start = listTarget(full, this) + 1.dp.roundToPx()
                        val width = (full - start).coerceAtLeast(0)
                        val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                        layout(full, constraints.maxHeight) { placeable.placeRelative(start, 0) }
                    }
                    .graphicsLayer { alpha = motion?.detailAlpha() ?: 1f }
            ) {
                CompositionLocalProvider(LocalPaneSides provides WindowInsetsSides.End, content = detail)
            }
        }
        if (split && motion != null) motion.flight.Overlay()
    }
}
