package cam.su.kernel.ui.screen.configbackup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.backup.ConfigBackup
import cam.su.kernel.backup.ConfigBackupStore
import cam.su.kernel.backup.RestorePlan
import cam.su.kernel.backup.RestoreResult
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface BackupUi {
    data object Idle : BackupUi
    data object Working : BackupUi
    data class Saved(val backup: ConfigBackup) : BackupUi
    data class Preview(val backup: ConfigBackup, val plan: RestorePlan) : BackupUi
    data class Restored(val result: RestoreResult) : BackupUi
    data class Failed(val message: String) : BackupUi
}

/**
 * Settings → Back up configuration: Cam's configuration ([ConfigBackup]) into one file and back.
 * A restore shows what it will change on this phone before it writes anything.
 */
@Composable
fun ConfigBackupScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<BackupUi>(BackupUi.Idle) }
    val failedText = stringResource(R.string.config_backup_failed)

    fun fail(e: Throwable) {
        ui = BackupUi.Failed(e.message?.let { "$failedText\n$it" } ?: failedText)
    }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ConfigBackup.MIME)) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ui = BackupUi.Working
        scope.launch {
            runCatching {
                val backup = ConfigBackupStore.collect()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(backup.toJson().toByteArray()) }
                }
                backup
            }.onSuccess { ui = BackupUi.Saved(it) }.onFailure(::fail)
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        ui = BackupUi.Working
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                val backup = ConfigBackup.parse(text)
                BackupUi.Preview(backup, ConfigBackupStore.plan(backup))
            }.onSuccess { ui = it }.onFailure(::fail)
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, scrollBehavior = scrollBehavior) {
                SmallTopAppBar(
                    title = stringResource(R.string.config_backup_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        GlassIconButton(onClick = { navigator.pop() }) {
                            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                            Icon(
                                modifier = Modifier.graphicsLayer { if (rtl) scaleX = -1f },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    val working = ui == BackupUi.Working
                    ActionsCard(
                        working = working,
                        onBackup = {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                            createFile.launch("cam-config-$stamp.json")
                        },
                        onRestore = { openFile.launch(arrayOf(ConfigBackup.MIME, "text/plain", "application/octet-stream")) },
                    )
                    when (val state = ui) {
                        BackupUi.Idle -> Unit
                        BackupUi.Working -> Note(stringResource(R.string.config_backup_working))
                        is BackupUi.Saved -> Note(
                            stringResource(
                                R.string.config_backup_saved,
                                state.backup.profiles.size,
                                state.backup.modules.size,
                                state.backup.bookmarks.size,
                            ),
                        )
                        is BackupUi.Preview -> PreviewCard(
                            state = state,
                            onCancel = { ui = BackupUi.Idle },
                            onRestore = {
                                ui = BackupUi.Working
                                scope.launch {
                                    runCatching { ConfigBackupStore.restore(state.backup, state.plan) }
                                        .onSuccess { ui = BackupUi.Restored(it) }
                                        .onFailure(::fail)
                                }
                            },
                        )
                        is BackupUi.Restored -> ResultCard(state.result)
                        is BackupUi.Failed -> Note(state.message, error = true)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(working: Boolean, onBackup: () -> Unit, onRestore: () -> Unit) {
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.config_backup_summary), color = colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.config_backup_save),
                    enabled = !working,
                    onClick = onBackup,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.config_backup_open),
                    enabled = !working,
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(text, color = if (error) colorScheme.error else colorScheme.onSurface, modifier = Modifier.padding(16.dp))
    }
}

/** What the restore will change, before anything is written. */
@Composable
private fun PreviewCard(state: BackupUi.Preview, onCancel: () -> Unit, onRestore: () -> Unit) {
    val backup = state.backup
    val plan = state.plan
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.config_backup_preview_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                stringResource(
                    R.string.config_backup_from,
                    backup.device,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.created)),
                    backup.manager,
                ),
                color = colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
            Text(stringResource(R.string.config_backup_will_profiles, plan.profiles.size))
            Text(stringResource(R.string.config_backup_will_features))
            if (plan.modules.isNotEmpty()) {
                Text(stringResource(R.string.config_backup_will_modules, plan.modules.joinToString { "${it.name.ifEmpty { it.id }} → ${if (it.enabled) "on" else "off"}" }))
            }
            if (plan.newBookmarks.isNotEmpty()) Text(stringResource(R.string.config_backup_will_bookmarks, plan.newBookmarks.size))
            if (plan.missingApps.isNotEmpty()) {
                Text(
                    stringResource(R.string.config_backup_missing_apps, plan.missingApps.size, plan.missingApps.take(MISSING_SHOWN).joinToString()),
                    color = colorScheme.error,
                    fontSize = 13.sp,
                )
            }
            if (plan.missingModules.isNotEmpty()) {
                Text(
                    stringResource(R.string.config_backup_missing_modules, plan.missingModules.joinToString { it.name.ifEmpty { it.id } }),
                    color = colorScheme.error,
                    fontSize = 13.sp,
                )
            }
            Text(stringResource(R.string.config_backup_additive), color = colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(android.R.string.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                TextButton(
                    text = stringResource(R.string.config_backup_restore),
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(result: RestoreResult) {
    GlassCard(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.config_backup_restored, result.profiles, result.modules, result.bookmarks))
            if (result.failed.isNotEmpty()) {
                Text(stringResource(R.string.config_backup_restore_failed, result.failed.joinToString()), color = colorScheme.error)
            }
            if (result.rebootNeeded) Text(stringResource(R.string.config_backup_reboot), color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

private const val MISSING_SHOWN = 8
