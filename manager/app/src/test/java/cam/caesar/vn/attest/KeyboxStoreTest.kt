package cam.caesar.vn.attest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxStoreTest {

    private fun validXml(): String =
        javaClass.classLoader!!.getResource("keybox_valid.xml")!!.readText()

    @Test
    fun parsesValidKeybox() {
        val kb = parseKeybox(validXml())
        assertTrue(kb != null && kb.serials.isNotEmpty())
    }

    @Test
    fun rejectsKeyboxWithoutBothAlgos() {
        val rsaOnly = validXml().replace(
            Regex("<Key algorithm=\"ecdsa\">.*?</Key>", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        assertNull(parseKeybox(rsaOnly))
    }

    @Test
    fun revokedSerialIsRevoked() {
        // the ecdsa leaf serial is 0x21 -> "21"
        val status = parseKeybox(validXml())!!.statusAgainst(mapOf("21" to "KEY_COMPROMISE"))
        assertTrue(status is KeyboxStatus.Revoked)
    }

    @Test
    fun cleanKeyboxIsValid() {
        assertEquals(KeyboxStatus.Valid, parseKeybox(validXml())!!.statusAgainst(emptyMap()))
    }

    @Test
    fun offlineRevocationIsValid() {
        assertEquals(KeyboxStatus.Valid, parseKeybox(validXml())!!.statusAgainst(null))
    }

    @Test
    fun malformedIsNull() {
        assertNull(parseKeybox("<not>a keybox</not>"))
        assertNull(parseKeybox("garbage"))
    }
}
