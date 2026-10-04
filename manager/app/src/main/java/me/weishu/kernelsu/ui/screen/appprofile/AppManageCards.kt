package me.weishu.kernelsu.ui.screen.appprofile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import me.weishu.kernelsu.ui.component.glass.GlassExpandableCard
import me.weishu.kernelsu.ui.component.glass.GlassListCard
import me.weishu.kernelsu.ui.util.AppDetails
import me.weishu.kernelsu.ui.util.AppStorage
import me.weishu.kernelsu.ui.util.backupApk
import me.weishu.kernelsu.ui.util.clearAppCache
import me.weishu.kernelsu.ui.util.clearAppData
import me.weishu.kernelsu.ui.util.formatBytes
import me.weishu.kernelsu.ui.util.isProtectedApp
import me.weishu.kernelsu.ui.util.loadAppDetails
import me.weishu.kernelsu.ui.util.measureAppStorage
import me.weishu.kernelsu.ui.util.removeSystemAppSystemless
import me.weishu.kernelsu.ui.util.setAppFrozen
import me.weishu.kernelsu.ui.util.uninstallApp
import me.weishu.kernelsu.ui.util.uninstallSystemUpdates
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.io.File
import java.text.DateFormat
import java.util.Date

private val Danger = Color(0xFFE5484D)
private val ApkColor = Color(0xFF5B8DEF)
private val DataColor = Color(0xFF3DBE8B)
private val CacheColor = Color(0xFFF5A524)
private val ExternalColor = Color(0xFFB57BEE)

/**
 * The app's own details under its profile: what it is ([InfoCard]), how much room it takes
 * ([StorageCard]) and what can be done to it ([ManageCard]). Each is a small card that opens up
 * when tapped, like the app cards on the Superuser page. [onChanged] asks for the app list to be
 * refreshed; [onRemoved] is called once the app is gone for this user.
 */
@Composable
fun AppManageCards(
    packageInfo: PackageInfo,
    userId: Int,
    onChanged: () -> Unit,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val details by produceState<AppDetails?>(null, packageInfo, userId, reload) {
        value = runCatching { loadAppDetails(context, packageInfo, userId) }.getOrNull()
    }
    val storage by produceState<AppStorage?>(null, details) {
        value = null
        value = details?.let { runCatching { measureAppStorage(it) }.getOrNull() }
    }
    val d = details ?: return
    InfoCard(d)
    StorageCard(storage)
    ManageCard(
        d = d,
        storage = storage,
        onChanged = {
            reload++
            onChanged()
        },
        onRemoved = onRemoved,
    )
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun InfoCard(d: AppDetails) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val type = when {
        d.isUpdatedSystem -> stringResource(R.string.app_type_updated_system)
        d.isSystem -> stringResource(R.string.app_type_system)
        else -> stringResource(R.string.app_type_user)
    }
    GlassExpandableCard(
        icon = Icons.Rounded.Info,
        title = stringResource(R.string.app_info),
        summary = "${d.versionName} · SDK ${d.targetSdk} · $type",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        InfoRow(stringResource(R.string.app_info_package), d.packageName, copy = true)
        InfoRow(stringResource(R.string.app_info_version), "${d.versionName} (${d.versionCode})", copy = true)
        InfoRow(
            stringResource(R.string.app_info_uid),
            "${d.uid} / ${d.uid}" + (d.sharedUserId?.let { " · $it" } ?: ""),
        )
        InfoRow(stringResource(R.string.app_info_sdk), "${d.targetSdk} / ${d.minSdk}")
        InfoRow(stringResource(R.string.app_info_installed), dates.format(Date(d.firstInstall)))
        InfoRow(stringResource(R.string.app_info_updated), dates.format(Date(d.lastUpdate)))
        InfoRow(stringResource(R.string.app_info_installer), installerName(context, d.installer))
        InfoRow(
            stringResource(R.string.app_info_abi),
            d.abi?.let { stringResource(R.string.app_info_abi_native, it) } ?: stringResource(R.string.app_info_no_native),
        )
        InfoRow(stringResource(R.string.app_info_signature), d.signature ?: "—", copy = d.signature != null)
        InfoRow(stringResource(R.string.app_info_type), type)
        val flags = buildList {
            if (d.debuggable) add(stringResource(R.string.app_flag_debuggable))
            if (d.frozen) add(stringResource(R.string.app_flag_frozen))
            if (d.hidden) add(stringResource(R.string.app_flag_hidden))
        }
        InfoRow(stringResource(R.string.app_info_flags), flags.joinToString(" · ").ifEmpty { stringResource(R.string.app_flag_none) })

        // Paths: tap one to copy it, hold any to copy them all.
        val paths = buildList {
            add(stringResource(R.string.app_path_apk) to d.apkPath)
            d.splitPaths.forEach { add(stringResource(R.string.app_path_split) to it) }
            add(stringResource(R.string.app_path_data) to d.dataDir)
            add(stringResource(R.string.app_path_de) to d.deDataDir)
            d.nativeDir?.let { add(stringResource(R.string.app_path_native) to it) }
            add(stringResource(R.string.app_path_external) to d.externalDir)
            add(stringResource(R.string.app_path_obb) to d.obbDir)
        }
        val copiedAll = stringResource(R.string.app_paths_copied)
        val copyAll = {
            copy(context, paths.joinToString("\n") { (label, path) -> "$label: $path" })
            toast(context, copiedAll)
        }
        Text(
            text = stringResource(R.string.app_paths) + " · " + stringResource(R.string.app_paths_hint),
            fontSize = 12.sp,
            fontWeight = FontWeight(550),
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        )
        paths.forEach { (label, path) -> InfoRow(label, path, copy = true, onLongClick = copyAll) }
    }
}

