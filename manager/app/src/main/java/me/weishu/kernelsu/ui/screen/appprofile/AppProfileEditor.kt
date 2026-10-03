package me.weishu.kernelsu.ui.screen.appprofile

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Ksu
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.superuser.GroupedApps
import me.weishu.kernelsu.ui.util.getSepolicy
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById

/** Loaded profile of one uid plus the actions that edit it; shared by the profile page and inline cards. */
@Stable
class AppProfileEditor(
    val state: AppProfileUiState,
    val onProfileChange: (Natives.Profile) -> Unit,
    val onViewTemplate: (String) -> Unit,
    val onManageTemplate: () -> Unit,
)

/**
 * Reads the kernel profile for [appGroup] and returns an editor whose changes are validated and
 * written straight back (sepolicy rules first, then the profile). [onSaved] runs after each
 * successful write.
 */
@Composable
fun rememberAppProfileEditor(appGroup: GroupedApps, onSaved: () -> Unit = {}): AppProfileEditor {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = appGroup.uid
    val primaryAppInfo = appGroup.primary
    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        Ksu.getAppProfile(packageName, uid).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }.also {
            if (it.allowSu && !primaryAppInfo.special) {
                it.rules = getSepolicy(packageName)
            }
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)

    fun showMessage(message: String) {
        scope.launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    return AppProfileEditor(
        state = AppProfileUiState(
            uid = uid,
            packageName = packageName,
            profile = profile,
            appGroup = appGroup,
            sharedUserId = sharedUserId,
        ),
        onProfileChange = { updatedProfile ->
            scope.launch {
                val profileToSave = if (primaryAppInfo.special) {
                    updatedProfile.copy(allowSu = false)
                } else {
                    updatedProfile
                }
                if (profileToSave.allowSu) {
                    if (uid < 2000 && uid != 1000) {
                        showMessage(suNotAllowed)
                        return@launch
                    }
                    if (!profileToSave.rootUseDefault
                        && profileToSave.rules.isNotEmpty()
                        && !primaryAppInfo.special
                        && !setSepolicy(profileToSave.name, profileToSave.rules)
                    ) {
                        showMessage(failToUpdateSepolicy)
                        return@launch
                    }
                }
                if (!Ksu.setAppProfile(profileToSave)) {
                    showMessage(failToUpdateAppProfile)
                } else {
                    profile = profileToSave
                    onSaved()
                }
            }
        },
        onViewTemplate = { templateId ->
            getTemplateInfoById(templateId)?.let { info ->
                navigator.push(Route.TemplateEditor(info, true))
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
    )
}
