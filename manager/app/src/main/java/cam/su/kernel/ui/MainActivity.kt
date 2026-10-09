package cam.su.kernel.ui

import androidx.activity.compose.BackHandler
import cam.su.kernel.ui.component.glass.rememberGradientStandIn
import cam.su.kernel.ui.component.glass.GlassSource
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.ui.component.glass.GlassBackgroundCache
import cam.su.kernel.ui.component.liquid.rememberQuantizedGravityAngle
import cam.su.kernel.ui.component.liquid.LocalLiquidGravityAngle
import android.os.Build
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.Backdrop
import cam.su.kernel.ui.component.liquid.rememberCombinedBackdrop
import cam.su.kernel.ui.component.glass.rememberGlassBackgroundState
import cam.su.kernel.ui.component.glass.LocalGlassBackgroundState
import cam.su.kernel.ui.component.glass.LocalGlassBackdrop
import cam.su.kernel.ui.component.glass.GlassPage
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.PagerDefaults.pageNestedScrollConnection
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import cam.su.kernel.Ksu
import cam.su.kernel.KsuServiceClient
import cam.su.kernel.ui.component.bottombar.BottomBar
import cam.su.kernel.ui.component.search.LocalMainSearch
import cam.su.kernel.ui.component.search.MainSearchState
import cam.su.kernel.ui.component.bottombar.MainPagerState
import cam.su.kernel.ui.component.bottombar.NavigationBadgeState
import cam.su.kernel.ui.component.bottombar.SideRail
import cam.su.kernel.ui.component.bottombar.rememberMainPagerState
import cam.su.kernel.ui.component.bottombar.useNavigationRail
import cam.su.kernel.ui.navigation3.IntentDispatcher
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.navigation3.Navigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.navigation3.rememberNavigator
import cam.su.kernel.ui.screen.about.AboutScreen
import cam.su.kernel.ui.screen.appprofile.AppProfileScreen
import cam.su.kernel.ui.screen.colorpalette.ColorPaletteScreen
import cam.su.kernel.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import cam.su.kernel.ui.screen.flash.FlashScreen
import cam.su.kernel.ui.screen.home.HomePager
import cam.su.kernel.ui.screen.install.InstallScreen
import cam.su.kernel.ui.screen.module.ModulePager
import cam.su.kernel.ui.screen.modulerepo.ModuleRepoDetailScreen
import cam.su.kernel.ui.screen.modulerepo.ModuleRepoScreen
import cam.su.kernel.ui.screen.features.FeaturesPager
import cam.su.kernel.ui.screen.settings.SettingPager
import cam.su.kernel.ui.screen.sulog.SulogScreen
import cam.su.kernel.ui.screen.superuser.SuperUserPager
import cam.su.kernel.ui.screen.template.AppProfileTemplateScreen
import cam.su.kernel.ui.screen.templateeditor.TemplateEditorScreen
import cam.su.kernel.ui.theme.KernelSUTheme
import cam.su.kernel.ui.theme.LocalColorMode
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.theme.LocalEnableFloatingBottomBar
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.slime.SlimeLayer
import androidx.compose.runtime.snapshotFlow
import cam.su.kernel.ui.slime.SlimeHome
import cam.su.kernel.ui.theme.LocalEnableFloatingBottomBarBlur
import cam.su.kernel.ui.theme.LocalEnableNavigationBadge
import cam.su.kernel.ui.theme.LocalModuleDescriptionMaxLines
import cam.su.kernel.ui.util.getSuperuserCount
import cam.su.kernel.ui.util.install
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.util.rememberContentReady
import cam.su.kernel.ui.viewmodel.MainActivityViewModel
import cam.su.kernel.ui.viewmodel.MainPagerConfig
import cam.su.kernel.ui.viewmodel.ModuleViewModel
import cam.su.kernel.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

