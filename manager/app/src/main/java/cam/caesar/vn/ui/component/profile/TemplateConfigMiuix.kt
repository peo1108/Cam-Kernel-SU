package cam.su.kernel.ui.component.profile

import cam.su.kernel.ui.component.glass.GlassDropdownPreference
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Create
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cam.su.kernel.CamNative
import cam.su.kernel.R
import cam.su.kernel.ui.util.setSepolicy
import cam.su.kernel.ui.viewmodel.getTemplateInfoById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * [profile] switched to template [id]: the template's uid, groups, capabilities, context and
 * namespace, with its rules stored for the id. Null when the template does not read or camd
 * refuses its rules. Two camd runs, so off the main thread.
 */
suspend fun profileFromTemplate(profile: CamNative.Profile, id: String): CamNative.Profile? = withContext(Dispatchers.IO) {
    val info = getTemplateInfoById(id) ?: return@withContext null
    if (!setSepolicy(id, info.rules.joinToString("\n"))) return@withContext null
    profile.copy(
        rootTemplate = id,
        rootUseDefault = false,
        uid = info.uid,
        gid = info.gid,
        groups = info.groups,
        capabilities = info.capabilities,
        context = info.context,
        namespace = info.namespace,
    )
}

/**
 * @author weishu
 * @date 2023/10/21.
 *
 * [templates] is the template list, read off the main thread by the caller; null while it loads.
 */
@Composable
fun TemplateConfigMiuix(
    modifier: Modifier = Modifier,
    profile: CamNative.Profile,
    templates: List<String>?,
    onViewTemplate: (id: String) -> Unit = {},
    onManageTemplate: () -> Unit = {},
    onProfileChange: (CamNative.Profile) -> Unit
) {
    val profileTemplates = templates ?: return
    val scope = rememberCoroutineScope()
    val noTemplates = profileTemplates.isEmpty()

    if (noTemplates) {
        ArrowPreference(
            modifier = modifier,
            title = stringResource(R.string.app_profile_template_create),
            startAction = {
                Icon(
                    Icons.Rounded.Create,
                    null,
                    modifier = Modifier.padding(end = 16.dp),
                    tint = MiuixTheme.colorScheme.onBackground
                )
            },
            onClick = onManageTemplate,
        )
    } else {
        val template = profile.rootTemplate ?: profileTemplates[0]

        Column(modifier = modifier) {
            GlassDropdownPreference(
                title = stringResource(R.string.profile_template),
                items = profileTemplates,
                selectedIndex = profileTemplates.indexOf(template).takeIf { it >= 0 } ?: 0,
                onSelectedIndexChange = { index ->
                    if (index < 0 || index >= profileTemplates.size) return@GlassDropdownPreference
                    val selected = profileTemplates[index]
                    scope.launch {
                        profileFromTemplate(profile, selected)?.let(onProfileChange)
                    }
                },
                maxHeight = 280.dp
            )
            ArrowPreference(
                title = stringResource(R.string.app_profile_template_view),
                onClick = { onViewTemplate(template) }
            )
        }
    }
}
