package cam.su.kernel.ui.util

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** How long the app may sit in the background before its 3D engines are let go. */
const val BACKGROUND_RELEASE_MS = 10_000L

/**
 * For GPU-heavy objects (the Filament engines of the status card and the roaming slimes, a few
 * hundred MB between them) that should not stay alive while the app sits in the background:
 * [release] runs [graceMillis] after the activity stops, and the returned generation changes when
 * it starts again after a release, so `remember(generation) { create() }` makes a fresh one.
 *
 * [release] runs from a lifecycle observer, not from composition: Compose pauses recomposition
 * while the activity is stopped, so nothing keyed on state would be disposed until it is back.
 * The released object is still referenced by composition until then: its methods must do nothing
 * once released (frame loops run once more before recomposition swaps it).
 */
@Composable
fun rememberBackgroundRelease(graceMillis: Long = BACKGROUND_RELEASE_MS, release: () -> Unit): Int {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRelease by rememberUpdatedState(release)
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val handler = Handler(Looper.getMainLooper())
        var released = false
        val doRelease = Runnable {
            released = true
            latestRelease()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> handler.postDelayed(doRelease, graceMillis)
                Lifecycle.Event.ON_START -> {
                    handler.removeCallbacks(doRelease)
                    if (released) {
                        released = false
                        generation++
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            handler.removeCallbacks(doRelease)
            lifecycle.removeObserver(observer)
        }
    }
    return generation
}
