package cam.su.kernel.ui.bunker

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import cam.su.kernel.ui.intro.IntroArt
import cam.su.kernel.ui.intro.IntroPaths
import cam.su.kernel.ui.intro.IntroText
import cam.su.kernel.ui.intro.loadIntroArt

/**
 * The bunker as the device's live wallpaper, in its own process (":wallpaper", so the app's setup
 * and its signals stay out of it). The logo sits above the launcher's dock ([bunkerWallpaperFit]).
 * It draws on its own thread, only while it is seen, 30 frames a second while idle like the page
 * background; with system animations off it draws once.
 */
class BunkerWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = BunkerEngine()

    private inner class BunkerEngine : Engine(), Choreographer.FrameCallback, SensorEventListener {
        private val thread = HandlerThread("bunker-wallpaper").apply { start() }
        private val handler = Handler(thread.looper)
        private lateinit var choreographer: Choreographer
        private val sensors = getSystemService(SensorManager::class.java)
        private val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        private val still =Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        // touched on the drawing thread only
        private var art: IntroArt? = null
        private val run = BunkerRun()
        private val frame = BunkerFrame()
        private val paths = IntroPaths()
        private val light = BunkerPaths()
        private val text = IntroText()
        private val scope = CanvasDrawScope()
        private var running = false
        private var last = -1L
        private var drawn = -1L
        private var gx = 0f
        private var gy = 1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            handler.post {
                choreographer = Choreographer.getInstance()
                art = BunkerWallpaperArt.get(applicationContext)
                if (isVisible) start() else drawOnce()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            handler.post { if (visible) start() else stop() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            handler.post {
                if (isVisible) start()
                drawOnce()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            handler.post { drawOnce() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // wait for the drawing thread to let go of the surface before it is torn down
            val done = CountDownLatch(1)
            handler.post {
                stop()
                done.countDown()
            }
            done.await(500, TimeUnit.MILLISECONDS)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.post {
                stop()
                art = null
                BunkerWallpaperArt.drop()
            }
            thread.quitSafely()
            super.onDestroy()
        }

        private fun start() {
            if (running || art == null) return
            if (still) {
                drawOnce()
                return
            }
            running = true
            last = -1L
            drawn = -1L
            gravity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
            choreographer.postFrameCallback(this)
        }

        private fun stop() {
            if (!running) return
            running = false
            choreographer.removeFrameCallback(this)
            sensors?.unregisterListener(this)
        }

        override fun doFrame(nanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            val dt = if (last < 0L) 0f else ((nanos - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = nanos
            run.tilt.update(gx, gy, dt)
            if (drawn < 0L || run.busy(BunkerSignals.clock()) || nanos - drawn >= IDLE_NANOS) {
                drawn = nanos
                draw()
            }
        }

        private fun drawOnce() {
            if (!running) draw()
        }

        private fun draw() {
            val a = art ?: return
            val holder = surfaceHolder
            if (holder.surface?.isValid != true) return
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                val size = Size(canvas.width.toFloat(), canvas.height.toFloat())
                run.fill(frame, BunkerSignals.clock())
                scope.draw(Density(resources.displayMetrics.density), LayoutDirection.Ltr, Canvas(canvas), size) {
                    drawBunker(a, frame, paths, light, text, bunkerWallpaperFit(size.width, size.height))
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            // the reading points up; gravity across the screen, x right and y down, for how it is turned
            val x = -event.values[0] / SensorManager.GRAVITY_EARTH
            val y = event.values[1] / SensorManager.GRAVITY_EARTH
            when (display?.rotation) {
                Surface.ROTATION_90 -> { gx = y; gy = -x }
                Surface.ROTATION_180 -> { gx = -x; gy = -y }
                Surface.ROTATION_270 -> { gx = -y; gy = x }
                else -> { gx = x; gy = y }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    companion object {
        private const val IDLE_NANOS = 32_000_000L

        /** Opens the system's preview of this wallpaper, where the user sets it; false if there is none. */
        fun open(context: Context): Boolean {
            val change = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, BunkerWallpaperService::class.java))
            val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            for (intent in listOf(change, chooser)) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (_: ActivityNotFoundException) {
                }
            }
            return false
        }
    }
}

/** One copy of the logo for every engine (the system runs a second one for a preview). */
private object BunkerWallpaperArt {
    private var art: IntroArt? = null
    private var users = 0

    @Synchronized
    fun get(context: Context): IntroArt? {
        users++
        return art ?: loadIntroArt(context).also { art = it }
    }

    @Synchronized
    fun drop() {
        users--
        if (users <= 0) {
            users = 0
            art = null
        }
    }
}
