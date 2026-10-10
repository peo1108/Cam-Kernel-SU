package cam.su.kernel.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cam.su.kernel.R
import cam.su.kernel.ui.CamActivity

/** Notifications for "an update is out" and "the update was installed". */
object UpdateNotifier {
    const val CHANNEL_ID = "app_update"
    const val EXTRA_SHOW_UPDATE = "cam.su.kernel.extra.SHOW_UPDATE"

    private const val ID_AVAILABLE = 0x0A70
    private const val ID_UPDATED = 0x0A71

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyAvailable(context: Context, info: UpdateInfo) {
        val intent = Intent(context, CamActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        post(
            context,
            ID_AVAILABLE,
            context.getString(R.string.update_available_title, context.getString(R.string.app_name), info.versionName),
            info.summary,
            intent,
        )
    }

    fun notifyUpdated(context: Context, versionName: String) {
        NotificationManagerCompat.from(context).cancel(ID_AVAILABLE)
        val intent = Intent(context, CamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            context,
            ID_UPDATED,
            context.getString(R.string.update_done_title, context.getString(R.string.app_name), versionName),
            context.getString(R.string.update_done_text),
            intent,
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String?, intent: Intent) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannel(context)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id, notification) }
    }
}
