package cam.caesar.vn.ui.screen.superuser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.caesar.vn.R
import cam.caesar.vn.ui.screen.appprofile.AppProfileEditorBody
import cam.caesar.vn.ui.screen.appprofile.ProfileWrites
import cam.caesar.vn.ui.screen.appprofile.rememberAppProfileEditor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.preference.ArrowPreference
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import cam.caesar.vn.ui.screen.appprofile.AppProfileEditor

/**
 * The app profile editor shown inside an expanded SuperUser card. Edits are written immediately
 * and the row's tags (ROOT, CUSTOM, ...) follow each write through [onSaved] (that uid alone, the
 * list keeps its order); the whole list refreshes once, through [onEdited], when the card closes
 * and the writes made in it are done. Coming back to the page (from the full App Profile, where
 * the same profile may have changed) reads the profile from the kernel again.
 */
@Composable
fun InlineAppProfile(
    group: GroupedApps,
    onOpenFullProfile: () -> Unit,
    onEdited: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val edited = remember { booleanArrayOf(false) }
    val latestOnEdited by rememberUpdatedState(onEdited)
    val latestOnSaved by rememberUpdatedState(onSaved)
    // a new editor (read from the kernel) each time the page comes back, never on the first resume
    var generation by remember { mutableIntStateOf(0) }
    val left = remember { booleanArrayOf(false) }
    LifecycleResumeEffect(Unit) {
        if (left[0]) generation++
        onPauseOrDispose { left[0] = true }
    }
    DisposableEffect(Unit) {
        onDispose {
            ProfileWrites.scope.launch {
                ProfileWrites.idle()
                if (edited[0]) latestOnEdited()
            }
        }
    }

    key(generation) {
        val editor = rememberAppProfileEditor(group, onSaved = {
            edited[0] = true
            latestOnSaved()
        })
        InlineEditorBody(group, editor, onOpenFullProfile)
    }
}

@Composable
private fun InlineEditorBody(group: GroupedApps, editor: AppProfileEditor, onOpenFullProfile: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        AppProfileEditorBody(
            profile = editor.state.profile,
            isSpecialApp = group.primary.special,
            carded = false,
            onViewTemplate = editor.onViewTemplate,
            onManageTemplate = editor.onManageTemplate,
            onProfileChange = editor.onProfileChange,
            rulesLoading = editor.state.rulesLoading,
        )
        ArrowPreference(
            title = stringResource(R.string.app_profile_open_full),
            onClick = onOpenFullProfile,
        )
    }
}
