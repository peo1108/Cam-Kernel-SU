package cam.su.kernel.ui.intro

/**
 * What the start intro, playing on its own thread, needs to know about the app starting up
 * underneath it: whether the status card's 3D slimes are on the first page, when the app first
 * drew, and when the slimes got past their start-up hitches (engine, first shader compiles, the
 * first panorama, the outfits), so the intro opens onto slimes already running.
 * [cam.su.kernel.ui.CamActivity] and [cam.su.kernel.ui.screen.home.arena.StatusArena] report.
 */
internal object IntroGate {
    /** Null until the app knows its first page. */
    @Volatile
    var expectArena: Boolean? = null
        private set

    @Volatile
    var isShown = false
        private set

    @Volatile
    var isWarm = false
        private set

    /** A new intro is about to start: forget the last run's app. */
    fun reset() {
        expectArena = null
        isShown = false
        isWarm = false
    }

    fun expect(arena: Boolean) {
        expectArena = arena
    }

    fun shown() {
        isShown = true
    }

    fun warm() {
        isWarm = true
    }
}
