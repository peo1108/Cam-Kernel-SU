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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FileFormat
import cam.su.kernel.files.FsPath
import cam.su.kernel.ui.viewmodel.FilesViewModel
import cam.su.kernel.ui.viewmodel.PropertiesUi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fullDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

@Composable
internal fun PropertiesDialog(vm: FilesViewModel, onDialog: (FilesDialogState?) -> Unit) {
    var more by remember { mutableStateOf(false) }
    FilesDialog(show = true, title = stringResource(R.string.files_properties), onDismiss = vm::hideProperties) {
        val live by vm.uiState.collectAsState()
        val props: PropertiesUi = live.properties ?: return@FilesDialog
        val e = props.entry
        PropRow(R.string.files_prop_name, e.name)
        PropRow(R.string.files_prop_parent, FsPath.parent(e.path).let { if (it == "/") it else "$it/" })
        PropRow(
            R.string.files_prop_type,
            stringResource(
                when (e.type) {
                    FileEntry.TYPE_DIR -> R.string.files_type_folder
                    FileEntry.TYPE_FILE -> R.string.files_type_file
                    FileEntry.TYPE_LINK -> R.string.files_type_link
                    else -> R.string.files_type_other
                },
            ),
        )
        e.linkTarget?.let { PropRow(R.string.files_prop_link, it) }
        val measure = props.measure
        PropRow(
            R.string.files_prop_size,
            when {
                measure != null -> "${FileFormat.size(measure[0])} (${measure[0]})"
                e.isDir -> stringResource(R.string.files_computing)
                else -> "${FileFormat.size(e.size)} (${e.size})"
            },
        )
        PropRow(R.string.files_prop_modified, fullDate.format(Date(e.mtime)))
        Spacer(Modifier.height(6.dp))
        PropRow(R.string.files_prop_perms, "${FileFormat.permissions(e.mode)}(${FileFormat.octal(e.mode).trimStart('0').padStart(3, '0')})")
        PropRow(R.string.files_prop_owner, "${e.owner} (${e.uid})")
        PropRow(R.string.files_prop_group, "${e.group} (${e.gid})")
        e.context?.let { PropRow(R.string.files_prop_context, it) }
        if (e.isDir && measure != null) {
            Spacer(Modifier.height(6.dp))
            PropRow(R.string.files_prop_files, measure[1].toString())
            PropRow(R.string.files_prop_folders, measure[2].toString())
        }
        Row(modifier = Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.files_prop_more), onClick = { more = !more }, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.files_close),
                onClick = vm::hideProperties,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        if (more) {
            if (!e.isLink) ListAction(stringResource(R.string.files_perm_title)) { onDialog(FilesDialogState.Permissions(e)) }
            ListAction(stringResource(R.string.files_owner_title)) { onDialog(FilesDialogState.Owner(e)) }
            if (!e.isLink) ListAction(stringResource(R.string.files_context_title)) { onDialog(FilesDialogState.Context(e)) }
        }
    }
}

@Composable
private fun PropRow(label: Int, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(stringResource(label), fontSize = 14.sp, modifier = Modifier.widthIn(min = 96.dp).padding(end = 8.dp), color = colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
internal fun ChecksumDialog(vm: FilesViewModel) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    FilesDialog(show = true, title = stringResource(R.string.files_tool_checksum), onDismiss = vm::hideChecksum) {
        val live by vm.uiState.collectAsState()
        val sums = live.checksum ?: return@FilesDialog
        Text(sums.entry.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        val values = sums.values
        if (values == null) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                InfiniteProgressIndicator()
                Text(stringResource(R.string.files_computing), modifier = Modifier.padding(start = 12.dp))
            }
        } else {
            listOf("MD5", "SHA-1", "SHA-256").zip(values).forEach { (name, value) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(value)) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(name, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    Text(value, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            Text(stringResource(R.string.files_tap_to_copy), fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
        }
        TextButton(text = stringResource(R.string.files_close), onClick = vm::hideChecksum, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

@Composable
internal fun ApkInfoDialog(vm: FilesViewModel) {
    FilesDialog(show = true, title = stringResource(R.string.files_apk_info), onDismiss = vm::hideApkInfo) {
        val live by vm.uiState.collectAsState()
        val apk = live.apkInfo ?: return@FilesDialog
        Row(verticalAlignment = Alignment.CenterVertically) {
            apk.icon?.let { icon ->
                androidx.compose.foundation.Image(
                    bitmap = icon.toBitmap(96, 96).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(apk.label, fontSize = 16.sp)
                Text(apk.packageName, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.height(10.dp))
        PropRow(R.string.files_apk_version, "${apk.versionName ?: "?"} (${apk.versionCode})")
        PropRow(R.string.files_apk_sdk, "min ${apk.minSdk} · target ${apk.targetSdk}")
        apk.signer?.let { PropRow(R.string.files_apk_signer, it) }
        PropRow(R.string.files_apk_permissions, apk.permissions.size.toString())
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            apk.permissions.forEach { Text(it.removePrefix("android.permission."), fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary) }
        }
        TextButton(text = stringResource(R.string.files_close), onClick = vm::hideApkInfo, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

/** MT's search: name with * and ?, optional content; results open their folder. */
@Composable
internal fun SearchDialog(vm: FilesViewModel, close: () -> Unit) {
    var pattern by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val dismiss = {
        vm.closeSearch()
        close()
    }
    FilesDialog(show = true, title = stringResource(R.string.files_search), onDismiss = dismiss) {
        val live by vm.uiState.collectAsState()
        val search = live.search
        val plain = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
        Text(
            stringResource(R.string.files_search_in, live.activePanel.nav.path),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
        )
        Spacer(Modifier.height(6.dp))
        TextField(value = pattern, onValueChange = { pattern = it }, label = stringResource(R.string.files_search_name), singleLine = true, keyboardOptions = plain, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        TextField(value = content, onValueChange = { content = it }, label = stringResource(R.string.files_search_content), singleLine = true, keyboardOptions = plain, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(R.string.files_close), onClick = dismiss, modifier = Modifier.weight(1f))
            if (search?.running == true) {
                TextButton(text = stringResource(R.string.files_search_stop), onClick = vm::stopSearch, modifier = Modifier.weight(1f))
            } else {
                TextButton(
                    text = stringResource(R.string.files_search),
                    enabled = pattern.isNotBlank() || content.isNotEmpty(),
                    onClick = { vm.search(pattern, content) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
        when {
            search == null -> Unit
            search.running -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                InfiniteProgressIndicator()
                Text(stringResource(R.string.files_searching), modifier = Modifier.padding(start = 12.dp))
            }
            else -> {
                Text(stringResource(R.string.files_search_found, search.results.size), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(search.results.size) { i ->
                        val hit = search.results[i]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    vm.reveal(hit)
                                    close()
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(hit.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(FsPath.parent(hit.path), fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                    }
                }
            }
        }
    }
}
