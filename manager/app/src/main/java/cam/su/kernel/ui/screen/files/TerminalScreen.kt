package cam.su.kernel.ui.screen.files

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import cam.su.kernel.ui.component.glass.GlassCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.R
import cam.su.kernel.files.FsPath
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.theme.isInDarkTheme
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TextStyle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import cam.su.kernel.ui.viewmodel.TerminalViewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * A real root terminal (MT's terminal, Termux's emulator): a PTY, colors, cursor keys, full
 * screen programs (vi, top), Ctrl+C. Opened in a folder, or running a script there with "Run as
 * root". The extra keys row gives what a soft keyboard lacks.
 */
@Composable
fun TerminalScreen(cwd: String, script: String?) {
    val vm = viewModel<TerminalViewModel>(key = "terminal:$cwd:$script")
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val density = LocalDensity.current
    LaunchedEffect(Unit) { vm.start(cwd, script) }

    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    // the terminal takes the theme's colors over the glass instead of a black box
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val dark = isInDarkTheme()
    val fg = colorScheme.onSurface.toArgb()
    val cursor = colorScheme.primary.toArgb()
    fun applyColors() {
        val palette = vm.session?.emulator?.mColors?.mCurrentColors ?: return
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = fg
        palette[TextStyle.COLOR_INDEX_CURSOR] = cursor
        if (!dark) {
            // white and yellow text vanish on a light glass: darker shades of them
            palette[7] = 0xFF505050.toInt()
            palette[15] = 0xFF2A2A2A.toInt()
            palette[3] = 0xFF9A7400.toInt()
            palette[11] = 0xFFB08600.toInt()
        }
        terminal?.onScreenUpdated()
    }
    vm.onColorsReset = ::applyColors

    var textSize by remember { mutableStateOf(with(density) { 14.sp.roundToPx() }) }
    var confirmLeave by remember { mutableStateOf(false) }
    val running = vm.exited == null
    val leave = { if (running) confirmLeave = true else navigator.pop() }
    BackHandler(enabled = running) { confirmLeave = true }

    // Typing goes through a hidden Compose field, not the terminal view: an Android view inside
    // Compose did not keep the keyboard's input connection (keys went nowhere), and a password
    // type field is what keeps Vietnamese IMEs from composing ("data" turned into "dât").
    val input = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun showKeyboard() {
        runCatching { input.requestFocus() }
        keyboard?.show()
    }

    val viewClient = remember {
        object : TerminalViewClient {
            private var scale = 1f

            override fun onScale(scale: Float): Float {
                // pinch: a step of text size each 15%
                if (scale < 0.85f || scale > 1.15f) {
                    textSize = (textSize + if (scale > 1f) 2 else -2).coerceIn(with(density) { 8.sp.roundToPx() }, with(density) { 32.sp.roundToPx() })
                    terminal?.setTextSize(textSize)
                    return 1f
                }
                return scale
            }

            override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
            override fun shouldBackButtonBeMappedToEscape() = false
            // TYPE_NULL: keyboards send plain keys. The char based type Termux offers instead
            // has no text class, and Vietnamese IMEs (Laban Telex) kept composing in it:
            // "data" arrived as "dât".
            override fun shouldEnforceCharBasedInput() = false
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
            override fun onLongPress(event: MotionEvent?) = false

            // the extra keys' CTRL / ALT apply to the next key, then let go
            override fun readControlKey(): Boolean = vm.ctrl.also { if (it) vm.ctrl = false }
            override fun readAltKey(): Boolean = vm.alt.also { if (it) vm.alt = false }
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
            override fun onEmulatorSet() = applyColors()
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
            override fun logStackTrace(tag: String?, e: Exception?) = Unit
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                SmallTopAppBar(
                    title = script?.let { FsPath.name(it) } ?: vm.title.ifEmpty { stringResource(R.string.terminal) },
                    color = barColor,
                    navigationIcon = {
                        GlassIconButton(onClick = dropUnlessResumed { leave() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colorScheme.onSurface)
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
                .padding(top = inner.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
                AndroidView(
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setTerminalViewClient(viewClient)
                            setTextSize(textSize)
                            // the app's font (Baloo 2); the renderer fits each glyph to its cell, so
                            // columns still line up
                            setTypeface(androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.baloo2_regular) ?: Typeface.MONOSPACE)
                            // keys reach the shell through the hidden field below
                            isFocusable = false
                            keepScreenOn = true
                            vm.attach(this)
                            vm.session?.let { attachSession(it) }
                            terminal = this
                        }
                    },
                    update = { view ->
                        vm.attach(view)
                        val s = vm.session
                        if (s != null && view.currentSession !== s) view.attachSession(s)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            vm.exited?.let { code ->
                Text(
                    text = stringResource(R.string.terminal_exited, code),
                    color = if (code == 0) colorScheme.primary else colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (running) {
                HiddenInput(vm, input)
                ExtraKeys(vm, terminal)
            }
        }
    }

    LaunchedEffect(terminal, vm.session) {
        val view = terminal ?: return@LaunchedEffect
        vm.session?.let { if (view.currentSession !== it) view.attachSession(it) }
        showKeyboard()
    }

    FilesDialog(show = confirmLeave, title = stringResource(R.string.terminal_leave_title), onDismiss = { confirmLeave = false }) {
        Text(stringResource(R.string.terminal_leave_message))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = stringResource(android.R.string.cancel), onClick = { confirmLeave = false }, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.script_stop_leave),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    confirmLeave = false
                    navigator.pop()
                },
            )
        }
    }
}

/**
 * What a soft keyboard does not have: ESC, TAB, CTRL, ALT, arrows, the usual shell symbols. Drawn
 * like the Files tab's toolbar: one glass card of round-cornered keys.
 */
@Composable
private fun ExtraKeys(vm: TerminalViewModel, view: TerminalView?) {
    fun key(code: Int) {
        view?.handleKeyCode(code, 0)
    }

    fun text(s: String) {
        val session = vm.session ?: return
        val bytes = s.toByteArray()
        session.write(bytes, 0, bytes.size)
    }
    // folded into one round button, like the Files toolbar; tapped, the keys slide out
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            modifier = Modifier.weight(1f, fill = false),
            enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.End) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.End) + androidx.compose.animation.fadeOut(),
        ) {
            GlassCard(cornerRadius = 24.dp, insideMargin = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExtraIcon(Icons.Rounded.ContentPaste) { vm.paste() }
                    ExtraKeysList(vm, ::key, ::text)
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        GlassCard(
            cornerRadius = 26.dp,
            insideMargin = PaddingValues(0.dp),
            colors = if (open) top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = colorScheme.primaryContainer, contentColor = colorScheme.onPrimaryContainer) else null,
            onClick = { open = !open },
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(if (open) Icons.Rounded.Close else Icons.Rounded.Keyboard, contentDescription = null, tint = colorScheme.onSurface, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ExtraKeysList(vm: TerminalViewModel, key: (Int) -> Unit, text: (String) -> Unit) {
            ExtraKey("ESC") { key(KeyEvent.KEYCODE_ESCAPE) }
            ExtraKey("TAB") { key(KeyEvent.KEYCODE_TAB) }
            ExtraKey("CTRL", held = vm.ctrl) { vm.ctrl = !vm.ctrl }
            ExtraKey("ALT", held = vm.alt) { vm.alt = !vm.alt }
            ExtraKey("^C") { vm.interrupt() }
            ExtraIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            ExtraIcon(Icons.Rounded.KeyboardArrowUp) { key(KeyEvent.KEYCODE_DPAD_UP) }
            ExtraIcon(Icons.Rounded.KeyboardArrowDown) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            ExtraIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            ExtraKey("HOME") { key(KeyEvent.KEYCODE_MOVE_HOME) }
            ExtraKey("END") { key(KeyEvent.KEYCODE_MOVE_END) }
            for (s in listOf("-", "/", "|", "~", "$", "&", ">", "*")) ExtraKey(s) { text(s) }
}

/** A key of the row, sized and shaped like the Files toolbar's buttons. */
@Composable
private fun ExtraKeyBox(held: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .height(44.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (held) colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ExtraKey(label: String, held: Boolean = false, onClick: () -> Unit) {
    ExtraKeyBox(held, onClick) {
        Text(
            label,
            color = if (held) colorScheme.onPrimaryContainer else colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = if (held) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ExtraIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ExtraKeyBox(held = false, onClick = onClick) {
        Icon(icon, contentDescription = null, tint = colorScheme.onSurface, modifier = Modifier.size(24.dp))
    }
}


private const val SENTINEL = " "

/**
 * The terminal's keyboard input: a 1 dp invisible text field that always holds one space. What
 * is typed after it goes to the shell (Enter as CR), deleting the space is Backspace (DEL), and
 * the field is reset after every change. CTRL and ALT from the extra keys apply to the next key.
 */
@Composable
private fun HiddenInput(vm: TerminalViewModel, focus: FocusRequester) {
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = { new ->
            val session = vm.session
            if (session != null) {
                if (new.text.length < SENTINEL.length) {
                    session.write(byteArrayOf(0x7f), 0, 1)
                } else {
                    val typed = new.text.removePrefix(SENTINEL)
                    var i = 0
                    while (i < typed.length) {
                        var cp = typed.codePointAt(i)
                        i += Character.charCount(cp)
                        if (cp == 10) cp = 13 // Enter: the terminal's CR, not LF
                        val alt = vm.alt.also { if (it) vm.alt = false }
                        if (vm.ctrl) {
                            vm.ctrl = false
                            // Ctrl+letter is the letter's control code (Ctrl+C = 3)
                            if (cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '@'.code..'_'.code) cp = cp and 0x1f
                        }
                        session.writeCodePoint(alt, cp)
                    }
                }
            }
            value = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focus),
    )
}
