package cam.su.kernel.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "AppManager"

/** Everything the app profile page shows about one installed app, for one user. */
data class AppDetails(
    val packageName: String,
    val userId: Int,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val uid: Int,
    val sharedUserId: String?,
    val targetSdk: Int,
    val minSdk: Int,
    val firstInstall: Long,
    val lastUpdate: Long,
    /** Package that installed it, or null when it came with the system or over adb. */
    val installer: String?,
    val abi: String?,
    val signature: String?,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val debuggable: Boolean,
    val frozen: Boolean,
    val hidden: Boolean,
    val apkPath: String,
    val splitPaths: List<String>,
    val dataDir: String,
    val deDataDir: String,
    val nativeDir: String?,
    val externalDir: String,
    val obbDir: String,
    /** Where the copy that ships in the ROM lives (/system/app/Foo, /product/priv-app/Bar...). */
    val systemCodePath: String?,
) {
    val hasNative get() = abi != null
}

/** Sizes in bytes. [data] and [external] do not include the caches, which are counted in [cache]. */
data class AppStorage(val apk: Long, val data: Long, val cache: Long, val external: Long) {
    val total get() = apk + data + cache + external
}

private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * Runs [cmds] as root in the global mount namespace: in this app's own namespace Android hides
 * other apps' data directories (app data isolation), so du and cleanup would find nothing there.
 */
private fun su(vararg cmds: String): Pair<Boolean, List<String>> {
    val out = ArrayList<String>()
    val result = getRootShell(globalMnt = true).newJob().add(*cmds).to(out, ArrayList()).exec()
    return result.isSuccess to out
}

suspend fun loadAppDetails(context: Context, info: PackageInfo, userId: Int): AppDetails = withContext(Dispatchers.IO) {
    val ai = info.applicationInfo!!
    val pkg = info.packageName
    val dump = su("dumpsys package $pkg").second
    fun field(name: String) = dump.firstNotNullOfOrNull { line ->
        val i = line.indexOf("$name=")
        if (i < 0) null else line.substring(i + name.length + 1).substringBefore(' ').trim()
    }
    // "User 0: ceDataInode=... installed=true hidden=false ... enabled=0 ..."
    val userLine = dump.firstOrNull { it.trim().startsWith("User $userId:") }.orEmpty()
    fun userField(name: String) = Regex("\\b$name=(\\S+)").find(userLine)?.groupValues?.get(1)
    val enabledState = userField("enabled")?.toIntOrNull() ?: 0
    val systemCode = dump.mapNotNull { line ->
        line.substringAfter("codePath=", "").trim().takeIf { p ->
            p.startsWith("/system/") || p.startsWith("/product/") || p.startsWith("/system_ext/") ||
                p.startsWith("/vendor/") || p.startsWith("/odm/")
        }
    }.firstOrNull()
    val installer = runCatching {
        if (userId == 0) context.packageManager.getInstallSourceInfo(pkg).installingPackageName else null
    }.getOrNull() ?: field("installerPackageName")?.takeIf { it != "null" }
    @Suppress("DEPRECATION")
    val sharedUid = info.sharedUserId
    AppDetails(
        packageName = pkg,
        userId = userId,
        label = ai.loadLabel(context.packageManager).toString(),
        versionName = info.versionName.orEmpty(),
        versionCode = info.longVersionCode,
        uid = ai.uid,
        sharedUserId = sharedUid,
        targetSdk = ai.targetSdkVersion,
        minSdk = ai.minSdkVersion,
        firstInstall = info.firstInstallTime,
        lastUpdate = info.lastUpdateTime,
        installer = installer,
        abi = field("primaryCpuAbi")?.takeIf { it != "null" && it.isNotBlank() },
        signature = signatureOf(context, ai.sourceDir, pkg),
        isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        isUpdatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
        debuggable = ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        frozen = if (userLine.isNotEmpty()) {
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } else {
            !ai.enabled
        },
        hidden = userField("hidden") == "true",
        apkPath = ai.sourceDir,
        splitPaths = ai.splitSourceDirs?.toList().orEmpty(),
        dataDir = "/data/user/$userId/$pkg",
        deDataDir = "/data/user_de/$userId/$pkg",
        nativeDir = ai.nativeLibraryDir,
        externalDir = "/storage/emulated/$userId/Android/data/$pkg",
        obbDir = "/storage/emulated/$userId/Android/obb/$pkg",
        systemCodePath = systemCode,
    )
}

