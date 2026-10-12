package cam.su.kernel.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON of site/api/routes/device.mjs as the Manager reads it. */
class AccountParseTest {

    @Test
    fun pairing() {
        val p = parsePairing("""{"id":"abc","code":"K7Q2MX","expires_in":600,"url":"https://caesarvn.xyz/ket-noi?code=K7Q2MX"}""")
        assertEquals("K7Q2MX", p.code)
        assertEquals("https://caesarvn.xyz/ket-noi?code=K7Q2MX", p.url)
        assertEquals(600, p.expiresInSeconds)
    }

    @Test
    fun poll() {
        assertEquals(PollResult.Pending, parsePoll("""{"status":"pending"}"""))
        assertEquals(PollResult.Expired, parsePoll("""{"status":"expired"}"""))
        val linked = parsePoll("""{"status":"linked","token":"tok","email":"a@x.io"}""") as PollResult.Linked
        assertEquals("tok", linked.token)
        assertEquals("a@x.io", linked.email)
        assertNull((parsePoll("""{"status":"linked","token":"tok","email":null}""") as PollResult.Linked).email)
    }

    @Test
    fun status() {
        val s = parseStatus(
            """{"email":"a@x.io","member":true,"tester":"pending",
               "locked":[{"version":"1.2.0","tag":"cam-v1.2.0","open_at":1760000000000,"prerelease":false,"price":20000}],
               "join_url":"https://caesarvn.xyz/thanh-vien"}""",
        )
        assertTrue(s.member)
        assertEquals("pending", s.tester)
        assertEquals(1, s.locked.size)
        assertEquals("1.2.0", s.locked[0].version)
        assertEquals(20000L, s.locked[0].price)
    }

    @Test
    fun lockedReleaseCarriesItsVersionCodeAndNotes() {
        val s = parseStatus(
            """{"email":"a@x.io","member":true,"tester":"none","can_buy_early":true,
               "locked":[{"version":"1.2.0","tag":"cam-v1.2.0","open_at":1,"prerelease":false,"price":20000,
                          "body":"- Faster boot\n- Fixes","apk":"SU_Kernel_1.2.0_32900-release.apk"}]}""",
        )
        assertTrue(s.canBuyEarly)
        val r = s.locked[0]
        assertEquals("cam-v1.2.0", r.tag)
        assertEquals(32900L, r.versionCode)
        assertEquals("Faster boot", r.summary)
    }

    @Test
    fun offerIsTheNewestLockedReleaseAboveWhatCanBeInstalled() {
        fun rel(v: String, code: Long, beta: Boolean = false) = LockedRelease(v, "cam-v$v", 0, beta, 20000, code)
        val status = AccountStatus("a@x.io", true, "none", listOf(rel("1.3.0-beta.1", 33000, beta = true), rel("1.2.0", 32900), rel("1.1.9", 32880)), "", true)
        assertEquals("1.2.0", newestOffer(status, currentVersionCode = 32850)?.version)
        assertEquals("1.3.0-beta.1", newestOffer(status, 32850, includePrereleases = true)?.version)
        assertNull("an open update already newer", newestOffer(status, currentVersionCode = 32850, openVersionCode = 32950))
        assertNull("already on it", newestOffer(status, currentVersionCode = 32900))
    }

    @Test
    fun order() {
        val o = parseOrder(
            """{"code":"CAMK7Q2MX","kind":"early","tag":"cam-v1.2.0","version":"1.2.0","amount":20000,"received":5000,
               "status":"pending","expires_at":99,"paid_at":null,
               "pay":{"image":"https://qr.sepay.vn/img?x","bank":"MBBank","account":"0859855556","holder":"DAO THE VINH","content":"CAMK7Q2MX"}}""",
        )
        assertEquals(15000L, o.due)
        assertEquals("CAMK7Q2MX", o.pay?.content)
        assertEquals(false, o.paid)
        val paid = parseOrder("""{"code":"CAMK7Q2MX","tag":null,"amount":20000,"received":20000,"status":"paid","pay":null}""")
        assertTrue(paid.paid)
        assertNull(paid.pay)
        assertNull(paid.tag)
        assertEquals("sales_closed", errorCode("""{"error":"sales_closed"}"""))
    }

    @Test
    fun statusOfANewAccount() {
        val s = parseStatus("""{"email":"b@x.io","member":false,"tester":"none","locked":[]}""")
        assertEquals(false, s.member)
        assertEquals("none", s.tester)
        assertEquals("https://caesarvn.xyz/thanh-vien", s.joinUrl)
        assertEquals(false, s.canBuyEarly)
    }
}
