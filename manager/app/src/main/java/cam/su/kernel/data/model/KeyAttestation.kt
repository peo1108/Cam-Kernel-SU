package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable

/** OID of the Android key attestation extension (KeyDescription). */
const val KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

/**
 * What an app reading this device's key attestation sees.
 * [securityLevel]: 0 software, 1 TEE, 2 StrongBox.
 * [verifiedBootState]: 0 Verified, 1 SelfSigned, 2 Unverified, 3 Failed.
 * The patch fields are null when the certificate does not carry that tag.
 * [osVersion] is encoded major*10000 + minor*100 + sub; [osPatchLevel] is YYYYMM;
 * [vendorPatchLevel]/[bootPatchLevel] are YYYYMMDD (older devices: YYYYMM).
 */
@Immutable
data class AttestationInfo(
    val securityLevel: Int,
    val deviceLocked: Boolean,
    val verifiedBootState: Int,
    val osVersion: Int? = null,
    val osPatchLevel: Int? = null,
    val vendorPatchLevel: Int? = null,
    val bootPatchLevel: Int? = null,
)

private const val TAG_ROOT_OF_TRUST = 704
private const val TAG_OS_VERSION = 705
private const val TAG_OS_PATCH_LEVEL = 706
private const val TAG_VENDOR_PATCH_LEVEL = 718
private const val TAG_BOOT_PATCH_LEVEL = 719

/** A DER element: [start] until [end] is its content. */
private class Tlv(val tagClass: Int, val tag: Int, val bytes: ByteArray, val start: Int, val end: Int) {
    val content: ByteArray get() = bytes.copyOfRange(start, end)

    fun children(): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var pos = start
        while (pos < end) {
            val child = readTlv(bytes, pos, end)
            out += child
            pos = child.end
        }
        return out
    }
}

/** Reads one element at [pos]; tags above 30 use the multi-byte (base 128) form. */
private fun readTlv(bytes: ByteArray, pos: Int, limit: Int): Tlv {
    var p = pos
    fun next(): Int {
        require(p < limit) { "truncated DER" }
        return bytes[p++].toInt() and 0xFF
    }

    val first = next()
    var tag = first and 0x1F
    if (tag == 0x1F) {
        tag = 0
        do {
            val b = next()
            tag = (tag shl 7) or (b and 0x7F)
            require(tag < 1 shl 24) { "tag too large" }
        } while (b and 0x80 != 0)
    }
    var length = next()
    if (length and 0x80 != 0) {
        val count = length and 0x7F
        require(count in 1..3) { "bad length" }
        length = 0
        repeat(count) { length = (length shl 8) or next() }
    }
    require(length <= limit - p) { "length past end" }
    return Tlv(tagClass = first shr 6, tag = tag, bytes = bytes, start = p, end = p + length)
}

private fun Tlv.intValue(): Int = content.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

private fun Tlv.findContextTag(number: Int): Tlv? =
    children().firstOrNull { it.tagClass == 2 && it.tag == number }

/**
 * Parses a KeyDescription, as raw bytes or wrapped in the OCTET STRING that
 * `X509Certificate.getExtensionValue` returns. Null when it has no RootOfTrust or is malformed.
 */
fun parseKeyDescription(extension: ByteArray): AttestationInfo? = runCatching {
    var top = readTlv(extension, 0, extension.size)
    if (top.tagClass == 0 && top.tag == 4) {
        val inner = top.content
        top = readTlv(inner, 0, inner.size)
    }
    require(top.tagClass == 0 && top.tag == 16) { "not a SEQUENCE" }
    val fields = top.children()
    val securityLevel = fields[1].intValue()
    val softwareEnforced = fields[6]
    val hardwareEnforced = fields[7]
    val explicit = hardwareEnforced.findContextTag(TAG_ROOT_OF_TRUST)
        ?: softwareEnforced.findContextTag(TAG_ROOT_OF_TRUST)
        ?: return@runCatching null
    val rootOfTrust = explicit.children().first().children()
    // Patch levels live in the same AuthorizationList, usually the hardware-enforced one.
    // Each is [n] EXPLICIT INTEGER, so unwrap the context tag to its INTEGER child.
    fun patchInt(number: Int): Int? =
        (hardwareEnforced.findContextTag(number) ?: softwareEnforced.findContextTag(number))
            ?.children()?.firstOrNull()?.intValue()
    AttestationInfo(
        securityLevel = securityLevel,
        deviceLocked = rootOfTrust[1].intValue() != 0,
        verifiedBootState = rootOfTrust[2].intValue(),
        osVersion = patchInt(TAG_OS_VERSION),
        osPatchLevel = patchInt(TAG_OS_PATCH_LEVEL),
        vendorPatchLevel = patchInt(TAG_VENDOR_PATCH_LEVEL),
        bootPatchLevel = patchInt(TAG_BOOT_PATCH_LEVEL),
    )
}.getOrNull()

/** "16.0.0" from the encoded osVersion (major*10000 + minor*100 + sub). */
fun formatOsVersion(encoded: Int): String =
    "${encoded / 10000}.${encoded / 100 % 100}.${encoded % 100}"

/** "2026-10" from YYYYMM, or "2026-10-05" from YYYYMMDD; the raw value if it fits neither. */
fun formatPatchLevel(encoded: Int): String = when {
    encoded in 100_000..999_999 -> "%04d-%02d".format(encoded / 100, encoded % 100)
    encoded in 10_000_000..99_999_999 ->
        "%04d-%02d-%02d".format(encoded / 10_000, encoded / 100 % 100, encoded % 100)
    else -> encoded.toString()
}
