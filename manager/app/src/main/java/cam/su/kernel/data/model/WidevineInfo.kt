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
