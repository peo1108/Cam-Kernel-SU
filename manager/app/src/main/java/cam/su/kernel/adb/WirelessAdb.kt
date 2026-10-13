package cam.su.kernel.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cam.su.kernel.ui.util.getRootShell
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Wireless ADB as root sees it. */
data class WirelessAdbStatus(
    /** the TCP port adbd listens on; null when it listens on USB only */
    val port: Int?,
    /** adbd is running */
    val running: Boolean,
    /** USB debugging (`adb_enabled`) is on */
    val adbEnabled: Boolean,
) {
    val on: Boolean
        get() = port != null && running

    companion object {
        val Off = WirelessAdbStatus(port = null, running = false, adbEnabled = false)

        /** Lines of `getprop service.adb.tcp.port; getprop init.svc.adbd; settings get global adb_enabled`. */
        fun parse(lines: List<String>): WirelessAdbStatus = WirelessAdbStatus(
            port = lines.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 },
            running = lines.getOrNull(1)?.trim() == "running",
            adbEnabled = lines.getOrNull(2)?.trim() == "1",
        )
    }
}

/**
 * ADB over Wi-Fi without pairing, the way `adb tcpip` does it: adbd is restarted listening on
 * [PORT]. The port property is not persistent, so a reboot turns it off. When USB debugging was
 * off, it is turned on for this and back off with it (also after a reboot). The computer still
 * has to be allowed on the phone the first time, as over USB.
 */
object WirelessAdb {
    const val PORT = 5555

    /** auto off choices, in minutes; 0 = never */
    val TIMEOUTS = listOf(0, 15, 30, 60, 120)

    internal const val WORK_NAME = "cam-wireless-adb-off"

    /** The boot clean-up; its own name, so queueing it never replaces the auto off timer. */
    internal const val BOOT_WORK_NAME = "cam-wireless-adb-boot"
    private const val PREFS = "wireless_adb"
    private const val KEY_ENABLED_ADB = "enabledAdb"
    private const val KEY_TIMEOUT = "timeoutMinutes"
    private const val KEY_ENABLED_AT = "enabledAt"
    internal const val INPUT_REQUESTED_AT = "requestedAt"
    internal const val INPUT_BOOT = "boot"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** USB debugging was turned on for wireless ADB and is to go back off with it. */
    fun enabledAdb(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED_ADB, false)

    fun timeoutMinutes(context: Context): Int = prefs(context).getInt(KEY_TIMEOUT, 30)

    /** When wireless ADB was last turned on from the app (wall clock ms); 0 if never. */
    fun enabledAt(context: Context): Long = prefs(context).getLong(KEY_ENABLED_AT, 0)

    /**
     * A turn-off request made at [requestedAt] is stale when ADB was turned on again after it:
     * a timer or boot cleanup queued earlier must not end a session started later. WorkManager
     * runs overdue work when the app process starts (after an update, a force stop or a reboot),
     * which used to cut ADB the moment the app was reopened. Requests from before this field
     * existed carry 0 and are dropped.
     */
    fun isStale(requestedAt: Long, enabledAt: Long): Boolean = requestedAt <= 0 || enabledAt > requestedAt

    /**
     * Whether the boot clean-up has anything to undo. Android 15+ also sends BOOT_COMPLETED to an
     * app the next time it starts after a force stop (an update, an `adb install`, Settings), with
     * no reboot at all: then the TCP port, which never survives a reboot, is still set and adbd
     * still runs, and the session must be left alone.
     */
    fun bootCleanupNeeded(status: WirelessAdbStatus): Boolean = !status.on

    internal fun offRequest(boot: Boolean = false): androidx.work.OneTimeWorkRequest.Builder =
        OneTimeWorkRequestBuilder<WirelessAdbOffWorker>()
            .setInputData(androidx.work.workDataOf(INPUT_REQUESTED_AT to System.currentTimeMillis(), INPUT_BOOT to boot))

    /** Blocking (root shell). */
    fun status(): WirelessAdbStatus {
        val out = getRootShell().newJob()
            .add("getprop service.adb.tcp.port", "getprop init.svc.adbd", "settings get global adb_enabled")
            .to(ArrayList(), null).exec()
        return if (out.isSuccess) WirelessAdbStatus.parse(out.out) else WirelessAdbStatus.Off
    }

