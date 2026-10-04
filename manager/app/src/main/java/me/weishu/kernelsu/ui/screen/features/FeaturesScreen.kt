package me.weishu.kernelsu.ui.screen.features

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.viewmodel.FeaturesViewModel

@Composable
fun FeaturesPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<FeaturesViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.refresh()
    }

    // back from the background: the boot guard or another app may have changed things
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) viewModel.refresh()
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    val actions = FeaturesActions(
        onSetBootGuardEnabled = viewModel::setBootGuardEnabled,
        onSetBootGuardFailures = viewModel::setBootGuardFailures,
        onSetBootGuardDisableAll = viewModel::setBootGuardDisableAll,
        onReenableModule = viewModel::reenableModule,
        onDismissBootGuard = viewModel::dismissBootGuard,
        onSetConflictDetection = viewModel::setConflictDetection,
        onSetConflictWarnOnFlash = viewModel::setConflictWarnOnFlash,
        onSetConflictIncludeProps = viewModel::setConflictIncludeProps,
        onRescanConflicts = viewModel::rescanConflicts,
        onSetHideBootloader = viewModel::setHideBootloader,
        onCheckAttestation = viewModel::checkAttestation,
    )

    FeaturesPagerMiuix(uiState, actions, bottomInnerPadding)
}
