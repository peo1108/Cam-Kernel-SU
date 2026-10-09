package cam.su.kernel.ui.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import cam.su.kernel.data.model.ATTESTATION_STATUS_URL
import cam.su.kernel.data.model.AttestationInfo
import cam.su.kernel.data.model.KEY_ATTESTATION_OID
import cam.su.kernel.data.model.RevokedCert
import cam.su.kernel.data.model.findRevoked
import cam.su.kernel.data.model.parseKeyDescription
import cam.su.kernel.data.model.parseRevocationList
import cam.su.kernel.data.model.serialHex
import cam.su.kernel.camApp
import okhttp3.Request
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate

private const val CHECK_ALIAS = "su_kernel_attestation_check"

/**
 * [info] is null when the certificate could not be read; [revoked] is null when the
 * revocation list could not be fetched (offline), empty when nothing in the chain is revoked.
 */
data class AttestationReport(
    val info: AttestationInfo?,
    val chainSize: Int,
    val revoked: List<RevokedCert>?,
)

/** [checkAttestation] plus a look-up of the whole chain in Google's revocation list. Blocking. */
fun checkAttestationReport(): AttestationReport {
    val (info, serials) = attest()
    val revoked = if (serials.isEmpty()) null else fetchRevocationList()?.let { findRevoked(serials, it) }
    return AttestationReport(info = info, chainSize = serials.size, revoked = revoked)
}

private fun fetchRevocationList(): Map<String, String>? = runCatching {
    val request = Request.Builder().url(ATTESTATION_STATUS_URL).header("Cache-Control", "no-cache").build()
    camApp.okhttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        parseRevocationList(response.body.string())
    }
}.onFailure { Log.w("AttestationCheck", "revocation list fetch failed", it) }.getOrNull()

/**
 * Generates a throwaway attested key the way a banking app would and reads its RootOfTrust,
 * so the Features page shows what such an app sees. Blocking: call off the main thread.
 */
fun checkAttestation(): AttestationInfo? = attest().first

/** The attestation of a fresh key, and the serial numbers of its chain (leaf first). */
private fun attest(): Pair<AttestationInfo?, List<String>> {
    val keyStore = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }.getOrNull()
        ?: return null to emptyList()
    return try {
        val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(CHECK_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()
        )
        generator.generateKeyPair()
        val chain = keyStore.getCertificateChain(CHECK_ALIAS).orEmpty().filterIsInstance<X509Certificate>()
        val info = chain.firstOrNull()?.getExtensionValue(KEY_ATTESTATION_OID)?.let(::parseKeyDescription)
        info to chain.map { serialHex(it.serialNumber) }
    } catch (e: Exception) {
        Log.w("AttestationCheck", "attestation check failed", e)
        null to emptyList()
    } finally {
        runCatching { keyStore.deleteEntry(CHECK_ALIAS) }
    }
}
