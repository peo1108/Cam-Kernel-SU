package cam.su.kernel.ui.screen.appprofile

import androidx.compose.foundation.layout.BoxWithConstraints
import cam.su.kernel.ui.component.listdetail.DetailPaneScaffold
import cam.su.kernel.ui.component.listdetail.DetailTwoColumnWidth
import cam.su.kernel.ui.component.listdetail.FlightPart
import cam.su.kernel.ui.component.listdetail.PaneMotion
import cam.su.kernel.ui.component.listdetail.flightTarget
import cam.su.kernel.ui.component.listdetail.panePiece
import cam.su.kernel.ui.component.listdetail.paneReveal
import cam.su.kernel.ui.component.glass.GlassSwitchPreference
import cam.su.kernel.ui.component.glass.GlassDropdownPreference
import cam.su.kernel.ui.component.glass.MorphIcon
import cam.su.kernel.ui.component.glass.morphAnchor
import cam.su.kernel.ui.component.glass.rememberMorphPopupState
import cam.su.kernel.ui.component.glass.GlassMenuColumn
import cam.su.kernel.ui.component.glass.GlassMenuDivider
import cam.su.kernel.ui.component.glass.GlassMenuItem
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import cam.su.kernel.ui.component.glass.GlassListPopup
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.CamNative
import cam.su.kernel.R
import cam.su.kernel.ui.component.AppIconImage
import cam.su.kernel.ui.component.hero.HeroPart
import cam.su.kernel.ui.component.hero.heroTarget
import cam.su.kernel.ui.component.ListPopupDefaults
import cam.su.kernel.ui.component.profile.AppProfileConfig
import cam.su.kernel.ui.component.profile.RootProfileConfig
import cam.su.kernel.ui.component.profile.TemplateConfig
import cam.su.kernel.ui.component.statustag.StatusTag
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.listAppProfileTemplates
import cam.su.kernel.ui.util.ownerNameForUid
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.util.setSepolicy
import cam.su.kernel.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import cam.su.kernel.ui.component.expand.expandClick
import cam.su.kernel.ui.component.expand.expandSource

/**
 * @author weishu
 * @date 2023/5/16.
 */
