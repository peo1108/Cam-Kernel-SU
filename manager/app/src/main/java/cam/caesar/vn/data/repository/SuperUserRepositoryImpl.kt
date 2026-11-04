package cam.su.kernel.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cam.su.kernel.ICamRootService
import cam.su.kernel.Cam
import cam.su.kernel.CamRootClient
import cam.su.kernel.data.model.AppInfo
import cam.su.kernel.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import cam.su.kernel.data.model.WEBVIEW_ZYGOTE_UID
import cam.su.kernel.camApp
import cam.su.kernel.ui.util.CamCli

class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!CamCli.SHELL.isRoot) {
                return@withContext Result.failure(
                    IllegalStateException("Root access is required")
                )
            }

            run {
                val pm = camApp.packageManager
                val start = SystemClock.elapsedRealtime()

                val idsArray = withService { it.userIds }
                val slice = withService { it.getPackages(0) }

                val packages = slice.list
                val newApps = packages.filter {
                    val ai = it.applicationInfo ?: return@filter false
                    ai.uid != WEBVIEW_ZYGOTE_UID &&
                            (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
                }.map {
                    val appInfo = it.applicationInfo!!
                    val profile = Cam.getAppProfile(it.packageName, appInfo.uid)
                    AppInfo(
                        label = appInfo.loadLabel(pm).toString(),
                        packageInfo = it,
                        profile = profile,
                    )
                }.toMutableList()

                // WebView Zygote is a single system UID, not a per-user package. Reuse the system icon.
                val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                    uid = WEBVIEW_ZYGOTE_UID
                }
                val placeholder = PackageInfo().apply {
                    packageName = ""
                    applicationInfo = systemInfo
                }
                newApps += AppInfo(
                    label = "WebView Zygote",
                    packageInfo = placeholder,
                    profile = Cam.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                    profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                    special = true,
                )

                Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
                Pair(newApps, idsArray.toList())
            }
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Cam.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile)
            }
        }
    }

    private suspend fun <T> withService(block: (ICamRootService) -> T): T {
        repeat(2) { attempt ->
            check(CamRootClient.connect()) { "CamRootService unavailable" }
            val service = CamRootClient.service ?: return@repeat
            try {
                return block(service)
            } catch (e: RemoteException) {
                Log.w(TAG, "CamRootService call failed, attempt $attempt", e)
                if (attempt == 1) throw e
            }
        }
        error("CamRootService unavailable")
    }
}
