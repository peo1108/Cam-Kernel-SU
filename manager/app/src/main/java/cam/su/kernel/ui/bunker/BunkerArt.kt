package cam.su.kernel.ui.bunker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cam.su.kernel.ui.intro.IntroArt
import cam.su.kernel.ui.intro.loadIntroArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One copy of the logo and its plates for every page showing the bunker (each page paints its
 * own background), cut on first use and dropped when the last of them goes away (about 11 MB).
 */
internal object BunkerArt {
    private val lock = Mutex()
    private var art: IntroArt? = null
    private var users = 0

    /** Called on the main thread, like [hold] and [drop]; only the cutting runs on IO. */
    suspend fun get(context: Context): IntroArt? = lock.withLock {
        art ?: withContext(Dispatchers.IO) { loadIntroArt(context.applicationContext) }.also { if (users > 0) art = it }
    }

    fun hold() {
        users++
    }

    fun drop() {
        users--
        if (users <= 0) {
            users = 0
            art = null
        }
    }
}

/** The bunker's pictures, null until they are cut. */
@Composable
internal fun rememberBunkerArt(): IntroArt? {
    val context = LocalContext.current
    var art by remember { mutableStateOf<IntroArt?>(null) }
    DisposableEffect(Unit) {
        BunkerArt.hold()
        onDispose { BunkerArt.drop() }
    }
    LaunchedEffect(Unit) { art = BunkerArt.get(context) }
    return art
}
