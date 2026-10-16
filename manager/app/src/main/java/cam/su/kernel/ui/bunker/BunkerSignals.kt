package cam.su.kernel.ui.bunker

import android.os.SystemClock

/**
 * What the app tells the bunker background, on the main thread: a list is being pulled to
 * refresh, root was granted or a module installed. Plain fields read while drawing, like
 * [cam.su.kernel.ui.slime.SlimeHome]; times are on [clock].
 */
internal object BunkerSignals {
    /** Seconds, shared by the signals and the drawing. */
    fun clock() = SystemClock.uptimeMillis() / 1000f

    /** How far a list is pulled down (0..1, 1 at the refresh threshold); 0 when nobody pulls. */
    var pull = 0f

    var dischargeAt = -100f
        private set

    var lockAt = -100f
        private set

    /** A pull to refresh went through. */
    fun discharge() {
        dischargeAt = clock()
        pull = 0f
    }

    /** Root granted to an app, or a module installed. */
    fun lock() {
        lockAt = clock()
    }
}
