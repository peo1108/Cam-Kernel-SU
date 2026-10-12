package cam.su.kernel.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.data.repository.UpdateRepositoryImpl
import java.util.concurrent.TimeUnit

/** Turns the 12-hour background check on or off with the "check for updates" setting. */
object UpdateScheduler {
    const val WORK_NAME = "cam-update-check"

    fun apply(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/**
 * Notifies once per new release, whether this account may install it now or only after the
 * waiting days (then the tap offers early access). Failures wait for the next period.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = UpdateRepositoryImpl()
        val settings = SettingsRepositoryImpl()
        val info = repo.fetchLatest().getOrNull()
        if (info != null && info.versionCode > settings.notifiedVersionCode) {
            UpdateNotifier.notifyAvailable(applicationContext, info)
            settings.notifiedVersionCode = info.versionCode
        }
        val offer = repo.fetchOffer(info?.versionCode ?: 0).getOrNull()
        if (offer != null && offer.versionCode > settings.notifiedVersionCode) {
            UpdateNotifier.notifyOffer(applicationContext, offer)
            settings.notifiedVersionCode = offer.versionCode
        }
        return Result.success()
    }
}
