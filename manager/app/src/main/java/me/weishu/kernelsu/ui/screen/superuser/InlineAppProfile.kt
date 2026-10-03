package me.weishu.kernelsu.ui.screen.superuser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.screen.appprofile.AppProfileEditorBody
import me.weishu.kernelsu.ui.screen.appprofile.rememberAppProfileEditor
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * The app profile editor shown inside an expanded SuperUser card. Edits are written immediately;
 * the list tags (ROOT, CUSTOM, ...) refresh once, through [onEdited], when the card closes.
 */
@Composable
fun InlineAppProfile(
    group: GroupedApps,
    onOpenFullProfile: () -> Unit,
    onEdited: () -> Unit,
) {
    var edited by remember { mutableStateOf(false) }
    val latestOnEdited by rememberUpdatedState(onEdited)
    val editor = rememberAppProfileEditor(group, onSaved = { edited = true })
    DisposableEffect(Unit) {
        onDispose { if (edited) latestOnEdited() }
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
        )
        ArrowPreference(
            title = stringResource(R.string.app_profile_open_full),
            onClick = onOpenFullProfile,
        )
    }
}