class MainActivity : ComponentActivity() {

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private val ksuInitDone = MutableStateFlow(false)
    @Volatile
    private var glassBackgroundReady = false
    private var splashStartedAt = 0L
    private val splashAnimationDurationMs = 500L


    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            val elapsed = SystemClock.uptimeMillis() - splashStartedAt
            // Also wait (briefly) for the liquid glass background so the app opens straight onto it.
            !contentReady || elapsed < splashAnimationDurationMs ||
                (!glassBackgroundReady && elapsed < GLASS_PRELOAD_TIMEOUT_MS)
        }
        val settings = SettingsRepositoryImpl()
        lifecycleScope.launch {
            GlassBackgroundCache.preload(applicationContext, settings.glassBackgroundType, settings.glassBackgroundBlur)
            glassBackgroundReady = true
        }

        // Root comes from the allowlist now: bind the uid 0 service first, and keep the
        // splash up until we know whether kernel features are reachable.
        lifecycleScope.launch {
            if (KsuServiceClient.connect() && Ksu.isFullFeatured()) {
                withContext(Dispatchers.IO) { install() }
            }
            ksuInitDone.value = true
        }

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        setContent {
            val ksuReady by ksuInitDone.collectAsStateWithLifecycle()
            if (!ksuReady) return@setContent
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                // The UI is always liquid glass.
                LocalEnableBlur provides true,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                // The floating bar's glass uses AGSL shaders (API 33+).
                LocalEnableFloatingBottomBarBlur provides (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || uiState.enableFloatingBottomBarBlur
                    ),
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                LocalModuleDescriptionMaxLines provides uiState.moduleDescriptionMaxLines,
            ) {
                KernelSUTheme(appSettings = appSettings) {
                    IntentDispatcher(intentChannel = intentChannel)
                    val swipeDismiss = if (uiState.enableSwipeDismiss) {
                        if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                            NavSwipeDirection.RightToLeft
                        } else {
                            NavSwipeDirection.LeftToRight
                        }
                    } else {
                        NavSwipeDirection.None
                    }
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            initialPage = selectedMainPage,
                            pagerInterceptionMode = uiState.pagerInterceptionMode,
                            onPageChanged = viewModel::setSelectedMainPage,
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                            onBack = {
                                when (val top = navigator.current()) {
                                    is Route.TemplateEditor -> {
                                        if (!top.readOnly) {
                                            navigator.setResult("template_edit", true)
                                        } else {
                                            navigator.pop()
                                        }
                                    }

                                    else -> navigator.pop()
                                }
                            }) {
                            entry<Route.Main>(swipeDismiss = swipeDismiss) { GlassPage { mainScreenEntry() } }
                            entry<Route.About>(swipeDismiss = swipeDismiss) { GlassPage { AboutScreen() } }
                            entry<Route.Sulog>(swipeDismiss = swipeDismiss) { GlassPage { SulogScreen() } }
                            entry<Route.ColorPalette>(swipeDismiss = swipeDismiss) { GlassPage { ColorPaletteScreen() } }
                            entry<Route.AppProfileTemplate>(swipeDismiss = swipeDismiss) { GlassPage { AppProfileTemplateScreen() } }
                            entry<Route.TemplateEditor>(swipeDismiss = swipeDismiss) { key -> GlassPage { TemplateEditorScreen(key.template, key.readOnly) } }
                            entry<Route.AppProfile>(swipeDismiss = swipeDismiss) { key -> GlassPage { AppProfileScreen(key.uid) } }
                            entry<Route.ModuleRepo>(swipeDismiss = swipeDismiss) { GlassPage { ModuleRepoScreen() } }
                            entry<Route.ModuleRepoDetail>(swipeDismiss = swipeDismiss) { key -> GlassPage { ModuleRepoDetailScreen(key.module) } }
                            entry<Route.Install>(swipeDismiss = swipeDismiss) { GlassPage { InstallScreen() } }
                            entry<Route.Flash>(swipeDismiss = swipeDismiss) { key -> GlassPage { FlashScreen(key.flashIt) } }
                            entry<Route.ExecuteModuleAction>(swipeDismiss = swipeDismiss) { key ->
                                GlassPage {
                                    ExecuteModuleActionScreen(
                                        key.moduleId,
                                        key.fromShortcut
                                    )
                                }
                            }
                            entry<Route.Home>(swipeDismiss = swipeDismiss) { GlassPage { mainScreenEntry() } }
                            entry<Route.SuperUser>(swipeDismiss = swipeDismiss) { GlassPage { mainScreenEntry() } }
                            entry<Route.Module>(swipeDismiss = swipeDismiss) { GlassPage { mainScreenEntry() } }
                            entry<Route.Settings>(swipeDismiss = swipeDismiss) { GlassPage { mainScreenEntry() } }
                        }
                    }

                    val glassState = rememberGlassBackgroundState(
                        type = uiState.glassBackgroundType,
                        blur = uiState.glassBackgroundBlur,
                        dim = uiState.glassBackgroundDim,
                        imageVersion = uiState.glassImageVersion,
                    )
                    CompositionLocalProvider(
                        LocalGlassBackgroundState provides glassState,
                        LocalLiquidGravityAngle provides rememberQuantizedGravityAngle(),
                    ) {
                        // The roaming slimes live over every page; a new page re-rolls who is around.
                        val route = navigator.backStack.lastOrNull()
                        val onMain = route == Route.Main || route == Route.Home || route == Route.SuperUser || route == Route.Module || route == Route.Settings
                        SlimeLayer(
                            page = if (onMain) "main_$selectedMainPage" else route ?: "none",
                            home = (onMain && selectedMainPage == 0) || route == Route.Home,
                            floorInset = if (onMain && uiState.enableFloatingBottomBar) 92.dp else 10.dp,
                            enabled = uiState.roamingSlimes,
                            maxCount = uiState.roamingSlimeCount,
                            nightNap = uiState.slimeNightNap,
                            // Out of the way while something is being flashed, installed or run.
                            quiet = route is Route.Flash || route is Route.ExecuteModuleAction || route == Route.Install,
                        ) {
                            Scaffold(containerColor = Color.Transparent) { navDisplay() }
                        }
                    }
                    SideEffect { contentReady = true }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    pagerInterceptionMode: Int = PagerInterceptionMode.CrossAxisInterceptor.ordinal,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )
    val isFullFeatured = Ksu.isFullFeatured()
    val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerInterceptionMode) {
        PagerInterceptionMode.Native
    }
    val interceptPagerGestures = pagerMode == PagerInterceptionMode.CrossAxisInterceptor
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }

    val enableNavigationBadge = LocalEnableNavigationBadge.current
    val badgeEnabled = enableNavigationBadge && isFullFeatured
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleUiState by moduleViewModel.uiState.collectAsStateWithLifecycle()

    val superUserViewModel = viewModel<SuperUserViewModel>()
    val grantedUidCount by remember(superUserViewModel) {
        superUserViewModel.uiState
            .map { state -> state.groupedApps.count { it.anyAllowSu } }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(0)

    var startupPreloadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isFullFeatured) {
        if (!isFullFeatured || startupPreloadStarted) {
            return@LaunchedEffect
        }

        moduleViewModel.initializePreferences()
        val moduleState = moduleViewModel.uiState.value
        if (!moduleState.hasLoaded) {
            if (!moduleState.isRefreshing) moduleViewModel.fetchModuleList()
            moduleViewModel.uiState.first { it.hasLoaded }
        }
        moduleViewModel.syncModuleUpdateInfo(moduleViewModel.uiState.value.modules)

        val superUserState = superUserViewModel.uiState.value
        if (!superUserState.hasLoaded) {
            superUserViewModel.initializePreferences()
            if (superUserState.isRefreshing) {
                superUserViewModel.uiState.first { it.hasLoaded }
            } else {
                superUserViewModel.loadAppList().join()
            }
        }

        startupPreloadStarted = true
    }

    // Loading the app list just for a badge is too expensive; read the kernel allowlist instead.
    var superuserCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(badgeEnabled, grantedUidCount) {
        superuserCount = if (badgeEnabled) withContext(Dispatchers.IO) { getSuperuserCount() } else 0
    }

    val navigationBadge = if (badgeEnabled) {
        NavigationBadgeState(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleUiState.modules.count { it.enabled },
            moduleUpdatableCount = moduleUiState.updateInfo.count { it.value.downloadUrl.isNotBlank() },
        )
    } else {
        NavigationBadgeState()
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawContent()
    }
    val glassBackdrop = LocalGlassBackdrop.current
    // Same rule as BlurredBar: an animated background is replaced by a static stand-in color
    // (sampling it would re-record every frame; sampling nothing renders black).
    val animatedBackground = LocalGlassBackgroundState.current.source is GlassSource.Gradient
    val barBackground: Backdrop? = when {
        glassBackdrop == null -> null
        animatedBackground -> rememberGradientStandIn()
        else -> glassBackdrop
    }
    val barBackdrop: Backdrop = if (barBackground != null) rememberCombinedBackdrop(barBackground, backdrop) else backdrop

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }
    // Lets the slimes in the status card see a swipe away from home as it starts.
    DisposableEffect(mainPagerState.pagerState) {
        onDispose { SlimeHome.pagerPos = Float.NaN }
    }
    LaunchedEffect(mainPagerState.pagerState) {
        val state = mainPagerState.pagerState
        snapshotFlow { state.currentPage + state.currentPageOffsetFraction }.collect { SlimeHome.pagerPos = it }
    }

    MainScreenBackHandler(mainPagerState, navController)

    // shared by the Superuser and Module pages; back and switching tabs close it first
    val mainSearch = remember { MainSearchState() }
    LaunchedEffect(mainPagerState.selectedPage) { mainSearch.close() }
    BackHandler(enabled = mainSearch.active) { mainSearch.close() }

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState,
        LocalMainSearch provides mainSearch,
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .pagerGestureOverride(
                            pagerState = mainPagerState.pagerState,
                            mode = pagerMode,
                            enabled = userScrollEnabled,
                        )
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) MainPagerConfig.LAST_PAGE_INDEX else 0,
                    overscrollEffect = null,
                    userScrollEnabled = userScrollEnabled && !interceptPagerGestures,
                    pageNestedScrollConnection = if (interceptPagerGestures) {
                        PagerGestureNestedScrollConnection
                    } else {
                        pageNestedScrollConnection(
                            state = mainPagerState.pagerState,
                            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                        )
                    },
                    flingBehavior = flingBehavior(
                        state = mainPagerState.pagerState,
                        snapAnimationSpec = PagerNavigationSpringSpec,
                    ),
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (contentReady || isCurrentPage) HomePager(navController, bottomInnerPadding, isCurrentPage)
                        1 -> if (contentReady || isCurrentPage) SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        2 -> if (contentReady || isCurrentPage) ModulePager(bottomInnerPadding, isCurrentPage)
                        3 -> if (contentReady || isCurrentPage) FeaturesPager(bottomInnerPadding, isCurrentPage)
                        4 -> if (contentReady || isCurrentPage) SettingPager(navController, bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            Scaffold(containerColor = Color.Transparent) { _ ->
                Row {
                    SideRail(navigationBadge)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .consumeWindowInsets(startInsets)
                    ) {
                        pagerContent(navBarBottomPadding)
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = barBackdrop,
                        navigationBadge = navigationBadge,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            Scaffold(bottomBar = bottomBar, containerColor = Color.Transparent) { innerPadding ->
                pagerContent(innerPadding.calculateBottomPadding())
            }
        }
    }
}


@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}

private const val GLASS_PRELOAD_TIMEOUT_MS = 1200L
