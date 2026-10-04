package me.weishu.kernelsu.ui.screen.features

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.CancellationException
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.glass.GlassDialog
import me.weishu.kernelsu.ui.util.SfsBuild
import me.weishu.kernelsu.ui.util.fetchSfsBuilds
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private sealed interface BuildsState {
    data object Loading : BuildsState
    data class Loaded(val builds: List<SfsBuild>) : BuildsState
    data class Failed(val message: String) : BuildsState
}

/** Lists the project's AnyKernel3 builds for [kmi]; the newest is preselected. */
@Composable
fun SfsBuildDialog(
    show: Boolean,
    kmi: String,
    onDismissRequest: () -> Unit,
    onSelected: (SfsBuild) -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<BuildsState>(BuildsState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by remember { mutableIntStateOf(0) }

    LaunchedEffect(show, kmi, attempt) {
        if (!show) return@LaunchedEffect
        state = BuildsState.Loading
        selected = 0
        state = try {
            BuildsState.Loaded(fetchSfsBuilds(kmi))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BuildsState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    GlassDialog(
        show = show,
        title = stringResource(R.string.gki_builds_title),
        summary = stringResource(R.string.gki_builds_kmi, kmi),
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 24.dp),
    ) {
        Column(modifier = Modifier.heightIn(max = 500.dp)) {
            when (val current = state) {
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

                is BuildsState.Failed -> Message(stringResource(R.string.gki_builds_error, current.message))

                is BuildsState.Loaded -> if (current.builds.isEmpty()) {
                    Message(stringResource(R.string.gki_builds_empty, kmi))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        itemsIndexed(current.builds) { index, build ->
                            val details = stringResource(
                                R.string.gki_build_summary,
                                build.tag,
                                Formatter.formatShortFileSize(context, build.size),
                                build.publishedAt.substringBefore('T'),
                            )
                            CheckboxPreference(
                                title = stringResource(R.string.gki_build_title, build.ksuVersion, build.susfsVersion),
                                summary = if (index == 0) {
                                    "${stringResource(R.string.gki_build_latest)} · $details"
                                } else {
                                    details
                                },
                                insideMargin = PaddingValues(horizontal = 30.dp, vertical = 16.dp),
                                checkboxLocation = CheckboxLocation.End,
                                checked = selected == index,
                                holdDownState = selected == index,
                                onCheckedChange = { selected = index },
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
                val loaded = state as? BuildsState.Loaded
                if (state is BuildsState.Failed) {
                    TextButton(
                        onClick = { attempt++ },
                        text = stringResource(R.string.gki_builds_retry),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                } else {
                    TextButton(
                        enabled = loaded != null && loaded.builds.isNotEmpty(),
                        onClick = {
                            loaded?.builds?.getOrNull(selected)?.let(onSelected)
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

/** Asks where the AnyKernel3 zip for the inactive slot comes from. */
@Composable
fun GkiSourceDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onLocal: () -> Unit,
    onProject: () -> Unit,
) {
    GlassDialog(
        show = show,
        title = stringResource(R.string.install_inactive_slot),
        summary = stringResource(R.string.gki_inactive_warning),
        onDismissRequest = onDismissRequest,
    ) {
        Column {
            TextButton(
                text = stringResource(R.string.gki_source_project),
                onClick = {
                    onDismissRequest()
                    onProject()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.gki_source_local),
                onClick = {
                    onDismissRequest()
                    onLocal()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(text = text, fontSize = 14.sp, color = colorScheme.onSurfaceVariantSummary)
    }
}
