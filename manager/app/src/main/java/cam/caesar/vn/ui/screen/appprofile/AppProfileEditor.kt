package cam.caesar.vn.ui.screen.appprofile

import cam.caesar.vn.ui.bunker.BunkerSignals
import cam.caesar.vn.ui.component.CamToast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import cam.caesar.vn.Cam
import cam.caesar.vn.CamNative
import cam.caesar.vn.R
import cam.caesar.vn.ui.navigation3.LocalNavigator
import cam.caesar.vn.ui.navigation3.Route
import cam.caesar.vn.ui.screen.superuser.GroupedApps
import cam.caesar.vn.ui.util.getSepolicy
import cam.caesar.vn.ui.util.setSepolicy
import cam.caesar.vn.ui.viewmodel.getTemplateInfoById

/** Loaded profile of one uid plus the actions that edit it; shared by the profile page and inline cards. */
@Stable
class AppProfileEditor(
    val state: AppProfileUiState,
    val onProfileChange: (CamNative.Profile) -> Unit,
    val onViewTemplate: (String) -> Unit,
    val onManageTemplate: () -> Unit,
)

/**
 * Profile writes, one at a time in the order they were made, off the main thread (the rules are a
 * camd run). They outlive the editor that made them: an edit made just before a card closes or a
 * pane goes away still reaches the kernel, and its [rememberAppProfileEditor] onSaved still runs.
 */
object ProfileWrites {
    private val lock = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun launch(write: suspend () -> Unit) {
        scope.launch { lock.withLock { write() } }
    }

    /** Returns once every write made so far is done. */
    suspend fun idle() = lock.withLock { }
}

/**
 * Reads the kernel profile for [appGroup] and returns an editor whose changes are validated and
 * written straight back (sepolicy rules first, then the profile) through [ProfileWrites].
 * [onSaved] runs after each successful write, also when the editor has gone by then.
 */
@Composable
fun rememberAppProfileEditor(appGroup: GroupedApps, onSaved: () -> Unit = {}): AppProfileEditor {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnSaved by rememberUpdatedState(onSaved)
    val uid = appGroup.uid
    val primaryAppInfo = appGroup.primary
    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        Cam.getAppProfile(packageName, uid).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }
    // An edit shows at once, so the next tap builds on it. What the kernel has is kept apart: a
    // write that fails puts that back, unless another edit has been made on top since.
    val written = remember(uid, packageName) { arrayOf(profile) }
    // The custom SELinux rules are a camd run: read off the main thread (a tap on a row beside the
    // Superuser list would otherwise hold it while the pick animates), once root is on, also when
    // it is turned on here. An edit that came first keeps its rules; one that left them empty
    // saved nothing over the rules on disk (see below). Their editor stays shut until they are in,
    // and when camd could not read them: opened blank, a confirm would replace rules the user
    // never saw.
    var rulesRead by remember(uid, packageName) {
        mutableStateOf(primaryAppInfo.special || profile.rules.isNotEmpty())
    }
    val rulesLoading = profile.allowSu && !rulesRead
    LaunchedEffect(uid, packageName, rulesLoading) {
        if (!rulesLoading) return@LaunchedEffect
        val rules = withContext(Dispatchers.IO) { runCatching { getSepolicy(packageName) }.getOrNull() }
            ?: return@LaunchedEffect
        if (rules.isNotEmpty()) {
            if (profile.rules.isEmpty()) profile = profile.copy(rules = rules)
            if (written[0].rules.isEmpty()) written[0] = written[0].copy(rules = rules)
        }
        rulesRead = true
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)

    // a write's message may come after the editor is gone
    fun showMessage(message: String) {
        ProfileWrites.scope.launch {
            CamToast.show(context, message)
        }
    }

    return AppProfileEditor(
        state = AppProfileUiState(
            uid = uid,
            packageName = packageName,
            profile = profile,
            appGroup = appGroup,
            sharedUserId = sharedUserId,
            rulesLoading = rulesLoading,
        ),
        onProfileChange = onProfileChange@{ updatedProfile ->
            val profileToSave = if (primaryAppInfo.special) {
                updatedProfile.copy(allowSu = false)
            } else {
                updatedProfile
            }
            if (profileToSave.allowSu && uid < 2000 && uid != 1000) {
                showMessage(suNotAllowed)
                return@onProfileChange
            }
            profile = profileToSave
            ProfileWrites.launch {
                val failure = withContext(Dispatchers.IO) {
                    when {
                        profileToSave.allowSu
                            && !profileToSave.rootUseDefault
                            && profileToSave.rules.isNotEmpty()
                            && !primaryAppInfo.special
                            && !setSepolicy(profileToSave.name, profileToSave.rules) -> failToUpdateSepolicy

                        !Cam.setAppProfile(profileToSave) -> failToUpdateAppProfile
                        else -> null
                    }
                }
                if (failure != null) {
                    showMessage(failure)
                    if (profile == profileToSave) profile = written[0]
                } else {
                    // root just granted: the bunker background's reticle locks on
                    if (profileToSave.allowSu && !written[0].allowSu) BunkerSignals.lock()
                    written[0] = profileToSave
                    latestOnSaved()
                }
            }
        },
        onViewTemplate = { templateId ->
            // reading the template is a camd run as well
            scope.launch {
                withContext(Dispatchers.IO) { getTemplateInfoById(templateId) }?.let { info ->
                    navigator.push(Route.TemplateEditor(info, true))
                }
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
    )
}
