package cam.su.kernel.ui.intro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import cam.su.kernel.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/** The logo and its plates cut out, each with where its top left corner sits on the logo. */
internal class IntroArt(val logo: ImageBitmap, val plates: Array<ImageBitmap>, val plateAt: Array<Offset>)

/**
 * The Inner Veterans logo for the start intro, decoded and cut into its plates off the main thread
 * while the splash is up, and dropped once the intro is over (it is several MB of pixels).
 */
internal object IntroLogo {
    @Volatile
    var art: IntroArt? = null
        private set

    @Volatile
    var failed = false
        private set

    /** Done one way or the other: the intro can start, or give up. */
    val settled get() = art != null || failed

    fun preload(scope: CoroutineScope, context: Context) {
        val app = context.applicationContext
        art = null
        failed = false
        scope.launch(Dispatchers.IO) {
            val made = loadIntroArt(app)
            if (made != null) art = made else failed = true
        }
    }

    fun release() {
        art = null
    }
}

/** Decodes the logo and cuts its plates (blocking: call it off the main thread); null if that fails. */
internal fun loadIntroArt(context: Context): IntroArt? = runCatching {
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val logo = BitmapFactory.decodeResource(context.resources, R.drawable.intro_logo, options) ?: error("no logo")
    logo.prepareToDraw()
    cutPlates(logo)
}.getOrNull()

private fun cutPlates(logo: Bitmap): IntroArt {
    val plates = IntroTimeline.plates
    // Each plate's edge is grown 1.5 px (and anti-aliased), so neighbours overlap instead of
    // leaving a hairline seam between them once they have landed.
    val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    val picture = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    val bitmaps = arrayOfNulls<ImageBitmap>(plates.size)
    val at = arrayOfNulls<Offset>(plates.size)
    val aim = IntroGeometry.aim
    for ((i, p) in plates.withIndex()) {
        val path = Path()
        if (p.ring == 3) {
            path.addCircle(aim.x, aim.y, IntroGeometry.AIM_RADIUS, Path.Direction.CW)
        } else {
            path.moveTo(p.outline[0].x, p.outline[0].y)
            for (k in 1 until p.outline.size) path.lineTo(p.outline[k].x, p.outline[k].y)
            path.close()
            // the core keeps a socket for the reticle
            if (p.ring == 2) {
                path.fillType = Path.FillType.EVEN_ODD
                path.addCircle(aim.x, aim.y, IntroGeometry.AIM_RADIUS, Path.Direction.CW)
            }
        }
        val b = RectF()
        path.computeBounds(b, true)
        val left = floor(b.left - 3f).coerceAtLeast(0f)
        val top = floor(b.top - 3f).coerceAtLeast(0f)
        val right = ceil(b.right + 3f).coerceAtMost(logo.width.toFloat())
        val bottom = ceil(b.bottom + 3f).coerceAtMost(logo.height.toFloat())
        val bmp = Bitmap.createBitmap((right - left).toInt().coerceAtLeast(1), (bottom - top).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-left, -top)
        c.drawPath(path, mask)
        c.drawBitmap(logo, 0f, 0f, picture)
        bmp.prepareToDraw()
        bitmaps[i] = bmp.asImageBitmap()
        at[i] = Offset(left, top)
    }
    return IntroArt(logo.asImageBitmap(), bitmaps.requireNoNulls(), at.requireNoNulls())
}
