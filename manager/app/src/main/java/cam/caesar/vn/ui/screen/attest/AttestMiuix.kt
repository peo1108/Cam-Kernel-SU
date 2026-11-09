package cam.caesar.vn.ui.screen.attest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.caesar.vn.R
import cam.caesar.vn.attest.AttestUiState
import cam.caesar.vn.attest.KeyboxState
import cam.caesar.vn.files.FileKind
import cam.caesar.vn.ui.component.AttestationResult
import cam.caesar.vn.ui.component.glass.GlassCard
import cam.caesar.vn.ui.component.glass.GlassIconButton
import cam.caesar.vn.ui.component.glass.GlassSwitchPreference
import cam.caesar.vn.ui.component.listdetail.DetailTwoColumnWidth
import cam.caesar.vn.ui.screen.files.PickFolder
import cam.caesar.vn.ui.screen.files.pickBrowser
import cam.caesar.vn.ui.theme.LocalEnableBlur
import cam.caesar.vn.ui.util.BlurredBar
import cam.caesar.vn.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** One column upright, as before. */
private val ATTEST_MAX_WIDTH = 760.dp

/** Two columns side by side: wider than this they only spread the cards out. */
private val ATTEST_WIDE_MAX_WIDTH = 1240.dp

private val cardModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp)
    .padding(bottom = 12.dp)

private val buttonModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 8.dp)

/**
 * Wide (a tablet held sideways): the keybox and its targets on the left, configure / enable / check
 * on the right; picking a keybox opens the in-app browser in the right column, the way the module
 * install pane opens beside the module list. Narrow: one column, the browser in place of the steps.
 */
