package cam.caesar.vn.ui.screen.files

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.caesar.vn.R
import cam.caesar.vn.files.FileEntry
import cam.caesar.vn.files.FileFormat
import cam.caesar.vn.files.FileKind
import cam.caesar.vn.files.PickDirs
import cam.caesar.vn.ui.component.glass.GlassCard
import cam.caesar.vn.ui.component.glass.GlassIconButton
import cam.caesar.vn.ui.component.listdetail.panePiece
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** What an in-app picker shows of one folder: where it is, and what it lists (already filtered). */
data class PickFolder(
    val path: String,
    val entries: List<FileEntry>,
    val listing: Boolean,
    val errno: Int?,
)

/**
 * The in-app file picker's rows, read through the root file service: a path bar with Up, Download
 * and internal storage, then the folders and the files [PickFolder.entries] kept. Used by the module
 * install pane (zips) and the attestation page (keybox.xml).
 */
internal fun LazyListScope.pickBrowser(
    folder: PickFolder,
    fileKind: FileKind,
    @StringRes emptyText: Int,
    isPicked: (FileEntry) -> Boolean,
    onUp: () -> Unit,
    onNavigate: (String) -> Unit,
    onPickFile: (FileEntry) -> Unit,
) {
    item(key = "pick_path") {
        GlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            insideMargin = PaddingValues(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = onUp, enabled = folder.path != "/") {
                    val direction = LocalLayoutDirection.current
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(R.string.module_install_up),
                        modifier = Modifier.graphicsLayer { if (direction == LayoutDirection.Rtl) scaleX = -1f },
                        tint = colorScheme.onSurface,
                    )
                }
                Text(
                    text = folder.path,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                GlassIconButton(onClick = { onNavigate(PickDirs.DOWNLOADS) }) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.module_install_downloads), tint = colorScheme.onSurface)
                }
                Spacer(Modifier.width(6.dp))
                GlassIconButton(onClick = { onNavigate(PickDirs.STORAGE) }) {
                    Icon(Icons.Rounded.SdStorage, contentDescription = stringResource(R.string.module_install_storage), tint = colorScheme.onSurface)
                }
            }
        }
    }
    items(folder.entries, key = { "pick:" + it.path }) { entry ->
        PickBrowserRow(entry, fileKind, picked = isPicked(entry)) {
            if (entry.isDir) onNavigate(entry.path) else onPickFile(entry)
        }
    }
    if (!folder.listing && folder.entries.isEmpty()) {
        item(key = "pick_empty") {
            val errno = folder.errno
            Text(
                text = if (errno != null) errnoText(LocalContext.current, errno) else stringResource(emptyText),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                fontSize = 14.sp,
                color = if (errno != null) colorScheme.error else colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun PickBrowserRow(entry: FileEntry, fileKind: FileKind, picked: Boolean, onClick: () -> Unit) {
    val details = buildString {
        if (entry.mtime > 0) append(FileFormat.date(entry.mtime))
        if (!entry.isDir) append("  ").append(FileFormat.size(entry.contentSize))
    }
    Row(
        modifier = Modifier
            .panePiece()
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (picked) colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(if (entry.isDir) FileKind.FOLDER else fileKind, link = entry.isLink, broken = entry.linkBroken)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(text = entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(text = details, fontSize = 10.sp, maxLines = 1, color = colorScheme.onSurfaceVariantSummary)
        }
        if (picked) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
