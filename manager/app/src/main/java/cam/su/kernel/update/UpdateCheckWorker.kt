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

/** Notifies once per new release; failures wait for the next period instead of retrying. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val info = UpdateRepositoryImpl().fetchLatest().getOrNull() ?: return Result.success()
        val settings = SettingsRepositoryImpl()
        if (info.versionCode > settings.notifiedVersionCode) {
            UpdateNotifier.notifyAvailable(applicationContext, info)
            settings.notifiedVersionCode = info.versionCode
        }
        return Result.success()
    }
}
