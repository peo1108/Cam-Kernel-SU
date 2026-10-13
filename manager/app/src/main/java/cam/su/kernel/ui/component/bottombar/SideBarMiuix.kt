package cam.su.kernel.ui.component.bottombar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.Cam
import cam.su.kernel.R
import cam.su.kernel.ui.LocalMainPagerState
import cam.su.kernel.ui.component.FloatingSideBar
import cam.su.kernel.ui.component.FloatingSideBarItem
import cam.su.kernel.ui.component.SIDE_BAR_WIDTH
import cam.su.kernel.ui.component.search.GlassCircleButton
import cam.su.kernel.ui.component.search.LocalMainSearch
import cam.su.kernel.ui.component.search.SearchDock
import cam.su.kernel.ui.component.search.SearchablePages
import cam.su.kernel.ui.theme.LocalEnableFloatingBottomBarBlur
import cam.su.kernel.ui.theme.LocalNavBarPosition
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * Room the floating side bar takes at the edge of the main pages: only their bodies keep clear of
 * it, so the top bar's buttons and the floating buttons line up in the bar's column.
 */
val LocalNavSideInsets = staticCompositionLocalOf<WindowInsets> { WindowInsets(0, 0, 0, 0) }

/** Width of that column: the bar and its margins on both sides. */
val SideBarColumnWidth = SIDE_BAR_WIDTH + 16.dp

/** Where the main navigation sits. */
enum class NavSide { Bottom, Left, Right }

/**
 * The navigation's place from the setting (SettingsRepository.navBarPosition) and the window:
 * auto puts it down the right side of a landscape tablet, where height is short and width is
 * not; left and right need a window wide enough to give the bar its column. [legacyRail] is the
 * plain (not floating) bar on a wide screen, which has always been a rail on the left.
 */
@Composable
fun rememberNavSide(legacyRail: Boolean = false): NavSide {
    val config = LocalConfiguration.current
    val width = config.screenWidthDp
    val height = config.screenHeightDp
    val roomForColumn = width >= 600 && height >= 420
    return when (LocalNavBarPosition.current) {
        POSITION_BOTTOM -> NavSide.Bottom
        POSITION_LEFT -> if (roomForColumn) NavSide.Left else NavSide.Bottom
        POSITION_RIGHT -> if (roomForColumn) NavSide.Right else NavSide.Bottom
        else -> when {
            width > height && width >= 840 && height >= 480 -> NavSide.Right
            legacyRail -> NavSide.Left
            else -> NavSide.Bottom
        }
    }
}

private const val POSITION_BOTTOM = 1
private const val POSITION_LEFT = 2
private const val POSITION_RIGHT = 3

/**
 * The floating glass bar down a side: the tabs, then the search button on pages that search. It
 * centres itself in the height the keyboard leaves, and scrolls if a short window cannot hold it.
 */
@Composable
fun SideBarMiuix(
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    if (!Cam.isFullFeatured()) return
    val mainState = LocalMainPagerState.current
    val isBlurEnabled = LocalEnableFloatingBottomBarBlur.current
    val search = LocalMainSearch.current
    val searchable = SearchablePages.contains(mainState.selectedPage)
    val destinations = BottomBarDestination.entries
    val labels = destinations.map { stringResource(it.label) }

    // a scrolling column cannot centre itself: the box around it does
    Box(
        modifier = modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        contentAlignment = Alignment.Center,
    ) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FloatingSideBar(
            selectedIndex = mainState.selectedPage,
            onSelected = { mainState.animateToPage(it) },
            backdrop = backdrop,
            tabsCount = destinations.size,
            isBlurEnabled = isBlurEnabled,
        ) { activateTab ->
            destinations.forEachIndexed { index, destination ->
                FloatingSideBarItem(
                    selected = mainState.selectedPage == index,
                    onClick = { activateTab(index) },
                ) {
                    // icon and label take LocalContentColor, so the drop's copy can turn them accent
                    val badge = navigationBadgeFor(index, navigationBadge, floating = true)
                    val icon: @Composable () -> Unit = { Icon(imageVector = destination.icon, contentDescription = labels[index]) }
                    if (badge != null) BadgedBox(badge = { badge() }) { icon() } else icon()
                    Text(
                        text = labels[index],
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = searchable && !search.active,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                GlassCircleButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.search_hint),
                    backdrop = backdrop,
                    isBlurEnabled = isBlurEnabled,
                    onClick = search::open,
                    size = SIDE_BAR_WIDTH,
                )
            }
        }
    }
    }
}

/** With the bar on a side, an open search sits at the bottom of the page, above the keyboard. */
@Composable
fun SideSearchDock(backdrop: Backdrop, modifier: Modifier = Modifier) {
    val mainState = LocalMainPagerState.current
    val search = LocalMainSearch.current
    if (!search.active || !SearchablePages.contains(mainState.selectedPage)) return
    SearchDock(
        state = search,
        backdrop = backdrop,
        isBlurEnabled = LocalEnableFloatingBottomBarBlur.current,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
    )
}
