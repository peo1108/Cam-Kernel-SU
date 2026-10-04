package me.weishu.kernelsu.ui.util

import android.content.Context
import android.system.Os
import androidx.compose.runtime.Immutable
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * The SUSFS settings ksud keeps in /data/adb/ksu/susfs.json and applies at every boot,
 * in place of the susfs4ksu module. Field names and values mirror userspace/ksud/src/susfs_config.rs.
 */

enum class HideSusMnts(val key: String) { Off("off"), Always("always"), UntilBootCompleted("untilBootCompleted") }

enum class UnameStage(val key: String) { BootCompleted("bootCompleted"), PostFsData("postFsData") }

enum class BootconfigMode(val key: String) { Off("off"), Auto("auto"), Custom("custom") }

enum class VoldAppData(val key: String) { Off("off"), SusPath("susPath"), SusPathLoop("susPathLoop") }

enum class RedirectStage(val key: String) { BootCompleted("bootCompleted"), Service("service") }

@Immutable
data class SusPathEntry(val path: String, val wait: Int = 0)

@Immutable
data class OpenRedirectEntry(
    val target: String,
    val redirect: String,
    /** 0..4, see the uid scheme strings */
    val uidScheme: Int = 2,
    val stage: RedirectStage = RedirectStage.BootCompleted,
)

@Immutable
data class KstatEntry(
    val path: String,
    /** in [KSTAT_FIELDS] order; "default" keeps the real value */
    val fields: List<String> = List(KSTAT_FIELDS.size) { "default" },
)

val KSTAT_FIELDS = listOf(
    "ino", "dev", "nlink", "size", "atime", "atimeNsec", "mtime", "mtimeNsec", "ctime", "ctimeNsec", "blocks", "blksize",
)

val DEFAULT_LEGIT_MOUNTS = listOf(
    "/system", "/system_ext", "/vendor", "/odm", "/product", "/system_dlkm", "/vendor_dlkm", "/odm_dlkm",
    "/apex", "/system/app", "/system/priv-app", "/system/lib", "/system/lib64", "/vendor/app",
    "/vendor/priv-app", "/vendor/lib", "/vendor/lib64", "/product/app", "/product/priv-app",
    "/product/lib", "/product/lib64", "/system_ext/app", "/system_ext/priv-app", "/system_ext/lib",
    "/system_ext/lib64", "/data", "/cache", "/metadata", "/persist", "/mnt", "/storage",
    "/debug_ramdisk", "/dev", "/proc", "/sys", "/sys/fs/cgroup", "/my_product", "/my_engineering",
    "/my_company", "/my_carrier", "/my_region", "/my_heytap", "/my_stock", "/my_preload",
    "/my_bigball", "/my_manifest",
)

