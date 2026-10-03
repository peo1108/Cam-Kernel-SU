package me.weishu.kernelsu.ui.slime

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * Where the roaming slimes can stand: the cards on screen report their bounds here (in root
 * coordinates, updated as they scroll), and the slimes walk along their top edges.
 * Touched from the main thread only.
 */
internal object SlimeSurfaces {
    private val rects = LinkedHashMap<Any, Rect>()

    fun put(key: Any, rect: Rect) {
        rects[key] = rect
    }

    fun remove(key: Any) {
        rects.remove(key)
    }

    operator fun get(key: Any): Rect? = rects[key]

    fun forEach(block: (Any, Rect) -> Unit) = rects.forEach(block)
}

/** Makes this card a ledge the roaming slimes can walk on, hop onto and fall off. */
fun Modifier.slimeSurface(): Modifier = composed {
    val key = remember { Any() }
    DisposableEffect(key) { onDispose { SlimeSurfaces.remove(key) } }
    onGloballyPositioned { coords ->
        if (!coords.isAttached) return@onGloballyPositioned
        val p = coords.positionInRoot()
        SlimeSurfaces.put(key, Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height))
    }
}