@Composable
fun AttestScreenMiuix(state: AttestUiState, actions: AttestActions) {
    val enableBlur = LocalEnableBlur.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.attest_title),
                    navigationIcon = {
                        GlassIconButton(onClick = actions.onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        BoxWithConstraints(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            val wide = maxWidth >= DetailTwoColumnWidth
            val side = ((maxWidth - if (wide) ATTEST_WIDE_MAX_WIDTH else ATTEST_MAX_WIDTH) / 2).coerceAtLeast(0.dp)
            val layoutDirection = LocalLayoutDirection.current
            val start = innerPadding.calculateStartPadding(layoutDirection) + side
            val end = innerPadding.calculateEndPadding(layoutDirection) + side
            val top = innerPadding.calculateTopPadding()
            val bottom = innerPadding.calculateBottomPadding()
            val nested = scrollBehavior.nestedScrollConnection

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    AttestColumn(
                        modifier = Modifier.weight(1f),
                        padding = PaddingValues(start = start, top = top, bottom = bottom),
                        nested = nested,
                    ) {
                        intro()
                        keyboxStep(state, actions)
                        targetsStep(state)
                    }
                    RightColumn(
                        state = state,
                        actions = actions,
                        modifier = Modifier.weight(1f),
                        padding = PaddingValues(end = end, top = top, bottom = bottom),
                        nested = nested,
                    )
                }
            } else {
                // upright: the browser takes the steps' place, Back brings them again
                val stepsState = rememberLazyListState()
                AnimatedContent(
                    targetState = state.browser.open,
                    transitionSpec = { browserTransition(targetState) },
                    label = "attestNarrow",
                ) { browsing ->
                    val listState = if (browsing) browserListState(state.browser.path) else stepsState
                    AttestColumn(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(start = start, end = end, top = top, bottom = bottom),
                        nested = nested,
                        listState = listState,
                    ) {
                        if (browsing) {
                            keyboxBrowser(state, actions)
                        } else {
                            intro()
                            keyboxStep(state, actions)
                            targetsStep(state)
                            configureStep(state, actions)
                            enableStep(state, actions)
                            checkStep(state, actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RightColumn(
    state: AttestUiState,
    actions: AttestActions,
    modifier: Modifier,
    padding: PaddingValues,
    nested: NestedScrollConnection,
) {
    // the steps keep where they were scrolled to while the browser is open
    val stepsState = rememberLazyListState()
    AnimatedContent(
        targetState = state.browser.open,
        modifier = modifier,
        transitionSpec = { browserTransition(targetState) },
        label = "attestRight",
    ) { browsing ->
        val listState = if (browsing) browserListState(state.browser.path) else stepsState
        AttestColumn(Modifier.fillMaxWidth(), padding, nested, listState) {
            if (browsing) {
                keyboxBrowser(state, actions)
            } else {
                configureStep(state, actions)
                enableStep(state, actions)
                checkStep(state, actions)
            }
        }
    }
}

/** Every folder starts at its top. */
@Composable
private fun browserListState(path: String): LazyListState = remember(path) { LazyListState() }

private fun browserTransition(opening: Boolean) =
    (fadeIn(tween(220)) + slideInHorizontally(tween(320)) { if (opening) it / 6 else -it / 6 }) togetherWith
        (fadeOut(tween(160)) + slideOutHorizontally(tween(320)) { if (opening) -it / 8 else it / 8 })

@Composable
private fun AttestColumn(
    modifier: Modifier,
    padding: PaddingValues,
    nested: NestedScrollConnection,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxHeight()
            .padding(top = 16.dp)
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(nested),
        contentPadding = padding,
        overscrollEffect = null,
        content = content,
    )
}

// What the feature does + the experimental warning, read before anything else.
private fun LazyListScope.intro() {
    item(key = "intro") {
        GlassCard(modifier = cardModifier) {
            BasicComponent(
                title = stringResource(R.string.attest_intro_title),
                summary = stringResource(R.string.attest_intro_body),
            )
            BasicComponent(
                title = stringResource(R.string.attest_warning_title),
                summary = stringResource(R.string.attest_warning_body),
            )
        }
    }
}

// Step 1 — import and revocation-check the keybox.
private fun LazyListScope.keyboxStep(state: AttestUiState, actions: AttestActions) {
    item(key = "keybox") {
        SmallTitle(text = stringResource(R.string.attest_step_keybox))
        GlassCard(modifier = cardModifier) {
            BasicComponent(
                title = stringResource(R.string.attest_keybox),
                summary = keyboxSummary(state),
            )
            BasicComponent(summary = stringResource(R.string.attest_keybox_hint))
            TextButton(
                text = stringResource(R.string.attest_import_keybox),
                enabled = !state.browser.open && !state.readingKeybox,
                onClick = actions.onOpenBrowser,
                modifier = buttonModifier,
            )
        }
    }
}

// Step 2 — which apps attest with the keybox.
private fun LazyListScope.targetsStep(state: AttestUiState) {
    item(key = "targets") {
        SmallTitle(text = stringResource(R.string.attest_step_targets))
        GlassCard(modifier = cardModifier) {
            state.packages.forEach { BasicComponent(title = it) }
            BasicComponent(summary = stringResource(R.string.attest_targets_summary))
        }
    }
}

// Step 3 — write the config and deploy the interceptor.
private fun LazyListScope.configureStep(state: AttestUiState, actions: AttestActions) {
    item(key = "configure") {
        SmallTitle(text = stringResource(R.string.attest_step_configure))
        GlassCard(modifier = cardModifier) {
            BasicComponent(summary = stringResource(R.string.attest_configure_hint))
            configureResult(state.message)?.let {
                BasicComponent(summary = stringResource(it))
            }
            TextButton(
                text = stringResource(
                    if (state.busy) R.string.attest_configuring else R.string.attest_configure,
                ),
                enabled = !state.busy && !state.readingKeybox && keyboxUsable(state.keybox),
                onClick = actions.onConfigure,
                modifier = buttonModifier,
            )
        }
    }
}

// Step 4 — the toggle, gated until a keybox is loaded and configured.
private fun LazyListScope.enableStep(state: AttestUiState, actions: AttestActions) {
    item(key = "enable") {
        SmallTitle(text = stringResource(R.string.attest_step_enable))
        GlassCard(modifier = cardModifier) {
            val canToggle = state.canEnable || state.enabled
            GlassSwitchPreference(
                title = stringResource(R.string.attest_enable),
                summary = stringResource(
                    if (canToggle) R.string.attest_enable_summary else R.string.attest_enable_blocked,
                ),
                checked = state.enabled,
                enabled = canToggle,
                onCheckedChange = actions.onSetEnabled,
            )
            BasicComponent(
                title = stringResource(R.string.attest_status),
                summary = statusSummary(state),
            )
        }
    }
}

// Step 5 — show what an attested key actually reports now.
private fun LazyListScope.checkStep(state: AttestUiState, actions: AttestActions) {
    item(key = "check") {
        SmallTitle(text = stringResource(R.string.attest_section_check))
        GlassCard(modifier = cardModifier) {
            state.attestationReport?.let { AttestationResult(it) }
            TextButton(
                text = stringResource(
                    if (state.checkingAttestation) R.string.features_attestation_checking
                    else R.string.features_attestation_check,
                ),
                enabled = !state.checkingAttestation,
                onClick = actions.onCheckAttestation,
                modifier = buttonModifier,
            )
        }
    }
}

// The module install pane's browser, listing folders and .xml files; a tap loads the keybox.
private fun LazyListScope.keyboxBrowser(state: AttestUiState, actions: AttestActions) {
    item(key = "browser_head") {
        SmallTitle(text = stringResource(R.string.attest_step_keybox))
        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            insideMargin = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.attest_browser_title),
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurface,
                )
                if (state.readingKeybox) {
                    InfiniteProgressIndicator(size = 18.dp)
                    Spacer(Modifier.width(12.dp))
                }
                GlassIconButton(onClick = actions.onSystemPicker) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = stringResource(R.string.module_install_picker),
                        tint = colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(6.dp))
                GlassIconButton(onClick = actions.onCloseBrowser) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = colorScheme.onSurface,
                    )
                }
            }
            Text(
                text = if (state.readingKeybox) {
                    stringResource(R.string.attest_keybox_reading)
                } else if (state.keybox == KeyboxState.INVALID) {
                    keyboxSummary(state)
                } else {
                    stringResource(R.string.attest_browser_hint)
                },
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                fontSize = 13.sp,
                color = if (!state.readingKeybox && state.keybox == KeyboxState.INVALID) {
                    colorScheme.error
                } else {
                    colorScheme.onSurfaceVariantSummary
                },
            )
        }
    }
    val browser = state.browser
    pickBrowser(
        folder = PickFolder(browser.path, browser.entries, browser.listing, browser.errno),
        fileKind = FileKind.TEXT,
        emptyText = R.string.attest_browser_empty,
        isPicked = { it.path == state.keyboxPath },
        onUp = actions.onBrowserUp,
        onNavigate = actions.onBrowserNavigate,
        onPickFile = { if (!state.readingKeybox) actions.onPickKeybox(it) },
    )
    item(key = "browser_end") { Box(Modifier.padding(bottom = 12.dp)) }
}

