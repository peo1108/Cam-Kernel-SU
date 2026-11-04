package cam.su.kernel.ui.component.pagerise

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSwitchTest {

    /** Frames as fast as they are asked for, 16 ms apart. */
    private class TestClock : MonotonicFrameClock {
        private var now = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            now += 16_000_000L
            return onFrame(now)
        }
    }

    private fun withClock(block: suspend CoroutineScope.() -> Unit) = runBlocking { withContext(TestClock(), block) }

    @Test
    fun aTapHidesThePageItGoesTo() {
        val switch = PageSwitch(3, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), animationsOff = false)
        switch.prepare(0, 1)
        assertEquals(0f, switch.page(1).t, 0f)
        assertEquals(1f, switch.page(0).t, 0f)
    }

    @Test
    fun aTapThatNeverStartedDoesNotLeaveItsPageHidden() {
        val switch = PageSwitch(3, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), animationsOff = false)
        switch.prepare(0, 1)
        switch.prepare(0, 2)
        assertEquals(1f, switch.page(1).t, 0f)
        assertEquals(0f, switch.page(2).t, 0f)
    }

    @Test
    fun goingBackToThePageInSightHidesNothing() {
        val switch = PageSwitch(3, CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), animationsOff = false)
        switch.prepare(0, 1)
        switch.prepare(0, 0)
        assertEquals(1f, switch.page(0).t, 0f)
        assertEquals(1f, switch.page(1).t, 0f)
    }

    @Test
    fun aFingerOnThePagerCancellingTheJumpLeavesThePageInSightAsItWas() = withClock {
        val switch = PageSwitch(3, this, animationsOff = false)
        switch.prepare(0, 1)
        try {
            switch.switch(0, 1) { throw CancellationException("a finger took the pager") }
        } catch (_: CancellationException) {
            // as the tap's job sees it
        }
        assertEquals(0f, switch.page(0).sink, 0f)
        assertEquals(1f, switch.page(1).t, 0f)
    }

    @Test
    fun aSwitchFadesOutThenTheNewPageRises() = withClock {
        val switch = PageSwitch(3, this, animationsOff = false)
        switch.prepare(0, 1)
        var sinkAtJump = 0f
        switch.switch(0, 1) { sinkAtJump = switch.page(0).sink }
        assertEquals(1f, sinkAtJump, 0f)
        assertEquals(0f, switch.page(0).sink, 0f)
        // the rise runs on the scope once the switch has returned
        assertTrue(switch.page(1).t < 1f)
    }

    @Test
    fun withAnimationsOffItJustJumps() = withClock {
        val switch = PageSwitch(3, this, animationsOff = true)
        switch.prepare(0, 1)
        var jumped = false
        switch.switch(0, 1) { jumped = true }
        assertTrue(jumped)
        assertEquals(1f, switch.page(1).t, 0f)
        assertEquals(0f, switch.page(0).sink, 0f)
    }
}