@Composable
private fun StorageCard(storage: AppStorage?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    GlassExpandableCard(
        icon = Icons.Rounded.Storage,
        title = stringResource(R.string.app_storage),
        summary = storage?.let { stringResource(R.string.app_storage_total, formatBytes(it.total)) }
            ?: stringResource(R.string.app_storage_measuring),
        expanded = expanded,
        onToggle = { expanded = !expanded },
        header = { if (storage != null) StorageBar(storage) },
    ) {
        if (storage == null) return@GlassExpandableCard
        StorageRow(ApkColor, stringResource(R.string.app_storage_apk), storage.apk)
        StorageRow(DataColor, stringResource(R.string.app_storage_data), storage.data)
        StorageRow(CacheColor, stringResource(R.string.app_storage_cache), storage.cache)
        StorageRow(ExternalColor, stringResource(R.string.app_storage_external), storage.external)
    }
}

@Composable
private fun ManageCard(
    d: AppDetails,
    storage: AppStorage?,
    onChanged: () -> Unit,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading = rememberLoadingDialog()
    val confirm = rememberConfirmDialog()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val protected = remember(d.packageName) { isProtectedApp(context, d.packageName) }
    var backup by remember { mutableStateOf<Pair<File, String>?>(null) }
    var askUninstall by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.app_action_failed)

    GlassExpandableCard(
        icon = Icons.Rounded.Handyman,
        title = stringResource(R.string.app_manage),
        summary = stringResource(R.string.app_manage_summary),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        val backupFailed = stringResource(R.string.app_backup_failed)
        ActionRow(Icons.Rounded.SaveAlt, stringResource(R.string.app_backup), stringResource(R.string.app_backup_summary)) {
            scope.launch {
                val r = loading.withLoading { backupApk(context, d) }
                if (r == null) toast(context, backupFailed) else backup = r
            }
        }
        val cleared = stringResource(R.string.app_cache_cleared)
        ActionRow(
            Icons.Rounded.CleaningServices,
            stringResource(R.string.app_clear_cache),
            storage?.let { formatBytes(it.cache) },
        ) {
            scope.launch {
                val freed = loading.withLoading { clearAppCache(d) }
                toast(context, cleared.format(formatBytes(freed)))
                onChanged()
            }
        }
        val clearTitle = stringResource(R.string.app_clear_data)
        val clearContent = stringResource(R.string.app_clear_data_confirm, d.label)
        val clearDone = stringResource(R.string.app_done)
        ActionRow(Icons.Rounded.DeleteSweep, clearTitle, stringResource(R.string.app_clear_data_summary), danger = true) {
            if (protected) {
                askUninstall = true
                return@ActionRow
            }
            scope.launch {
                if (confirm.awaitConfirm(title = clearTitle, content = clearContent, confirm = clearTitle) != ConfirmResult.Confirmed) return@launch
                val ok = loading.withLoading { clearAppData(d) }
                toast(context, if (ok) clearDone else failed)
                onChanged()
            }
        }
        ActionRow(
            if (d.frozen) Icons.Rounded.LocalFireDepartment else Icons.Rounded.AcUnit,
            stringResource(if (d.frozen) R.string.app_unfreeze else R.string.app_freeze),
            stringResource(if (d.frozen) R.string.app_unfreeze_summary else R.string.app_freeze_summary),
        ) {
            if (protected && !d.frozen) {
                askUninstall = true
                return@ActionRow
            }
            scope.launch {
                val ok = loading.withLoading { setAppFrozen(d, !d.frozen) }
                if (!ok) toast(context, failed)
                onChanged()
            }
        }
        ActionRow(
            Icons.Rounded.Delete,
            stringResource(R.string.app_uninstall),
            stringResource(if (d.isSystem) R.string.app_uninstall_system_summary else R.string.app_uninstall_summary),
            danger = true,
        ) { askUninstall = true }
    }

    backup?.let { (file, path) ->
        GlassDialog(
            show = true,
            title = stringResource(R.string.app_backup),
            summary = stringResource(R.string.app_backup_done, path),
            onDismissRequest = { backup = null },
        ) {
            DialogButtons(
                dismiss = stringResource(android.R.string.cancel),
                confirm = stringResource(R.string.app_share),
                onDismiss = { backup = null },
                onConfirm = {
                    share(context, file)
                    backup = null
                },
            )
        }
    }

    if (askUninstall) {
        UninstallDialog(
            d = d,
            protected = protected,
            onDismiss = { askUninstall = false },
            onRun = { work, after ->
                askUninstall = false
                scope.launch { after(loading.withLoading { runCatching { work() }.getOrDefault(false) }) }
            },
            onRemoved = onRemoved,
            onChanged = onChanged,
        )
    }
}

