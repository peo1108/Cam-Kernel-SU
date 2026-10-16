package cam.su.kernel.ui.intro

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import cam.su.kernel.ui.intro.IntroTimeline.BLAST_AT
import cam.su.kernel.ui.intro.IntroTimeline.REVEAL_LEN

/**
 * Plays the start intro on its own thread, into a [SurfaceView] laid over the whole window, so
 * the app starting up underneath it (root, the first composition, the 3D slimes) cannot make it
 * stutter. The clock starts once the window is first on screen ([windowShown]); a tap skips to
 * the opening; [onDone] runs on the main thread when the opening is over, or the intro had to stop.
 */
internal class IntroPlayer(context: Context, private val onDone: () -> Unit) : SurfaceHolder.Callback {
    val view: SurfaceView = SurfaceView(context)

    /** Set on the main thread once the window has drawn: the intro is visible from here on. */
    @Volatile
    var windowShown = false

    @Volatile
    private var tapped = false

    @Volatile
    private var stopped = false

    private val thread = HandlerThread("cam-intro", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val main = Handler(Looper.getMainLooper())

    // The surface, written on the main thread, drawn into on the intro thread.
    private val lock = Any()
    private var surface: Surface? = null
    private var width = 0f
    private var height = 0f

    // Intro thread only.
    private val clock = IntroClock()
    private val scope = CanvasDrawScope()
    private val paths = IntroPaths()
    private val text = IntroText()
    private val density = Density(context.resources.displayMetrics.density)
    private var lastDrawn = -1L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (stopped) return
            if (step(nanos)) finish() else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        view.setZOrderOnTop(true)
        view.holder.setFormat(PixelFormat.TRANSLUCENT)
        view.holder.addCallback(this)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Nothing underneath gets a touch while the intro is up; a tap skips to the opening.
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) tapped = true
            true
        }
        Handler(thread.looper).post { Choreographer.getInstance().postFrameCallback(frame) }
    }

    /** Stops at once (the activity is going away); [onDone] is not called. */
    fun stop() {
        stopped = true
        thread.quitSafely()
    }

    private fun finish() {
        stopped = true
        thread.quitSafely()
        main.post(onDone)
    }

    /** One vsync on the intro thread; true once the intro is over. */
    private fun step(nanos: Long): Boolean {
        val art = IntroLogo.art
        if (art == null) return IntroLogo.failed
        // No closer than 7 ms apart (every other vsync at 165 Hz): the app starting up needs the CPU.
        if (lastDrawn >= 0L && nanos - lastDrawn < MIN_FRAME_NANOS) return false
        synchronized(lock) {
            val s = surface ?: return false
            val done = clock.advance(nanos, windowShown, tapped)
            clock.buzz()?.let { buzz(it) }
            val canvas = try {
                s.lockHardwareCanvas()
            } catch (e: Exception) {
                return false
            }
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                scope.draw(density, LayoutDirection.Ltr, Canvas(canvas), Size(width, height)) {
                    drawIntro(art, clock.t, clock.revealAt, paths, text)
                }
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
            lastDrawn = nanos
            return done
        }
    }

    private fun buzz(constant: Int) {
        // a one-way call into the window session; fine off the main thread
        try {
            view.performHapticFeedback(constant)
        } catch (_: Exception) {
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) { surface = holder.surface }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        synchronized(lock) {
            surface = holder.surface
            width = w.toFloat()
            height = h.toFloat()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Waits for a frame being drawn; the user left (or it was taken off): the intro is over.
        synchronized(lock) { surface = null }
        if (!stopped) finish()
    }

    private companion object {
        const val MIN_FRAME_NANOS = 7_000_000L
    }
}

/** The intro's clock and what it waits on; advanced on the intro thread. */
internal class IntroClock {
    var t = 0f
        private set
    var revealAt = Float.NaN
        private set
    private var last = -1L
    private var shownAt = Float.NaN
    private var warmAt = Float.NaN
    private var buzzed = 0L

    /** Moves to frame time [nanos] (the clock only runs once [visible]); true once the opening is over. */
    fun advance(nanos: Long, visible: Boolean, tapped: Boolean): Boolean {
        if (!visible) return false
        // A late frame slows the intro down rather than skipping a beat.
        if (last >= 0L) t += ((nanos - last) / 1_000_000_000f).coerceAtMost(MAX_STEP)
        last = nanos
        if (shownAt.isNaN() && IntroGate.isShown) shownAt = t
        if (warmAt.isNaN() && IntroGate.isWarm) warmAt = t
        if (revealAt.isNaN()) {
            val start = if (tapped) {
                t
            } else {
                IntroTimeline.revealStart(t, shownAt.takeUnless { it.isNaN() }, IntroGate.expectArena ?: true, warmAt.takeUnless { it.isNaN() })
            }
            if (start != null && t >= start) revealAt = start
        }
        return !revealAt.isNaN() && t >= revealAt + REVEAL_LEN
    }

    /** The haptic for this frame, if any: a tick per rivet and for the reticle locking, a thump for the blast. */
    fun buzz(): Int? {
        if (!revealAt.isNaN()) return null
        val rivets = IntroTimeline.rivets
        for (i in rivets.indices) if (once(i, rivets[i].time)) return HapticFeedbackConstants.CONTEXT_CLICK
        if (once(RETICLE_BIT, IntroTimeline.RETICLE_AT)) return HapticFeedbackConstants.CONTEXT_CLICK
        if (once(BLAST_BIT, BLAST_AT)) return HapticFeedbackConstants.LONG_PRESS
        return null
    }

    private fun once(bit: Int, at: Float): Boolean {
        val mask = 1L shl bit
        if (t < at || buzzed and mask != 0L) return false
        buzzed = buzzed or mask
        return true
    }

    private companion object {
        const val MAX_STEP = 0.05f
        const val RETICLE_BIT = 40
        const val BLAST_BIT = 41
    }
}
