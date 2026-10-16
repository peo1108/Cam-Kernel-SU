package cam.su.kernel.ui.bunker

import android.os.SystemClock

/**
 * What the app tells the bunker background, on the main thread: the tab changed, a list is being
 * pulled to refresh, root was granted or a module installed. Plain fields read while drawing, like
 * [cam.su.kernel.ui.slime.SlimeHome]; times are on [clock].
 */
internal object BunkerSignals {
    /** Seconds, shared by the signals and the drawing. */
    fun clock() = SystemClock.uptimeMillis() / 1000f

    /** Tabs turned so far (signed), the count before the last turn, and when it turned. */
    var turnSteps = 0
        private set
    var turnFrom = 0
        private set
    var turnAt = -100f
        private set
    private var page = -1

    /** How far a list is pulled down (0..1, 1 at the refresh threshold); 0 when nobody pulls. */
    var pull = 0f

    var dischargeAt = -100f
        private set

    var lockAt = -100f
        private set

    /** The main pager settled on [newPage]: turn one step toward it. */
    fun page(newPage: Int) {
        if (page >= 0 && newPage != page) {
            turnFrom = turnSteps
            turnSteps += if (newPage > page) 1 else -1
            turnAt = clock()
        }
        page = newPage
    }

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
