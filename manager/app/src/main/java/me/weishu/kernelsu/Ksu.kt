package me.weishu.kernelsu

import android.os.RemoteException
import android.util.Log

/**
 * UI-side access to the kernel. Every call goes through [KsuServiceClient] (uid 0),
 * because the kernel no longer recognizes a manager app uid.
 * Returns safe defaults when the service is not bound.
 */
object Ksu {
    private const val TAG = "Ksu"
    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    private inline fun <T> call(default: T, block: (IKsuInterface) -> T): T {
        val service = KsuServiceClient.service ?: return default
        return try {
            block(service)
        } catch (e: RemoteException) {
            Log.w(TAG, "KsuService call failed", e)
            default
        }
    }

    /** True when the root service is bound and the kernel answers. */
    val isAvailable: Boolean
        get() = version > 0

    val version: Int
        get() = call(0) { it.version }

    val kernelUAPIVersion: Int
        get() = call(0) { it.kernelUapiVersion }

    val isSafeMode: Boolean
        get() = call(false) { it.isSafeMode }

    val isLkmMode: Boolean
        get() = call(false) { it.isLkmMode }

    val isLkmBundled: Boolean
        get() = call(false) { it.isLkmBundled }

    val isLateLoadMode: Boolean
        get() = call(false) { it.isLateLoadMode }

    val isPrBuild: Boolean
        get() = call(false) { it.isPrBuild }

    fun uidShouldUmount(uid: Int): Boolean = call(false) { it.uidShouldUmount(uid) }

    fun getAppProfile(key: String?, uid: Int): Natives.Profile =
        call(null) { KsuServiceClient.readProfile(it.getAppProfile(key, uid)) }
            ?: Natives.Profile(name = key ?: "", currentUid = uid)

    fun setAppProfile(profile: Natives.Profile?): Boolean =
        call(false) { it.setAppProfile(KsuServiceClient.writeProfile(profile)) }

    fun isSuEnabled(): Boolean = call(false) { it.isSuEnabled }
    fun setSuEnabled(enabled: Boolean): Boolean = call(false) { it.setSuEnabled(enabled) }

    fun isKernelUmountEnabled(): Boolean = call(false) { it.isKernelUmountEnabled }
    fun setKernelUmountEnabled(enabled: Boolean): Boolean = call(false) { it.setKernelUmountEnabled(enabled) }

    fun isSelinuxHideEnabled(): Boolean = call(false) { it.isSelinuxHideEnabled }
    fun setSelinuxHideEnabled(enabled: Boolean): Int = call(-1) { it.setSelinuxHideEnabled(enabled) }

    fun getSuperuserCount(): Int = call(0) { it.superuserCount }

    fun getUserName(uid: Int): String? = Natives.getUserName(uid)

    fun setDefaultUmountModules(umountModules: Boolean): Boolean =
        setAppProfile(
            Natives.Profile(
                NON_ROOT_DEFAULT_PROFILE_KEY,
                NOBODY_UID,
                false,
                umountModules = umountModules
            )
        )

    fun isDefaultUmountModules(): Boolean =
        getAppProfile(NON_ROOT_DEFAULT_PROFILE_KEY, NOBODY_UID).umountModules

    fun isFullFeatured(): Boolean =
        isAvailable && kernelUAPIVersion == Natives.managerUAPIVersion
}
