package cam.su.kernel.ui.component.hero

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroMathTest {

    @Test
    fun progressMapsDepthToZeroOne() {
        assertEquals(0f, heroProgress(-1f), 0.001f)
        assertEquals(0.5f, heroProgress(-0.5f), 0.001f)
        assertEquals(1f, heroProgress(0f), 0.001f)
        assertEquals(0f, heroProgress(-1.5f), 0.001f)
        assertEquals(1f, heroProgress(0.4f), 0.001f)
    }

    @Test
    fun noTargetIsGone() =
        assertEquals(HeroPhase.Gone, heroPhase(-0.5f, running = true, sourceAttached = true, targetAttached = false))

    @Test
    fun coveredByAnotherPageIsDocked() =
        assertEquals(HeroPhase.Docked, heroPhase(0.5f, running = true, sourceAttached = true, targetAttached = true))

    @Test
    fun landedIsDocked() =
        assertEquals(HeroPhase.Docked, heroPhase(0f, running = false, sourceAttached = true, targetAttached = true))

    @Test
    fun movingIsFlying() {
        assertEquals(HeroPhase.Flying, heroPhase(-0.3f, running = true, sourceAttached = true, targetAttached = true))
        // a back swipe that has just started: still at depth 0 but running
        assertEquals(HeroPhase.Flying, heroPhase(0f, running = true, sourceAttached = true, targetAttached = true))
    }

    @Test
    fun missingSourceFades() =
        assertEquals(HeroPhase.FadingOut, heroPhase(-0.3f, running = true, sourceAttached = false, targetAttached = true))

    @Test
    fun hiddenElementsPerPhase() {
        assertTrue(HeroPhase.Flying.hidesSource() && HeroPhase.Flying.hidesTarget())
        assertTrue(!HeroPhase.FadingOut.hidesSource() && HeroPhase.FadingOut.hidesTarget())
        for (p in listOf(HeroPhase.Docked, HeroPhase.Gone)) assertTrue(!p.hidesSource() && !p.hidesTarget())
    }

    @Test
    fun visibleFractionOfIcon() {
        val full = Rect(0f, 0f, 100f, 100f)
        assertEquals(1f, visibleFraction(full, full), 0.001f)
        assertEquals(0.5f, visibleFraction(full, Rect(0f, 50f, 100f, 100f)), 0.001f)
        assertEquals(0f, visibleFraction(full, Rect.Zero), 0.001f)
        assertEquals(0f, visibleFraction(Rect.Zero, Rect.Zero), 0.001f)
    }

    @Test
    fun launchNeedsAnimationsAndAVisibleIcon() {
        assertTrue(heroCanLaunch(animationsOff = false, sourceVisible = 0.95f))
        assertFalse(heroCanLaunch(animationsOff = false, sourceVisible = 0.89f))
        assertFalse(heroCanLaunch(animationsOff = true, sourceVisible = 1f))
    }
}
