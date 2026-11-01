package cam.su.kernel.ui.screen.superuser

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
import cam.su.kernel.R
import cam.su.kernel.ui.screen.appprofile.AppProfileEditorBody
import cam.su.kernel.ui.screen.appprofile.ProfileWrites
import cam.su.kernel.ui.screen.appprofile.rememberAppProfileEditor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * The app profile editor shown inside an expanded SuperUser card. Edits are written immediately;
 * the list tags (ROOT, CUSTOM, ...) refresh once, through [onEdited], when the card closes and the
 * writes made in it are done.
 */
@Composable
fun InlineAppProfile(
    group: GroupedApps,
    onOpenFullProfile: () -> Unit,
    onEdited: () -> Unit,
) {
    val edited = remember { booleanArrayOf(false) }
    val latestOnEdited by rememberUpdatedState(onEdited)
    val editor = rememberAppProfileEditor(group, onSaved = { edited[0] = true })
    DisposableEffect(Unit) {
        onDispose {
            ProfileWrites.scope.launch {
                ProfileWrites.idle()
                if (edited[0]) latestOnEdited()
            }
        }
    }

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
