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
    /**
     * Release notes, which the Release workflow copies from CHANGELOG.md. When the update skips
     * releases, every skipped one is here too, newest first, each under a `## <version>` heading.
     */
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
 * The newest published cam-v* release whose APK is newer than [currentVersionCode], or null, with
 * the notes of every newer release folded into its changelog. Drafts, other tags (sfs-*, upstream
 * v*) and anything unparsable are skipped; pre-releases (cam-vX.Y.Z-beta.N) only with
 * [includePrereleases], the "beta updates" setting.
 */
fun parseReleases(json: String, currentVersionCode: Long, includePrereleases: Boolean = false): UpdateInfo? = runCatching {
    val releases = JSONArray(json)
    val newer = mutableListOf<UpdateInfo>()
    for (i in 0 until releases.length()) {
        val release = releases.getJSONObject(i)
        val tag = release.optString("tag_name")
        if (!tag.startsWith(TAG_PREFIX)) continue
        if (release.optBoolean("draft")) continue
        if (release.optBoolean("prerelease") && !includePrereleases) continue
        val assets = release.optJSONArray("assets") ?: continue
        var apk: UpdateInfo? = null
        for (j in 0 until assets.length()) {
            val asset = assets.getJSONObject(j)
            val name = asset.optString("name")
            val code = APK_NAME.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (apk != null && code <= apk.versionCode) continue
            apk = UpdateInfo(
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
        if (apk != null && apk.versionCode > currentVersionCode) newer += apk
    }
    val sorted = newer.sortedByDescending { it.versionCode }
    val best = sorted.firstOrNull() ?: return@runCatching null
    // betas of one version share its CHANGELOG.md entry: keep the newest of each
    val notes = sorted.distinctBy { it.versionName.substringBefore('-') }
    if (notes.size == 1) best
    else best.copy(changelog = notes.joinToString("\n\n") { "## ${it.versionName}\n\n${it.changelog.trim()}" })
}.getOrNull()

/** A failed check (rate limit, no network) keeps what Home already knew; only a real answer replaces it. */
fun keepKnownUpdate(previous: UpdateInfo?, result: Result<UpdateInfo?>): UpdateInfo? = result.getOrElse { previous }
