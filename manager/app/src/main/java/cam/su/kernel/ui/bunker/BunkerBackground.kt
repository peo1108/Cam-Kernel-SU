package cam.su.kernel.ui.bunker

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cam.su.kernel.ui.intro.IntroPaths
import cam.su.kernel.ui.intro.IntroText
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.abs

/**
 * The page background of type "Inner Veterans": the command bunker, alive. Runs only while
 * [animate] (not while the page slides) and the app is in front; still with system animations off.
 * [blur] and [dim] are the user's background settings. The logo stays as it is on a light theme
 * too (no wash): it is a dark picture by design.
 */
@Composable
internal fun BunkerBackground(animate: Boolean, blur: Dp, dim: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val art = rememberBunkerArt()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val tilt = rememberDeviceTilt()
    val run = remember { BunkerRun() }
    val frame = remember { BunkerFrame() }
    val paths = remember { IntroPaths() }
    val light = remember { BunkerPaths() }
    val text = remember { IntroText() }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(animate, resumed, art, still) {
        if (art == null || !animate || !resumed || still) return@LaunchedEffect
        var last = -1L
        var drawn = -1L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (last < 0L) 0f else ((nanos - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = nanos
                val t = tilt.value
                run.tilt.update(t.gravityX, t.gravityY, dt)
                // 30 frames a second while idle, every frame while something is happening
                if (drawn < 0L || run.busy(BunkerSignals.clock()) || nanos - drawn >= IDLE_FRAME_NANOS) {
                    drawn = nanos
                    tick++
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (blur.value > 0f) {
                        Modifier.graphicsLayer {
                            val px = blur.toPx()
                            renderEffect = BlurEffect(px, px, TileMode.Clamp)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            tick
            val a = art
            if (a == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            run.fill(frame, BunkerSignals.clock())
            drawBunker(a, frame, paths, light, text)
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

private const val IDLE_FRAME_NANOS = 32_000_000L

/** The bunker's cold light: a pale ice blue (the hottest parts are white). */
internal val ColdBody = Color(0xFFDDF6FF)

/** A bunker's own clockwork: its random events and the tilt. */
internal class BunkerRun {
    val tilt = TiltFollower()
    private var nextEventAt = Float.NaN
    private var eventIndex = 0
    private var eventStart = -100f
    private var eventKind = -1

    /** Something is moving that wants every frame, not just 30 a second. */
    fun busy(now: Float): Boolean =
        eventKind >= 0 ||
            now - BunkerSignals.turnAt < 1f ||
            BunkerSignals.pull > 0f ||
            now - BunkerSignals.dischargeAt < 1f ||
            now - BunkerSignals.lockAt < 2.5f ||
            abs(tilt.x) + abs(tilt.y) > 0.02f ||
            BunkerMotion.sweepAt(now) >= 0f

    fun fill(f: BunkerFrame, now: Float) {
        if (nextEventAt.isNaN()) nextEventAt = now + FIRST_EVENT
        if (eventKind >= 0 && now - eventStart > lengthOf(eventKind)) eventKind = -1
        if (eventKind < 0 && now >= nextEventAt) {
            eventKind = BunkerMotion.eventKind(eventIndex)
            eventStart = now
            nextEventAt = now + BunkerMotion.eventGap(eventIndex)
            eventIndex++
        }
        f.t = now
        f.tiltX = tilt.x
        f.tiltY = tilt.y
        f.angle = BunkerMotion.turnAngle(now, BunkerSignals.turnAt, BunkerSignals.turnFrom, BunkerSignals.turnSteps)
        f.turnAge = now - BunkerSignals.turnAt
        f.pull = BunkerSignals.pull
        f.dischargeAge = now - BunkerSignals.dischargeAt
        f.lockAge = now - BunkerSignals.lockAt
        f.event = eventKind
        f.eventAge = now - eventStart
        f.eventSeed = eventIndex * 7 + 3
    }

    private fun lengthOf(kind: Int) = if (kind == BunkerMotion.SHOT) BunkerMotion.SHOT_LEN else BunkerMotion.POP_LEN


    private companion object {
        /** The first event comes soon after the page opens, then every 20 to 40 s. */
        const val FIRST_EVENT = 8f
    }
}

/** Reports how far [state]'s list is pulled to the bunker, so its frame charges up. */
@Composable
internal fun ReportPullToBunker(state: PullToRefreshState) {
    LaunchedEffect(state) {
        snapshotFlow { state.pullProgress }.collect { BunkerSignals.pull = it.coerceIn(0f, 1f) }
    }
    DisposableEffect(state) {
        onDispose { BunkerSignals.pull = 0f }
    }
}
