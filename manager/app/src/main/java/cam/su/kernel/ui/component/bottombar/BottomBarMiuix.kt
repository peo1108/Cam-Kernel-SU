package cam.su.kernel.ui.component.bottombar

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.Cam
import cam.su.kernel.R
import cam.su.kernel.ui.LocalMainPagerState
import cam.su.kernel.ui.component.FloatingBottomBar
import cam.su.kernel.ui.component.FloatingBottomBarItem
import cam.su.kernel.ui.component.search.GlassCircleButton
import cam.su.kernel.ui.component.search.LocalMainSearch
import cam.su.kernel.ui.component.search.SearchDock
import cam.su.kernel.ui.component.search.SearchablePages
import cam.su.kernel.ui.theme.LocalEnableFloatingBottomBar
import cam.su.kernel.ui.theme.LocalEnableFloatingBottomBarBlur
import cam.su.kernel.ui.util.BlurredBar
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BottomBarMiuix(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier,
) {
    val fullFeatured = Cam.isFullFeatured()
    if (!fullFeatured) return

    val mainState = LocalMainPagerState.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current

    val search = LocalMainSearch.current
    val searchable = SearchablePages.contains(mainState.selectedPage)
    val searchLabel = stringResource(R.string.search_hint)
    // the open search sits right above the keyboard, or above the navigation bar
    val dockInsets = WindowInsets.ime.union(WindowInsets.navigationBars)

    val items = BottomBarDestination.entries.map { destination ->
        NavigationItem(
            label = stringResource(destination.label),
            icon = destination.icon,
        )
    }
    if (search.active && searchable) {
        SearchDock(
            state = search,
            backdrop = backdrop,
            isBlurEnabled = enableFloatingBottomBarBlur,
            modifier = modifier
                .windowInsetsPadding(dockInsets)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        )
        return
    }

    if (!enableFloatingBottomBar) {
        Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            AnimatedVisibility(visible = searchable, enter = fadeIn(), exit = fadeOut()) {
                GlassCircleButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = searchLabel,
                    backdrop = backdrop,
                    isBlurEnabled = enableFloatingBottomBarBlur,
                    onClick = search::open,
                    modifier = Modifier.padding(end = 16.dp, bottom = 10.dp),
                    size = 52.dp,
                )
            }
        BlurredBar(blurBackdrop, glass = true) {
            NavigationBar(
                modifier = modifier,
                color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                content = {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            modifier = Modifier.weight(1f),
                            icon = item.icon,
                            label = item.label,
                            selected = mainState.selectedPage == index,
                            onClick = {
                                mainState.animateToPage(index)
                            },
                            badge = navigationBadgeFor(index, navigationBadge),
                        )
                    }
                }
            )
        }
        }
    } else {
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }
        Row(
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .padding(start = if (searchable) 16.dp else 28.dp, end = if (searchable) 16.dp else 28.dp, bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
        FloatingBottomBar(
            modifier = Modifier.weight(1f, fill = false),
            selectedIndex = mainState.selectedPage,
            onSelected = { mainState.animateToPage(it) },
            backdrop = backdrop,
            tabsCount = items.size,
            isBlurEnabled = enableFloatingBottomBarBlur,
        ) { activateTab ->
            items.forEachIndexed { index, item ->
                FloatingBottomBarItem(
                    selected = mainState.selectedPage == index,
                    onClick = {
                        activateTab(index)
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                ) {
                    // Icon and label take LocalContentColor so the FloatingBottomBar backdrop copy
                    // can recolor them to the accent tone inside the indicator pill.
                    val badge = navigationBadgeFor(index, navigationBadge, floating = true)
                    val icon: @Composable () -> Unit = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                        )
                    }
                    if (badge != null) {
                        BadgedBox(badge = { badge() }) { icon() }
                    } else {
                        icon()
                    }
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = searchable,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row {
                Spacer(Modifier.width(10.dp))
                GlassCircleButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = searchLabel,
                    backdrop = backdrop,
                    isBlurEnabled = enableFloatingBottomBarBlur,
                    onClick = search::open,
                )
            }
        }
        }
    }
}

enum class BottomBarDestination(
    @get:StringRes val label: Int,
    val icon: ImageVector,
) {
    Home(R.string.home, Icons.Rounded.Cottage),
    SuperUser(R.string.superuser, Icons.Rounded.Security),
    Module(R.string.module, Icons.Rounded.Extension),
    Features(R.string.features, Icons.Rounded.AutoAwesome),
    Setting(R.string.settings, Icons.Rounded.Settings)
}

internal fun navigationBadgeFor(
    index: Int,
    state: NavigationBadgeState,
    floating: Boolean = false,
): (@Composable () -> Unit)? {
    val badge = badgeFor(index, state) ?: return null
    return when (badge.tone) {
        BadgeTone.Alert -> {
            {
                Badge {
                    Text(badge.count.toString())
                }
            }
        }

        BadgeTone.Accent -> {
            {
                Badge(
                    containerColor = if (floating) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.primary,
                    contentColor = if (floating) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onPrimary,
                ) {
                    Text(badge.count.toString())
                }
            }
        }
    }
}
