package cam.su.kernel.hiding

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cam.su.kernel.R
import cam.su.kernel.data.model.HidingAudit
import cam.su.kernel.data.repository.HidingHistoryRepository
import cam.su.kernel.data.repository.HidingRulesRepository
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.ui.CamActivity
import cam.su.kernel.ui.screen.hidingcheck.findingTitleRes
import cam.su.kernel.ui.util.listModules
import cam.su.kernel.ui.util.rootAvailable
import cam.su.kernel.ui.util.runHidingAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** The modules seen at the last check, in `files/hiding_check/modules.json`. */
class ModuleBaseline(context: Context) {
    private val file = File(context.filesDir, "hiding_check/modules.json")

    /** null before the first check */
    fun load(): Map<String, String>? = runCatching { ModuleScan.parseBaseline(file.readText()) }.getOrNull()

    fun save(baseline: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ModuleScan.baselineToJson(baseline))
        }
    }

    /**
     * Takes the module list as the starting point when there is none yet, so a module
     * installed before the first boot-time check still counts as new. Without root the list
     * reads empty, which would make every module look new: then nothing is taken.
     */
    fun seedIfMissing(moduleList: String) {
        if (file.exists() || !rootAvailable()) return
        ModuleScan.parseModules(moduleList)?.let { save(ModuleScan.nextBaseline(emptyMap(), it)) }
    }

    fun clear() {
        file.delete()
    }
}

/** Queues the check a little after boot, when apps (and the libraries modules load into them) are up. */
class ModuleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsRepositoryImpl().moduleHidingScan) return
        val request = OneTimeWorkRequestBuilder<ModuleScanWorker>()
            .setInitialDelay(DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "cam-module-hiding-scan"
        const val DELAY_MINUTES = 2L
    }
}

/**
 * After a boot that brought a module in (installed, updated, enabled again), runs the hiding
 * audit once, the root view and the app view, and notifies about the leaks those modules
 * cause. Nothing runs when no module changed; nothing is shown when they leak nothing.
 * The scan is not saved to the page's history: it leaves out the app profiles, and the page
 * scans again when opened from the notification.
 */
class ModuleScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SettingsRepositoryImpl().moduleHidingScan || !rootAvailable()) return@withContext Result.success()
        val modules = ModuleScan.parseModules(listModules()) ?: return@withContext Result.success()
        val store = ModuleBaseline(applicationContext)
        val baseline = store.load()
        if (baseline == null) {
            // nothing to compare with yet: this boot is the starting point
            store.save(ModuleScan.nextBaseline(emptyMap(), modules))
            return@withContext Result.success()
        }
        val changed = ModuleScan.changed(baseline, modules)
        if (changed.isNotEmpty()) {
            Log.i(TAG, "modules changed since the last check: $changed")
            // neither view ran: keep the baseline, so the next boot tries again
            val audit = audit() ?: return@withContext Result.success()
            val leaks = ModuleScan.leaksBy(audit, changed, HidingHistoryRepository().ignored)
            if (leaks.isNotEmpty()) {
                val names = modules.associate { it.id to it.name.ifBlank { it.id } }
                ModuleScanNotifier.notify(applicationContext, leaks, names)
            }
        }
        store.save(ModuleScan.nextBaseline(baseline, modules))
        Result.success()
    }

    /** camd's audit and the isolated probe's, as the page runs them; null when neither ran. */
    private suspend fun audit(): HidingAudit? = coroutineScope {
        val rulesRepo = HidingRulesRepository()
        val rules = rulesRepo.current()
        val root = async { runHidingAudit(rulesRepo.currentFile()) }
        val app = async { HidingProbeService.scan(applicationContext, rules) }
        listOfNotNull(root.await(), app.await()).reduceOrNull(HidingAudit::plus)
    }

    private companion object {
        const val TAG = "ModuleScan"
    }
}

/** "These new modules leak root", opening the hiding check page. */
object ModuleScanNotifier {
    const val CHANNEL_ID = "hiding_check"
    const val EXTRA_OPEN_HIDING_CHECK = "cam.su.kernel.extra.OPEN_HIDING_CHECK"

    private const val ID_LEAKS = 0x0A72

    fun notify(context: Context, leaks: Map<String, List<String>>, names: Map<String, String>) {
        val manager = NotificationManagerCompat.from(context)
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !manager.areNotificationsEnabled()) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.module_scan_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val lines = leaks.map { (id, findings) ->
            val titles = findings.joinToString(", ") { f -> findingTitleRes(f)?.let(context::getString) ?: f }
            context.getString(R.string.module_scan_line, names[id] ?: id, titles)
        }
        val intent = Intent(context, CamActivity::class.java)
            .putExtra(EXTRA_OPEN_HIDING_CHECK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, ID_LEAKS, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.module_scan_title, leaks.size))
            .setContentText(leaks.keys.joinToString(", ") { names[it] ?: it })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(ID_LEAKS, notification) }
    }
}
