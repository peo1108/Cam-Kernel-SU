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

    /** DER INTEGER for values past two bytes (patch levels are up to YYYYMMDD). */
    private fun bigInt(v: Int): ByteArray {
        val bytes = ArrayDeque<Int>()
        var x = v
        if (x == 0) bytes.addFirst(0)
        while (x > 0) { bytes.addFirst(x and 0xFF); x = x ushr 8 }
        if ((bytes.first() and 0x80) != 0) bytes.addFirst(0) // keep it positive
        return tlv(tag(0x02), ByteArray(bytes.size) { bytes[it].toByte() })
    }

    /** [n] EXPLICIT INTEGER, high tag number form: n = 640 + low (705 = 0x85 0x41). */
    private fun explicitInt(low: Int, v: Int) = tlv(tag(0xBF, 0x85, low), bigInt(v))
    private fun osVersion(v: Int) = explicitInt(0x41, v)       // 705
    private fun osPatch(v: Int) = explicitInt(0x42, v)         // 706
    private fun vendorPatch(v: Int) = explicitInt(0x4E, v)     // 718
    private fun bootPatch(v: Int) = explicitInt(0x4F, v)       // 719

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
    fun parsesPatchLevels() {
        val tee = cat(
            osVersion(160000), osPatch(202610), vendorPatch(20261005), bootPatch(20261005),
            rootOfTrust(locked = true, state = 0),
        )
        val info = parseKeyDescription(keyDescription(sw = otherTags(), tee = tee))
        assertEquals(160000, info?.osVersion)
        assertEquals(202610, info?.osPatchLevel)
        assertEquals(20261005, info?.vendorPatchLevel)
        assertEquals(20261005, info?.bootPatchLevel)
    }

    @Test
    fun patchLevelsAbsentAreNull() {
        val desc = keyDescription(sw = otherTags(), tee = cat(otherTags(), rootOfTrust(locked = true, state = 0)))
        val info = parseKeyDescription(desc)
        assertNull(info?.osVersion)
        assertNull(info?.osPatchLevel)
        assertNull(info?.vendorPatchLevel)
        assertNull(info?.bootPatchLevel)
    }

    @Test
    fun formatsLevels() {
        assertEquals("16.0.0", formatOsVersion(160000))
        assertEquals("16.1.2", formatOsVersion(160102))
        assertEquals("2026-10", formatPatchLevel(202610))
        assertEquals("2026-10-05", formatPatchLevel(20261005))
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
