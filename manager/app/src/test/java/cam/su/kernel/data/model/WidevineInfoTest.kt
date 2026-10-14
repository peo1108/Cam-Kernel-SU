package cam.su.kernel.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidevineInfoTest {

    @Test
    fun readsTheProperties() {
        val props = mapOf(
            "securityLevel" to "L1",
            "systemId" to "16301",
            "version" to "18.0.0",
            "vendor" to "Google",
            "hdcpLevel" to "HDCP-2.3",
            "maxHdcpLevel" to "HDCP-2.3",
        )
        val info = WidevineInfo.fromProperties { props[it] }
        assertTrue(info.supported)
        assertTrue(info.isL1)
        assertEquals("16301", info.systemId)
        assertEquals(
            "Widevine: L1\nSystem ID: 16301\nVersion: 18.0.0\nVendor: Google\nHDCP: HDCP-2.3\nMax HDCP: HDCP-2.3",
            info.summary(),
        )
    }

    @Test
    fun blankAndFailedReadsAreLeftOut() {
        val info = WidevineInfo.fromProperties { name ->
            when (name) {
                "securityLevel" -> " L3 "
                "systemId" -> ""
                else -> throw IllegalStateException("no such property")
            }
        }
        assertEquals("L3", info.securityLevel)
        assertFalse(info.isL1)
        assertNull(info.systemId)
        assertNull(info.hdcpLevel)
        assertEquals("Widevine: L3", info.summary())
    }

    private val l1 = WidevineInfo(supported = true, securityLevel = "L1", provisioned = true)
    private val l3 = WidevineInfo(supported = true, securityLevel = "L3", provisioned = true)

    @Test
    fun diagnosis() {
        assertEquals(WidevineDiagnosis.L1, diagnoseWidevine(l1, halRunning = true, bootloaderLocked = false))
        assertEquals(WidevineDiagnosis.NoPlugin, diagnoseWidevine(WidevineInfo.None, halRunning = null, bootloaderLocked = null))
        assertEquals(WidevineDiagnosis.HalDown, diagnoseWidevine(WidevineInfo.None, halRunning = false, bootloaderLocked = true))
        assertEquals(WidevineDiagnosis.HalDown, diagnoseWidevine(l3, halRunning = false, bootloaderLocked = true))
        assertEquals(
            WidevineDiagnosis.NotProvisioned,
            diagnoseWidevine(l3.copy(provisioned = false), halRunning = true, bootloaderLocked = false),
        )
        assertEquals(WidevineDiagnosis.BootloaderUnlocked, diagnoseWidevine(l3, halRunning = true, bootloaderLocked = false))
        assertEquals(WidevineDiagnosis.KeyboxMissing, diagnoseWidevine(l3, halRunning = null, bootloaderLocked = true))
        assertEquals(WidevineDiagnosis.Unknown, diagnoseWidevine(l3, halRunning = true, bootloaderLocked = null))
    }

    @Test
    fun reprovisioningIsOfferedWhereItCanHelp() {
        assertFalse(WidevineDiagnosis.L1.reprovisionHelps)
        assertFalse(WidevineDiagnosis.NoPlugin.reprovisionHelps)
        assertFalse(WidevineDiagnosis.HalDown.reprovisionHelps)
        assertTrue(WidevineDiagnosis.NotProvisioned.reprovisionHelps)
        assertTrue(WidevineDiagnosis.BootloaderUnlocked.reprovisionHelps)
    }

    @Test
    fun noPlugin() {
        assertFalse(WidevineInfo.None.supported)
        assertEquals("Widevine: none", WidevineInfo.None.summary())
    }
}
