package cam.su.kernel.ui.component.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private class EntranceState(val fromAbove: Boolean, val staggerSteps: Int) {
    val progress = Animatable(0f)
    var started = false
}

/**
 * Slides a lazy-list card into view the first time it is placed, from the side it scrolled in
 * from, so long lists glide like macOS ones. Prefetched items are composed but not placed, so the
 * animation only starts once the card actually reaches the viewport. Cards visible on the first
 * frame are staggered by their position.
 *
 * Only translation and alpha are animated: glass cards track their layer position to sample the
 * page background, and a scale would shrink the sampled region along with the card.
 */
@Composable
fun Modifier.cardEntrance(listState: LazyListState, index: Int): Modifier {
    val distance = with(LocalDensity.current) { 56.dp.toPx() }
    val scope = rememberCoroutineScope()
    val state = remember {
        val untouched = !listState.lastScrolledForward && !listState.lastScrolledBackward
        EntranceState(
            fromAbove = listState.lastScrolledBackward,
            staggerSteps = if (untouched) (index - listState.firstVisibleItemIndex).coerceIn(0, 8) else 0,
        )
    }
    return this
        .onPlaced {
            if (state.started) return@onPlaced
            state.started = true
            scope.launch {
                if (state.staggerSteps > 0) delay((state.staggerSteps * 40).milliseconds)
                state.progress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
        .graphicsLayer {
            val p = state.progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * distance * if (state.fromAbove) -1f else 1f
        }
}
