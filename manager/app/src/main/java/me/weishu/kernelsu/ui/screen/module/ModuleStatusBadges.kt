package me.weishu.kernelsu.ui.screen.module

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.ConflictKind
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** "Disabled by boot guard" and "Conflict" chips under a module's author line. */
@Composable
fun ModuleStatusBadges(module: Module, onRestoreBackup: () -> Unit = {}) {
    val showAutoDisabled = module.autoDisabled && !module.enabled
    val conflicts = module.conflicts
    // a pending update (or restore) is applied on the next boot; offer nothing until then
    val backupVersion = module.backupVersion.takeUnless { module.update || module.remove }
    if (!showAutoDisabled && conflicts.isEmpty() && backupVersion == null) return

    var showConflicts by rememberSaveable(module.id) { mutableStateOf(false) }
    var confirmRestore by rememberSaveable(module.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showAutoDisabled) {
            Badge(
                text = stringResource(R.string.module_badge_auto_disabled),
                background = colorScheme.errorContainer.copy(alpha = 0.6f),
                color = colorScheme.onErrorContainer,
            )
        }
        if (conflicts.isNotEmpty()) {
            Badge(
                text = "${stringResource(R.string.module_badge_conflict)} · ${conflicts.size}",
                background = colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                color = colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                onClick = { showConflicts = true },
            )
        }
        if (backupVersion != null) {
            Badge(
                text = "↺ ${stringResource(R.string.module_badge_restore, backupVersion.ifBlank { "?" })}",
                background = colorScheme.primary.copy(alpha = 0.15f),
                color = colorScheme.primary,
                onClick = { confirmRestore = true },
            )
        }
    }

    GlassDialog(
        show = confirmRestore,
        title = stringResource(R.string.module_restore_title),
        summary = stringResource(R.string.module_restore_summary, module.version, backupVersion.orEmpty()),
        onDismissRequest = { confirmRestore = false },
    ) {
        Row {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { confirmRestore = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.module_restore_confirm),
                onClick = {
                    confirmRestore = false
                    onRestoreBackup()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    GlassDialog(
        show = showConflicts && conflicts.isNotEmpty(),
        title = stringResource(R.string.module_conflict_title),
        onDismissRequest = { showConflicts = false },
    ) {
        Column {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                conflicts.forEach { conflict ->
                    val others = conflict.modules.filter { it != module.id }.joinToString(", ")
                    val res = when (conflict.kind) {
                        ConflictKind.File -> R.string.module_conflict_file
                        ConflictKind.Replace -> R.string.module_conflict_replace
                        ConflictKind.Prop -> R.string.module_conflict_prop
                    }
                    Text(
                        text = stringResource(res, conflict.path, others),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.close),
                onClick = { showConflicts = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Badge(text: String, background: Color, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        fontWeight = FontWeight(750),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
