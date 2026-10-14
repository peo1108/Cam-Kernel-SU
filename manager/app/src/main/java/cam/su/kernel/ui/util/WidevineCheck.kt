package cam.su.kernel.ui.util

import android.media.MediaDrm
import android.util.Log
import cam.su.kernel.data.model.WidevineInfo
import java.util.UUID

private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

/**
 * The Widevine level and properties, as any app reads them (no root): the same call streaming
 * apps make to pick HD or SD. Blocking: the DRM service can take a moment to answer.
 */
fun checkWidevine(): WidevineInfo {
    if (!runCatching { MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID) }.getOrDefault(false)) return WidevineInfo.None
    return runCatching {
        MediaDrm(WIDEVINE_UUID).use { drm -> WidevineInfo.fromProperties(drm::getPropertyString) }
    }.onFailure { Log.w("WidevineCheck", "reading the Widevine plugin failed", it) }
        .getOrElse { WidevineInfo(supported = true) }
}
