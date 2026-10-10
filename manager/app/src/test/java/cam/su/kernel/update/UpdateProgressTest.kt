package cam.su.kernel.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class UpdateProgressTest {

    @Test
    fun megabytesUseTheLocaleDecimalSeparator() {
        assertEquals("8,2 / 21,4 MB", formatMegabytes(8_598_323, 22_439_526, Locale.forLanguageTag("vi")))
        assertEquals("8.2 / 21.4 MB", formatMegabytes(8_598_323, 22_439_526, Locale.US))
    }

    @Test
    fun unknownTotalShowsOnlyTheDownloadedPart() {
        assertEquals("0.5 MB", formatMegabytes(524_288, 0, Locale.US))
    }

    @Test
    fun stepsFollowTheState() {
        assertEquals(UpdateStep.DOWNLOAD, UpdateState.Downloading(10, 1, 10).step)
        assertEquals(UpdateStep.VERIFY, UpdateState.Verifying.step)
        assertEquals(UpdateStep.INSTALL, UpdateState.Installing.step)
        assertEquals(UpdateStep.INSTALL, UpdateState.Failed(UpdateFailure.INSTALL).step)
        assertEquals(UpdateStep.VERIFY, UpdateState.Failed(UpdateFailure.SIGNATURE).step)
        assertEquals(UpdateStep.DOWNLOAD, UpdateState.Failed(UpdateFailure.DOWNLOAD).step)
        assertNull(UpdateState.Idle.step)
    }
}
