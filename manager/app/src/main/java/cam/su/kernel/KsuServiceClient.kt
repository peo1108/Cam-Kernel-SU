package cam.su.kernel

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cam.su.kernel.ui.KsuService
import cam.su.kernel.ui.util.KsuCli
import kotlin.coroutines.resume

/**
 * Keeps one binding to [KsuService], the uid 0 process that talks to the kernel.
 */
object KsuServiceClient {
    private const val TAG = "KsuServiceClient"
    private const val BIND_TIMEOUT_MS = 15_000L
    const val PROFILE_KEY = "profile"

    private val mutex = Mutex()
    private val _service = MutableStateFlow<IKsuInterface?>(null)

    /** Emits the bound service, or null while unbound. */
    val serviceFlow: StateFlow<IKsuInterface?> = _service.asStateFlow()

    val service: IKsuInterface?
        get() = _service.value?.takeIf { it.asBinder().isBinderAlive }

    /** Binds the root service if root is available. Returns true when bound. */
    suspend fun connect(): Boolean = mutex.withLock {
        if (service != null) return true
        val hasRoot = withContext(Dispatchers.IO) { KsuCli.SHELL.isRoot }
        if (!hasRoot) {
            Log.i(TAG, "no root, KsuService not bound")
            return false
        }
        val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { bind() }
        if (binder == null) {
            Log.w(TAG, "bind KsuService timed out")
            return false
        }
        _service.value = IKsuInterface.Stub.asInterface(binder)
        true
    }

    fun readProfile(bundle: Bundle?): Natives.Profile? {
        bundle ?: return null
        bundle.classLoader = Natives.Profile::class.java.classLoader
        @Suppress("DEPRECATION")
        return bundle.getParcelable(PROFILE_KEY)
    }

    fun writeProfile(profile: Natives.Profile?): Bundle =
        Bundle().apply { putParcelable(PROFILE_KEY, profile) }

    private suspend fun bind(): IBinder = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && cont.isActive) cont.resume(binder)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "KsuService disconnected")
                    _service.value = null
                }
            }
            val intent = Intent(ksuApp, KsuService::class.java)
            val task = RootService.bindOrTask(intent, Shell.EXECUTOR, connection)
            task?.let { KsuCli.SHELL.execTask(it) }
        }
    }
}
