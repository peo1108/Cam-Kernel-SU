package cam.su.kernel.files

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidIdsTest {

    @Test
    fun namesLikeLs() {
        assertEquals("root", AndroidIds.name(0))
        assertEquals("system", AndroidIds.name(1000))
        assertEquals("shell", AndroidIds.name(2000))
        assertEquals("media_rw", AndroidIds.name(1023))
        assertEquals("u0_a123", AndroidIds.name(10123))
        assertEquals("u10_a123", AndroidIds.name(1_010_123))
        assertEquals("u10_system", AndroidIds.name(1_001_000))
        assertEquals("u0_a123_cache", AndroidIds.name(20123))
        assertEquals("u0_a5_ext", AndroidIds.name(30005))
        assertEquals("all_a123", AndroidIds.name(50123))
        assertEquals("u0_i9000", AndroidIds.name(99000))
        assertEquals("5555", AndroidIds.name(5555))
        assertEquals("4294967295", AndroidIds.name(-1))
    }
}
