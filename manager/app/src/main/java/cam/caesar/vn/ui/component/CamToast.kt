package cam.su.kernel.ui.component

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.util.concurrent.atomic.AtomicInteger

/**
 * Short messages as an iOS style banner: a pill that drops from the top and leaves after a moment
 * (swipe it up to send it away). Shown by [CamToastHost] while the Manager is on screen; with no
 * host resumed (WebUI, background) it falls back to a system toast. Safe to call from any thread.
 */
object CamToast {
    internal data class Message(val text: CharSequence, val long: Boolean, val id: Long = System.nanoTime())

    internal val messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    internal val resumedHosts = AtomicInteger(0)

    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        if (resumedHosts.get() > 0 && messages.tryEmit(Message(text, long))) return
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun show(context: Context, @StringRes text: Int, long: Boolean = false) = show(context, context.getString(text), long)
}

/** Draws [CamToast] banners over the app. Place once at the root, above the navigation. */
@Composable
fun CamToastHost() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var counted = false
        val observer = LifecycleEventObserver { _, _ ->
            val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed != counted) {
                counted = resumed
                if (resumed) CamToast.resumedHosts.incrementAndGet() else CamToast.resumedHosts.decrementAndGet()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (counted) CamToast.resumedHosts.decrementAndGet()
        }
    }

    var current by remember { mutableStateOf<CamToast.Message?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        CamToast.messages.collect {
            current = it
            visible = true
        }
    }
    // a new message restarts the timer; the last one shown leaves after its time
    LaunchedEffect(current?.id) {
        val message = current ?: return@LaunchedEffect
        delay(if (message.long) 3500 else 2200)
        visible = false
    }

    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 420f)) { -it * 2 } + fadeIn(tween(160)),
            exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 600f)) { -it * 2 } + fadeOut(tween(200)),
        ) {
            val shape = RoundedCornerShape(percent = 50)
            Text(
                text = current?.text?.toString().orEmpty(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier
                    .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                    .widthIn(max = 520.dp)
                    .shadow(16.dp, shape, clip = false)
                    .background(colorScheme.surface.copy(alpha = 0.96f), shape)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, amount ->
                            if (amount < -4f) {
                                change.consume()
                                visible = false
                            }
                        }
                    }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
    }
}
