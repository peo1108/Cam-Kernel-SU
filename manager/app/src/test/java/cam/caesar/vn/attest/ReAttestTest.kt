package cam.caesar.vn.attest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReAttestTest {

    @Test
    fun onlyInstalledTargetsAreSelected() {
        // configured targets 10123, 10200; only 10123 is installed.
        val sel = installedTargets(configured = listOf(10123, 10200), installed = setOf(10123, 99999))
        assertEquals(listOf(10123), sel)
    }

    @Test
    fun nonTargetUidIsNeverSelected() {
        // 99999 is installed but not a configured target → must not be touched.
        val sel = installedTargets(configured = listOf(10123), installed = setOf(10123, 99999))
        assertFalse(sel.contains(99999))
    }

    @Test
    fun deduplicatesTargets() {
        assertEquals(listOf(10123), installedTargets(listOf(10123, 10123), setOf(10123)))
    }
}
