package cam.su.kernel.ui.component.listdetail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneMotionTest {

    // animations off: no clock runs, only the layout bookkeeping is left to check
    private fun motion(split: Boolean) = PaneMotion(CoroutineScope(Dispatchers.Unconfined), animationsOff = true).apply {
        applySplit(split) { emptyMap() }
    }

    @Test
    fun theOpenCardStaysOpenInTheFrameThePanesSplit() {
        val motion = motion(split = false)
        assertTrue(motion.inlineOpen(split = false))
        // the frame the layout turns to two panes composes before the split's motion starts:
        // closing the card there and opening it again a frame later made it flicker
        assertTrue(motion.inlineOpen(split = true))
        motion.applySplit(true) { emptyMap() }
        assertFalse(motion.inlineOpen(split = true))
    }

    @Test
    fun theOpenCardWaitsInTheFrameThePanesJoin() {
        val motion = motion(split = true)
        assertFalse(motion.inlineOpen(split = true))
        assertFalse(motion.inlineOpen(split = false))
        motion.applySplit(false) { emptyMap() }
        assertTrue(motion.inlineOpen(split = false))
    }
}
