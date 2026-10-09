package cam.su.kernel.ui.component.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassContrastTest {

    @Test
    fun brightImageInDarkModeIsDimmedEnough() =
        assertTrue(effectiveDim(userDim = 0.2f, meanLuma = 0.95f, dark = true) >= 0.55f)

    @Test
    fun darkImageInLightModeIsLightenedEnough() =
        assertTrue(effectiveDim(userDim = 0.2f, meanLuma = 0.05f, dark = false) >= 0.55f)

    @Test
    fun matchingImageKeepsUserDim() {
        assertEquals(0.2f, effectiveDim(userDim = 0.2f, meanLuma = 0.1f, dark = true), 0.001f)
        assertEquals(0.2f, effectiveDim(userDim = 0.2f, meanLuma = 0.9f, dark = false), 0.001f)
    }

    @Test
    fun neverExceedsMaxDim() =
        assertTrue(effectiveDim(userDim = 0.6f, meanLuma = 1f, dark = true) <= 0.75f)

    @Test
    fun higherUserDimIsKept() =
        assertEquals(0.6f, effectiveDim(userDim = 0.6f, meanLuma = 0.5f, dark = true), 0.001f)
}
