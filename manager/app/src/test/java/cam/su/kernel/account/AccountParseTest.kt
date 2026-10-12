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
    fun statusOfANewAccount() {
        val s = parseStatus("""{"email":"b@x.io","member":false,"tester":"none","locked":[]}""")
        assertEquals(false, s.member)
        assertEquals("none", s.tester)
        assertEquals("https://caesarvn.xyz/thanh-vien", s.joinUrl)
    }
}