/** SHA-256 of the signing certificate, as AA:BB:CC... */
private fun signatureOf(context: Context, apk: String, pkg: String): String? {
    val pm = context.packageManager
    val signing = runCatching { pm.getPackageArchiveInfo(apk, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo }.getOrNull()
        ?: runCatching { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo }.getOrNull()
        ?: return null
    val cert = (if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory)
        ?.firstOrNull() ?: return null
    return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
}

/** Measures the app with du as root (no usage-stats permission needed). */
suspend fun measureAppStorage(d: AppDetails): AppStorage = withContext(Dispatchers.IO) {
    val media = "/data/media/${d.userId}/Android"
    val ext = "$media/data/${d.packageName}"
    val caches = listOf(
        "${d.dataDir}/cache", "${d.dataDir}/code_cache", "${d.deDataDir}/cache", "${d.deDataDir}/code_cache",
    )
    fun kb(vararg paths: String) = "du -skc ${paths.joinToString(" ") { q(it) }} 2>/dev/null | tail -n 1 | cut -f1"
    val apkDirs = (listOf(d.apkPath) + d.splitPaths).map { File(it).parent ?: it }.distinct().toTypedArray()
    val (_, out) = su(
        "echo apk=$(${kb(*apkDirs)})",
        "echo data=$(${kb(d.dataDir, d.deDataDir)})",
        "echo cache=$(${kb(*caches.toTypedArray())})",
        "echo ext=$(${kb(ext, "$media/obb/${d.packageName}")})",
        "echo extcache=$(${kb("$ext/cache")})",
    )
    val v = out.associate { it.substringBefore('=') to (it.substringAfter('=').trim().toLongOrNull() ?: 0L) * 1024L }
    val extCache = v["extcache"] ?: 0L
    AppStorage(
        apk = v["apk"] ?: 0L,
        data = ((v["data"] ?: 0L) - (v["cache"] ?: 0L)).coerceAtLeast(0L),
        cache = (v["cache"] ?: 0L) + extCache,
        external = ((v["ext"] ?: 0L) - extCache).coerceAtLeast(0L),
    )
}

/** Empties the app's caches, inside and out. Returns the bytes freed. */
suspend fun clearAppCache(d: AppDetails): Long = withContext(Dispatchers.IO) {
    val before = measureAppStorage(d).cache
    val dirs = listOf(
        "${d.dataDir}/cache", "${d.dataDir}/code_cache", "${d.deDataDir}/cache", "${d.deDataDir}/code_cache",
        "/data/media/${d.userId}/Android/data/${d.packageName}/cache",
    )
    su(*dirs.map { "[ -d ${q(it)} ] && find ${q(it)} -mindepth 1 -delete" }.toTypedArray(), "true")
    (before - measureAppStorage(d).cache).coerceAtLeast(0L)
}

suspend fun clearAppData(d: AppDetails): Boolean = withContext(Dispatchers.IO) {
    su("pm clear --user ${d.userId} ${d.packageName}").second.any { it.contains("Success") }
}

suspend fun setAppFrozen(d: AppDetails, frozen: Boolean): Boolean = withContext(Dispatchers.IO) {
    val cmd = if (frozen) "pm disable-user --user ${d.userId}" else "pm enable --user ${d.userId}"
    su("$cmd ${d.packageName}").first
}

/** Uninstalls for this user; [keepData] leaves the data and cache behind (pm -k). */
suspend fun uninstallApp(d: AppDetails, keepData: Boolean = false): Boolean = withContext(Dispatchers.IO) {
    val k = if (keepData) " -k" else ""
    su("pm uninstall$k --user ${d.userId} ${d.packageName}").second.any { it.contains("Success") }
}

suspend fun uninstallSystemUpdates(d: AppDetails): Boolean = withContext(Dispatchers.IO) {
    su("cmd package uninstall-system-updates ${d.packageName}").second.any { it.contains("Success") }
}

/**
 * Removes a system app systemlessly: a small KernelSU module whose overlay replaces the app's
 * folder in the ROM with an empty one. It is also uninstalled for this user right away; disabling
 * or removing the module (and reinstalling the system app) brings it back.
 */
suspend fun removeSystemAppSystemless(context: Context, d: AppDetails): Boolean = withContext(Dispatchers.IO) {
    val codePath = d.systemCodePath ?: return@withContext false
    // Partitions other than /system sit under system/ inside a module.
    val inModule = if (codePath.startsWith("/system/")) codePath.removePrefix("/") else "system$codePath"
    val id = "debloat_" + d.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val zip = File(context.cacheDir, "$id.zip")
    runCatching {
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("module.prop"))
            z.write(
                """
                id=$id
                name=Gỡ ${d.label}
                version=v1
                versionCode=1
                author=SU Kernel
                description=Gỡ systemless ${d.packageName} ($codePath). Tắt hoặc xoá module để khôi phục.
                """.trimIndent().toByteArray()
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry("$inModule/.replace"))
            z.closeEntry()
        }
    }.onFailure {
        Log.e(TAG, "module zip failed", it)
        return@withContext false
    }
    val ok = execCamd("module install ${q(zip.absolutePath)}")
    zip.delete()
    if (ok) su("pm uninstall --user ${d.userId} ${d.packageName}")
    ok
}

