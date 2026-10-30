package cam.su.kernel.ui

import cam.su.kernel.ui.component.CamToastHost
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import cam.su.kernel.ui.intro.IntroGate
import cam.su.kernel.ui.intro.IntroLogo
import cam.su.kernel.ui.intro.IntroPlayer
import cam.su.kernel.ui.component.hero.HeroOverlay
import cam.su.kernel.ui.component.hero.LocalHero
import cam.su.kernel.ui.component.hero.rememberHeroState
import cam.su.kernel.update.UpdateNotifier
import cam.su.kernel.update.UpdateSignal
import androidx.activity.compose.BackHandler
import cam.su.kernel.ui.component.glass.rememberAnimatedStandIn
import cam.su.kernel.ui.component.glass.animated
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
import cam.su.kernel.ui.component.expand.ExpandTransition
import cam.su.kernel.ui.component.expand.LocalExpand
import cam.su.kernel.ui.component.expand.rememberExpandState
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
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
import cam.su.kernel.Cam
import cam.su.kernel.CamRootClient
import cam.su.kernel.ui.component.bottombar.BottomBar
import cam.su.kernel.ui.component.search.LocalMainSearch
import cam.su.kernel.ui.component.search.MainSearchState
import cam.su.kernel.ui.component.bottombar.MainPagerState
import cam.su.kernel.ui.component.bottombar.NavigationBadgeState
import cam.su.kernel.ui.component.bottombar.SideRail
import cam.su.kernel.ui.component.bottombar.NavSide
import cam.su.kernel.ui.component.bottombar.SideBarMiuix
import cam.su.kernel.ui.component.bottombar.SideSearchDock
import cam.su.kernel.ui.component.bottombar.LocalNavSideInsets
import cam.su.kernel.ui.component.bottombar.SideBarColumnWidth
import cam.su.kernel.ui.component.bottombar.rememberNavSide
import cam.su.kernel.ui.theme.LocalNavBarPosition
import cam.su.kernel.ui.component.bottombar.rememberMainPagerState
import cam.su.kernel.ui.component.bottombar.useNavigationRail
import cam.su.kernel.ui.navigation3.IntentDispatcher
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.navigation3.Navigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.navigation3.rememberNavigator
import cam.su.kernel.ui.screen.about.AboutScreen
import cam.su.kernel.ui.screen.account.AccountScreen
import cam.su.kernel.ui.screen.appprofile.AppProfileScreen
import cam.su.kernel.ui.screen.hidingcheck.HidingCheckScreen
import cam.su.kernel.ui.screen.colorpalette.ColorPaletteScreen
import cam.su.kernel.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import cam.su.kernel.ui.screen.flash.FlashScreen
import cam.su.kernel.ui.screen.home.HomePager
import cam.su.kernel.ui.screen.install.InstallScreen
import cam.su.kernel.ui.screen.module.ModulePager
import cam.su.kernel.ui.screen.modulerepo.ModuleRepoDetailScreen
import cam.su.kernel.ui.screen.modulerepo.ModuleRepoScreen
import cam.su.kernel.ui.screen.features.FeaturesPager
import cam.su.kernel.ui.screen.files.FilesPager
import cam.su.kernel.update.AppCompile
import cam.su.kernel.ui.screen.files.TextEditorScreen
import cam.su.kernel.ui.screen.files.DiffScreen
import cam.su.kernel.ui.screen.files.HexViewerScreen
import cam.su.kernel.ui.screen.files.ScriptScreen
import cam.su.kernel.ui.screen.files.TerminalScreen
import cam.su.kernel.ui.component.bottombar.BottomBarDestination
import cam.su.kernel.ui.screen.settings.SettingPager
import cam.su.kernel.ui.screen.sulog.SulogScreen
import cam.su.kernel.ui.screen.superuser.SuperUserPager
import cam.su.kernel.ui.screen.template.AppProfileTemplateScreen
import cam.su.kernel.ui.screen.templateeditor.TemplateEditorScreen
import cam.su.kernel.ui.theme.CamTheme
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
import cam.su.kernel.ui.viewmodel.CamActivityViewModel
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

class CamActivity : ComponentActivity() {

    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private val camInitDone = MutableStateFlow(false)
    @Volatile
    private var glassBackgroundReady = false
    private var splashStartedAt = 0L
    private val splashAnimationDurationMs = 500L

