package cam.su.kernel.ui.component.skeleton

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cam.su.kernel.ui.component.glass.GlassListCard
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** The card a list shows; the placeholder copies its layout. */
enum class SkeletonKind { App, Module, RepoModule }

/**
 * Whether a list's first-load skeleton is on screen ([skeletonShown]): [loading] is true while
 * the list has no data yet. Only the first load counts; a screen that opens with data never shows
 * it, and refreshes keep the pull indicator.
 */
@Composable
fun rememberSkeletonShown(loading: Boolean): Boolean {
    val startedAt = remember { SystemClock.uptimeMillis() }
    var finishedAt by remember { mutableStateOf(if (loading) null else startedAt) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (!loading && finishedAt == null) finishedAt = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            shown = skeletonShown(startedAt, finishedAt, now)
            val next = skeletonNextChange(startedAt, finishedAt, now) ?: break
            delay(next - now)
        }
    }
    return shown
}

/**
 * Placeholder cards over a list that is still loading, fading out once it has data. Same padding
 * as the list it covers ([contentPadding]); it does not scroll.
 */
@Composable
fun SkeletonOverlay(
    visible: Boolean,
    kind: SkeletonKind,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    rows: Int = 8,
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val sweep = rememberSweep()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = false)
                .padding(contentPadding)
        ) {
            repeat(rows) { index ->
                when (kind) {
                    SkeletonKind.App -> AppCard(sweep, index)
                    SkeletonKind.Module -> ModuleCard(sweep, index, toggle = true)
                    SkeletonKind.RepoModule -> ModuleCard(sweep, index, toggle = false)
                }
            }
        }
    }
}

// one sweep for every block: 0..1 across a block every 1.2 s; null when animations are off
@Composable
private fun rememberSweep(): State<Float>? {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (reduceMotion) return null
    return rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonSweep",
    )
}

// bar widths vary a little from card to card, like real names do
private fun widthFor(index: Int, base: Float) = base - 0.12f * ((index * 7) % 3)

@Composable
private fun AppCard(sweep: State<Float>?, index: Int) {
    GlassListCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Block(sweep, Modifier.padding(end = 10.dp).size(48.dp), CircleShape)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bar(sweep, widthFor(index, 0.55f), 14.dp)
                Bar(sweep, widthFor(index, 0.75f), 11.dp)
            }
        }
    }
}

// name, version and author lines, then the description, like ModuleItem and the repo card
@Composable
private fun ModuleCard(sweep: State<Float>?, index: Int, toggle: Boolean) {
    GlassListCard(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Bar(sweep, widthFor(index, 0.4f), 16.dp)
                Bar(sweep, 0.18f, 10.dp)
                Bar(sweep, 0.22f, 10.dp)
            }
            if (toggle) Block(sweep, Modifier.width(48.dp).height(28.dp), RoundedCornerShape(14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Bar(sweep, widthFor(index, 0.8f), 11.dp)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (toggle) {
                // the action buttons ("Open" left, "Uninstall" right)
                Block(sweep, Modifier.width(64.dp).height(32.dp), RoundedCornerShape(16.dp))
                Spacer(Modifier.weight(1f))
                Block(sweep, Modifier.width(112.dp).height(32.dp), RoundedCornerShape(16.dp))
            } else {
                // the repo footer: stars left, release date right
                Block(sweep, Modifier.width(36.dp).height(10.dp), RoundedCornerShape(5.dp))
                Spacer(Modifier.weight(1f))
                Block(sweep, Modifier.width(72.dp).height(10.dp), RoundedCornerShape(5.dp))
            }
        }
    }
}

@Composable
private fun Bar(sweep: State<Float>?, fraction: Float, height: Dp) =
    Block(sweep, Modifier.fillMaxWidth(fraction).height(height), RoundedCornerShape(height / 2))

@Composable
private fun Block(sweep: State<Float>?, modifier: Modifier, shape: Shape) {
    val base = colorScheme.onSurface.copy(alpha = 0.08f)
    val highlight = colorScheme.onSurface.copy(alpha = 0.10f)
    // the sweep is read while drawing, so it only redraws, never recomposes
    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                drawRect(base)
                val progress = sweep?.value ?: return@drawBehind
                val band = maxOf(size.width, size.height) * 0.6f
                val x = -band + (size.width + 2 * band) * progress
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(x - band / 2, 0f),
                        end = Offset(x + band / 2, size.height),
                    )
                )
            }
    )
}