@Immutable
data class SusfsConfig(
    val enableLog: Boolean = true,
    val avcLogSpoofing: Boolean = false,
    val hideSusMnts: HideSusMnts = HideSusMnts.Always,
    val unameRelease: String = "",
    val unameVersion: String = "",
    val unameStage: UnameStage = UnameStage.BootCompleted,
    val bootconfigMode: BootconfigMode = BootconfigMode.Off,
    val fakeBootconfig: String = "",
    val hideLoops: Boolean = false,
    val hideVendorSepolicy: Boolean = false,
    val hideCompatMatrix: Boolean = false,
    /** 0 off, 1..5 */
    val hideCusrom: Int = 0,
    val hideGapps: Boolean = false,
    val hideRevanced: Boolean = false,
    val forceHideLsposed: Boolean = false,
    val emulateVoldAppData: VoldAppData = VoldAppData.Off,
    val autoTryUmount: Boolean = false,
    val skipLegitMounts: Boolean = false,
    val legitMounts: List<String> = DEFAULT_LEGIT_MOUNTS,
    val spoofProps: Boolean = false,
    val vbmetaSize: Int = 8192,
    val vbmetaDigest: String = "",
    val susPaths: List<SusPathEntry> = emptyList(),
    val susPathLoops: List<SusPathEntry> = emptyList(),
    val susMaps: List<String> = emptyList(),
    val tryUmounts: List<String> = emptyList(),
    val openRedirects: List<OpenRedirectEntry> = emptyList(),
    val susKstats: List<KstatEntry> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enableLog", enableLog)
        put("avcLogSpoofing", avcLogSpoofing)
        put("hideSusMnts", hideSusMnts.key)
        put("unameRelease", unameRelease)
        put("unameVersion", unameVersion)
        put("unameStage", unameStage.key)
        put("bootconfigMode", bootconfigMode.key)
        put("fakeBootconfig", fakeBootconfig)
        put("hideLoops", hideLoops)
        put("hideVendorSepolicy", hideVendorSepolicy)
        put("hideCompatMatrix", hideCompatMatrix)
        put("hideCusrom", hideCusrom)
        put("hideGapps", hideGapps)
        put("hideRevanced", hideRevanced)
        put("forceHideLsposed", forceHideLsposed)
        put("emulateVoldAppData", emulateVoldAppData.key)
        put("autoTryUmount", autoTryUmount)
        put("skipLegitMounts", skipLegitMounts)
        put("legitMounts", JSONArray(legitMounts))
        put("spoofProps", spoofProps)
        put("vbmetaSize", vbmetaSize)
        put("vbmetaDigest", vbmetaDigest)
        put("susPaths", susPathsJson(susPaths))
        put("susPathLoops", susPathsJson(susPathLoops))
        put("susMaps", JSONArray(susMaps))
        put("tryUmounts", JSONArray(tryUmounts))
        put("openRedirects", JSONArray().apply {
            openRedirects.forEach {
                put(JSONObject().apply {
                    put("target", it.target)
                    put("redirect", it.redirect)
                    put("uidScheme", it.uidScheme)
                    put("stage", it.stage.key)
                })
            }
        })
        put("susKstats", JSONArray().apply {
            susKstats.forEach { entry ->
                put(JSONObject().apply {
                    put("path", entry.path)
                    KSTAT_FIELDS.forEachIndexed { i, name -> put(name, entry.fields.getOrElse(i) { "default" }) }
                })
            }
        })
    }

    companion object {
        private fun susPathsJson(list: List<SusPathEntry>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("path", it.path).put("wait", it.wait)) }
        }

        private fun JSONObject.strings(key: String): List<String>? = optJSONArray(key)?.let { array ->
            List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
        }

        private fun JSONObject.susPaths(key: String): List<SusPathEntry> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { i ->
                when (val item = array.opt(i)) {
                    is JSONObject -> SusPathEntry(item.optString("path"), item.optInt("wait"))
                    else -> SusPathEntry(item?.toString().orEmpty())
                }
            }.filter { it.path.isNotBlank() }
        }

        private inline fun <reified T : Enum<T>> JSONObject.choice(key: String, default: T, keyOf: (T) -> String): T =
            enumValues<T>().firstOrNull { keyOf(it) == optString(key) } ?: default

        fun fromJson(json: JSONObject): SusfsConfig {
            val d = SusfsConfig()
            val redirects = json.optJSONArray("openRedirects")
            val kstats = json.optJSONArray("susKstats")
            return SusfsConfig(
                enableLog = json.optBoolean("enableLog", d.enableLog),
                avcLogSpoofing = json.optBoolean("avcLogSpoofing", d.avcLogSpoofing),
                hideSusMnts = json.choice("hideSusMnts", d.hideSusMnts) { it.key },
                unameRelease = json.optString("unameRelease"),
                unameVersion = json.optString("unameVersion"),
                unameStage = json.choice("unameStage", d.unameStage) { it.key },
                bootconfigMode = json.choice("bootconfigMode", d.bootconfigMode) { it.key },
                fakeBootconfig = json.optString("fakeBootconfig"),
                hideLoops = json.optBoolean("hideLoops"),
                hideVendorSepolicy = json.optBoolean("hideVendorSepolicy"),
                hideCompatMatrix = json.optBoolean("hideCompatMatrix"),
                hideCusrom = json.optInt("hideCusrom").coerceIn(0, 5),
                hideGapps = json.optBoolean("hideGapps"),
                hideRevanced = json.optBoolean("hideRevanced"),
                forceHideLsposed = json.optBoolean("forceHideLsposed"),
                emulateVoldAppData = json.choice("emulateVoldAppData", d.emulateVoldAppData) { it.key },
                autoTryUmount = json.optBoolean("autoTryUmount"),
                skipLegitMounts = json.optBoolean("skipLegitMounts"),
                legitMounts = json.strings("legitMounts") ?: d.legitMounts,
                spoofProps = json.optBoolean("spoofProps"),
                vbmetaSize = json.optInt("vbmetaSize", d.vbmetaSize),
                vbmetaDigest = json.optString("vbmetaDigest"),
                susPaths = json.susPaths("susPaths"),
                susPathLoops = json.susPaths("susPathLoops"),
                susMaps = json.strings("susMaps").orEmpty(),
                tryUmounts = json.strings("tryUmounts").orEmpty(),
                openRedirects = List(redirects?.length() ?: 0) { i ->
                    val o = redirects!!.getJSONObject(i)
                    OpenRedirectEntry(
                        target = o.optString("target"),
                        redirect = o.optString("redirect"),
                        uidScheme = o.optInt("uidScheme", 2),
                        stage = o.choice("stage", RedirectStage.BootCompleted) { it.key },
                    )
                },
                susKstats = List(kstats?.length() ?: 0) { i ->
                    val o = kstats!!.getJSONObject(i)
                    KstatEntry(o.optString("path"), KSTAT_FIELDS.map { o.optString(it, "default").ifBlank { "default" } })
                },
            )
        }
    }
}

