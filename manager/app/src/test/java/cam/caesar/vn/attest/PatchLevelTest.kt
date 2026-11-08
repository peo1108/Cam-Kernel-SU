package cam.caesar.vn.attest

import org.junit.Assert.assertEquals
import org.junit.Test

class PatchLevelTest {

    @Test
    fun osVersionEncoding() {
        assertEquals(160000, osVersionInt(16, 0, 0))
        assertEquals(160102, osVersionInt(16, 1, 2))
    }

    @Test
    fun osPatchLevelEncoding() {
        assertEquals(202611, osPatchLevelInt(2026, 11))
    }

    @Test
    fun datePatchLevelEncoding() {
        assertEquals(20261105, datePatchLevelInt(2026, 11, 5))
    }
}