    /** Blocking. Starts adbd on [PORT], and the auto off timer when one is set. */
    fun enable(context: Context): WirelessAdbStatus {
        val before = status()
        val commands = mutableListOf("setprop service.adb.tcp.port $PORT")
        if (!before.adbEnabled) {
            // remembered first: a crash in between must still turn it back off
            prefs(context).edit(commit = true) { putBoolean(KEY_ENABLED_ADB, true) }
            commands += "settings put global adb_enabled 1"
        }
        commands += "setprop ctl.restart adbd"
        prefs(context).edit(commit = true) { putLong(KEY_ENABLED_AT, System.currentTimeMillis()) }
        getRootShell().newJob().add(*commands.toTypedArray()).exec()
        schedule(context, timeoutMinutes(context))
        return waitFor(on = true)
    }

    /** Blocking. Back to USB only; USB debugging goes back off when it was this that turned it on. */
    fun disable(context: Context): WirelessAdbStatus {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        val before = status()
        val commands = mutableListOf<String>()
        if (before.port != null) {
            commands += "setprop service.adb.tcp.port 0"
            if (before.running) commands += "setprop ctl.restart adbd"
        }
        val enabledAdb = enabledAdb(context)
        if (enabledAdb) commands += "settings put global adb_enabled 0"
        if (commands.isEmpty()) return before
        getRootShell().newJob().add(*commands.toTypedArray()).exec()
        if (enabledAdb) prefs(context).edit { remove(KEY_ENABLED_ADB) }
        return waitFor(on = false)
    }

    /** A new auto off time; when wireless ADB is on, it counts from now. */
    fun setTimeout(context: Context, minutes: Int, on: Boolean) {
        prefs(context).edit { putInt(KEY_TIMEOUT, minutes) }
        if (on) schedule(context, minutes)
    }

    private fun schedule(context: Context, minutes: Int) {
        val work = WorkManager.getInstance(context)
        if (minutes <= 0) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = offRequest()
            .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** adbd takes a moment to come back after a restart. */
    private fun waitFor(on: Boolean): WirelessAdbStatus {
        var last = status()
        repeat(10) {
            if (last.on == on) return last
            Thread.sleep(300)
            last = status()
        }
        return last
    }

    /** The phone's IPv4 address on Wi-Fi; null when not on Wi-Fi. */
    fun wifiAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return ipv4(cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address })
    }

    /** The first IPv4 address that is not loopback or link local. */
    fun ipv4(addresses: List<InetAddress>): String? = addresses
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}

/** Turns wireless ADB off when its time is up, and cleans up after a reboot. */
class WirelessAdbOffWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestedAt = inputData.getLong(WirelessAdb.INPUT_REQUESTED_AT, 0)
        if (WirelessAdb.isStale(requestedAt, WirelessAdb.enabledAt(applicationContext))) return Result.success()
        if (inputData.getBoolean(WirelessAdb.INPUT_BOOT, false)) {
            // BOOT_COMPLETED after a force stop, no reboot: the session and its timer stay
            if (!WirelessAdb.bootCleanupNeeded(WirelessAdb.status())) return Result.success()
            // A real reboot: a timer left from before it has nothing to turn off any more, and
            // must not end a session started since. USB debugging this turned on goes back off.
            if (!WirelessAdb.enabledAdb(applicationContext)) {
                WorkManager.getInstance(applicationContext).cancelUniqueWork(WirelessAdb.WORK_NAME)
                return Result.success()
            }
        }
        WirelessAdb.disable(applicationContext)
        return Result.success()
    }
}

/**
 * The port does not survive a reboot, but USB debugging this turned on does: put it back off.
 * Android 15+ also delivers BOOT_COMPLETED after a force stop; the worker tells the two apart.
 */
class WirelessAdbBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // root shell work off the main thread, queued apart from the auto off timer
        WorkManager.getInstance(context).enqueueUniqueWork(
            WirelessAdb.BOOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            WirelessAdb.offRequest(boot = true).build(),
        )
    }
}
