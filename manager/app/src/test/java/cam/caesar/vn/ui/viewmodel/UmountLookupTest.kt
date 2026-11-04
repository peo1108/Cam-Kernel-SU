package cam.su.kernel.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UmountLookupTest {

    @Test
    fun knownUidIsAnsweredWithoutAskingRoot() {
        val asked = mutableListOf<Int>()
        val lookup = umountLookup(mapOf(10100 to true, 10101 to false)) { asked += it; false }
        assertTrue(lookup(10100))
        assertFalse(lookup(10101))
        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun unknownUidAsksRoot() {
        val asked = mutableListOf<Int>()
        val lookup = umountLookup(mapOf(10100 to false)) { asked += it; true }
        assertTrue(lookup(10200))
        assertEquals(listOf(10200), asked)
    }
}
