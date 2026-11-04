package cam.su.kernel.ui.util

import android.media.MediaDrm
import android.media.NotProvisionedException
import android.util.Log
import cam.su.kernel.camApp
import cam.su.kernel.data.model.WidevineInfo
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

private const val TAG = "WidevineCheck"
private val WIDEVINE_UUID: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
private const val MEDIADRM_DIR = "/data/vendor/mediadrm"
private const val BACKUP_ROOT = "/data/adb/cam/mediadrm-backup"

/**
 * The Widevine level and properties, as any app reads them (no root): the same call streaming
 * apps make to pick HD or SD, plus whether a session opens without provisioning first.
 * Blocking: the DRM service can take a moment to answer.
 */
fun checkWidevine(): WidevineInfo {
    if (!runCatching { MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID) }.getOrDefault(false)) return WidevineInfo.None
    val info = runCatching { MediaDrm(WIDEVINE_UUID).use { WidevineInfo.fromProperties(it::getPropertyString) } }
        .onFailure { Log.w(TAG, "reading the Widevine plugin failed", it) }
        .getOrElse { return WidevineInfo(supported = true) }
    // Widevine hands each app its own certificate: a Manager that never asked has none, which says
    // nothing about the device. Asking for one, as a streaming app does on first use, does: the
    // server only answers a device whose keybox is valid.
    return try {
        info.copy(provisioned = true, justProvisioned = provision())
    } catch (e: Exception) {
        Log.w(TAG, "provisioning the Manager failed", e)
        info.copy(provisioned = false, sessionError = e.brief())
    }
}

private fun root(vararg commands: String): List<String> =
    getRootShell().newJob().add(*commands).to(ArrayList(), null).exec().out

/** `init.svc.<name>` -> state, for the DRM services (framework and vendor HALs). */
private fun drmServices(): Map<String, String> =
    root("getprop").mapNotNull { line ->
        val match = Regex("""^\[init\.svc\.([^\]]+)]: \[([^\]]*)]""").find(line) ?: return@mapNotNull null
        val name = match.groupValues[1]
        if ("drm" in name.lowercase() || "widevine" in name.lowercase()) name to match.groupValues[2] else null
    }.toMap()

/** Whether the Widevine HAL service runs; null when no service has "widevine" in its name. Blocking, root. */
fun widevineHalRunning(): Boolean? =
    drmServices().filterKeys { "widevine" in it.lowercase() }.values.takeIf { it.isNotEmpty() }?.any { it == "running" }

/**
 * Opens a session, first fetching a DRM certificate from Google's provisioning server when there is
 * none, as streaming apps do on first use. True when it had to fetch one. Throws when it cannot.
 */
private fun provision(): Boolean = MediaDrm(WIDEVINE_UUID).use { drm ->
    // Provisioning 3.0 devices take two rounds after a clear: the OEM certificate, then the DRM one
    repeat(MAX_PROVISION_ROUNDS + 1) { round ->
        try {
            drm.closeSession(drm.openSession())
            return@use round > 0
        } catch (e: NotProvisionedException) {
            if (round == MAX_PROVISION_ROUNDS) throw e
        }
        val request = drm.provisionRequest
        val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
        val response = camApp.okhttpClient.newCall(
            Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        ).execute().use { if (it.isSuccessful) it.body.bytes() else error("provisioning server: HTTP ${it.code}") }
        drm.provideProvisionResponse(response)
    }
    error("unreachable")
}

private const val MAX_PROVISION_ROUNDS = 3

/** An exception in one line: the DRM plugin appends its whole log to the message. */
private fun Throwable.brief(): String =
    javaClass.simpleName + (message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

data class WidevineReprovision(
    val before: WidevineInfo,
    val after: WidevineInfo,
    /** the old certificates were put back because the new ones did worse */
    val restored: Boolean,
    /** where the old certificates were saved */
    val backup: String,
    val error: String? = null,
)

/**
 * Clears the Widevine DRM certificates and fetches them again: the fix when they broke (a data
 * restore, an OTA, a ROM change) while the keybox is still there. It cannot bring back a wiped or
 * revoked keybox. The old files are kept under [BACKUP_ROOT] and put back when the device ends up
 * below the level it started at. Blocking, root, network.
 */
fun reprovisionWidevine(onStep: (String) -> Unit = {}): WidevineReprovision {
    val before = checkWidevine()
    val backup = "$BACKUP_ROOT/${System.currentTimeMillis()}"
    val services = drmServices().filterValues { it == "running" }.keys
    // the framework DRM servers hold the HAL: stop them first, start them last
    val (framework, vendor) = services.partition { !it.startsWith("vendor.") }

    fun restart(clear: String) {
        root(*(framework + vendor).map { "setprop ctl.stop $it" }.toTypedArray())
        root(clear)
        root(*(vendor + framework).map { "setprop ctl.start $it" }.toTypedArray())
        // the HAL registers again within a second or two
        repeat(20) {
            if (drmServices().filterKeys { it in services }.values.all { state -> state == "running" }) return
            Thread.sleep(250)
        }
    }

    onStep("backup")
    root("mkdir -p $backup", "cp -a $MEDIADRM_DIR/. $backup/")
    if (root("ls -A $backup").isEmpty() && root("ls -A $MEDIADRM_DIR").isNotEmpty()) {
        return WidevineReprovision(before, before, restored = false, backup = backup, error = "backup failed")
    }

    onStep("clear")
    restart("rm -rf $MEDIADRM_DIR/*")

    onStep("provision")
    val error = runCatching { provision() }.exceptionOrNull()?.let {
        Log.w(TAG, "provisioning failed", it)
        it.brief()
    }
    val after = checkWidevine()

    val worse = (before.isL1 && !after.isL1) || (before.provisioned == true && after.provisioned != true)
    if (!worse) return WidevineReprovision(before, after, restored = false, backup = backup, error = error)

    onStep("restore")
    restart("rm -rf $MEDIADRM_DIR/* && cp -a $backup/. $MEDIADRM_DIR/ && restorecon -R $MEDIADRM_DIR")
    return WidevineReprovision(before, checkWidevine(), restored = true, backup = backup, error = error)
}
