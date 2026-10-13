package cam.su.kernel.ui.screen.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.R
import cam.su.kernel.files.FsPath
import cam.su.kernel.files.Highlighter
import cam.su.kernel.files.SpanKind
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.theme.isInDarkTheme
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.viewmodel.EditorLimit
import cam.su.kernel.ui.viewmodel.EditorMessage
import cam.su.kernel.ui.viewmodel.TextEditorViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun TextEditorScreen(path: String) {
    val vm = viewModel<TextEditorViewModel>(key = path)
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) { vm.load(path) }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var fontSize by rememberSaveable { mutableStateOf(13f) }
    var confirmExit by remember { mutableStateOf(false) }
    var goToLine by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableStateOf(0) }

    /** Brings [offset] into view, a third down the screen. */
    fun reveal(offset: Int) {
        val l = layout ?: return
        val box = l.getCursorRect(offset.coerceIn(0, l.layoutInput.text.length))
        scope.launch {
            vScroll.animateScrollTo((box.top - viewport / 3).toInt().coerceAtLeast(0))
            if (!wrap) hScroll.animateScrollTo((box.left - 80).toInt().coerceAtLeast(0))
        }
    }

    val leave = { if (vm.modified) confirmExit = true else navigator.pop() }
    BackHandler(enabled = vm.modified) { confirmExit = true }

    LaunchedEffect(vm.message) {
        val msg = vm.message ?: return@LaunchedEffect
        val text = when (msg) {
            EditorMessage.Saved -> context.getString(R.string.editor_saved)
            EditorMessage.NotFound -> context.getString(R.string.editor_not_found)
            is EditorMessage.Failed -> errnoText(context, msg.errno) + (msg.detail?.let { "\n$it" } ?: "")
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        vm.message = null
    }

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val name = FsPath.name(path.substringAfterLast('/')).ifEmpty { path }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                SmallTopAppBar(
                    title = name + if (vm.modified) " *" else "",
                    color = barColor,
                    navigationIcon = {
                        GlassIconButton(onClick = dropUnlessResumed { leave() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colorScheme.onSurface)
                        }
                    },
                    actions = {
                        GlassButtonGroup {
                            BarButton(Icons.AutoMirrored.Rounded.Undo, enabled = vm.canUndo, onClick = vm::undo)
                            BarButton(Icons.AutoMirrored.Rounded.Redo, enabled = vm.canRedo, onClick = vm::redo)
                            BarButton(Icons.Rounded.Search) { searching = !searching }
                            BarButton(Icons.Rounded.Save, enabled = vm.modified && !vm.saving) { vm.save() }
                            BarButton(Icons.Rounded.MoreVert) { menu = true }
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            modifier = (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.ime.add(WindowInsets.systemBars.only(WindowInsetsSides.Bottom))),
        ) {
            StatusLine(vm)
            if (searching) {
                SearchBar(
                    query = query,
                    replacement = replacement,
                    readOnly = vm.readOnly,
                    onQuery = { query = it },
                    onReplacement = { replacement = it },
                    onFind = { forward -> vm.find(query, forward)?.let { reveal(it.first) } ?: run { vm.message = EditorMessage.NotFound } },
                    onReplace = { vm.replace(query, replacement)?.let { reveal(it.first) } },
                    onReplaceAll = {
                        val n = vm.replaceAll(query, replacement)
                        Toast.makeText(context, context.getString(R.string.editor_replaced, n), Toast.LENGTH_SHORT).show()
                    },
                )
            }
            when {
                vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() }
                vm.limit == EditorLimit.TOO_LARGE -> Message(stringResource(R.string.editor_too_large))
                vm.loadError != null -> Message(errnoText(context, vm.loadError!!))
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    viewport = with(LocalDensity.current) { maxHeight.roundToPx() }
                    EditorBody(vm, fontSize, wrap, vScroll, hScroll, viewport, onLayout = { layout = it })
                }
            }
        }
    }

    // ⋮ menu: go to line, word wrap, font size
    FilesDialog(show = menu, title = null, onDismiss = { menu = false }) {
        MenuRow(stringResource(R.string.editor_go_to_line)) {
            menu = false
            goToLine = true
        }
        MenuRow(stringResource(if (wrap) R.string.editor_wrap_off else R.string.editor_wrap_on)) {
            menu = false
            wrap = !wrap
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.editor_font_size), modifier = Modifier.weight(1f))
            TextButton(text = "A−", onClick = { fontSize = (fontSize - 1).coerceAtLeast(9f) })
            Text("${fontSize.toInt()}", modifier = Modifier.padding(horizontal = 12.dp))
            TextButton(text = "A+", onClick = { fontSize = (fontSize + 1).coerceAtMost(24f) })
        }
    }

    if (goToLine) {
        var line by remember { mutableStateOf("") }
        FilesDialog(show = true, title = stringResource(R.string.editor_go_to_line), onDismiss = { goToLine = false }) {
            TextField(
                value = line,
                onValueChange = { line = it.filter(Char::isDigit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(text = stringResource(android.R.string.cancel), onClick = { goToLine = false }, modifier = Modifier.weight(1f))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    enabled = line.toIntOrNull() != null,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        goToLine = false
                        reveal(vm.goToLine(line.toInt()))
                    },
                )
            }
        }
    }

    FilesDialog(show = confirmExit, title = stringResource(R.string.editor_unsaved_title), onDismiss = { confirmExit = false }) {
        Text(stringResource(R.string.editor_unsaved_message))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(android.R.string.cancel), onClick = { confirmExit = false }, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.editor_discard),
                onClick = {
                    confirmExit = false
                    navigator.pop()
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.editor_save),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    confirmExit = false
                    vm.save { navigator.pop() }
                },
            )
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    GlassIconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, tint = colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    TextButton(text = label, onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun StatusLine(vm: TextEditorViewModel) {
    val doc = vm.doc ?: return
    val lines = vm.value.text.count { it == '\n' } + 1
    val reason = when (vm.limit) {
        EditorLimit.ARCHIVE -> stringResource(R.string.editor_ro_archive)
        EditorLimit.BINARY -> stringResource(R.string.editor_ro_binary)
        EditorLimit.LARGE -> stringResource(R.string.editor_ro_large)
        else -> null
    }
    val parts = listOfNotNull(doc.charset, if (doc.crlf) "CRLF" else "LF", stringResource(R.string.editor_lines, lines), reason)
    Text(
        text = parts.joinToString("  ·  "),
        fontSize = 11.sp,
        color = if (reason != null) colorScheme.error else colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun SearchBar(
    query: String,
    replacement: String,
    readOnly: Boolean,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onFind: (Boolean) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    val plain = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = query, onValueChange = onQuery, label = stringResource(R.string.editor_find), singleLine = true, keyboardOptions = plain, modifier = Modifier.weight(1f))
            BarButton(Icons.Rounded.KeyboardArrowUp) { onFind(false) }
            BarButton(Icons.Rounded.KeyboardArrowDown) { onFind(true) }
        }
        if (!readOnly) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = replacement, onValueChange = onReplacement, label = stringResource(R.string.editor_replace_with), singleLine = true, keyboardOptions = plain, modifier = Modifier.weight(1f))
                TextButton(text = stringResource(R.string.editor_replace), onClick = onReplace, modifier = Modifier.padding(start = 6.dp))
                TextButton(text = stringResource(R.string.editor_replace_all), onClick = onReplaceAll, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun spanColor(kind: SpanKind): Color {
    val dark = isInDarkTheme()
    return when (kind) {
        SpanKind.COMMENT -> if (dark) Color(0xFF8A9199) else Color(0xFF7A828A)
        SpanKind.STRING -> if (dark) Color(0xFF9CCC65) else Color(0xFF2E7D32)
        SpanKind.KEYWORD -> if (dark) Color(0xFFCE93D8) else Color(0xFF8E24AA)
        SpanKind.NUMBER -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        SpanKind.KEY -> if (dark) Color(0xFF4DD0E1) else Color(0xFF00838F)
        SpanKind.TAG -> if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
        SpanKind.VARIABLE -> if (dark) Color(0xFFF48FB1) else Color(0xFFC2185B)
    }
}

/** Colors from [Highlighter] without changing the text (identity offsets). */
private class HighlightTransformation(
    private val lang: Highlighter.Lang,
    private val colors: Map<SpanKind, Color>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = AnnotatedString(
            text.text,
            spanStyles = Highlighter.spans(text.text, lang).map {
                AnnotatedString.Range(SpanStyle(color = colors.getValue(it.kind)), it.start, it.end)
            },
        )
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@Composable
private fun EditorBody(
    vm: TextEditorViewModel,
    fontSize: Float,
    wrap: Boolean,
    vScroll: androidx.compose.foundation.ScrollState,
    hScroll: androidx.compose.foundation.ScrollState,
    viewport: Int,
    onLayout: (TextLayoutResult) -> Unit,
) {
    val colors = SpanKind.entries.associateWith { spanColor(it) }
    val transformation = remember(vm.lang, vm.highlight, colors) {
        if (vm.highlight) HighlightTransformation(vm.lang, colors) else VisualTransformation.None
    }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp, color = colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = vm.value.text
    val lineStarts = remember(text) {
        buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
    }
    val digits = lineStarts.size.toString().length
    val gutterColor = colorScheme.onSurfaceVariantSummary
    val gutterStyle = style.copy(color = gutterColor, fontSize = (fontSize - 1).sp)
    val gutterWidth = with(LocalDensity.current) { (measurer.measure("0".repeat(digits), gutterStyle).size.width + 16.dp.toPx()).toDp() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScroll),
    ) {
        // Line numbers, drawn only for what is on screen so long files stay smooth.
        Box(
            modifier = Modifier
                .width(gutterWidth)
                .height(with(LocalDensity.current) { (layout?.size?.height ?: 0).toDp() })
                .background(colorScheme.onSurface.copy(alpha = 0.04f))
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val top = vScroll.value
                    val bottom = top + viewport
                    for ((index, start) in lineStarts.withIndex()) {
                        if (start > l.layoutInput.text.length) break
                        val y = l.getLineTop(l.getLineForOffset(start))
                        if (y < top - 100) continue
                        if (y > bottom) break
                        val label = measurer.measure((index + 1).toString(), gutterStyle)
                        drawText(label, topLeft = Offset(size.width - label.size.width - 6.dp.toPx(), y))
                    }
                },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(hScroll)),
        ) {
            BasicTextField(
                value = vm.value,
                onValueChange = vm::onValueChange,
                textStyle = style,
                readOnly = vm.readOnly,
                visualTransformation = transformation,
                cursorBrush = SolidColor(colorScheme.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                onTextLayout = {
                    layout = it
                    onLayout(it)
                },
                modifier = Modifier
                    .then(if (wrap) Modifier.fillMaxWidth() else Modifier)
                    .padding(start = 8.dp, end = 12.dp, bottom = 240.dp),
            )
        }
    }
}