@Composable
fun AppProfileScreenMiuix(
    state: AppProfileUiState,
    actions: AppProfileActions,
) {
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopBar(
                onBack = actions.onBack,
                showActions = !state.isUidGroup && !state.appGroup.primary.isWebViewZygote,
                packageName = state.packageName,
                userId = state.uid / 100000,
                onLaunchApp = actions.onLaunchApp,
                onForceStopApp = actions.onForceStopApp,
                onRestartApp = actions.onRestartApp,
                onOpenSystemInfo = actions.onOpenSystemInfo,
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 16.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    AppProfileInner(
                        packageName = if (state.isUidGroup) {
                            ""
                        } else {
                            state.appGroup.primary.displayIdentifier
                        },
                        appLabel = if (state.isUidGroup) ownerNameForUid(state.appGroup.primary.uid) else state.appGroup.primary.label,
                        appIcon = {
                            AppIconImage(
                                packageInfo = state.appGroup.primary.packageInfo,
                                label = state.appGroup.primary.label,
                                modifier = Modifier
                                    .size(64.dp)
                                    .heroTarget(state.uid, HeroPart.Icon)
                            )
                        },
                        appUid = state.uid,
                        sharedUserId = if (state.isUidGroup) state.sharedUserId else "",
                        appVersionName = if (state.isUidGroup) "" else (state.appGroup.primary.packageInfo.versionName ?: ""),
                        appVersionCode = if (state.isUidGroup) 0L else state.appGroup.primary.packageInfo.longVersionCode,
                        profile = state.profile,
                        isUidGroup = state.isUidGroup,
                        isSpecialApp = state.appGroup.primary.special,
                        affectedApps = state.appGroup.apps,
                        onViewTemplate = actions.onViewTemplate,
                        onManageTemplate = actions.onManageTemplate,
                        onProfileChange = actions.onProfileChange,
                    )
                    if (!state.appGroup.primary.special) {
                        HidingCheckEntry(allowSu = state.appGroup.anyAllowSu, onClick = actions.onCheckHiding)
                    }
                    // What the app is, how much room it takes, and what can be done to it.
                    if (!state.isUidGroup && !state.appGroup.primary.special) {
                        AppManageCards(
                            packageInfo = state.appGroup.primary.packageInfo,
                            userId = state.uid / 100000,
                            onChanged = actions.onAppChanged,
                            onRemoved = actions.onAppRemoved,
                        )
                    }
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AppProfileInner(
    modifier: Modifier = Modifier,
    packageName: String,
    appLabel: String,
    appIcon: @Composable (() -> Unit),
    appUid: Int,
    sharedUserId: String = "",
    appVersionName: String,
    appVersionCode: Long,
    profile: CamNative.Profile,
    isUidGroup: Boolean = false,
    isSpecialApp: Boolean = false,
    affectedApps: List<SuperUserViewModel.AppInfo> = emptyList(),
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (CamNative.Profile) -> Unit,
) {
    Column(
        modifier = modifier
    ) {
        AppProfileHeader(
            packageName = packageName,
            appLabel = appLabel,
            appIcon = appIcon,
            appUid = appUid,
            sharedUserId = sharedUserId,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            isUidGroup = isUidGroup,
            isSpecialApp = isSpecialApp,
            affectedCount = affectedApps.size,
            labelModifier = Modifier.heroTarget(appUid, HeroPart.Label),
        )

        AppProfileEditorBody(
            profile = profile,
            isSpecialApp = isSpecialApp,
            carded = true,
            onViewTemplate = onViewTemplate,
            onManageTemplate = onManageTemplate,
            onProfileChange = onProfileChange,
        )

        if (isUidGroup) AffectedApps(affectedApps)
    }
}

/** The app's icon and name, its version and package (or shared uid), and its uid: a profile's top. */
@Composable
private fun AppProfileHeader(
    packageName: String,
    appLabel: String,
    appIcon: @Composable (() -> Unit),
    appUid: Int,
    sharedUserId: String,
    appVersionName: String,
    appVersionCode: Long,
    isUidGroup: Boolean,
    isSpecialApp: Boolean,
    affectedCount: Int,
    labelModifier: Modifier,
) {
    val userId = appUid / 100000
    val appId = appUid % 100000

    Column {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                appIcon()
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 8.dp)
                        .weight(1f)
                        .paneReveal(),
                ) {
                    Text(
                        text = appLabel,
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight(550),
                        modifier = labelModifier.basicMarquee(),
                        maxLines = 1,
                        softWrap = false
                    )
                    if (isSpecialApp) {
                        Text(
                            text = packageName,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                    } else if (!isUidGroup) {
                        Text(
                            text = "$appVersionName ($appVersionCode)",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = packageName,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                    } else {
                        if (sharedUserId.isNotEmpty()) {
                            Text(
                                text = sharedUserId,
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .basicMarquee(),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = stringResource(R.string.group_contains_apps, affectedCount),
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .basicMarquee(),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Column(
                    modifier = Modifier.paneReveal(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (userId != 0) {
                        StatusTag(
                            label = "USER $userId",
                            backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                            contentColor = colorScheme.onPrimary
                        )
                        StatusTag(
                            label = "UID $appId",
                            backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                            contentColor = colorScheme.onPrimary
                        )
                    } else {
                        StatusTag(
                            label = "UID $appUid",
                            backgroundColor = colorScheme.primary.copy(alpha = 0.8f),
                            contentColor = colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/** The apps sharing the uid: the profile applies to all of them. */
@Composable
private fun AffectedApps(affectedApps: List<SuperUserViewModel.AppInfo>) {
    SmallTitle(
        text = stringResource(R.string.app_profile_affects_following_apps),
        modifier = Modifier.panePiece().padding(top = 4.dp)
    )
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Spacer(Modifier.height(3.dp))
        affectedApps.forEach { app ->
            BasicComponent(
                startAction = {
                    AppIconImage(
                        packageInfo = app.packageInfo,
                        label = app.label,
                        modifier = Modifier
                            .padding(end = 1.dp)
                            .size(48.dp)
                    )
                },
                title = app.label,
                summary = app.displayIdentifier,
                insideMargin = PaddingValues(start = 11.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
    }
}

/** Opens the root hiding check for this app: what its own running processes see. */
@Composable
private fun HidingCheckEntry(allowSu: Boolean, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .expandSource(SOURCE_HIDING_CHECK),
    ) {
        ArrowPreference(
            title = stringResource(R.string.hiding_app_entry),
            summary = stringResource(if (allowSu) R.string.hiding_app_entry_root else R.string.hiding_app_entry_summary),
            startAction = {
                Icon(
                    Icons.Rounded.Shield,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = colorScheme.onBackground,
                )
            },
            onClick = expandClick(SOURCE_HIDING_CHECK, onClick),
        )
    }
}

// the card this app's hiding check grows out of (expandSource key)
private const val SOURCE_HIDING_CHECK = "appprofile/hiding"

@Composable
private fun TopBar(
    onBack: () -> Unit,
    showActions: Boolean = true,
    packageName: String,
    userId: Int,
    onLaunchApp: (String, Int) -> Unit = { _, _ -> },
    onForceStopApp: (String, Int) -> Unit = { _, _ -> },
    onRestartApp: (String, Int) -> Unit = { _, _ -> },
    onOpenSystemInfo: (String, Int) -> Unit = { _, _ -> },
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.profile),
            navigationIcon = {
                GlassIconButton(
                    onClick = onBack
                ) {
                    val layoutDirection = LocalLayoutDirection.current
                    Icon(
                        modifier = Modifier.graphicsLayer {
                            if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                        },
                        imageVector = MiuixIcons.Back,
                        contentDescription = null,
                        tint = colorScheme.onBackground
                    )
                }
            },
            actions = {
                GlassButtonGroup {
                    if (showActions) {
                        AppMenu(packageName, userId, onLaunchApp, onForceStopApp, onRestartApp, onOpenSystemInfo)
                    }
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}

/** The app's menu: launch, restart, system info, force stop. */
@Composable
private fun AppMenu(
    packageName: String,
    userId: Int,
    onLaunchApp: (String, Int) -> Unit,
    onForceStopApp: (String, Int) -> Unit,
    onRestartApp: (String, Int) -> Unit,
    onOpenSystemInfo: (String, Int) -> Unit,
) {
    val showTopPopup = remember { mutableStateOf(false) }
    val moreMorph = rememberMorphPopupState()
    GlassIconButton(
        onClick = { showTopPopup.value = true },
        modifier = Modifier.morphAnchor(moreMorph),
    ) {
        MorphIcon(moreMorph) { Icon(
            imageVector = MiuixIcons.MoreCircle,
            tint = colorScheme.onSurface,
            contentDescription = stringResource(id = R.string.settings)
        ) }
    }
    GlassListPopup(
        show = showTopPopup.value,
        popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        morph = moreMorph,
        onDismissRequest = { showTopPopup.value = false },
        content = {
            GlassMenuColumn {
                val items = listOf(
                    Triple(R.string.launch_app, Icons.Rounded.PlayArrow) { onLaunchApp(packageName, userId) },
                    Triple(R.string.restart_app, Icons.Rounded.RestartAlt) { onRestartApp(packageName, userId) },
                    Triple(R.string.app_system_info, Icons.Rounded.Info) { onOpenSystemInfo(packageName, userId) },
                )
                items.forEach { (label, icon, action) ->
                    GlassMenuItem(
                        text = stringResource(label),
                        icon = icon,
                        onClick = {
                            action()
                            showTopPopup.value = false
                        },
                    )
                }
                GlassMenuDivider()
                GlassMenuItem(
                    text = stringResource(R.string.force_stop_app),
                    icon = Icons.Rounded.Stop,
                    destructive = true,
                    onClick = {
                        onForceStopApp(packageName, userId)
                        showTopPopup.value = false
                    },
                )
            }
        }
    )
}

/**
 * The profile beside the Superuser list: the header pinned under a small bar, then the profile
 * editor and, when the pane is wide enough, the app's checks and management in a second column.
 * The picked row's icon and name fly into the header and the cards come out of the row ([motion]).
 */
@Composable
fun AppProfilePaneMiuix(
    state: AppProfileUiState,
    actions: AppProfileActions,
    motion: PaneMotion?,
    bottomPadding: Dp,
) {
    val primary = state.appGroup.primary
    DetailPaneScaffold(
        title = stringResource(R.string.profile),
        onClose = actions.onBack,
        bottomPadding = bottomPadding,
        actions = {
            if (!state.isUidGroup && !primary.isWebViewZygote) {
                AppMenu(
                    state.packageName,
                    state.uid / 100000,
                    actions.onLaunchApp,
                    actions.onForceStopApp,
                    actions.onRestartApp,
                    actions.onOpenSystemInfo,
                )
            }
        },
        header = {
            AppProfileHeader(
                packageName = if (state.isUidGroup) "" else primary.displayIdentifier,
                appLabel = if (state.isUidGroup) ownerNameForUid(primary.uid) else primary.label,
                appIcon = {
                    AppIconImage(
                        packageInfo = primary.packageInfo,
                        label = primary.label,
                        modifier = Modifier
                            .size(64.dp)
                            .flightTarget(motion, state.uid, FlightPart.Icon)
                    )
                },
                appUid = state.uid,
                sharedUserId = if (state.isUidGroup) state.sharedUserId else "",
                appVersionName = if (state.isUidGroup) "" else (primary.packageInfo.versionName ?: ""),
                appVersionCode = if (state.isUidGroup) 0L else primary.packageInfo.longVersionCode,
                isUidGroup = state.isUidGroup,
                isSpecialApp = primary.special,
                affectedCount = state.appGroup.apps.size,
                labelModifier = Modifier.flightTarget(motion, state.uid, FlightPart.Label),
            )
        },
    ) {
        item {
            val editor = @Composable {
                AppProfileEditorBody(
                    profile = state.profile,
                    isSpecialApp = primary.special,
                    carded = true,
                    onViewTemplate = actions.onViewTemplate,
                    onManageTemplate = actions.onManageTemplate,
                    onProfileChange = actions.onProfileChange,
                )
                if (state.isUidGroup) AffectedApps(state.appGroup.apps)
            }
            val app = @Composable {
                if (!primary.special) {
                    HidingCheckEntry(allowSu = state.appGroup.anyAllowSu, onClick = actions.onCheckHiding)
                }
                if (!state.isUidGroup && !primary.special) {
                    AppManageCards(
                        packageInfo = primary.packageInfo,
                        userId = state.uid / 100000,
                        onChanged = actions.onAppChanged,
                        onRemoved = actions.onAppRemoved,
                    )
                }
            }
            // each glass card comes out of the picked row on its own (PaneDetail)
            BoxWithConstraints {
                if (maxWidth >= DetailTwoColumnWidth) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { editor() }
                        Column(Modifier.weight(1f)) { app() }
                    }
                } else {
                    Column {
                        editor()
                        app()
                    }
                }
            }
        }
    }
}

/**
 * Root switch, profile mode and the matching config: the editable part of an app profile.
 * [carded] puts each part on its own glass card (profile page); otherwise the parts stack flat,
 * for use inside an already-glass container such as an expanded list card.
 */
@Composable
fun AppProfileEditorBody(
    profile: CamNative.Profile,
    isSpecialApp: Boolean,
    carded: Boolean,
    onViewTemplate: (id: String) -> Unit,
    onManageTemplate: () -> Unit,
    onProfileChange: (CamNative.Profile) -> Unit,
) {
    val isRootGranted = !isSpecialApp && profile.allowSu
    val templates = remember { listAppProfileTemplates() }

    if (!isSpecialApp) {
        EditorSection(carded, bottom = 12.dp) {
            GlassSwitchPreference(
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                        tint = colorScheme.onBackground
                    )
                },
                title = stringResource(id = R.string.superuser),
                checked = isRootGranted,
                onCheckedChange = { onProfileChange(profile.copy(allowSu = it)) },
            )
        }
    }

    val initialRootMode = if (profile.rootUseDefault) {
        Mode.Default
    } else if (profile.rootTemplate != null) {
        Mode.Template
    } else {
        Mode.Custom
    }
    var rootMode by rememberSaveable {
        mutableStateOf(initialRootMode)
    }
    val nonRootMode = if (profile.nonRootUseDefault) Mode.Default else Mode.Custom
    val dropdownMode = if (isRootGranted) rootMode else nonRootMode
    ProfileBox(dropdownMode, isRootGranted, carded) { mode ->
        if (isRootGranted) {
            when (mode) {
                Mode.Default, Mode.Custom -> {
                    onProfileChange(
                        profile.copy(
                            rootUseDefault = mode == Mode.Default,
                            rootTemplate = null
                        )
                    )
                    rootMode = mode
                }

                Mode.Template -> {
                    if (templates.isNotEmpty()) {
                        val selected = profile.rootTemplate ?: templates[0]
                        val info = cam.su.kernel.ui.viewmodel.getTemplateInfoById(selected)
                        if (info != null && setSepolicy(selected, info.rules.joinToString("\n"))) {
                            onProfileChange(
                                profile.copy(
                                    rootUseDefault = false,
                                    rootTemplate = selected,
                                    uid = info.uid,
                                    gid = info.gid,
                                    groups = info.groups,
                                    capabilities = info.capabilities,
                                    context = info.context,
                                    namespace = info.namespace,
                                )
                            )
                        } else if (profile.rootTemplate != selected || profile.rootUseDefault) {
                            onProfileChange(
                                profile.copy(
                                    rootUseDefault = false,
                                    rootTemplate = selected
                                )
                            )
                        }
                        rootMode = Mode.Template
                    }
                }
            }
        } else {
            onProfileChange(profile.copy(nonRootUseDefault = (mode == Mode.Default)))
        }
    }
    if (carded) Spacer(Modifier.height(12.dp))

    AnimatedVisibility(
        visible = isRootGranted && rootMode != Mode.Default,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        EditorSection(carded, bottom = 12.dp) {
            AnimatedVisibility(
                visible = rootMode == Mode.Template,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                TemplateConfig(
                    profile = profile,
                    onViewTemplate = onViewTemplate,
                    onManageTemplate = onManageTemplate,
                    onProfileChange = onProfileChange
                )
            }
            AnimatedVisibility(
                visible = rootMode == Mode.Custom,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                RootProfileConfig(
                    fixedName = true,
                    enabled = rootMode == Mode.Custom,
                    profile = profile,
                    onProfileChange = onProfileChange
                )
            }
        }
    }
    AnimatedVisibility(
        visible = !isRootGranted && nonRootMode == Mode.Custom,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        EditorSection(carded, bottom = 12.dp) {
            AnimatedVisibility(
                visible = nonRootMode == Mode.Custom,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                AppProfileConfig(
                    fixedName = true,
                    profile = profile,
                    enabled = true,
                    onProfileChange = onProfileChange
                )
            }
        }
    }
}

@Composable
private fun EditorSection(carded: Boolean, bottom: Dp, content: @Composable ColumnScope.() -> Unit) {
    if (carded) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = bottom),
            content = content,
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun ProfileBox(
    mode: Mode,
    hasTemplate: Boolean,
    carded: Boolean,
    onModeChange: (Mode) -> Unit,
) {
    val defaultText = stringResource(R.string.profile_default)
    val templateText = stringResource(R.string.profile_template)
    val customText = stringResource(R.string.profile_custom)
    val list =
        remember(hasTemplate, defaultText, templateText, customText) {
            buildList {
                add(defaultText)
                if (hasTemplate) {
                    add(templateText)
                }
                add(customText)
            }
        }

    val modesAndTitles = remember(hasTemplate, defaultText, templateText, customText) {
        buildList {
            add(Mode.Default to defaultText)
            if (hasTemplate) {
                add(Mode.Template to templateText)
            }
            add(Mode.Custom to customText)
        }
    }
    val selectedIndex = modesAndTitles.indexOfFirst { it.first == mode }
    EditorSection(carded, bottom = 0.dp) {
        GlassDropdownPreference(
            title = stringResource(R.string.profile),
            items = list,
            startAction = {
                Icon(
                    Icons.Rounded.AccountCircle,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = colorScheme.onBackground
                )
            },
            selectedIndex = if (selectedIndex == -1) 0 else selectedIndex,
        ) {
            onModeChange(modesAndTitles[it].first)
        }
    }
}
