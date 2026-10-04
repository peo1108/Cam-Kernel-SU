package me.weishu.kernelsu.data.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.math.BigInteger

/** Google's public list of revoked attestation certificates. */
const val ATTESTATION_STATUS_URL = "https://android.googleapis.com/attestation/status"

/** A certificate of the attestation chain found on the revocation list; [index] 0 is the leaf. */
@Immutable
data class RevokedCert(val index: Int, val serial: String, val reason: String)

/** Serial numbers are listed as lower-case hex without leading zeros. */
fun serialHex(serial: BigInteger): String = normaliseSerial(serial.toString(16))

private fun normaliseSerial(hex: String): String = hex.lowercase().trimStart('0')

/** serial -> reason (or status when no reason is given); null when the response is not the list. */
fun parseRevocationList(json: String): Map<String, String>? = runCatching {
    val entries = JSONObject(json).getJSONObject("entries")
    entries.keys().asSequence().associate { key ->
        val entry = entries.optJSONObject(key)
        val reason = entry?.optString("reason").takeUnless { it.isNullOrEmpty() }
            ?: entry?.optString("status").orEmpty()
        normaliseSerial(key) to reason
    }
}.getOrNull()

fun findRevoked(chainSerials: List<String>, revoked: Map<String, String>): List<RevokedCert> =
    chainSerials.mapIndexedNotNull { index, serial ->
        revoked[serial]?.let { RevokedCert(index = index, serial = serial, reason = it) }
    }
