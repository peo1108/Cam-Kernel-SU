package cam.su.kernel.update

import org.json.JSONArray

/** A Cam release newer than the running Manager, read from the GitHub Releases API. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lowercase hex from the asset's "sha256:<hex>" digest; null when GitHub gave none. */
    val sha256: String?,
    /** Release notes, which the Release workflow copies from CHANGELOG.md. */
    val changelog: String,
) {
    /** First "- " line of the notes, shown in the notification. */
    val summary: String?
        get() = changelog.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("- ") }
            ?.removePrefix("- ")
            ?.trim()
}

private const val TAG_PREFIX = "cam-v"
// <app name>_<version name>_<version code>-release.apk (archivesName + repack_apk.py); a release has one APK
private val APK_NAME = Regex("""_(\d+)-release\.apk$""")

/**
 * The newest published cam-v* release whose APK is newer than [currentVersionCode], or null.
 * Drafts, pre-releases, other tags (sfs-*, upstream v*) and anything unparsable are skipped.
 */
fun parseReleases(json: String, currentVersionCode: Long): UpdateInfo? = runCatching {
    val releases = JSONArray(json)
    var best: UpdateInfo? = null
    for (i in 0 until releases.length()) {
        val release = releases.getJSONObject(i)
        val tag = release.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) continue
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (j in 0 until assets.length()) {
            val asset = assets.getJSONObject(j)
            val name = asset.optString("name")
            val code = APK_NAME.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (best != null && code <= best.versionCode) continue
            best = UpdateInfo(
                versionName = tag.removePrefix(TAG_PREFIX),
                versionCode = code,
                apkName = name,
                apkUrl = asset.getString("browser_download_url"),
                apkSize = asset.optLong("size"),
                sha256 = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                    ?.removePrefix("sha256:")?.lowercase(),
                changelog = if (release.isNull("body")) "" else release.optString("body"),
            )
        }
    }
    best?.takeIf { it.versionCode > currentVersionCode }
}.getOrNull()
