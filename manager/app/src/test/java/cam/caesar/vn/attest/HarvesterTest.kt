package cam.caesar.vn.attest

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarvesterTest {

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private val zero32 = b64(ByteArray(32))
    private val real32 = b64(ByteArray(32) { (it + 1).toByte() })
    private val hex32 = "ab".repeat(32)

    @Test
    fun keepsUsableCapturedBootKey() {
        assertEquals(real32, chooseBootKeyB64(real32, null))
        // A genuinely captured value wins over the property stand-in.
        assertEquals(real32, chooseBootKeyB64(real32, hex32))
    }

    @Test
    fun replacesAllZeroKeyWithPublicKeyDigest() {
        val out = chooseBootKeyB64(zero32, hex32)
        assertEquals(hexToUsableBootB64(hex32), out)
        assertNotEquals(zero32, out)
    }

    @Test
    fun fallsBackToStableConstantKeyWhenNothingUsable() {
        val a = chooseBootKeyB64(zero32, null)
        val b = chooseBootKeyB64(null, "00".repeat(32)) // all-zero hex is also unusable
        assertEquals(a, b) // deterministic constant, never random
        assertEquals(32, Base64.getDecoder().decode(a).size)
        assertNotEquals(zero32, a)
    }

    @Test
    fun replacesAllZeroHashWithComputedDigestThenFingerprint() {
        assertEquals(hexToUsableBootB64(hex32), chooseBootHashB64(zero32, hex32, "fp"))
        val f1 = chooseBootHashB64(zero32, null, "brand/a:16")
        val f2 = chooseBootHashB64(zero32, null, "brand/b:16")
        assertEquals(f1, chooseBootHashB64(null, null, "brand/a:16")) // per-device deterministic
        assertNotEquals(f1, f2) // differs across devices, so it is not a shared fingerprint
        assertEquals(32, Base64.getDecoder().decode(f1).size)
    }

    @Test
    fun usableBootBytesRejectsZeroWrongSizeAndGarbage() {
        assertNull(usableBootBytes(zero32))
        assertNull(usableBootBytes(b64(ByteArray(16) { 1 })))
        assertNull(usableBootBytes(null))
        assertNull(usableBootBytes("!!not base64"))
        assertTrue(usableBootBytes(real32) != null)
    }

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
