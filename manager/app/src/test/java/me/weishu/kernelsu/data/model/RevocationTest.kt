package me.weishu.kernelsu.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class RevocationTest {

    private val list = """
        {"entries": {
          "2c8cdddfd5e03bfc": {"status": "REVOKED", "reason": "KEY_COMPROMISE"},
          "00C35747A084470C3135AEEFE2B8D40CD6": {"status": "REVOKED"},
          "8350192447815228107": {"status": "SUSPENDED", "reason": "SOFTWARE_FLAW"}
        }}
    """.trimIndent()

    @Test
    fun parsesRevocationList() {
        val revoked = parseRevocationList(list)!!
        assertEquals("KEY_COMPROMISE", revoked["2c8cdddfd5e03bfc"])
        // keys are normalised: lower case, no leading zeros; status stands in for a missing reason
        assertEquals("REVOKED", revoked["c35747a084470c3135aeefe2b8d40cd6"])
        assertEquals(3, revoked.size)
    }

    @Test
    fun garbageListIsNull() {
        assertNull(parseRevocationList("<html>"))
        assertNull(parseRevocationList("""{"nope": 1}"""))
    }

    @Test
    fun findsRevokedCertificatesInChain() {
        val revoked = parseRevocationList(list)!!
        val chain = listOf(
            serialHex(BigInteger("1234", 16)),
            serialHex(BigInteger("c35747a084470c3135aeefe2b8d40cd6", 16)),
        )
        assertEquals(
            listOf(RevokedCert(index = 1, serial = "c35747a084470c3135aeefe2b8d40cd6", reason = "REVOKED")),
            findRevoked(chain, revoked),
        )
        assertEquals(emptyList<RevokedCert>(), findRevoked(listOf("1234"), revoked))
    }
}
