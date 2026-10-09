package cam.su.kernel.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class KeyAttestationTest {

    private fun tlv(tag: ByteArray, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun tag(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun cat(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun seq(vararg parts: ByteArray) = tlv(tag(0x30), cat(*parts))
    private fun set(vararg parts: ByteArray) = tlv(tag(0x31), cat(*parts))
    private fun int(v: Int) = tlv(tag(0x02), if (v < 0x80) tag(v) else tag(v shr 8, v and 0xFF))
    private fun enum(v: Int) = tlv(tag(0x0A), tag(v))
    private fun oct(b: ByteArray) = tlv(tag(0x04), b)
    private fun bool(v: Boolean) = tlv(tag(0x01), tag(if (v) 0xFF else 0))

    /** [704] EXPLICIT RootOfTrust: high tag number form, 704 = 0x05 0x40 in base 128. */
    private fun rootOfTrust(locked: Boolean, state: Int) =
        tlv(tag(0xBF, 0x85, 0x40), seq(oct(ByteArray(32)), bool(locked), enum(state), oct(ByteArray(32))))

    /** [1] purpose and [709] attestationApplicationId, so the parser has to skip tags. */
    private fun otherTags() = cat(tlv(tag(0xA1), set(int(2))), tlv(tag(0xBF, 0x85, 0x45), oct(ByteArray(200))))

    private fun keyDescription(sw: ByteArray, tee: ByteArray, securityLevel: Int = 1) = seq(
        int(300), enum(securityLevel), int(300), enum(securityLevel),
        oct(ByteArray(16)), oct(ByteArray(0)),
        seq(sw), seq(tee),
    )

    @Test
    fun parsesRootOfTrustFromTeeList() {
        val desc = keyDescription(sw = otherTags(), tee = cat(otherTags(), rootOfTrust(locked = false, state = 2)))
        val expected = AttestationInfo(securityLevel = 1, deviceLocked = false, verifiedBootState = 2)
        assertEquals(expected, parseKeyDescription(desc))
        // X509Certificate.getExtensionValue wraps the extension in an OCTET STRING
        assertEquals(expected, parseKeyDescription(oct(desc)))
    }

    @Test
    fun fallsBackToSoftwareList() {
        val desc = keyDescription(sw = rootOfTrust(locked = true, state = 0), tee = otherTags(), securityLevel = 0)
        assertEquals(AttestationInfo(securityLevel = 0, deviceLocked = true, verifiedBootState = 0), parseKeyDescription(desc))
    }

    @Test
    fun missingRootOfTrustIsNull() {
        assertNull(parseKeyDescription(keyDescription(sw = otherTags(), tee = otherTags())))
    }

    @Test
    fun garbageIsNull() {
        assertNull(parseKeyDescription(tag(0x30, 0x7F, 0x01)))
        assertNull(parseKeyDescription(ByteArray(0)))
        assertNull(parseKeyDescription(tag(0x02, 0x01, 0x05)))
    }
}
