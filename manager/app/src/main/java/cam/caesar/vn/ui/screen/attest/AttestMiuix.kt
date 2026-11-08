package cam.caesar.vn.ui.screen.attest

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.caesar.vn.R
import cam.caesar.vn.attest.AttestUiState
import cam.caesar.vn.attest.KeyboxState
import cam.caesar.vn.ui.component.AttestationResult
import cam.caesar.vn.ui.component.glass.GlassCard
import cam.caesar.vn.ui.component.glass.GlassIconButton
import cam.caesar.vn.ui.component.glass.GlassSwitchPreference
import cam.caesar.vn.ui.theme.LocalEnableBlur
import cam.caesar.vn.ui.util.BlurredBar
import cam.caesar.vn.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val ATTEST_MAX_WIDTH = 760.dp

private val cardModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp)
    .padding(bottom = 12.dp)

private val buttonModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 8.dp)

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
            val side = ((maxWidth - ATTEST_MAX_WIDTH) / 2).coerceAtLeast(0.dp)
            val layoutDirection = LocalLayoutDirection.current
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 16.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + side,
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection) + side,
                    bottom = innerPadding.calculateBottomPadding(),
                ),
                overscrollEffect = null,
            ) {
                // What the feature does + the experimental warning, read before anything else.
                item {
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

                // Step 1 — import and revocation-check the keybox.
                item {
                    SmallTitle(text = stringResource(R.string.attest_step_keybox))
                    GlassCard(modifier = cardModifier) {
                        BasicComponent(
                            title = stringResource(R.string.attest_keybox),
                            summary = keyboxSummary(state.keybox),
                        )
                        BasicComponent(summary = stringResource(R.string.attest_keybox_hint))
                        TextButton(
                            text = stringResource(R.string.attest_import_keybox),
                            onClick = actions.onImportKeybox,
                            modifier = buttonModifier,
                        )
                    }
                }

                // Step 2 — which apps attest with the keybox.
                item {
                    SmallTitle(text = stringResource(R.string.attest_step_targets))
                    GlassCard(modifier = cardModifier) {
                        state.packages.forEach { BasicComponent(title = it) }
                        BasicComponent(summary = stringResource(R.string.attest_targets_summary))
                    }
                }

                // Step 3 — write the config and deploy the interceptor.
                item {
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
                            enabled = !state.busy && keyboxUsable(state.keybox),
                            onClick = actions.onConfigure,
                            modifier = buttonModifier,
                        )
                    }
                }

                // Step 4 — the toggle, gated until a keybox is loaded and configured.
                item {
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

                // Step 5 — show what an attested key actually reports now.
                item {
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
        }
    }
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
private fun keyboxSummary(keybox: KeyboxState): String = stringResource(
    when (keybox) {
        KeyboxState.NONE -> R.string.attest_keybox_none
        KeyboxState.VALID -> R.string.attest_keybox_valid
        KeyboxState.REVOKED -> R.string.attest_keybox_revoked
        KeyboxState.INVALID -> R.string.attest_keybox_invalid
    },
)

/** A keybox the user may configure with: valid, or revoked (their call, warned). Not NONE/INVALID. */
private fun keyboxUsable(keybox: KeyboxState): Boolean =
    keybox == KeyboxState.VALID || keybox == KeyboxState.REVOKED

/** The string for the last configure outcome, or null when there is nothing to show. */
private fun configureResult(message: String?): Int? = when (message) {
    "ok" -> R.string.attest_result_ok
    "fail" -> R.string.attest_result_fail
    else -> null
}
