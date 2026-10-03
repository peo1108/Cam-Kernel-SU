package me.weishu.kernelsu.ui.screen.superuser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import me.weishu.kernelsu.ui.util.listRemovedSystemApps
import me.weishu.kernelsu.ui.util.reinstallSystemApp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * System apps that were uninstalled for this user from their profile page; tap one to put it
 * back from the ROM. [onRestored] refreshes the app list.
 */
@Composable
fun RestoreSystemAppsDialog(show: Boolean, onDismiss: () -> Unit, onRestored: () -> Unit) {
    if (!show) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userId = android.os.Process.myUid() / 100000
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) { apps = listRemovedSystemApps(context, userId) }
    val restored = stringResource(R.string.app_restored)
    val failed = stringResource(R.string.app_action_failed)
    GlassDialog(
        show = true,
        title = stringResource(R.string.app_restore_system),
        summary = when {
            apps == null -> stringResource(R.string.app_storage_measuring)
            apps!!.isEmpty() -> stringResource(R.string.app_restore_none)
            else -> null
        },
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 24.dp),
    ) {
        Column(Modifier.heightIn(max = 500.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(apps.orEmpty(), key = { it.first }) { (pkg, label) ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    val ok = reinstallSystemApp(pkg, userId)
                                    Toast.makeText(context, if (ok) restored.format(label) else failed, Toast.LENGTH_SHORT).show()
                                    if (ok) {
                                        apps = apps?.filterNot { it.first == pkg }
                                        onRestored()
                                    }
                                }
                            }
                            .padding(horizontal = 30.dp, vertical = 12.dp),
                    ) {
                        Text(text = label, fontWeight = FontWeight(550), color = colorScheme.onSurface)
                        Text(text = pkg, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(android.R.string.ok),
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
        }
    }
}
