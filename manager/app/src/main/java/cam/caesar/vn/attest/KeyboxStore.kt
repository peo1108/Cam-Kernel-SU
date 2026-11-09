package cam.caesar.vn.attest

import cam.caesar.vn.data.model.RevokedCert
import cam.caesar.vn.data.model.findRevoked
import cam.caesar.vn.data.model.serialHex
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A keybox's standing. [Revoked] and (future) not-Google-signed are WARNINGS the user may override —
 * it is their keybox (spec §1); only [Invalid] (unparseable/structurally wrong) blocks it.
 */
sealed interface KeyboxStatus {
    object Valid : KeyboxStatus
    data class Revoked(val certs: List<RevokedCert>) : KeyboxStatus
    data class Invalid(val reason: String) : KeyboxStatus
}

/** A parsed, structurally valid keybox: the raw XML (what the TA signs with) and its chain serials. */
class Keybox internal constructor(val xml: String, val serials: List<String>) {
    /** base64 of the raw keybox XML, for the config push's `keyboxB64`. */
    fun toB64(): String = java.util.Base64.getEncoder().encodeToString(xml.toByteArray())

    /** Check the chain serials against Google's revocation list; null = offline (cannot check). */
    fun statusAgainst(revoked: Map<String, String>?): KeyboxStatus {
        if (revoked == null) return KeyboxStatus.Valid
        val found = findRevoked(serials, revoked)
        return if (found.isNotEmpty()) KeyboxStatus.Revoked(found) else KeyboxStatus.Valid
    }
}

/**
 * Parse a keybox XML. Returns null unless it has BOTH an `rsa` and an `ecdsa` key, each with a
 * non-empty PrivateKey and a certificate chain of at least two parseable certs.
 */
fun parseKeybox(xml: String): Keybox? = runCatching {
    // A keybox is a user-supplied file and never has a DOCTYPE: refuse one outright (XXE). This is
    // the guard on a device: Android's DocumentBuilderFactory throws on the Xerces feature below,
    // which made every keybox "invalid" there while the JVM tests passed (emulator, 2026-11-09).
    if (xml.contains("<!DOCTYPE", ignoreCase = true)) return@runCatching null
    val doc = DocumentBuilderFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    val byAlgo = HashMap<String, Element>()
    val keys = doc.getElementsByTagName("Key")
    for (i in 0 until keys.length) {
        val el = keys.item(i) as Element
        byAlgo[el.getAttribute("algorithm").lowercase()] = el
    }
    val rsa = byAlgo["rsa"] ?: return@runCatching null
    val ecdsa = byAlgo["ecdsa"] ?: return@runCatching null

    val cf = CertificateFactory.getInstance("X.509")
    val serials = mutableListOf<String>()
    for (key in listOf(rsa, ecdsa)) {
        val pk = key.getElementsByTagName("PrivateKey")
        if (pk.length == 0 || pk.item(0).textContent.isBlank()) return@runCatching null
        val certs = key.getElementsByTagName("Certificate")
        if (certs.length < 2) return@runCatching null
        for (j in 0 until certs.length) {
            val cert = cf.generateCertificate(
                ByteArrayInputStream(certs.item(j).textContent.toByteArray()),
            ) as X509Certificate
            serials += serialHex(cert.serialNumber)
        }
    }
    Keybox(xml, serials)
}.getOrNull()
