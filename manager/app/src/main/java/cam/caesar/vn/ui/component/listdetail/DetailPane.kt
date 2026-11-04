package cam.su.kernel.ui.component.listdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.ui.component.glass.GlassButtonColumn
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassDefaults
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

/** What sits above a detail's content for now, scrolled into view while [showing]. */
class DetailNotice(val showing: Boolean, val content: @Composable () -> Unit)

val LocalDetailNotice = staticCompositionLocalOf<DetailNotice?> { null }

/** Where the bar down the screen edge goes: on the spot the top bar keeps for it, from the end of the page. */
private class EdgeBarSpot {
    private var bar: LayoutCoordinates? = null
    private var slot: LayoutCoordinates? = null
    var offset by mutableStateOf<IntOffset?>(null)
        private set

    fun placeBar(coordinates: LayoutCoordinates, rtl: Boolean) {
        bar = coordinates
        update(rtl)
    }

    fun placeSlot(coordinates: LayoutCoordinates, rtl: Boolean) {
        slot = coordinates
        update(rtl)
    }

    private fun update(rtl: Boolean) {
        val bar = bar?.takeIf { it.isAttached } ?: return
        val slot = slot?.takeIf { it.isAttached } ?: return
        val at = bar.localPositionOf(slot, Offset.Zero)
        val fromEnd = if (rtl) at.x else bar.size.width - at.x - slot.size.width
        val next = IntOffset(-fromEnd.roundToInt(), at.y.roundToInt())
        if (next != offset) offset = next
    }
}

/**
 * A detail beside a list: the list's bar with [title] and, down the screen edge, one bar with the
 * close button (when [onClose] is set) and [actions]; then a [header], where a picked row's icon and
 * name land, and [content]. All of it scrolls under the bar and blurs away there, as the list beside
 * it does. Inside [PaneDetail], the glass cards of
 * [content] come out of the picked row; the header's card stays put and its text, marked
 * [paneReveal], shows as the icon and name land.
 */
@Composable
fun DetailPaneScaffold(
    title: String,
    onClose: (() -> Unit)?,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    actions: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val notice = LocalDetailNotice.current
    LaunchedEffect(notice?.showing) {
        if (notice?.showing == true) listState.animateScrollToItem(0)
    }
    DetailPaneFrame(title, onClose, bottomPadding, actions) { padding, scrolling ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight().then(scrolling),
            contentPadding = padding,
            overscrollEffect = null,
        ) {
            if (notice != null) item(key = "notice") { notice.content() }
            if (header != null) item(key = "header") { PaneHeader(header) }
            content()
        }
    }
}

/**
 * [DetailPaneScaffold]'s frame, for a pane that is not one list: its [body] gets the padding that
 * keeps content clear of the bar and the edges, and the modifier its scrolling content needs to
 * move under the bar and collapse it.
 */
@Composable
fun DetailPaneFrame(
    title: String,
    onClose: (() -> Unit)?,
    bottomPadding: Dp,
    actions: (@Composable () -> Unit)? = null,
    body: @Composable (PaddingValues, Modifier) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val spot = remember { EdgeBarSpot() }
                Box(Modifier.onGloballyPositioned { spot.placeBar(it, rtl) }) {
                    // the list's bar, not SmallTopAppBar: a bar that cannot collapse never reports
                    // content overlapping it, so BlurredBar's scroll edge would not show
                    TopAppBar(
                        title = title,
                        color = barColor,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            // keeps the spot where the Files tab's menu button sits
                            Spacer(Modifier.size(GlassDefaults.dropletSize).onGloballyPositioned { spot.placeSlot(it, rtl) })
                        },
                    )
                    // over the bar, not in it: the bar cuts off what hangs below it once collapsed
                    val offset = spot.offset
                    if (offset != null && (onClose != null || actions != null)) {
                        GlassButtonColumn(Modifier.align(Alignment.TopEnd).offset { offset }) {
                            if (onClose != null) {
                                GlassIconButton(onClick = onClose) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.close),
                                        tint = colorScheme.onSurface,
                                    )
                                }
                            }
                            actions?.invoke()
                        }
                    }
                }
            }
        },
        popupHost = { },
        contentWindowInsets = pageHorizontalInsets(),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            body(
                PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp + LocalDetailTopExtra.current,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = bottomPadding + 12.dp,
                ),
                Modifier
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            )
        }
    }
}

/** A labelled button in a detail or a log: the main action, unless [background] and [tint] say otherwise. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    background: Color = colorScheme.primary,
    tint: Color = colorScheme.onPrimary,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text = label, color = tint, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
    }
}

/** One line of an overview card: what is counted and how many. */
class OverviewLine(val title: String, val value: String)

/** The card a pane shows while nothing is picked: a few counts over the whole list. */
@Composable
fun OverviewCard(lines: List<OverviewLine>, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        lines.forEach { line ->
            BasicComponent(
                title = line.title,
                endActions = {
                    Text(
                        text = line.value,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantActions,
                    )
                },
            )
        }
    }
}

/** A quiet line under the overview saying what picking a row does. */
@Composable
fun PaneHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = modifier.panePiece().padding(horizontal = 28.dp, vertical = 4.dp),
    )
}
