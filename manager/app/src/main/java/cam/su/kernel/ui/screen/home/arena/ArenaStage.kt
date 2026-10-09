package cam.su.kernel.ui.screen.home.arena

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import cam.su.kernel.ui.theme.AppFontFamily
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

internal const val MOCHI = 0
internal const val BO = 1
internal const val SODA = 2
internal const val CHANH = 3
internal const val SLIME_COUNT = 4
internal val PI_F = PI.toFloat()

/** The diorama box in world units: camera, glass front and back wall distances along z. */
/**
 * Where the diorama's walls bend, as fractions of the panorama's width: the left wall runs from 0
 * to [cornerFrom], its rounded corner to [cornerTo], then the back wall to 1 - cornerTo, and the
 * right side mirrors the left.
 */
internal class WallSplit(val cornerFrom: Float, val cornerTo: Float)

internal object Box {
    const val CAMERA = 15f
    const val GLASS = 2.6f
    const val BACK = -5.5f
}

/** Left-to-right order on stage: big Bơ at the edge so the middle stays clear of the word. */
internal val Lineup = intArrayOf(BO, MOCHI, SODA, CHANH)

/** Size-dependent layout of the stage, rebuilt whenever the card is resized. */
internal class Stage(val w: Float, val h: Float, val px: Float) {
    private val sizeSum = Cast.sumOf { it.size.toDouble() }.toFloat()
    val r = min(h * 0.175f, w * 0.9f / (1.96f * sizeSum + 2.4f))
    val radius = FloatArray(SLIME_COUNT) { r * Cast[it].size }
    val groundY = h * 0.88f
    /** How deep in the diorama box each slime stands (world units, + toward the glass). */
    val homeDepth = floatArrayOf(0.35f, -0.9f, -0.35f, 0.55f)
    val center = w / 2f
    val homes = FloatArray(SLIME_COUNT).also { homes ->
        val spacing = r * 0.8f
        val total = radius.sum() * 1.96f + spacing * (SLIME_COUNT - 1)
        var x = center - total / 2f
        Lineup.forEach { k ->
            homes[k] = x + radius[k] * 0.98f
            x += radius[k] * 1.96f + spacing
        }
    }
    val textY = h * 0.27f
    val brawl = Offset(center, groundY - r * 1.05f)
    val leftmost get() = Lineup.first()
    val rightmost get() = Lineup.last()

    /** Screen y of the camera's eye height: things at other depths scale about (center, eyeY). */
    val eyeY = groundY - (h - groundY) * (Box.CAMERA - Box.GLASS) / Box.GLASS

    /** On-screen scale of something standing [depth] world units in front of the action plane. */
    fun viewScale(depth: Float) = Box.CAMERA / (Box.CAMERA - depth)
}

/** A status string split into graphemes, measured once so letters can fly one by one. */
internal class GlyphRun(
    val glyphs: List<TextLayoutResult>,
    val offsets: FloatArray,
    val width: Float,
    val height: Float,
    val label: TextLayoutResult,
    val retch: TextLayoutResult,
    /** Indices of the glyphs that can be eaten, left to right (spaces are skipped). */
    val eatList: IntArray,
)

internal fun graphemes(text: String): List<String> {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}

internal fun measureRun(measurer: TextMeasurer, lang: ArenaLanguage, maxWidth: Float, density: Density): GlyphRun {
    val parts = graphemes(lang.text)
    val dp = density.density
    fun style(size: Float) = TextStyle(
        brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFFFF1E0))),
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        shadow = Shadow(Color(0x66000000), Offset(0f, 2f * dp), 10f * dp),
    )

    fun measureAll(size: Float) = parts.map { measurer.measure(it, style(size), softWrap = false, maxLines = 1, density = density) }
    var layouts = measureAll(40f)
    var width = layouts.sumOf { it.size.width }.toFloat()
    if (width > maxWidth) {
        layouts = measureAll(max(14f, 40f * maxWidth / width))
        width = layouts.sumOf { it.size.width }.toFloat()
    }
    val offsets = FloatArray(layouts.size)
    var x = 0f
    layouts.forEachIndexed { i, l ->
        offsets[i] = x
        x += l.size.width
    }
    val label = measurer.measure(
        lang.label,
        TextStyle(color = Color.White, fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.6.sp),
        softWrap = false,
        maxLines = 1,
        density = density,
    )
    val retch = measurer.measure(
        lang.retch,
        TextStyle(color = Color(0xFF2A2440), fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp),
        softWrap = false,
        maxLines = 1,
        density = density,
    )
    val eat = parts.indices.filter { parts[it].isNotBlank() }.toIntArray()
    return GlyphRun(layouts, offsets, width, layouts.maxOf { it.size.height }.toFloat(), label, retch, eat)
}

internal fun DrawScope.drawGlyph(layout: TextLayoutResult, center: Offset, scale: Float, rotation: Float, alpha: Float) {
    withTransform({
        rotate(rotation, center)
        scale(scale, scale, center)
    }) {
        drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f), alpha = alpha.coerceIn(0f, 1f))
    }
}