@Immutable
data class SusfsState(
    /** null: the kernel has no SUSFS */
    val info: SusfsInfo? = null,
    val config: SusfsConfig = SusfsConfig(),
    /** the susfs4ksu module is installed and enabled, so ksud leaves SUSFS to it */
    val moduleActive: Boolean = false,
    /** uname -r / -v of the running kernel, as apps see it now */
    val currentRelease: String = "",
    val currentVersion: String = "",
)

@Immutable
data class SusfsSaveResult(
    val saved: Boolean,
    /** the settings were refused */
    val errors: List<String> = emptyList(),
    /** saved, but these could not be applied now */
    val failed: List<String> = emptyList(),
    val rebootNeeded: Boolean = false,
)

suspend fun loadSusfsState(): SusfsState = withContext(Dispatchers.IO) {
    val info = getSusfsInfo()
    val json = runCatching { JSONObject(ksudStdout("susfs config")) }.getOrNull()
    val uname = Os.uname()
    SusfsState(
        info = info,
        config = json?.optJSONObject("config")?.let { SusfsConfig.fromJson(it) } ?: SusfsConfig(),
        moduleActive = json?.optBoolean("moduleActive") ?: false,
        currentRelease = uname.release,
        currentVersion = uname.version,
    )
}

/** Hand the settings to ksud, which saves them and applies what it can right away. */
suspend fun saveSusfsConfig(context: Context, config: SusfsConfig): SusfsSaveResult = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "susfs.json")
    file.writeText(config.toJson().toString())
    try {
        val json = JSONObject(ksudStdout("susfs set-config '${file.absolutePath}'"))
        fun list(key: String) = json.optJSONArray(key)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        SusfsSaveResult(
            saved = json.optBoolean("saved"),
            errors = list("errors"),
            failed = list("failed"),
            rebootNeeded = json.optBoolean("rebootNeeded"),
        )
    } catch (e: Exception) {
        SusfsSaveResult(saved = false, errors = listOf(e.message ?: e.javaClass.simpleName))
    } finally {
        file.delete()
    }
}

/** The fake bootconfig the auto mode builds from this device's real one. */
suspend fun getAutoBootconfig(): String = withContext(Dispatchers.IO) { ksudStdout("susfs bootconfig") }

suspend fun getSusfsLog(): String = withContext(Dispatchers.IO) { ksudStdout("susfs log") }

/** `#1 SMP PREEMPT <ro.build.date>`, what a stock kernel of this build reports, as the module offers it. */
suspend fun stockKernelBuild(): String = withContext(Dispatchers.IO) {
    val date = ShellUtils.fastCmd(getRootShell(), "getprop ro.build.date").trim()
    if (date.isEmpty()) "" else "#1 SMP PREEMPT $date"
}
