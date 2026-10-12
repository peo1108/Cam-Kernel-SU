package cam.su.kernel.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cam.su.kernel.BuildConfig
import cam.su.kernel.account.Account
import cam.su.kernel.account.LockedRelease
import cam.su.kernel.account.newestOffer
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
         * Releases this phone's account may install, in the GitHub releases API layout
         * (site/api/routes/device.mjs): the server applies membership, the waiting days, early
         * access and beta tester. Not linked or not a member: no updates (Settings → Account).
         */
        const val OTA_URL = "${Account.SITE}/api/ota/releases"
    }

    override suspend fun fetchLatest(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(camApp) || !Account.isLinked()) return@withContext Result.success(null)
        runCatching {
            val builder = Request.Builder()
                .url(OTA_URL)
                // revalidate every time: a new release still shows at once
                .cacheControl(CacheControl.Builder().noCache().build())
            Account.authorize(builder, OTA_URL)
            camApp.okhttpClient.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    200 -> parseReleases(
                        response.body.string(),
                        BuildConfig.VERSION_CODE.toLong(),
                        // The server only sends betas to approved testers; the switch still lets a
                        // tester stay on stable releases.
                        includePrereleases = SettingsRepositoryImpl().betaUpdates,
                    )
                    401 -> {
                        Account.forget()
                        null
                    }
                    403 -> null
                    else -> throw IOException("HTTP ${response.code}")
                }
            }
        }
    }

    override suspend fun fetchOffer(openVersionCode: Long): Result<LockedRelease?> {
        if (!isNetworkAvailable(camApp) || !Account.isLinked()) return Result.success(null)
        return Account.status().map { status ->
            status?.takeIf { it.member }?.let {
                newestOffer(it, BuildConfig.VERSION_CODE.toLong(), openVersionCode, SettingsRepositoryImpl().betaUpdates)
            }
        }
    }
}
