package cam.caesar.vn.attest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverTest {

    private val harvest = HarvestRecord(
        verifiedBootKeyB64 = "QUJD", verifiedBootHashB64 = "REVG", moduleHashB64 = null,
        deviceLocked = true, verifiedBootState = 0, strongBoxAvailable = false,
        attestVersionTee = 400, attestVersionStrongBox = 300,
        osVersion = 160000, osPatchLevel = 202611, vendorPatchLevel = 20261105, bootPatchLevel = 20261105,
        fingerprint = "brand/x",
    )

    @Test
    fun pushJsonHasBootInfoAndProfile() {
        val profile = AttestProfile(id = "default", keyboxB64 = "S0VZ", securityLevel = 1, packages = listOf("com.x"))
        val push = resolvePush(listOf(profile), harvest) { listOf(10123 to 0) }
        val o = JSONObject(push)
        assertEquals("config", o.getString("type"))
        assertEquals(0, o.getJSONObject("bootInfo").getInt("verifiedBootState"))
        assertEquals("QUJD", o.getJSONObject("bootInfo").getString("verifiedBootKey"))
        val p = o.getJSONArray("profiles").getJSONObject(0)
        assertEquals("default", p.getString("id"))
        assertEquals("S0VZ", p.getString("keyboxB64"))
        assertEquals(1, p.getInt("securityLevel"))
        assertEquals(160000, p.getInt("osVersion"))
        assertEquals(20261105, p.getInt("bootPatchLevel"))
    }

    @Test
    fun workProfileCloneGetsTwoUidEntries() {
        val profile = AttestProfile(id = "default", keyboxB64 = "S0VZ", securityLevel = 1, packages = listOf("com.x"))
        // com.x resolves to uid 10123 (user 0) and its work-profile clone 1010123 (user 10).
        val push = resolvePush(listOf(profile), harvest) { listOf(10123 to 0, 1010123 to 10) }
        val p = JSONObject(push).getJSONArray("profiles").getJSONObject(0)
        val uids = p.getJSONArray("uids")
        assertEquals(2, uids.length())
        assertEquals(10123, uids.getInt(0))
        assertEquals(1010123, uids.getInt(1))
        val uidPackages = p.getJSONArray("uidPackages")
        assertEquals("com.x", uidPackages.getString(0))
        assertEquals("com.x", uidPackages.getString(1))
        val packageUsers = p.getJSONArray("packageUsers")
        assertEquals(0, packageUsers.getInt(0))
        assertEquals(10, packageUsers.getInt(1))
        assertTrue(p.getJSONArray("packages").length() == 2)
    }
}
