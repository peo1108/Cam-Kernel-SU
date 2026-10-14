package cam.su.kernel.ui.screen.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import cam.su.kernel.R
import cam.su.kernel.files.DiffRow
import cam.su.kernel.files.LineDiff
import cam.su.kernel.ui.component.glass.GlassButtonGroup
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.navigation3.LocalNavigator
import cam.su.kernel.ui.navigation3.Route
import cam.su.kernel.ui.theme.LocalEnableBlur
import cam.su.kernel.ui.theme.isInDarkTheme
import cam.su.kernel.ui.util.BlurredBar
import cam.su.kernel.ui.util.rememberBlurBackdrop
import cam.su.kernel.ui.viewmodel.DiffOutcome
import cam.su.kernel.ui.viewmodel.DiffViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * What [right] changed from [left], unified like `diff -u`: deleted lines red, inserted green,
 * the changed part of a pair marked, long unchanged stretches folded. Tapping a line opens its
 * file in the editor at that line.
 */
@Composable
fun DiffScreen(left: String, right: String) {
    val vm = viewModel<DiffViewModel>(key = "diff:$left:$right")
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(left, right) { vm.load(left, right) }
    val state = rememberLazyListState()

    fun jump(forward: Boolean) {
        val starts = vm.changeStarts
        if (starts.isEmpty()) return
        // a change is shown with one line above it
        val at = state.firstVisibleItemIndex + 1
        val target = if (forward) starts.firstOrNull { it > at } ?: starts.first() else starts.lastOrNull { it < at } ?: starts.last()
        scope.launch { state.animateScrollToItem((target - 1).coerceAtLeast(0)) }
    }

    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop) {
                SmallTopAppBar(
                    title = stringResource(R.string.files_compare),
                    color = barColor,
                    navigationIcon = {
                        GlassIconButton(onClick = dropUnlessResumed { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colorScheme.onSurface)
                        }
                    },
                    actions = {
                        val lines = vm.outcome == null && !vm.loading
                        GlassButtonGroup {
                            DiffButton(Icons.Rounded.KeyboardArrowUp, enabled = lines && vm.changeStarts.isNotEmpty()) { jump(false) }
                            DiffButton(Icons.Rounded.KeyboardArrowDown, enabled = lines && vm.changeStarts.isNotEmpty()) { jump(true) }
                            DiffButton(Icons.Rounded.SwapHoriz, enabled = !vm.loading, onClick = vm::swap)
                            DiffButton(if (vm.all) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore, enabled = lines, onClick = vm::toggleAll)
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
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
        ) {
            val colors = diffColors()
            SideLabel("\u2212", vm.left, colors.deletedSign)
            SideLabel("+", vm.right, colors.insertedSign)
            vm.summary?.takeIf { vm.outcome == null && !vm.loading }?.let {
                Text(
                    text = stringResource(R.string.diff_summary, it.added, it.deleted, it.changes),
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            val outcome = vm.outcome
            when {
                vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() }
                outcome != null -> DiffMessage(
                    when (outcome) {
                        DiffOutcome.Identical -> stringResource(R.string.diff_identical)
                        is DiffOutcome.SameText -> stringResource(R.string.diff_same_text, outcome.left, outcome.right)
                        DiffOutcome.Binary -> stringResource(R.string.diff_binary)
                        DiffOutcome.TooLarge -> stringResource(R.string.diff_too_large)
                        is DiffOutcome.Failed -> errnoText(context, outcome.errno)
                    },
                )
                else -> DiffLines(vm, state, colors) { path, line -> navigator.push(Route.TextEditor(path, line)) }
            }
        }
    }
}

private class DiffColors(
    val deleted: Color,
    val inserted: Color,
    val deletedMark: Color,
    val insertedMark: Color,
    val deletedSign: Color,
    val insertedSign: Color,
)

@Composable
private fun diffColors(): DiffColors = if (isInDarkTheme()) {
    DiffColors(Color(0x2EF85149), Color(0x2E3FB950), Color(0x66F85149), Color(0x663FB950), Color(0xFFF85149), Color(0xFF3FB950))
} else {
    DiffColors(Color(0x1FCF222E), Color(0x1F1A7F37), Color(0x4DCF222E), Color(0x4D1A7F37), Color(0xFFCF222E), Color(0xFF1A7F37))
}

@Composable
private fun DiffButton(icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    GlassIconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, tint = colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun SideLabel(sign: String, path: String, color: Color) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(sign, color = color, fontSize = 13.sp, modifier = Modifier.width(16.dp))
        Text(path, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun DiffMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DiffLines(
    vm: DiffViewModel,
    state: androidx.compose.foundation.lazy.LazyListState,
    colors: DiffColors,
    onOpen: (path: String, line: Int) -> Unit,
) {
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = colorScheme.onSurface)
    val numberStyle = style.copy(color = colorScheme.onSurfaceVariantSummary, fontSize = 11.sp, textAlign = TextAlign.End)
    val rows = vm.rows
    val widest = rows.maxOfOrNull { (it as? DiffRow.Line)?.let { line -> maxOf(line.aLine, line.bLine) } ?: 0 } ?: 0
    val measurer = rememberTextMeasurer()
    val numberWidth = with(LocalDensity.current) {
        (measurer.measure("0".repeat(widest.toString().length), numberStyle).size.width + 8.dp.toPx()).toDp()
    }
    val gutter = colorScheme.onSurface.copy(alpha = 0.04f)
    val unchanged = stringResource(R.string.diff_unchanged)
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)) {
        items(rows.size) { index ->
            when (val row = rows[index]) {
                is DiffRow.Fold -> Text(
                    text = "\u22EF  " + unchanged.format(row.count),
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(gutter)
                        .clickable { vm.expand(row) }
                        .padding(vertical = 8.dp),
                )

                is DiffRow.Line -> {
                    val (background, markColor, sign) = when (row.op) {
                        LineDiff.Op.DELETE -> Triple(colors.deleted, colors.deletedMark, "\u2212")
                        LineDiff.Op.INSERT -> Triple(colors.inserted, colors.insertedMark, "+")
                        LineDiff.Op.EQUAL -> Triple(Color.Transparent, Color.Transparent, " ")
                    }
                    val text = remember(row, markColor) {
                        val shown = if (row.text.length > MAX_SHOWN) row.text.take(MAX_SHOWN) + "\u2026" else row.text
                        val mark = row.mark?.takeIf { it.first < MAX_SHOWN }
                        if (mark == null) {
                            AnnotatedString(shown)
                        } else {
                            val end = minOf(mark.last + 1, MAX_SHOWN)
                            AnnotatedString(shown, spanStyles = listOf(AnnotatedString.Range(SpanStyle(background = markColor), mark.first, end)))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(background)
                            .clickable {
                                if (row.op == LineDiff.Op.DELETE) onOpen(vm.left, row.aLine) else onOpen(vm.right, row.bLine)
                            },
                    ) {
                        Row(Modifier.background(gutter)) {
                            BasicText(if (row.aLine > 0) row.aLine.toString() else "", style = numberStyle, modifier = Modifier.width(numberWidth))
                            BasicText(if (row.bLine > 0) row.bLine.toString() else "", style = numberStyle, modifier = Modifier.width(numberWidth))
                        }
                        BasicText(sign, style = style, modifier = Modifier.padding(horizontal = 6.dp))
                        BasicText(text, style = style, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    }
                }
            }
        }
    }
}

/** A minified file is one huge line: show its start, not megabytes of one row. */
private const val MAX_SHOWN = 4000
