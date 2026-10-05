package me.weishu.kernelsu.ui.util

import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.ksuApp
import okhttp3.CacheControl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** GitHub repo whose releases carry the GKI + SUSFS AnyKernel3 zips (built by build-sfs-kernel.yml). */
const val SFS_RELEASE_REPO = "peo1108/Cam-Kernel-SU"

// KernelSU-SFS-<android release>-<KMI generation>-<kernel version>-<ksu version>-susfs-<susfs version>.zip,
// e.g. KernelSU-SFS-android16-5-6.12.30-32760-susfs-v2.3.0.zip; see build-sfs-kernel.yml
/** Build info + sha256 of every zip of a release, merged by release.yml from what sfs/build.sh writes. */
private const val SFS_MANIFEST = "sfs-manifest.json"

private val SFS_ASSET = Regex("""^KernelSU-SFS-(android\d+)-(\d+)-(\d+\.\d+)\.(\d+)-(\d+)-susfs-(.+)\.zip$""")

/** 30 from a kernel release such as 6.12.30-android16-5-...; -1 when it cannot be read. */
fun sublevelOf(release: String): Int =
    Regex("""^\d+\.\d+\.(\d+)""").find(release)?.groupValues?.get(1)?.toIntOrNull() ?: -1

/** android16-5 from a GKI release such as 6.12.30-android16-5-g1750f757fabe-ab13938768-4k */
fun kmiTagOf(release: String): String =
    Regex("""-(android\d+-\d+)(-|$)""").find(release)?.groupValues?.get(1).orEmpty()

@Immutable
@Parcelize
data class SfsBuild(
    val tag: String,
    val fileName: String,
    val kmi: String,
    /** e.g. android16-5: the KMI generation, which must match the device's */
    val kmiTag: String,
    /** the 30 of 6.12.30: the GKI release the kernel was built from */
    val sublevel: Int,
    val ksuVersion: Int,
    val susfsVersion: String,
    val url: String,
    val size: Long,
    /** ISO-8601 date the release was published */
    val publishedAt: String,
    /** from the release's sfs-manifest.json; empty for releases made before it existed */
    val sha256: String = "",
) : Parcelable {
    /** e.g. 6.12.30 */
    val kernelVersion: String
        get() = "${kmi.substringAfter('-')}.$sublevel"
}

/**
 * The build to preselect for a device running [deviceSublevel]: the same GKI
 * release if there is one, else the closest one (the lower on a tie, since
 * vendor modules are built against the release the device shipped). Among
 * builds of that release the newest wins; [builds] is newest first.
 */
fun recommendSfsBuild(builds: List<SfsBuild>, deviceSublevel: Int): SfsBuild? {
    if (builds.isEmpty()) return null
    if (deviceSublevel < 0) return builds.first()
    return builds.minWithOrNull(
        compareBy<SfsBuild> { kotlin.math.abs(it.sublevel - deviceSublevel) }
            .thenBy { it.sublevel }
            .thenBy { builds.indexOf(it) }
    )
}

/**
 * AnyKernel3 builds for [kmi] and the KMI generation [kmiTag] that carry exactly
 * [ksuVersion], the version of this Manager: release.yml builds the Manager and
 * the kernels from one commit, so a kernel of another version would not match
 * this Manager's ksud and is never offered. A build of another generation would
 * bootloop, so those are left out too; any sublevel of the right generation is
 * offered. Empty when no release carries this version (e.g. a local build).
 */
suspend fun fetchSfsBuilds(kmi: String, kmiTag: String, ksuVersion: Int): List<SfsBuild> =
    withContext(Dispatchers.IO) {
        // the release is tagged sfs-<version>; look it up directly first
        val tagged = getJson("https://api.github.com/repos/$SFS_RELEASE_REPO/releases/tags/sfs-$ksuVersion")
            ?.let { sfsBuildsOf(JSONArray().put(JSONObject(it)), kmi, kmiTag, ksuVersion) }
        if (!tagged.isNullOrEmpty()) return@withContext tagged
        // no such tag, or it lacks this KMI: the zip may sit in another release of this version
        val recent = getJson("https://api.github.com/repos/$SFS_RELEASE_REPO/releases?per_page=30")
            ?: throw IOException("GitHub: HTTP 404")
        sfsBuildsOf(JSONArray(recent), kmi, kmiTag, ksuVersion)
    }

private fun sfsBuildsOf(releases: JSONArray, kmi: String, kmiTag: String, ksuVersion: Int): List<SfsBuild> =
    buildList {
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            // fetched only for a release that has a build for this device
            val manifest by lazy { manifestOf(assets) }
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                val match = SFS_ASSET.matchEntire(name) ?: continue
                val (android, generation, kernel, sublevel, version) = match.destructured
                val assetKmi = "$android-$kernel"
                val assetTag = "$android-$generation"
                if (assetKmi != kmi || assetTag != kmiTag) continue
                if (version.toIntOrNull() != ksuVersion) continue
                val entry = manifest[name]
                add(
                    SfsBuild(
                        tag = release.optString("tag_name"),
                        fileName = name,
                        kmi = kmi,
                        kmiTag = assetTag,
                        sublevel = sublevel.toInt(),
                        ksuVersion = ksuVersion,
                        susfsVersion = entry?.optString("susfsVersion")?.takeIf { it.isNotEmpty() }
                            ?: match.groupValues[6],
                        url = asset.optString("browser_download_url"),
                        size = asset.optLong("size"),
                        publishedAt = release.optString("published_at"),
                        sha256 = entry?.optString("sha256").orEmpty().lowercase(),
                    )
                )
            }
        }
    }

/**
 * The entries of a release's sfs-manifest.json by zip name. Empty when the release has none
 * (made before it existed) or it cannot be read: the builds are still offered, unverified.
 */
private fun manifestOf(assets: JSONArray): Map<String, JSONObject> {
    val url = (0 until assets.length())
        .map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name") == SFS_MANIFEST }
        ?.optString("browser_download_url")
        ?.takeIf { it.isNotEmpty() }
        ?: return emptyMap()
    return runCatching {
        val builds = JSONObject(getText(url, github = false) ?: return emptyMap()).optJSONArray("builds")
            ?: return emptyMap()
        (0 until builds.length())
            .map { builds.getJSONObject(it) }
            .associateBy { it.optString("file") }
    }.onFailure { Log.w("KernelSU", "cannot read $SFS_MANIFEST: $it") }.getOrDefault(emptyMap())
}

/** Lowercase hex SHA-256 of [file]. */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** The body of a GitHub API GET, or null on 404. */
private fun getJson(url: String): String? = getText(url, github = true)

/** The body of a GET, or null on 404; [github] asks the API for JSON. */
private fun getText(url: String, github: Boolean): String? {
    val request = Request.Builder()
        .url(url)
        .apply { if (github) header("Accept", "application/vnd.github+json") }
        // revalidate: a retry right after the zips are uploaded must not get the cached list
        .cacheControl(CacheControl.Builder().noCache().build())
        .build()
    return ksuApp.okhttpClient.newCall(request).execute().use { response ->
        if (response.code == 404) return@use null
        if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
        response.body.string()
    }
}

/** Downloads [url] to [dest], reporting whole percents through [onProgress]. */
fun downloadTo(url: String, dest: File, onProgress: (Int) -> Unit) {
    val request = Request.Builder().url(url).build()
    ksuApp.okhttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body
        val total = body.contentLength()
        body.byteStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
    }
}
