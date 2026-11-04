package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import cam.su.kernel.R
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FsPath
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val ROW = 16
private const val CHUNK = 64 * 1024

/** Read-only hex dump, 16 bytes a row; 64 KB chunks are read from root as they scroll in. */
@Composable
fun HexViewerScreen(path: String) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableLongStateOf(-1L) }
    val chunks = remember { mutableStateMapOf<Long, ByteArray>() }
    val listState = rememberLazyListState()
    var goTo by remember { mutableStateOf(false) }

    // contentSize: for a link, the size of the file it points to
    LaunchedEffect(path) { size = runCatching { CamFileClient.stat(path)?.contentSize }.getOrNull() ?: 0L }
    // load the chunks under the visible rows
    LaunchedEffect(path, size) {
        if (size <= 0) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }.collect { (first, count) ->
            val from = first.toLong() * ROW / CHUNK
            val to = (first + count + 1).toLong() * ROW / CHUNK
            for (c in from..to) {
                if (c * CHUNK >= size || c in chunks) continue
                chunks[c] = ByteArray(0) // loading
                launch { chunks[c] = runCatching { CamFileClient.readRange(path, c * CHUNK, CHUNK) }.getOrDefault(ByteArray(0)) }
            }
        }
    }

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val rows = if (size > 0) ((size + ROW - 1) / ROW).toInt() else 0

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                SmallTopAppBar(
                    title = FsPath.name(path),
                    color = if (backdrop != null) Color.Transparent else colorScheme.surface,
                    navigationIcon = {
                        GlassIconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colorScheme.onSurface)
                        }
                    },
                    actions = {
                        GlassButtonGroup {
                            GlassIconButton(onClick = { goTo = true }) {
                                Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.hex_go_to), tint = colorScheme.onSurface)
                            }
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Box((if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).fillMaxSize().padding(top = inner.calculateTopPadding())) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(rows) { row ->
                    val offset = row.toLong() * ROW
                    val chunk = chunks[offset / CHUNK]
                    val inChunk = (offset % CHUNK).toInt()
                    val bytes = if (chunk != null && chunk.size > inChunk) chunk.copyOfRange(inChunk, minOf(inChunk + ROW, chunk.size)) else null
                    HexRow(offset, bytes)
                }
            }
        }
    }

    if (goTo) {
        var text by remember { mutableStateOf("") }
        val target = parseOffset(text)
        FilesDialog(show = true, title = stringResource(R.string.hex_go_to), onDismiss = { goTo = false }) {
            TextField(
                value = text,
                onValueChange = { text = it.trim() },
                label = stringResource(R.string.hex_offset_hint),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(text = stringResource(android.R.string.cancel), onClick = { goTo = false }, modifier = Modifier.weight(1f))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    enabled = target != null && target < size,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        goTo = false
                        scope.launch { listState.scrollToItem((target!! / ROW).toInt()) }
                    },
                )
            }
        }
    }
}

/** "0x1a2b" or "6699". */
fun parseOffset(text: String): Long? =
    if (text.startsWith("0x", ignoreCase = true)) text.drop(2).toLongOrNull(16) else text.toLongOrNull()

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

/** [digits] uppercase hex digits of [value]; String.format per byte is too slow for a fling. */
private fun StringBuilder.appendHex(value: Long, digits: Int): StringBuilder {
    for (shift in (digits - 1) * 4 downTo 0 step 4) append(HEX_DIGITS[((value shr shift) and 0xF).toInt()])
    return this
}

@Composable
private fun HexRow(offset: Long, bytes: ByteArray?) {
    val hex = StringBuilder(ROW * 3 + 2)
    val ascii = StringBuilder(ROW)
    for (i in 0 until ROW) {
        val b = bytes?.getOrNull(i)
        if (b == null) hex.append("  ") else hex.appendHex(b.toLong() and 0xFF, 2)
        hex.append(if (i == 7) "  " else " ")
        ascii.append(
            when {
                b == null -> ' '
                b in 0x20..0x7E -> b.toInt().toChar()
                else -> '.'
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val digits = maxOf(8, (64 - java.lang.Long.numberOfLeadingZeros(offset) + 3) / 4)
        Text(StringBuilder(digits).appendHex(offset, digits).toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colorScheme.primary)
        Text("  $hex ", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(ascii.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}
