package cam.su.kernel.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cam.su.kernel.BuildConfig
import cam.su.kernel.camApp
import cam.su.kernel.ui.util.isNetworkAvailable
import cam.su.kernel.update.UpdateInfo
import cam.su.kernel.update.parseReleases
import okhttp3.CacheControl
import okhttp3.Request
import java.io.IOException

class UpdateRepositoryImpl : UpdateRepository {

    private companion object {
        /**
         * The site's mirror of the releases API (site/scripts/build-feed.mjs): same JSON layout,
         * APKs served from caesarvn.xyz, so updates keep working once the repository is private.
         */
        const val FEED_URL = "https://caesarvn.xyz/feed/releases.json"

        // TODO(after the repository goes private): drop this fallback (docs/CAM_CHANGES.md, section 14).
        // releases/latest is no good here: it can point at a non-Cam release (sfs-archive)
        const val GITHUB_URL = "https://api.github.com/repos/peo1108/Cam-Kernel-SU/releases?per_page=30"
    }

    override suspend fun fetchLatest(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(camApp)) return@withContext Result.success(null)
        val json = runCatching { get(FEED_URL) }.recoverCatching { get(GITHUB_URL) }
        json.map {
            parseReleases(
                it,
                BuildConfig.VERSION_CODE.toLong(),
                includePrereleases = SettingsRepositoryImpl().betaUpdates,
            )
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            // revalidate every time (ETag): a new release still shows at once, and an unchanged
            // feed costs a 304 (on GitHub those do not count against the 60 requests/hour limit)
            .cacheControl(CacheControl.Builder().noCache().build())
            .build()
        return camApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body.string()
        }
    }
}
