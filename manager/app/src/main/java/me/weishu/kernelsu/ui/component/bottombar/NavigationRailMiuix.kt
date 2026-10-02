package me.weishu.kernelsu.ui.component.bottombar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import me.weishu.kernelsu.ui.component.glass.glassMaterial
import me.weishu.kernelsu.ui.component.glass.GlassDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.weishu.kernelsu.Ksu
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.LocalMainPagerState
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState

@Composable
fun NavigationRailMiuix(
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    val fullFeatured = Ksu.isFullFeatured()
    if (!fullFeatured) return

    val mainState = LocalMainPagerState.current

    val items = BottomBarDestination.entries.map { destination ->
        Pair(stringResource(destination.label), destination.icon)
    }
    val settingsRepo = remember { SettingsRepositoryImpl() }
    val state = rememberNavigationRailState(
        initialValue = if (settingsRepo.navigationRailExpanded) {
            NavigationRailValue.Expanded
        } else {
            NavigationRailValue.Collapsed
        },
    )
    LaunchedEffect(state.currentValue) {
        settingsRepo.navigationRailExpanded = state.isExpanded
    }

    NavigationRail(
        // Glass sidebar: sits beside the content, so it samples the page background only.
        modifier = modifier.glassMaterial(RoundedCornerShape(0.dp), GlassDefaults.cardTint(), rim = false),
        state = state,
        color = Color.Transparent,
        expandContentDescription = stringResource(R.string.nav_rail_expand),
        collapseContentDescription = stringResource(R.string.nav_rail_collapse),
    ) {
        items.forEachIndexed { index, (label, icon) ->
            NavigationRailItem(
                selected = mainState.selectedPage == index,
                onClick = {
                    mainState.animateToPage(index)
                },
                icon = icon,
                label = label,
                badge = navigationBadgeFor(index, navigationBadge),
            )
        }
    }
}
