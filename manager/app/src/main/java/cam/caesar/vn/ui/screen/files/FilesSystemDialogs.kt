package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.FileFormat
import cam.su.kernel.ui.viewmodel.FilesSystemTools
import cam.su.kernel.ui.viewmodel.FilesViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Systemless override of one system file through the cam_file_override module. */
@Composable
internal fun OverrideDialog(vm: FilesViewModel) {
    FilesDialog(show = true, title = stringResource(R.string.files_override), onDismiss = vm.system::hideOverride) {
        val live by vm.uiState.collectAsState()
        val o = live.override ?: return@FilesDialog
        Text(o.entry.path, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
        Text(
            stringResource(if (o.exists) R.string.files_override_active else R.string.files_override_none),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!o.metamodule) {
            Text(stringResource(R.string.files_override_no_metamodule), fontSize = 13.sp, color = colorScheme.error, modifier = Modifier.padding(top = 10.dp))
        } else {
            Spacer(Modifier.height(6.dp))
            ListAction(stringResource(R.string.files_override_edit), onClick = vm.system::overrideEdit)
            o.replacement?.let { r ->
                ListAction(stringResource(R.string.files_override_replace, r.path), onClick = vm.system::overrideReplace)
            }
            if (o.exists) ListAction(stringResource(R.string.files_override_remove), onClick = vm.system::overrideRemove)
            Text(stringResource(R.string.files_override_reboot), fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
        }
        TextButton(text = stringResource(R.string.files_close), onClick = vm.system::hideOverride, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** "Browse as an app": pick a running app process. */
@Composable
internal fun ProcessDialog(vm: FilesViewModel) {
    FilesDialog(show = true, title = stringResource(R.string.files_view_as_app), onDismiss = vm.system::hideProcesses) {
        val live by vm.uiState.collectAsState()
        val processes = live.processes ?: return@FilesDialog
        Text(stringResource(R.string.files_view_as_app_summary), fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
            items(processes.size) { i ->
                val (pid, uid, name) = processes[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.system.viewAsApp(pid, name) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("pid $pid · uid $uid", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        TextButton(text = stringResource(R.string.files_close), onClick = vm.system::hideProcesses, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/** Partitions under by-name: tap one to back it up, or (with an image picked) to flash it there. */
@Composable
internal fun PartitionsDialog(vm: FilesViewModel, onDialog: (FilesDialogState?) -> Unit) {
    val title = vm.uiState.collectAsState().value.partitions?.image?.let { stringResource(R.string.files_flash_pick, it.name) }
        ?: stringResource(R.string.files_partitions)
    FilesDialog(show = true, title = title, onDismiss = vm.system::hidePartitions) {
        val live by vm.uiState.collectAsState()
        val ui = live.partitions ?: return@FilesDialog
        val image = ui.image
        val flashable = ui.list.filter { cam.su.kernel.files.Partitions.canFlash(it.name) }
        val suggested = image?.let { cam.su.kernel.files.Partitions.guess(it.name, ui.list, ui.slot) }
        // flashing only ever offers the boot chain; a backup can be of anything
        val shown = when {
            image != null -> listOfNotNull(suggested) + flashable.filter { it != suggested }
            ui.all -> ui.list
            else -> flashable
        }
        // short enough that "Show all" and Close stay on screen under it
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(shown.size) { i ->
                val p = shown[i]
                val active = ui.slot.isNotEmpty() && p.name.endsWith(ui.slot)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            vm.system.hidePartitions()
                            onDialog(
                                if (image != null) {
                                    FilesDialogState.ConfirmFlash(image, p, otherSlot = !cam.su.kernel.files.Partitions.inSlot(p.name, ui.slot))
                                } else {
                                    FilesDialogState.ConfirmBackup(p)
                                },
                            )
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
                        Text(FileFormat.size(p.size), fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                    }
                    val tag = when {
                        p == suggested -> stringResource(R.string.files_partition_suggested)
                        active -> stringResource(R.string.files_partition_active)
                        else -> null
                    }
                    tag?.let { Text(it, fontSize = 11.sp, color = colorScheme.primary) }
                }
            }
        }
        if (image == null && !ui.all && ui.list.size > shown.size) {
            ListAction(stringResource(R.string.files_partitions_all, ui.list.size), onClick = vm.system::showAllPartitions)
        }
        TextButton(text = stringResource(R.string.files_close), onClick = vm.system::hidePartitions, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/**
 * Two steps: what gets written where, with the image checked against the partition, then a last
 * warning where the partition's name has to be typed before the red Flash works.
 */
@Composable
internal fun FlashConfirmDialog(dialog: FilesDialogState.ConfirmFlash, vm: FilesViewModel, onDialog: (FilesDialogState?) -> Unit) {
    val close = { onDialog(null) }
    val title = stringResource(if (dialog.last) R.string.files_flash_last_title else R.string.files_flash)
    // overlay dialogs keep the content they were shown with: each step is a dialog of its own
    androidx.compose.runtime.key(dialog.last) {
        FilesDialog(show = true, title = title, onDismiss = close) {
            if (dialog.last) FlashLastStep(dialog, vm, close) else FlashFirstStep(dialog, vm, onDialog, close)
        }
    }
}

@Composable
private fun FlashFirstStep(dialog: FilesDialogState.ConfirmFlash, vm: FilesViewModel, onDialog: (FilesDialogState?) -> Unit, close: () -> Unit) {
    var check by remember { mutableStateOf<FilesSystemTools.FlashCheck?>(null) }
    androidx.compose.runtime.LaunchedEffect(dialog.image.path, dialog.partition.name) {
        check = vm.system.checkFlash(dialog.image, dialog.partition)
    }
    Text(
        stringResource(
            R.string.files_flash_confirm,
            dialog.image.name,
            FileFormat.size(dialog.image.contentSize),
            dialog.partition.name,
            FileFormat.size(dialog.partition.size),
        ),
    )
    val problems = buildList {
        if (dialog.image.contentSize > dialog.partition.size) add(stringResource(R.string.files_flash_too_big))
        when (check?.problem) {
            cam.su.kernel.files.ImageProblem.WRONG_FORMAT -> add(stringResource(R.string.files_flash_wrong_format, dialog.partition.name))
            cam.su.kernel.files.ImageProblem.KERNEL_INTO_INIT_BOOT -> add(stringResource(R.string.files_flash_kernel_into_init_boot))
            cam.su.kernel.files.ImageProblem.NO_KERNEL -> add(stringResource(R.string.files_flash_no_kernel, dialog.partition.name))
            null -> {}
        }
        if (check?.room == false) add(stringResource(R.string.files_flash_no_room, FileFormat.size(dialog.partition.size)))
    }
    problems.forEach { Text(it, color = colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    if (dialog.otherSlot) Text(stringResource(R.string.files_flash_other_slot), color = colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    val note = when {
        check == null -> stringResource(R.string.files_flash_checking)
        problems.isEmpty() -> stringResource(R.string.files_flash_backup_first, dialog.partition.name, cam.su.kernel.files.Partitions.BACKUP_DIR)
        else -> null
    }
    note?.let { Text(it, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp)) }
    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(text = stringResource(android.R.string.cancel), onClick = close, modifier = Modifier.weight(1f))
        TextButton(
            text = stringResource(android.R.string.ok),
            enabled = check != null && problems.isEmpty(),
            onClick = { onDialog(dialog.copy(last = true)) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun FlashLastStep(dialog: FilesDialogState.ConfirmFlash, vm: FilesViewModel, close: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Text(stringResource(R.string.files_flash_last_message), color = colorScheme.error)
    Text(stringResource(R.string.files_flash_type_name, dialog.partition.name), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    TextField(
        value = typed,
        onValueChange = { typed = it },
        label = dialog.partition.name,
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(text = stringResource(android.R.string.cancel), onClick = close, modifier = Modifier.weight(1f))
        TextButton(
            text = stringResource(R.string.files_flash_go),
            enabled = typed.trim() == dialog.partition.name,
            onClick = {
                close()
                vm.system.flashPartition(dialog.image, dialog.partition.name)
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColors(color = colorScheme.error, textColor = colorScheme.onError),
        )
    }
}
