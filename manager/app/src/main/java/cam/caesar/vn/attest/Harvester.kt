package cam.caesar.vn.attest

import android.content.pm.PackageManager
import android.os.Build
import cam.caesar.vn.Cam
import cam.caesar.vn.camApp
import cam.caesar.vn.ui.util.checkAttestation
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/**
 * The device's real, frozen root of trust and attestation parameters — what the TA signs with. The
 * verified-boot key/hash/state feed the TA's key-encryption-key derivation, so they must be stable
 * per device; re-harvest only when [fingerprint] (ro.build.fingerprint) changes. All optional fields
 * are absent (null) when the device's own attestation omits them — never a made-up default.
 */
data class HarvestRecord(
    val verifiedBootKeyB64: String?,
    val verifiedBootHashB64: String?,
    val moduleHashB64: String?,
    val deviceLocked: Boolean,
    val verifiedBootState: Int,
    val strongBoxAvailable: Boolean,
    val attestVersionTee: Int,
    val attestVersionStrongBox: Int,
    val osVersion: Int?,
    val osPatchLevel: Int?,
    val vendorPatchLevel: Int?,
    val bootPatchLevel: Int?,
    val fingerprint: String,
) {
    /** The `bootInfo` object of a config push (spec §3.2); absent base64 fields are omitted. */
    fun toBootInfoJson(): String = JSONObject().apply {
        verifiedBootKeyB64?.let { put("verifiedBootKey", it) }
        verifiedBootHashB64?.let { put("verifiedBootHash", it) }
        moduleHashB64?.let { put("moduleHash", it) }
        put("deviceLocked", deviceLocked)
        put("verifiedBootState", verifiedBootState)
        put("strongBoxAvailable", strongBoxAvailable)
        put("attestVersionTee", attestVersionTee)
        put("attestVersionStrongBox", attestVersionStrongBox)
    }.toString()

    /** The full record, for caching to boot.json. */
    fun toJson(): String = JSONObject().apply {
        verifiedBootKeyB64?.let { put("verifiedBootKey", it) }
        verifiedBootHashB64?.let { put("verifiedBootHash", it) }
        moduleHashB64?.let { put("moduleHash", it) }
        put("deviceLocked", deviceLocked)
        put("verifiedBootState", verifiedBootState)
        put("strongBoxAvailable", strongBoxAvailable)
        put("attestVersionTee", attestVersionTee)
        put("attestVersionStrongBox", attestVersionStrongBox)
        osVersion?.let { put("osVersion", it) }
        osPatchLevel?.let { put("osPatchLevel", it) }
        vendorPatchLevel?.let { put("vendorPatchLevel", it) }
        bootPatchLevel?.let { put("bootPatchLevel", it) }
        put("fingerprint", fingerprint)
    }.toString()

    companion object {
        fun fromJson(json: String): HarvestRecord = JSONObject(json).run {
            HarvestRecord(
                verifiedBootKeyB64 = optStringOrNull("verifiedBootKey"),
                verifiedBootHashB64 = optStringOrNull("verifiedBootHash"),
                moduleHashB64 = optStringOrNull("moduleHash"),
                deviceLocked = getBoolean("deviceLocked"),
                verifiedBootState = getInt("verifiedBootState"),
                strongBoxAvailable = getBoolean("strongBoxAvailable"),
                attestVersionTee = getInt("attestVersionTee"),
                attestVersionStrongBox = getInt("attestVersionStrongBox"),
                osVersion = optIntOrNull("osVersion"),
                osPatchLevel = optIntOrNull("osPatchLevel"),
                vendorPatchLevel = optIntOrNull("vendorPatchLevel"),
                bootPatchLevel = optIntOrNull("bootPatchLevel"),
                fingerprint = optString("fingerprint", ""),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) getInt(key) else null
    }
}

/**
 * The device's real `ro.boot.vbmeta.*` values (hex), read as root through [Cam.vbmetaBootProps] —
 * the stand-ins used when the TEE reported an all-zero verified-boot key/hash (what a real TEE
 * returns on an *unlocked* device). A null field falls through to a constant/derived value in
 * [chooseBootKeyB64] / [chooseBootHashB64]. [digestHex] is camd's own computed vbmeta digest
 * (`vbmeta.rs`) when available, else the live `ro.boot.vbmeta.digest`.
 */
data class BootFallback(val publicKeyDigestHex: String?, val digestHex: String?)

/**
 * Harvest the device's real attestation parameters by attesting a throwaway key (blocking; call off
 * the main thread). Returns null when the device produced no readable attestation.
 *
 * On an unlocked device the TEE reports an all-zero (or absent) verified-boot key/hash, while the
 * attested RootOfTrust is still presented as locked/Verified — a combination a real locked device
 * never produces, which a key-attestation reader (e.g. Duck) flags. So an unusable key/hash is
 * replaced here, before it reaches the config push, exactly as TEESimulator's harvester does.
 */
fun harvest(): HarvestRecord? {
    val info = checkAttestation() ?: return null
    val boot = Cam.vbmetaBootProps()
    val strongBox =
        camApp.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    val tee = info.attestationVersion ?: 400
    return HarvestRecord(
        verifiedBootKeyB64 = chooseBootKeyB64(info.verifiedBootKeyB64, boot.publicKeyDigestHex),
        verifiedBootHashB64 =
            chooseBootHashB64(info.verifiedBootHashB64, boot.digestHex, Build.FINGERPRINT),
        moduleHashB64 = info.moduleHashB64,
        deviceLocked = info.deviceLocked,
        verifiedBootState = info.verifiedBootState,
        strongBoxAvailable = strongBox,
        attestVersionTee = tee,
        // Refined with a real StrongBox attestation in M3-7 if a profile needs that level; the TEE
        // value is a safe default (never a made-up higher one).
        attestVersionStrongBox = tee,
        osVersion = info.osVersion,
        osPatchLevel = info.osPatchLevel,
        vendorPatchLevel = info.vendorPatchLevel,
        bootPatchLevel = info.bootPatchLevel,
        fingerprint = Build.FINGERPRINT,
    )
}

/**
 * The verified-boot KEY (base64) to attest: the captured value when usable, else the device's own
 * `ro.boot.vbmeta.public_key_digest`, else a fixed constant. It feeds the TA's key-encryption-key
 * derivation, so it must be stable and is therefore **never random** — a constant keeps every
 * wrapped key decryptable across reboots.
 */
internal fun chooseBootKeyB64(capturedB64: String?, publicKeyDigestHex: String?): String =
    if (usableBootBytes(capturedB64) != null) capturedB64!!
    else hexToUsableBootB64(publicKeyDigestHex) ?: fallbackBootKeyB64()

/**
 * The verified-boot HASH (base64) to attest: the captured value when usable, else the computed
 * vbmeta digest surfaced in [digestHex], else a per-device value derived from the fingerprint. The
 * fallback is deterministic (not random) so `ro.boot.vbmeta.digest` does not flap across reboots,
 * which would itself be a signal.
 */
internal fun chooseBootHashB64(
    capturedB64: String?,
    digestHex: String?,
    fingerprint: String,
): String =
    if (usableBootBytes(capturedB64) != null) capturedB64!!
    else hexToUsableBootB64(digestHex) ?: fallbackBootHashB64(fingerprint)

/**
 * The decoded bytes of a base64 verified-boot value, but only when it is a *usable* root of trust:
 * exactly 32 bytes and not all zero. An unlocked device reports 32 zero bytes, which a
 * locked/Verified certificate must never carry; null signals "replace it".
 */
internal fun usableBootBytes(b64: String?): ByteArray? {
    val bytes = b64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
    return bytes.takeIf { it.size == 32 && it.any { b -> b.toInt() != 0 } }
}

/** Parse 64 hex chars (a `ro.boot.vbmeta.*` prop) into a usable 32-byte value as base64, or null. */
internal fun hexToUsableBootB64(hex: String?): String? {
    val clean = hex?.trim()?.removePrefix("0x") ?: return null
    if (clean.length != 64 || clean.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' })
        return null
    val bytes =
        ByteArray(32) {
            ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte()
        }
    return if (bytes.any { it.toInt() != 0 }) base64(bytes) else null
}

/** A fixed stand-in for the verified-boot key; stable forever (it feeds the KEK), never random. */
private fun fallbackBootKeyB64(): String = base64(sha256("Cam/verified_boot_key"))

/** A per-device-stable stand-in for the verified-boot hash, derived from the build fingerprint. */
private fun fallbackBootHashB64(fingerprint: String): String =
    base64(sha256("$fingerprint/cam-verified-boot-hash"))

private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

private fun sha256(s: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
