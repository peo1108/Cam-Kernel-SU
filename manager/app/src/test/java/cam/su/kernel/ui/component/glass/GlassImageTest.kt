package cam.su.kernel.ui.component.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassImageTest {

    @Test
    fun portraitDownscaled() = assertEquals(1080 to 2400, downscaleTarget(2160, 4800, 2400))

    @Test
    fun neverUpscales() = assertEquals(500 to 800, downscaleTarget(500, 800, 2400))

    @Test
    fun hugeImage() = assertEquals(1067 to 2400, downscaleTarget(4000, 9000, 2400))

    @Test
    fun landscapeDownscaled() = assertEquals(2400 to 1350, downscaleTarget(3840, 2160, 2400))

    @Test
    fun degenerateImage() = assertEquals(1 to 2400, downscaleTarget(1, 9000, 2400))
}
