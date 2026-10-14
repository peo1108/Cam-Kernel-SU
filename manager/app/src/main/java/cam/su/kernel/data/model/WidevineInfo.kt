package cam.su.kernel.data.model

import androidx.compose.runtime.Immutable

/**
 * What the Widevine DRM plugin reports ([cam.su.kernel.ui.util.checkWidevine]). [securityLevel] is
 * "L1" (keys handled in the TEE: HD in streaming apps), "L2" or "L3" (software only: SD at most).
 * [supported] is false when the device has no Widevine plugin at all.
 */
@Immutable
data class WidevineInfo(
    val supported: Boolean,
    val securityLevel: String? = null,
    val systemId: String? = null,
    val version: String? = null,
    val vendor: String? = null,
    val hdcpLevel: String? = null,
    val maxHdcpLevel: String? = null,
    /** whether the Manager holds (or just got) a DRM certificate; false when Google would not give one */
    val provisioned: Boolean? = null,
    /** the certificate had to be fetched just now (each app gets its own, so a first check always does) */
    val justProvisioned: Boolean = false,
    /** why no certificate could be had, or a session would not open */
    val sessionError: String? = null,
) {
    /** L1, the level streaming apps need for HD */
    val isL1: Boolean get() = securityLevel.equals("L1", ignoreCase = true)

    /** the lines to copy, one property per line */
    fun summary(): String = listOfNotNull(
        "Widevine: ${securityLevel ?: if (supported) "?" else "none"}",
        systemId?.let { "System ID: $it" },
        version?.let { "Version: $it" },
        vendor?.let { "Vendor: $it" },
        hdcpLevel?.let { "HDCP: $it" },
        maxHdcpLevel?.let { "Max HDCP: $it" },
        provisioned?.let { "Provisioned: $it" },
        sessionError?.let { "Session error: $it" },
    ).joinToString("\n")

    companion object {
        val None = WidevineInfo(supported = false)

        /** From the plugin's string properties; blank or failed reads are left out. */
        fun fromProperties(read: (String) -> String?): WidevineInfo {
            fun prop(name: String) = runCatching { read(name) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            return WidevineInfo(
                supported = true,
                securityLevel = prop("securityLevel"),
                systemId = prop("systemId"),
                version = prop("version"),
                vendor = prop("vendor"),
                hdcpLevel = prop("hdcpLevel"),
                maxHdcpLevel = prop("maxHdcpLevel"),
            )
        }
    }
}

/** Why a device is (or is not) at L1, as far as can be told from outside the TEE. */
enum class WidevineDiagnosis {
    /** all good */
    L1,

    /** no Widevine plugin: the ROM lacks the vendor's DRM blobs */
    NoPlugin,

    /** the Widevine HAL service is not running */
    HalDown,

    /** no DRM certificate could be had: the network, a broken local certificate, or a revoked keybox */
    NotProvisioned,

    /** bootloader unlocked: the TEE may refuse L1 while unlocked, or the maker wiped the keybox */
    BootloaderUnlocked,

    /** bootloader locked yet below L1: keybox gone or revoked, the maker has to provision it again */
    KeyboxMissing,

    /** below L1 for a reason that cannot be told apart */
    Unknown,
    ;

    /** cases where clearing and fetching the DRM certificates again is worth a try */
    val reprovisionHelps: Boolean get() = this == NotProvisioned || this == BootloaderUnlocked || this == KeyboxMissing || this == Unknown
}

/**
 * [halRunning]: the Widevine HAL service state (null when no such service was found by name).
 * [bootloaderLocked]: from the key attestation, which the chip signs (props can be spoofed, by
 * Cam's own hide bootloader among others); null before it was read.
 */
fun diagnoseWidevine(info: WidevineInfo, halRunning: Boolean?, bootloaderLocked: Boolean?): WidevineDiagnosis = when {
    info.supported && info.isL1 -> WidevineDiagnosis.L1
    halRunning == false -> WidevineDiagnosis.HalDown
    !info.supported -> WidevineDiagnosis.NoPlugin
    info.provisioned == false -> WidevineDiagnosis.NotProvisioned
    bootloaderLocked == false -> WidevineDiagnosis.BootloaderUnlocked
    bootloaderLocked == true -> WidevineDiagnosis.KeyboxMissing
    else -> WidevineDiagnosis.Unknown
}
