package cam.su.kernel.ui.screen.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.R
import cam.su.kernel.files.FsPath
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.viewmodel.ScriptViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** MT's "Execute": a script run as root, its output as it comes, Stop and Run again. */
@Composable
fun ScriptScreen(path: String) {
    val vm = viewModel<ScriptViewModel>(key = "script:$path")
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(path) { vm.start(path) }

    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (vm.running) confirmLeave = true else navigator.pop() }
    BackHandler(enabled = vm.running) { confirmLeave = true }

    val listState = rememberLazyListState()
    // follow the output while the end is in view; scrolling up to read stops following
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
            if (scrolling) follow = !more
        }
    }
    val lines = vm.lines
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                SmallTopAppBar(
                    title = FsPath.name(path),
                    color = if (backdrop != null) Color.Transparent else colorScheme.surface,
                    navigationIcon = {
                        GlassIconButton(onClick = dropUnlessResumed { leave() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colorScheme.onSurface)
                        }
                    },
                    actions = {
                        GlassButtonGroup {
                            GlassIconButton(onClick = {
                                clipboard.setText(AnnotatedString(vm.text()))
                                Toast.makeText(context, R.string.script_copied, Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.script_copy), tint = colorScheme.onSurface)
                            }
                            if (vm.running) {
                                GlassIconButton(onClick = vm::stop) {
                                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.script_stop), tint = colorScheme.error)
                                }
                            } else {
                                GlassIconButton(onClick = vm::run) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.script_rerun), tint = colorScheme.onSurface)
                                }
                            }
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding()),
        ) {
            val code = vm.exitCode
            val status = when {
                vm.error != null -> errnoText(context, vm.error!!.errno) + (vm.error!!.message?.let { ": $it" } ?: "")
                vm.running -> stringResource(R.string.script_running)
                code == -1 -> stringResource(R.string.script_lost)
                code != null -> stringResource(R.string.script_exit, code)
                else -> ""
            }
            Text(
                text = status,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    vm.error != null || (code != null && code != 0) -> colorScheme.error
                    code == 0 -> colorScheme.primary
                    else -> colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 48.dp),
                ) {
                    items(lines.size) { i ->
                        Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            // stdin: a line at a time, as typed at a prompt; EOF ends a `while read` loop
            if (vm.running && !vm.inputClosed) {
                var typed by remember { mutableStateOf("") }
                val send = {
                    vm.send(typed)
                    typed = ""
                    follow = true
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = stringResource(R.string.script_input),
                        singleLine = true,
                        // plain input: Vietnamese IMEs would turn "dd" into "đ" in a command
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(onClick = send) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.script_send), tint = colorScheme.primary)
                    }
                    TextButton(text = "EOF", onClick = vm::endInput)
                }
            } else {
                Row(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {}
            }
        }
    }

    FilesDialog(show = confirmLeave, title = stringResource(R.string.script_leave_title), onDismiss = { confirmLeave = false }) {
        Text(stringResource(R.string.script_leave_message))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(android.R.string.cancel), onClick = { confirmLeave = false }, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.script_stop_leave),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    confirmLeave = false
                    vm.stop()
                    navigator.pop()
                },
            )
        }
    }
}
