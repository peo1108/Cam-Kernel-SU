package cam.su.kernel.hiding

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import cam.su.kernel.data.model.HidingAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Runs [AppViewProbe] in an isolated process (see the manifest): no root, no app data, and
 * modules unmounted by the kernel. It does not use the app zygote, whose preload starts camd.
 * The process lives only while the page is bound to it.
 */
class HidingProbeService : Service() {

    private val binder = object : IHidingProbe.Stub() {
        override fun scan(): String = AppViewProbe.run().toJson().toString()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "HidingProbe"
        private const val TIMEOUT_MS = 15_000L

        /** Binds, scans once and unbinds; null when the probe could not run. */
        suspend fun scan(context: Context): HidingAudit? = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val unbound = AtomicBoolean(false)
                lateinit var connection: ServiceConnection
                fun finish(result: HidingAudit?) {
                    if (unbound.compareAndSet(false, true)) runCatching { context.unbindService(connection) }
                    if (cont.isActive) cont.resume(result)
                }
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val json = runCatching { IHidingProbe.Stub.asInterface(service).scan() }
                            .onFailure { Log.w(TAG, "probe failed", it) }
                            .getOrNull()
                        finish(json?.let(HidingAudit::parse))
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = finish(null)
                    override fun onBindingDied(name: ComponentName?) = finish(null)
                    override fun onNullBinding(name: ComponentName?) = finish(null)
                }
                // callbacks off the main thread: the scan call blocks until the probe answers
                val bound = runCatching {
                    context.bindService(
                        Intent(context, HidingProbeService::class.java),
                        Context.BIND_AUTO_CREATE,
                        Dispatchers.IO.asExecutor(),
                        connection,
                    )
                }.getOrDefault(false)
                if (!bound) finish(null)
                cont.invokeOnCancellation { finish(null) }
            }
        }
    }
}
