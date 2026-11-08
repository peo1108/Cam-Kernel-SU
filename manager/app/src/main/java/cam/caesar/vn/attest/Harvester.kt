package cam.caesar.vn.attest

import android.content.pm.PackageManager
import android.os.Build
import cam.caesar.vn.camApp
import cam.caesar.vn.ui.util.checkAttestation
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
 * Harvest the device's real attestation parameters by attesting a throwaway key (blocking; call off
 * the main thread). Returns null when the device produced no readable attestation.
 */
fun harvest(): HarvestRecord? {
    val info = checkAttestation() ?: return null
    val strongBox =
        camApp.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    val tee = info.attestationVersion ?: 400
    return HarvestRecord(
        verifiedBootKeyB64 = info.verifiedBootKeyB64,
        verifiedBootHashB64 = info.verifiedBootHashB64,
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
