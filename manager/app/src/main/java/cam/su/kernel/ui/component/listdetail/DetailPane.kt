package cam.su.kernel.ui.component.listdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cam.su.kernel.R
import cam.su.kernel.ui.component.glass.GlassCard
import cam.su.kernel.ui.component.glass.GlassIconButton
import cam.su.kernel.ui.util.BlurredBar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * A detail beside a list: a small bar with [title] (and a close button when [onClose] is set), a
 * [header] pinned under it, where a picked row's icon and name land, and [content] scrolling
 * below. Nothing scrolls under the bar, so it stays clear glass. Inside [PaneDetail], the glass
 * cards of [content] come out of the picked row; the header's card stays put and its text, marked
 * [paneReveal], shows as the icon and name land.
 */
@Composable
fun DetailPaneScaffold(
    title: String,
    onClose: (() -> Unit)?,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(null) {
                SmallTopAppBar(
                    title = title,
                    color = Color.Transparent,
                    navigationIcon = {
                        if (onClose != null) {
                            GlassIconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = pageHorizontalInsets(),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val start = innerPadding.calculateStartPadding(layoutDirection)
        val end = innerPadding.calculateEndPadding(layoutDirection)
        Column(Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
            if (header != null) Box(Modifier.padding(start = start, end = end)) { PaneHeader(header) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(start = start, end = end, bottom = bottomPadding + 12.dp),
                overscrollEffect = null,
                content = content,
            )
        }
    }
}

/** One line of an overview card: what is counted and how many. */
class OverviewLine(val title: String, val value: String)

/** The card a pane shows while nothing is picked: a few counts over the whole list. */
@Composable
fun OverviewCard(lines: List<OverviewLine>, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        lines.forEach { line ->
            BasicComponent(
                title = line.title,
                endActions = {
                    Text(
                        text = line.value,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantActions,
                    )
                },
            )
        }
    }
}

/** A quiet line under the overview saying what picking a row does. */
@Composable
fun PaneHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = modifier.panePiece().padding(horizontal = 28.dp, vertical = 4.dp),
    )
}
