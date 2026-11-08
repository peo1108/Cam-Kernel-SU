package cam.caesar.vn.attest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HarvesterTest {

    private fun record(moduleHash: String? = null) = HarvestRecord(
        verifiedBootKeyB64 = "QUJD",
        verifiedBootHashB64 = "REVG",
        moduleHashB64 = moduleHash,
        deviceLocked = true,
        verifiedBootState = 0,
        strongBoxAvailable = false,
        attestVersionTee = 400,
        attestVersionStrongBox = 300,
        osVersion = 160000,
        osPatchLevel = 202611,
        vendorPatchLevel = 20261105,
        bootPatchLevel = 20261105,
        fingerprint = "brand/device/x:16/ABC/123:user/release-keys",
    )

    @Test
    fun bootInfoJsonMatchesTheControlProtocol() {
        val o = JSONObject(record().toBootInfoJson())
        assertEquals("QUJD", o.getString("verifiedBootKey"))
        assertEquals("REVG", o.getString("verifiedBootHash"))
        assertEquals(true, o.getBoolean("deviceLocked"))
        assertEquals(0, o.getInt("verifiedBootState"))
        assertEquals(false, o.getBoolean("strongBoxAvailable"))
        assertEquals(400, o.getInt("attestVersionTee"))
        assertEquals(300, o.getInt("attestVersionStrongBox"))
    }

    @Test
    fun moduleHashOmittedWhenNull() {
        assertFalse(JSONObject(record(moduleHash = null).toBootInfoJson()).has("moduleHash"))
        assertEquals("TU9E", JSONObject(record(moduleHash = "TU9E").toBootInfoJson()).getString("moduleHash"))
    }

    @Test
    fun roundTripsThroughJson() {
        val r = record(moduleHash = "TU9E")
        assertEquals(r, HarvestRecord.fromJson(r.toJson()))
    }
}
