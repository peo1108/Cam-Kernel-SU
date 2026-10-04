package me.weishu.kernelsu.ui.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import me.weishu.kernelsu.data.model.AttestationInfo
import me.weishu.kernelsu.data.model.KEY_ATTESTATION_OID
import me.weishu.kernelsu.data.model.parseKeyDescription
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate

private const val CHECK_ALIAS = "su_kernel_attestation_check"

/**
 * Generates a throwaway attested key the way a banking app would and reads its RootOfTrust,
 * so the Features page shows what such an app sees. Blocking: call off the main thread.
 */
fun checkAttestation(): AttestationInfo? {
    val keyStore = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }.getOrNull() ?: return null
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
        val certificate = keyStore.getCertificate(CHECK_ALIAS) as? X509Certificate
        certificate?.getExtensionValue(KEY_ATTESTATION_OID)?.let(::parseKeyDescription)
    } catch (e: Exception) {
        Log.w("AttestationCheck", "attestation check failed", e)
        null
    } finally {
        runCatching { keyStore.deleteEntry(CHECK_ALIAS) }
    }
}
