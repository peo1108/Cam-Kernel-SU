package cam.su.kernel.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cam.su.kernel.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The running copy/move/delete/extract job, shared by the Files tab and [FileOpService]. */
object FileOpProgress {
    data class State(val kind: OpKind, val done: Long, val total: Long, val current: String)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Set by whoever started the job; the notification's Cancel calls it. */
    @Volatile
    var onCancel: (() -> Unit)? = null

    fun update(state: State?) {
        _state.value = state
    }
}

/**
 * Keeps the app (and so the libsu root process holding the job) alive while a long file job
 * runs, with its progress and a Cancel action in a notification. Stops when the job ends.
 */
class FileOpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var manager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.files_op_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileOpProgress.onCancel?.invoke()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, build(FileOpProgress.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            FileOpProgress.state.collect { state ->
                if (state == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    manager.notify(NOTIFICATION_ID, build(state))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: FileOpProgress.State?) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(titleOf(state?.kind)))
        .setContentText(state?.current?.substringAfterLast('/').orEmpty())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent(state), state == null || state.total <= 0)
        .addAction(
            0,
            getString(android.R.string.cancel),
            PendingIntent.getService(
                this,
                0,
                Intent(this, FileOpService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun percent(state: FileOpProgress.State?): Int =
        if (state == null || state.total <= 0) 0 else (state.done * 100 / state.total).toInt().coerceIn(0, 100)

    private fun titleOf(kind: OpKind?) = when (kind) {
        OpKind.COPY -> R.string.files_op_copy
        OpKind.MOVE -> R.string.files_op_move
        OpKind.DELETE -> R.string.files_op_delete
        OpKind.EXTRACT -> R.string.files_op_extract
        OpKind.COMPRESS -> R.string.files_op_compress
        null -> R.string.files
    }

    companion object {
        private const val CHANNEL_ID = "file_ops"
        private const val NOTIFICATION_ID = 0xF11E
        private const val ACTION_CANCEL = "cam.su.kernel.action.CANCEL_FILE_OP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, FileOpService::class.java)) }
        }
    }
}