@Composable
private fun statusSummary(state: AttestUiState): String = stringResource(
    when {
        !state.enabled -> R.string.attest_status_off
        state.configured && state.deployed -> R.string.attest_status_on
        else -> R.string.attest_status_on_unconfigured
    },
)

@Composable
private fun keyboxSummary(state: AttestUiState): String {
    if (state.readingKeybox) return stringResource(R.string.attest_keybox_reading)
    val standing = stringResource(
        when (state.keybox) {
            KeyboxState.NONE -> R.string.attest_keybox_none
            KeyboxState.VALID -> R.string.attest_keybox_valid
            KeyboxState.REVOKED -> R.string.attest_keybox_revoked
            KeyboxState.INVALID -> R.string.attest_keybox_invalid
        },
    )
    val name = state.keyboxName
    return if (name != null && state.keybox != KeyboxState.NONE) "$standing · $name" else standing
}

/** A keybox the user may configure with: valid, or revoked (their call, warned). Not NONE/INVALID. */
private fun keyboxUsable(keybox: KeyboxState): Boolean =
    keybox == KeyboxState.VALID || keybox == KeyboxState.REVOKED

/** The string for the last configure outcome, or null when there is nothing to show. */
private fun configureResult(message: String?): Int? = when (message) {
    "ok" -> R.string.attest_result_ok
    "fail" -> R.string.attest_result_fail
    else -> null
}
