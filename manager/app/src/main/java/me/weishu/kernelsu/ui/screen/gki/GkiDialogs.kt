package me.weishu.kernelsu.ui.screen.gki

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import me.weishu.kernelsu.ui.util.SfsBuild
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Lists the project's AnyKernel3 builds for [kmi]; the newest one is marked. */
@Composable
fun SfsBuildDialog(
    show: Boolean,
    kmi: String,
    state: BuildsState,
    selected: SfsBuild?,
    onDismissRequest: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (SfsBuild) -> Unit,
) {
    var choice by remember(show, selected) { mutableStateOf(selected) }

    GlassDialog(
        show = show,
        title = stringResource(R.string.gki_builds_title),
        summary = stringResource(R.string.gki_builds_kmi, kmi),
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 24.dp),
    ) {
        Column(modifier = Modifier.heightIn(max = 500.dp)) {
            when (state) {
                BuildsState.Loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfiniteProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.gki_builds_loading))
                }

                is BuildsState.Failed -> Message(stringResource(R.string.gki_builds_error, state.message))

                is BuildsState.Loaded -> if (state.builds.isEmpty()) {
                    Message(stringResource(R.string.gki_builds_empty, kmi))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        itemsIndexed(state.builds) { index, build ->
                            CheckboxPreference(
                                title = buildTitle(build),
                                summary = buildSummary(build, latest = index == 0),
                                insideMargin = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                                checkboxLocation = CheckboxLocation.End,
                                checked = choice == build,
                                holdDownState = choice == build,
                                onCheckedChange = { choice = build },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onDismissRequest,
                    text = stringResource(android.R.string.cancel),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                if (state is BuildsState.Failed) {
                    TextButton(
                        onClick = onRetry,
                        text = stringResource(R.string.gki_builds_retry),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                } else {
                    TextButton(
                        enabled = choice != null,
                        onClick = {
                            choice?.let(onSelected)
                            onDismissRequest()
                        },
                        text = stringResource(R.string.confirm),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun buildTitle(build: SfsBuild): String =
    stringResource(R.string.gki_build_title, build.ksuVersion, build.susfsVersion)

@Composable
internal fun buildSummary(build: SfsBuild, latest: Boolean): String {
    val details = stringResource(
        R.string.gki_build_summary,
        build.tag,
        Formatter.formatShortFileSize(LocalContext.current, build.size),
        build.publishedAt.substringBefore('T'),
    )
    return if (latest) "${stringResource(R.string.gki_build_latest)} · $details" else details
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(text = text, fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}
