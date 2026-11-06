package cam.caesar.vn.ui.util

import android.app.Activity
import android.os.Handler
import android.os.Looper

/**
 * Hands the status card's 3D engine from an activity being recreated to the one that replaces
 * it. Freeing a Filament engine and building it again in the same process at once crashed natively (row 73 of docs/CAM_CHANGES.md). The manifest keeps most
 * changes in the same activity, but not all: a wallpaper colour or overlay change, a new SIM,
 * bold text, recreate(). One that nobody takes within [BACKGROUND_RELEASE_MS] is freed, as it
 * would be in the background. Main thread only.
 */
class EngineCarry<T : Any>(private val alive: (T) -> Boolean, private val free: (T) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var parked: T? = null
    private val expire = Runnable { take()?.let(free) }

    /** The engine the activity before this one left, if any. */
    fun take(): T? = parked.also {
        parked = null
        handler.removeCallbacks(expire)
    }

    /** [engine] leaves the screen: kept for the next activity when [activity] is being recreated, freed otherwise. */
    fun letGo(engine: T, activity: Activity?) {
        if (activity?.isChangingConfigurations != true || !alive(engine)) {
            free(engine)
            return
        }
        take()?.let(free)
        parked = engine
        handler.postDelayed(expire, BACKGROUND_RELEASE_MS)
    }
}