/**
 * Copies the APK (and its splits, zipped into an .apks) to Download/SU Kernel/APK. Returns the
 * copy left in the cache for sharing, and the public path, or null on failure.
 */
suspend fun backupApk(context: Context, d: AppDetails): Pair<File, String>? = withContext(Dispatchers.IO) {
    val safe = "${d.label}_${d.versionName}".replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
    val dir = File(context.cacheDir, "apk_backup").apply {
        deleteRecursively()
        mkdirs()
    }
    val out = runCatching {
        if (d.splitPaths.isEmpty()) {
            File(dir, "$safe.apk").also { File(d.apkPath).copyTo(it, overwrite = true) }
        } else {
            File(dir, "$safe.apks").also { f ->
                ZipOutputStream(f.outputStream()).use { z ->
                    for (path in listOf(d.apkPath) + d.splitPaths) {
                        z.putNextEntry(ZipEntry(File(path).name))
                        File(path).inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        }
    }.getOrElse {
        Log.e(TAG, "backup failed", it)
        return@withContext null
    }
    // Into the user's Download folder as root, owned by the media provider like any download.
    val me = android.os.Process.myUid() / 100000
    val publicDir = "/data/media/$me/Download/SU Kernel/APK"
    val target = "$publicDir/${out.name}"
    val ok = su(
        "mkdir -p ${q(publicDir)}",
        "cp -f ${q(out.absolutePath)} ${q(target)}",
        "chown -R media_rw:media_rw ${q("/data/media/$me/Download/SU Kernel")}",
        "chmod 664 ${q(target)}",
        "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${q("file:///storage/emulated/$me/Download/SU Kernel/APK/${out.name}")} >/dev/null 2>&1; true",
    ).first
    if (ok) out to "Download/SU Kernel/APK/${out.name}" else null
}

/** Opens Android's own App info page for the app, as the right user. */
fun openSystemAppInfo(packageName: String, userId: Int) {
    su("am start --user $userId -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName")
}

/** System apps that were uninstalled for [userId] but can be reinstalled from the ROM: package to label. */
suspend fun listRemovedSystemApps(context: Context, userId: Int): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    fun list(flags: String) = su("pm list packages -s $flags --user $userId").second
        .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
    val removed = list("-u") - list("")
    removed.map { pkg ->
        val label = runCatching {
            context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(context.packageManager).toString()
        }.getOrDefault(pkg)
        pkg to label
    }.sortedBy { it.second.lowercase() }
}

suspend fun reinstallSystemApp(packageName: String, userId: Int): Boolean = withContext(Dispatchers.IO) {
    su("cmd package install-existing --user $userId $packageName").second.any { it.contains("installed", ignoreCase = true) }
}

private val CORE_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.phone",
    "com.android.providers.settings",
    "com.android.providers.telephony",
    "com.android.providers.media",
    "com.android.providers.media.module",
    "com.google.android.providers.media.module",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.shell",
    "com.android.networkstack",
    "com.google.android.networkstack",
    "com.android.server.telecom",
    "com.android.externalstorage",
)

/**
 * Apps that must not be removed or frozen: core system packages, the current launcher and
 * keyboard, and this app itself. Taking one of them away can leave the phone unable to boot.
 */
fun isProtectedApp(context: Context, packageName: String): Boolean {
    if (packageName in CORE_PACKAGES || packageName == context.packageName) return true
    val home = runCatching {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()
    if (packageName == home) return true
    val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    return packageName == ime
}

/** 1.2 GB, 340 MB, 12 KB... */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}
