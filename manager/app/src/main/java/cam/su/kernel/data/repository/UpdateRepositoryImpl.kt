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
        // releases/latest is no good here: it can point at a non-Cam release (sfs-archive)
        const val RELEASES_URL = "https://api.github.com/repos/peo1108/Cam-Kernel-SU/releases?per_page=30"
    }

    override suspend fun fetchLatest(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(camApp)) return@withContext Result.success(null)
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                // revalidate every time (ETag): a new release still shows at once, and GitHub's
                // 304 answers do not count against the 60 requests/hour limit
                .cacheControl(CacheControl.Builder().noCache().build())
                .build()
            camApp.okhttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parseReleases(response.body.string(), BuildConfig.VERSION_CODE.toLong())
            }
        }
    }
}
