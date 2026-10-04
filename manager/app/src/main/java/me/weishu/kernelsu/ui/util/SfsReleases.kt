package me.weishu.kernelsu.ui.util

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.ksuApp
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException

/** GitHub repo whose releases carry the GKI + SUSFS AnyKernel3 zips (built by build-sfs-kernel.yml). */
const val SFS_RELEASE_REPO = "peo1108/Cam-Kernel-SU"

// KernelSU-SFS-<android release>-<KMI generation>-<kernel version>-<ksu version>-susfs-<susfs version>.zip,
// e.g. KernelSU-SFS-android16-5-6.12.30-32760-susfs-v2.3.0.zip; see build-sfs-kernel.yml
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
 * AnyKernel3 builds for [kmi] and the KMI generation [kmiTag] from the project's
 * releases, newest release first. A build of another generation would bootloop,
 * so those are left out; any sublevel of the right generation is offered.
 */
suspend fun fetchSfsBuilds(kmi: String, kmiTag: String): List<SfsBuild> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("https://api.github.com/repos/$SFS_RELEASE_REPO/releases?per_page=30")
        .header("Accept", "application/vnd.github+json")
        .build()
    val body = ksuApp.okhttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
        response.body.string()
    }
    val releases = JSONArray(body)
    buildList {
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                val match = SFS_ASSET.matchEntire(name) ?: continue
                val (android, generation, kernel, sublevel) = match.destructured
                val assetKmi = "$android-$kernel"
                val assetTag = "$android-$generation"
                if (assetKmi != kmi || assetTag != kmiTag) continue
                add(
                    SfsBuild(
                        tag = release.optString("tag_name"),
                        fileName = name,
                        kmi = kmi,
                        kmiTag = assetTag,
                        sublevel = sublevel.toInt(),
                        ksuVersion = match.groupValues[5].toInt(),
                        susfsVersion = match.groupValues[6],
                        url = asset.optString("browser_download_url"),
                        size = asset.optLong("size"),
                        publishedAt = release.optString("published_at"),
                    )
                )
            }
        }
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