    /** The start intro while it plays over everything, and the window background it borrowed. */
    private var intro: IntroPlayer? = null
    private val introRunning = MutableStateFlow(false)
    private var windowBackground: Drawable? = null

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            val elapsed = SystemClock.uptimeMillis() - splashStartedAt
            val player = intro
            val keep = if (player != null) {
                // The splash is plain black: leave it as soon as the intro's pictures are cut,
                // and let the intro cover the app starting up (root, the first composition).
                !IntroLogo.settled && elapsed < INTRO_ART_TIMEOUT_MS
            } else {
                // Also wait (briefly) for the liquid glass background so the app opens straight onto it.
                !contentReady || elapsed < splashAnimationDurationMs ||
                    (!glassBackgroundReady && elapsed < GLASS_PRELOAD_TIMEOUT_MS)
            }
            if (!keep) player?.windowShown = true
            keep
        }
        val settings = SettingsRepositoryImpl()
        lifecycleScope.launch {
            GlassBackgroundCache.preload(applicationContext, settings.glassBackgroundType, settings.glassBackgroundBlur)
            glassBackgroundReady = true
        }

        // The intro only greets a fresh start from the launcher: not a notification, a shortcut,
        // a restore after rotation, or someone who turned animations off.
        val launcher = intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        val animations = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        val showIntro = savedInstanceState == null && launcher && animations && settings.startIntro
        if (showIntro) {
            IntroGate.reset()
            IntroLogo.preload(lifecycleScope, applicationContext)
            introRunning.value = true
            // Black under the intro until the app draws, light status icons over it.
            windowBackground = window.decorView.background
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        }

        // Root comes from the allowlist now: bind the uid 0 service first, and keep the
        // splash up until we know whether kernel features are reachable.
        lifecycleScope.launch {
            if (CamRootClient.connect() && Cam.isFullFeatured()) {
                withContext(Dispatchers.IO) { install() }
                AppCompile.ensureCompiled(applicationContext, lifecycleScope)
            }
            camInitDone.value = true
        }

        if (savedInstanceState == null) intent?.let {
            if (it.getBooleanExtra(UpdateNotifier.EXTRA_SHOW_UPDATE, false)) UpdateSignal.request()
            intentChannel.trySend(it)
        }

        setContent {
            val camReady by camInitDone.collectAsStateWithLifecycle()
            if (!camReady) return@setContent
            val viewModel = viewModel<CamActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())
            val introOn by introRunning.collectAsStateWithLifecycle()
            // light bar icons over the intro's black too
            val darkBars = darkMode || introOn

            DisposableEffect(darkBars) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkBars },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkBars },
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
                LocalNavBarPosition provides uiState.navBarPosition,
                // The floating bar's glass uses AGSL shaders (API 33+).
                LocalEnableFloatingBottomBarBlur provides (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || uiState.enableFloatingBottomBarBlur
                    ),
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                LocalModuleDescriptionMaxLines provides uiState.moduleDescriptionMaxLines,
            ) {
                CamTheme(appSettings = appSettings) {
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
                    val flashRoute = navigator.current() as? Route.Flash
                    var flashBackEnabled by remember(flashRoute) { mutableStateOf(false) }
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            initialPage = selectedMainPage,
                            pagerInterceptionMode = uiState.pagerInterceptionMode,
                            onPageChanged = viewModel::setSelectedMainPage,
                        )
                    }

                    val hero = rememberHeroState()
                    val expand = rememberExpandState(
                        isTop = { navigator.current() == it },
                        onBackStack = { it in navigator.backStack },
                    )
                    val expandTransition = remember(expand) { ExpandTransition(expand) }
                    val navDisplay = @Composable {
                        val cornerRadius = rememberNavSystemCornerRadius()
                        expand.screenCorner = cornerRadius
                        NavDisplay(
                            backStack = navigator.backStack,
                            transition = expandTransition,
                            effects = NavDisplayEffects(cornerClipRadius = cornerRadius),
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
                            entry<Route.Main>(swipeDismiss = swipeDismiss) { GlassPage(it) { mainScreenEntry() } }
                            entry<Route.About>(swipeDismiss = swipeDismiss) { GlassPage(it) { AboutScreen() } }
                            entry<Route.Account>(swipeDismiss = swipeDismiss) { GlassPage(it) { AccountScreen() } }
                            entry<Route.ConfigBackup>(swipeDismiss = swipeDismiss) {
                                GlassPage(it) { cam.su.kernel.ui.screen.configbackup.ConfigBackupScreen() }
                            }
                            entry<Route.Sulog>(swipeDismiss = swipeDismiss) { GlassPage(it) { SulogScreen() } }
                            entry<Route.ColorPalette>(swipeDismiss = swipeDismiss) { GlassPage(it) { ColorPaletteScreen() } }
                            entry<Route.AppProfileTemplate>(swipeDismiss = swipeDismiss) { GlassPage(it) { AppProfileTemplateScreen() } }
                            entry<Route.TemplateEditor>(swipeDismiss = swipeDismiss) { key -> GlassPage(key) { TemplateEditorScreen(key.template, key.readOnly) } }
                            entry<Route.AppProfile>(swipeDismiss = swipeDismiss) { key -> GlassPage(key) { AppProfileScreen(key.uid) } }
                            entry<Route.HidingCheck>(swipeDismiss = swipeDismiss) { key ->
                                GlassPage(key) { HidingCheckScreen(key.uid.takeIf { it != Route.HidingCheck.DEVICE }, key.scanNow) }
                            }
                            entry<Route.ModuleRepo>(swipeDismiss = swipeDismiss) { GlassPage(it) { ModuleRepoScreen() } }
                            // no swipe to close: a stray swipe while typing must not drop the edits
                            entry<Route.TextEditor>(swipeDismiss = NavSwipeDirection.None) { key -> GlassPage(key) { TextEditorScreen(key.path, key.line) } }
                            entry<Route.Diff>(swipeDismiss = swipeDismiss) { key -> GlassPage(key) { DiffScreen(key.left, key.right) } }
                            entry<Route.HexViewer>(swipeDismiss = swipeDismiss) { key -> GlassPage(key) { HexViewerScreen(key.path) } }
                            // no swipe to close: it would stop a running script without asking
                            entry<Route.Script>(swipeDismiss = NavSwipeDirection.None) { key -> GlassPage(key) { ScriptScreen(key.path) } }
                            // no swipe to close: horizontal swipes belong to the terminal (and it would end the shell)
                            entry<Route.Terminal>(swipeDismiss = NavSwipeDirection.None) { key -> GlassPage(key) { TerminalScreen(key.cwd, key.script) } }
                            entry<Route.ModuleRepoDetail>(swipeDismiss = swipeDismiss) { key -> GlassPage(key) { ModuleRepoDetailScreen(key.module) } }
                            entry<Route.Install>(swipeDismiss = swipeDismiss) { GlassPage(it) { InstallScreen() } }
                            entry<Route.Flash>(swipeDismiss = if (flashBackEnabled) swipeDismiss else NavSwipeDirection.None) { key ->
                                GlassPage(key) {
                                    FlashScreen(
                                        key.flashIt,
                                        onBackEnabledChange = { enabled ->
                                            if (navigator.current() == key) flashBackEnabled = enabled
                                        },
                                    )
                                }
                            }
                            entry<Route.ExecuteModuleAction>(swipeDismiss = swipeDismiss) { key ->
                                GlassPage(key) {
                                    ExecuteModuleActionScreen(
                                        key.moduleId,
                                        key.fromShortcut
                                    )
                                }
                            }
                            entry<Route.Home>(swipeDismiss = swipeDismiss) { GlassPage(it) { mainScreenEntry() } }
                            entry<Route.SuperUser>(swipeDismiss = swipeDismiss) { GlassPage(it) { mainScreenEntry() } }
                            entry<Route.Module>(swipeDismiss = swipeDismiss) { GlassPage(it) { mainScreenEntry() } }
                            entry<Route.Settings>(swipeDismiss = swipeDismiss) { GlassPage(it) { mainScreenEntry() } }
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
                            // the floating bar at the bottom is the slimes' floor; down a side it is not
                            floorInset = if (onMain && uiState.enableFloatingBottomBar && rememberNavSide() == NavSide.Bottom) 92.dp else 10.dp,
                            enabled = uiState.roamingSlimes,
                            maxCount = uiState.roamingSlimeCount,
                            nightNap = uiState.slimeNightNap,
                            // Out of the way while something is being flashed, installed or run.
                            quiet = route is Route.Flash || route is Route.ExecuteModuleAction || route == Route.Install,
                        ) {
                            Scaffold(containerColor = Color.Transparent) {
                                Box {
                                    CompositionLocalProvider(LocalHero provides hero, LocalExpand provides expand) { navDisplay() }
                                    HeroOverlay(hero)
                                }
                            }
                        }
                    }
                    if (introOn) {
                        // Tell the intro playing over all this what it is waiting for, and when it first drew.
                        val expectArena = remember { selectedMainPage == 0 && Cam.isFullFeatured() }
                        SideEffect { IntroGate.expect(expectArena) }
                        Spacer(Modifier.fillMaxSize().drawBehind { IntroGate.shown() })
                    }
                    CamToastHost()
                    SideEffect { contentReady = true }
                }
            }
        }

        // Over the whole window (stray slimes included) on its own thread, from the first frame on.
        if (showIntro) {
            intro = IntroPlayer(this, ::endIntro).also {
                addContentView(it.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun endIntro() {
        val player = intro ?: return
        intro = null
        (player.view.parent as? ViewGroup)?.removeView(player.view)
        windowBackground?.let { window.setBackgroundDrawable(it) }
        windowBackground = null
        introRunning.value = false
        IntroLogo.release()
    }

    override fun onDestroy() {
        intro?.let {
            it.stop()
            intro = null
            IntroLogo.release()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(UpdateNotifier.EXTRA_SHOW_UPDATE, false)) UpdateSignal.request()
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
    val navSide = rememberNavSide(legacyRail = useNavigationRail)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = navSide == NavSide.Bottom,
    )
    val isFullFeatured = Cam.isFullFeatured()
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
    val animatedBackground = LocalGlassBackgroundState.current.source.animated
    val barBackground: Backdrop? = when {
        glassBackdrop == null -> null
        animatedBackground -> rememberAnimatedStandIn()
        else -> glassBackdrop
    }
    val barBackdrop: Backdrop = if (barBackground != null) rememberCombinedBackdrop(barBackground, backdrop) else backdrop

    val settledPage = mainPagerState.pagerState.settledPage
    var filesVisited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settledPage) { if (settledPage == BottomBarDestination.Files.ordinal) filesVisited = true }
    // The Files tab uses sideways swipes to switch its panels; leave it through the bar.
    val pagerSwipe = userScrollEnabled && settledPage != BottomBarDestination.Files.ordinal
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }
    // An update was asked for (notification tap, "Update now" after early access): Home shows it.
    val updateRequested by UpdateSignal.showUpdateDialog.collectAsState()
    LaunchedEffect(updateRequested) {
        if (updateRequested) mainPagerState.animateToPage(0)
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
                            enabled = pagerSwipe,
                        )
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) MainPagerConfig.LAST_PAGE_INDEX else 0,
                    overscrollEffect = null,
                    userScrollEnabled = pagerSwipe && !interceptPagerGestures,
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
                        // Files is heavy (two lists, dialogs) and rarely the first page: build it on first visit
                        4 -> if (filesVisited || isCurrentPage) FilesPager(bottomInnerPadding, isCurrentPage)
                        5 -> if (contentReady || isCurrentPage) SettingPager(navController, bottomInnerPadding, isCurrentPage)
                    }
                }
            }
        }

        if (navSide != NavSide.Bottom) {
            // The bar down a side: the pages get the whole height and give the bar its column,
            // so their floating buttons (Files' toolbar, Module's +) stay clear of it.
            val left = navSide == NavSide.Left
            val sideInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(if (left) WindowInsetsSides.Start else WindowInsetsSides.End)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
            val bar = @Composable {
                if (enableFloatingBottomBar) {
                    SideBarMiuix(
                        backdrop = barBackdrop,
                        navigationBadge = navigationBadge,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(sideInsets),
                    )
        // The activity handles rotation itself (manifest), and rotating a tablet moves the bar
        // between the bottom and a side, so the pager changes branch below. Moved, not rebuilt:
        // rebuilt, every page lost its state and the status card and the roaming slimes made their
        // 3D engines again, which crashed in Filament (see AndroidManifest). The pager lambda runs
        // at the same spot inside this group, so its state comes along.
        val pagerHost = remember {
            movableContentOf { bottomInnerPadding: Dp, content: @Composable (Dp) -> Unit -> content(bottomInnerPadding) }
        }
                } else {
                    SideRail(navigationBadge)
                }
            }

            Scaffold(containerColor = Color.Transparent) { _ ->
                if (enableFloatingBottomBar) {
                    // The glass bar floats over the edge of the pages; they keep only their bodies
                    // clear of it, so top bar buttons and floating buttons share its column.
                    val column = if (left) {
                        WindowInsets(left = SideBarColumnWidth)
                    } else {
                        WindowInsets(right = SideBarColumnWidth)
                    }
                    Box(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(LocalNavSideInsets provides column) {
                            pagerHost(navBarBottomPadding, pagerContent)
                        }
                        Box(Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)) { bar() }
                        SideSearchDock(
                            barBackdrop,
                            Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(column),
                        )
                    }
                } else {
                    Row {
                        if (left) bar()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(sideInsets)
                        ) {
                            pagerHost(navBarBottomPadding, pagerContent)
                        }
                        if (!left) bar()
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
                pagerHost(innerPadding.calculateBottomPadding(), pagerContent)
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

/** How long the black splash waits for the intro's pictures before giving the app the screen. */
private const val INTRO_ART_TIMEOUT_MS = 800L
