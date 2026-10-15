package cam.su.kernel.ui.viewmodel

import cam.su.kernel.camApp
import cam.su.kernel.files.Archives
import cam.su.kernel.files.CamFileClient
import cam.su.kernel.files.FileEntry
import cam.su.kernel.files.FsException
import cam.su.kernel.files.FsPath
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** The read-only dialogs of the Files tab: properties, checksums, APK info and search. */
class FilesInspector internal constructor(private val host: FilesHost) {
    private val state get() = host.state

    /** Tells a dialog's opening from the next one, so a late answer for an old one is dropped. */
    private var dialogToken = 0

    fun showProperties(entry: FileEntry) {
        val token = ++dialogToken
        state.update { it.copy(properties = PropertiesUi(entry, token)) }
        host.scope.launch {
            val measure = try {
                CamFileClient.measure(entry.path)
            } catch (_: FsException) {
                return@launch
            }
            state.update { s -> s.copy(properties = s.properties?.let { if (it.token == token) it.copy(measure = measure) else it }) }
        }
    }

    fun hideProperties() {
        val measuring = state.value.properties?.measure == null
        state.update { it.copy(properties = null) }
        if (measuring) cancelReads()
    }

    internal suspend fun reloadProperties(path: String) {
        if (state.value.properties?.entry?.path != path) return
        CamFileClient.stat(path)?.let { fresh -> state.update { it.copy(properties = it.properties?.copy(entry = fresh)) } }
    }

    fun showChecksum(entry: FileEntry) {
        val token = ++dialogToken
        state.update { it.copy(checksum = ChecksumUi(entry, token)) }
        host.scope.launch {
            try {
                val values = CamFileClient.checksums(entry.path)
                state.update { s -> s.copy(checksum = s.checksum?.let { if (it.token == token) it.copy(values = values) else it }) }
            } catch (e: FsException) {
                // closed meanwhile (that cancelled it): nothing to report
                if (state.value.checksum?.token != token) return@launch
                state.update { it.copy(checksum = null, message = FilesMessage.Failed(e.errno, e.message)) }
            }
        }
    }

    fun hideChecksum() {
        val computing = state.value.checksum?.values == null
        state.update { it.copy(checksum = null) }
        if (computing) cancelReads()
    }

    /** A closed Properties or Checksum dialog stops its walk or hash on the root side. */
    private fun cancelReads() {
        host.scope.launch { runCatching { CamFileClient.cancelReads() } }
    }

    /** Reads the APK (or the base APK inside a bundle) like the installer would, without installing. */
    fun showApkInfo(entry: FileEntry) {
        state.update { it.copy(busy = true) }
        host.scope.launch {
            try {
                val source = if (Archives.isApkBundle(entry.name)) {
                    val inside = CamFileClient.listDir(Archives.join(entry.path, "")).filter { it.name.endsWith(".apk", true) }
                    (inside.firstOrNull { it.name.equals("base.apk", true) } ?: inside.firstOrNull())?.path
                        ?: throw FsException(cam.su.kernel.files.Errno.ENOENT, entry.path)
                } else {
                    entry.path
                }
                val file = File(camApp.cacheDir, "apk_info.apk")
                CamFileClient.copyToLocal(source, file)
                val info = readApk(file) ?: throw FsException(cam.su.kernel.files.Errno.EINVAL, entry.name, "not a valid APK")
                state.update { it.copy(apkInfo = info) }
            } catch (e: FsException) {
                state.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun hideApkInfo() = state.update { it.copy(apkInfo = null) }

    @Suppress("DEPRECATION")
    private fun readApk(file: File): ApkInfoUi? {
        val pm = camApp.packageManager
        val flags = android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let { cert ->
            java.security.MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
        }
        return ApkInfoUi(
            label = app.loadLabel(pm).toString(),
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            signer = signer,
            permissions = info.requestedPermissions?.toList().orEmpty(),
            icon = runCatching { app.loadIcon(pm) }.getOrNull(),
        )
    }

    /** MT's search: from the active folder down, by name and optionally by content. */
    fun search(pattern: String, content: String) {
        val root = state.value.activePanel.nav.path
        if (Archives.isInside(root)) return
        state.update { it.copy(search = SearchUi(root)) }
        host.scope.launch {
            val results = try {
                CamFileClient.search(root, pattern, content.ifEmpty { null }, SEARCH_LIMIT)
            } catch (e: FsException) {
                state.update { it.copy(message = FilesMessage.Failed(e.errno, e.message)) }
                emptyList()
            }
            state.update { s -> s.copy(search = s.search?.copy(running = false, results = results)) }
        }
    }

    fun stopSearch() {
        host.scope.launch { runCatching { CamFileClient.cancelSearch() } }
    }

    fun closeSearch() {
        if (state.value.search?.running == true) stopSearch()
        state.update { it.copy(search = null) }
    }

    /** A search hit: its folder opens in the active panel. */
    fun reveal(entry: FileEntry) {
        closeSearch()
        host.navigateActive(FsPath.parent(entry.path))
    }

    private companion object {
        const val SEARCH_LIMIT = 500
    }
}
