package cam.su.kernel.ui.screen.superuser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.ui.component.SearchStatus
import cam.su.kernel.ui.component.hero.LocalHero
import cam.su.kernel.ui.util.ownerNameForUid
import cam.su.kernel.ui.component.search.mainSearchQueryFor
import cam.su.kernel.ui.navigation3.Navigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.viewmodel.SuperUserViewModel

@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<SuperUserViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.hasLoaded && !state.isRefreshing) {
                viewModel.initializePreferences()
                viewModel.loadAppList()
            }
        } else if (!uiState.searchStatus.isCollapsed()) {
            viewModel.updateSearchStatus(uiState.searchStatus.copy(searchText = "", current = SearchStatus.Status.COLLAPSED))
        }
    }

    val searchQuery = mainSearchQueryFor(isCurrentPage)
    LaunchedEffect(searchQuery) { viewModel.updateSearchText(searchQuery) }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            val state = viewModel.uiState.value
            if (!state.isRefreshing) {
                if (!state.hasLoaded) {
                    viewModel.initializePreferences()
                    viewModel.loadAppList()
                } else if (viewModel.isNeedRefresh) {
                    viewModel.loadAppList(resort = false)
                }
            }
        }
        initialResumeHandled.value = true
        onPauseOrDispose {}
    }

    val onSearchTextChange: (String) -> Unit = viewModel::updateSearchText
    val onToggleShowSystemApps: () -> Unit = {
        viewModel.toggleShowSystemApps()
    }
    val onToggleShowOnlyPrimaryUserApps: () -> Unit = {
        viewModel.toggleShowOnlyPrimaryUserApps()
    }
    val hero = LocalHero.current
    val onOpenProfile: (GroupedApps) -> Unit = { group ->
        // same label as the card and the App Profile header show
        val label = if (group.apps.size > 1) ownerNameForUid(group.uid) else group.primary.label
        hero?.launch(group.uid, group.primary.packageInfo, label)
        navigator.push(Route.AppProfile(group.uid))
        viewModel.markNeedRefresh()
    }
    val actions = SuperUserActions(
        onRefresh = { viewModel.loadAppList(force = true) },
        onOpenSulog = { navigator.push(Route.Sulog) },
        onSearchTextChange = onSearchTextChange,
        onSearchStatusChange = viewModel::updateSearchStatus,
        onClearSearch = { onSearchTextChange("") },
        onToggleShowSystemApps = onToggleShowSystemApps,
        onToggleShowOnlyPrimaryUserApps = onToggleShowOnlyPrimaryUserApps,
        onUpdateSortConfig = { viewModel.updateSortConfig(it) },
        onOpenProfile = onOpenProfile,
        onProfileEdited = { viewModel.loadAppList(resort = false) },
        onProfileEditedUid = { viewModel.refreshUid(it) },
    )

    SuperUserPagerMiuix(
        uiState = uiState,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
        isCurrentPage = isCurrentPage,
    )
}
