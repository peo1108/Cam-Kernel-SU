package cam.su.kernel.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import cam.su.kernel.camApp
import cam.su.kernel.ui.util.getRootShell
import cam.su.kernel.ui.util.rootAvailable
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val percent: Int, val downloaded: Long = 0, val total: Long = 0) : UpdateState
    data object Verifying : UpdateState
    data object Installing : UpdateState
    data class Failed(val reason: UpdateFailure, val detail: String? = null) : UpdateState
}

enum class UpdateFailure { DOWNLOAD, CHECKSUM, PACKAGE, SIGNATURE, VERSION, INSTALL }

/** The three steps the progress dialog shows; null while idle. */
enum class UpdateStep { DOWNLOAD, VERIFY, INSTALL }

val UpdateState.step: UpdateStep?
    get() = when (this) {
        UpdateState.Idle -> null
        is UpdateState.Downloading -> UpdateStep.DOWNLOAD
        UpdateState.Verifying -> UpdateStep.VERIFY
        UpdateState.Installing -> UpdateStep.INSTALL
        is UpdateState.Failed -> when (reason) {
            UpdateFailure.DOWNLOAD -> UpdateStep.DOWNLOAD
            UpdateFailure.CHECKSUM, UpdateFailure.PACKAGE, UpdateFailure.SIGNATURE, UpdateFailure.VERSION -> UpdateStep.VERIFY
            UpdateFailure.INSTALL -> UpdateStep.INSTALL
        }
    }

/** "8,2 / 21,4 MB" (or "8.2 / 21.4 MB"); only the downloaded part when the size is unknown. */
fun formatMegabytes(downloaded: Long, total: Long, locale: Locale = Locale.getDefault()): String {
    fun mb(bytes: Long) = String.format(locale, "%.1f", bytes / 1_048_576.0)
    return if (total > 0) "${mb(downloaded)} / ${mb(total)} MB" else "${mb(downloaded)} MB"
}

private const val TAG = "UpdateInstaller"
private const val OTA_DIR = "ota"
private const val RESULT_FILE = "result"
private const val SCRIPT_FILE = "install.sh"
private const val INSTALL_TIMEOUT_MS = 120_000L
private const val POLL_MS = 500L

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Root install, run detached (setsid) by [UpdateInstaller]. KernelSU's su stays in the app's
 * cgroup, and Android kills that whole cgroup when it replaces the app, so the script moves
 * itself to the root cgroup first. The APK goes through stdin so system_server never has to
 * open a file in the app's private directory.
 */
internal fun rootInstallScript(apk: File, size: Long, result: File): String =
    "echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null; " +
        "echo $$ > /acct/cgroup.procs 2>/dev/null; " +
        "cat '${apk.invariantSeparatorsPath}' | pm install -r -S $size > '${result.invariantSeparatorsPath}' 2>&1; " +
        "grep -q Success '${result.invariantSeparatorsPath}' && am start -n cam.su.kernel/.ui.CamActivity\n"

/** Downloads, verifies and installs a Manager update; one at a time, process-wide. */
object UpdateInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private fun otaDir(context: Context) = File(context.cacheDir, OTA_DIR)

    private fun verifiedApk(context: Context): File? =
        otaDir(context).listFiles { f -> f.name.endsWith(".apk") }?.firstOrNull()

    fun start(context: Context, info: UpdateInfo) {
        val current = _state.value
        if (current !is UpdateState.Idle && current !is UpdateState.Failed) return
        _state.value = UpdateState.Downloading(0)
        val app = context.applicationContext
        scope.launch {
            val apk = download(app, info) ?: return@launch
            _state.value = UpdateState.Verifying
            val failure = verify(app, apk, info)
            if (failure != null) {
                apk.delete()
                _state.value = UpdateState.Failed(failure)
                return@launch
            }
            if (rootAvailable()) installWithRoot(app, apk) else launchSystemInstaller(app, apk)
        }
    }

    fun installWithSystemInstaller(context: Context) {
        val apk = verifiedApk(context) ?: run {
            _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD)
            return
        }
        launchSystemInstaller(context.applicationContext, apk)
    }

    fun cleanup(context: Context) {
        otaDir(context).listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun download(context: Context, info: UpdateInfo): File? {
        cleanup(context)
        val dir = otaDir(context).apply { mkdirs() }
        val target = File(dir, info.apkName)
        return runCatching {
            val request = Request.Builder().url(info.apkUrl).build()
            camApp.okhttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                var soFar = 0L
                target.outputStream().use { out ->
                    val source = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        soFar += read
                        if (total > 0) {
                            _state.value = UpdateState.Downloading(((soFar * 100) / total).toInt().coerceIn(0, 100), soFar, total)
                        }
                    }
                }
            }
            target
        }.onFailure {
            Log.w(TAG, "download failed", it)
            target.delete()
            _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, it.message)
        }.getOrNull()
    }

    private fun verify(context: Context, apk: File, info: UpdateInfo): UpdateFailure? {
        if (info.sha256 != null && sha256Hex(apk) != info.sha256) return UpdateFailure.CHECKSUM
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return UpdateFailure.PACKAGE
        if (archive.packageName != context.packageName) return UpdateFailure.PACKAGE
        if (archive.longVersionCode != info.versionCode) return UpdateFailure.VERSION
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val newSigners = archive.signingInfo?.apkContentsSigners?.toSet()
        val oldSigners = installed.signingInfo?.apkContentsSigners?.toSet()
        if (newSigners.isNullOrEmpty() || newSigners != oldSigners) return UpdateFailure.SIGNATURE
        return null
    }

    private suspend fun installWithRoot(context: Context, apk: File) {
        _state.value = UpdateState.Installing
        val dir = otaDir(context)
        val result = File(dir, RESULT_FILE).apply { delete() }
        val script = File(dir, SCRIPT_FILE)
        script.writeText(rootInstallScript(apk, apk.length(), result))
        getRootShell().newJob()
            .add("setsid sh '${script.path}' >/dev/null 2>&1 &")
            .exec()

        var waited = 0L
        while (waited < INSTALL_TIMEOUT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            val text = result.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            if (text.isEmpty()) continue
            // pm still writing, or done: on Success Android is about to kill this process
            if (text.contains("Success")) return
            if (text.contains("Failure") || text.contains("Error") || text.contains("Exception")) {
                _state.value = UpdateState.Failed(UpdateFailure.INSTALL, text.lineSequence().first())
                return
            }
        }
        _state.value = UpdateState.Failed(UpdateFailure.INSTALL)
    }

    private fun launchSystemInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { _state.value = UpdateState.Idle }
            .onFailure { _state.value = UpdateState.Failed(UpdateFailure.INSTALL, it.message) }
    }
}