/**
 * Uninstall choices. A user app: plain uninstall, optionally keeping its data. A system app:
 * uninstall for this user (reinstallable), remove it systemlessly with a module, or roll back its
 * updates. Protected apps only get a warning.
 */
@Composable
private fun UninstallDialog(
    d: AppDetails,
    protected: Boolean,
    onDismiss: () -> Unit,
    onRun: (work: suspend () -> Boolean, after: (Boolean) -> Unit) -> Unit,
    onRemoved: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val failed = stringResource(R.string.app_action_failed)
    val removed = stringResource(R.string.app_uninstalled)
    val moduleMade = stringResource(R.string.app_module_created)
    val rolledBack = stringResource(R.string.app_done)

    if (protected) {
        GlassDialog(
            show = true,
            title = stringResource(R.string.app_protected),
            summary = stringResource(R.string.app_protected_summary),
            onDismissRequest = onDismiss,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Danger, modifier = Modifier.padding(end = 10.dp))
                Text(d.label, color = Danger, fontWeight = FontWeight(550))
            }
            TextButton(text = stringResource(android.R.string.ok), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    if (!d.isSystem) {
        var keepData by remember { mutableStateOf(false) }
        GlassDialog(
            show = true,
            title = stringResource(R.string.app_uninstall),
            summary = stringResource(R.string.app_uninstall_confirm, d.label),
            onDismissRequest = onDismiss,
            insideMargin = DpSize(0.dp, 24.dp),
        ) {
            CheckboxPreference(
                title = stringResource(R.string.app_uninstall_keep_data),
                insideMargin = PaddingValues(horizontal = 30.dp, vertical = 12.dp),
                checkboxLocation = CheckboxLocation.End,
                checked = keepData,
                onCheckedChange = { keepData = it },
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(horizontal = 24.dp)) {
                DialogButtons(
                    dismiss = stringResource(android.R.string.cancel),
                    confirm = stringResource(R.string.app_uninstall),
                    onDismiss = onDismiss,
                    onConfirm = { onRun({ uninstallApp(d, keepData) }) { ok -> finish(context, ok, removed, failed, true, onRemoved, onChanged) } },
                )
            }
        }
        return
    }

    GlassDialog(
        show = true,
        title = stringResource(R.string.app_uninstall),
        summary = d.label,
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 24.dp),
    ) {
        ActionRow(Icons.Rounded.Person, stringResource(R.string.app_uninstall_user), stringResource(R.string.app_uninstall_user_summary)) {
            onRun({ uninstallApp(d) }) { ok -> finish(context, ok, removed, failed, true, onRemoved, onChanged) }
        }
        if (d.systemCodePath != null) {
            ActionRow(Icons.Rounded.Extension, stringResource(R.string.app_uninstall_systemless), stringResource(R.string.app_uninstall_systemless_summary)) {
                onRun({ removeSystemAppSystemless(context, d) }) { ok -> finish(context, ok, moduleMade, failed, true, onRemoved, onChanged) }
            }
        }
        if (d.isUpdatedSystem) {
            ActionRow(Icons.Rounded.Restore, stringResource(R.string.app_uninstall_updates), stringResource(R.string.app_uninstall_updates_summary)) {
                onRun({ uninstallSystemUpdates(d) }) { ok -> finish(context, ok, rolledBack, failed, false, onRemoved, onChanged) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = stringResource(android.R.string.cancel),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
    }
}

private fun finish(
    context: Context,
    ok: Boolean,
    done: String,
    failed: String,
    gone: Boolean,
    onRemoved: () -> Unit,
    onChanged: () -> Unit,
) {
    toast(context, if (ok) done else failed)
    if (ok && gone) onRemoved() else onChanged()
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

/** A card with a tappable header that opens up to show [content], arrow turning like Superuser's. */
@Composable
private fun InfoRow(label: String, value: String, copy: Boolean = false, onLongClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val copied = stringResource(R.string.app_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = copy || onLongClick != null,
                onClick = {
                    if (copy) {
                        copy(context, value)
                        toast(context, copied)
                    }
                },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
            Text(text = value, fontSize = 14.sp, color = colorScheme.onSurface)
        }
        if (copy) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariantActions,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(16.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, summary: String?, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) Danger else colorScheme.onBackground,
            modifier = Modifier.padding(end = 14.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight(550), color = if (danger) Danger else colorScheme.onSurface)
            if (summary != null) Text(text = summary, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        }
    }
}

/** APK · data · cache · external, as one bar. */
@Composable
private fun StorageBar(s: AppStorage) {
    val parts = listOf(ApkColor to s.apk, DataColor to s.data, CacheColor to s.cache, ExternalColor to s.external).filter { it.second > 0 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colorScheme.onSurface.copy(alpha = 0.08f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { (color, size) ->
            Box(
                Modifier
                    .weight(size.toFloat().coerceAtLeast(s.total * 0.01f))
                    .height(10.dp)
                    .background(color),
            )
        }
    }
}

@Composable
private fun StorageRow(color: Color, label: String, size: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(text = formatBytes(size), color = colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun DialogButtons(dismiss: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(text = dismiss, onClick = onDismiss, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(20.dp))
        TextButton(
            text = confirm,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

private fun installerName(context: Context, installer: String?): String = when (installer) {
    null, "com.android.shell" -> context.getString(R.string.app_installer_adb)
    "com.android.vending" -> "Google Play"
    "com.android.packageinstaller", "com.google.android.packageinstaller" -> context.getString(R.string.app_installer_file)
    else -> runCatching {
        context.packageManager.getApplicationInfo(installer, 0).loadLabel(context.packageManager).toString()
    }.getOrDefault(installer)
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("SU Kernel", text))
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/vnd.android.package-archive")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
