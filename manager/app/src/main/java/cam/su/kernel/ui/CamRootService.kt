package cam.su.kernel.ui

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import cam.su.kernel.ICamRootService
import cam.su.kernel.CamRootClient
import cam.su.kernel.CamNative
import rikka.parcelablelist.ParcelableListSlice

/**
 * @author weishu
 * @date 2023/4/18.
 */

class CamRootService : RootService() {

    companion object {
        private const val TAG = "CamRootService"
    }

    override fun onBind(intent: Intent): IBinder {
        return Stub()
    }

    private fun getAllUserIds(): IntArray {
        val um = getSystemService(USER_SERVICE) as UserManager
        // getAliveUsers() was added in API 31
        try {
            val method = um.javaClass.getMethod("getAliveUsers")
            val users = method.invoke(um) as List<*>
            return extractUserIds(users)
        } catch (e: Exception) {
            Log.e(TAG, "getAliveUsers reflection failed", e)
        }

        return intArrayOf(0)
    }

    private fun extractUserIds(users: List<*>?): IntArray {
        if (users.isNullOrEmpty()) return intArrayOf(0)

        return try {
            users.map { user ->
                user!!.javaClass.getField("id").getInt(user)
            }.toIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ID from UserInfo", e)
            intArrayOf(0)
        }
    }

    private fun getInstalledPackagesAll(flags: Int): ArrayList<PackageInfo> {
        val packages = ArrayList<PackageInfo>()
        for (userId in getAllUserIds()) {
            Log.i(TAG, "getInstalledPackagesAll: $userId")
            packages.addAll(getInstalledPackagesAsUser(flags, userId))
        }
        return packages
    }

    @Suppress("UNCHECKED_CAST")
    private fun getInstalledPackagesAsUser(flags: Int, userId: Int): List<PackageInfo> {
        return try {
            val pm: PackageManager = packageManager
            val method = pm.javaClass.getDeclaredMethod(
                "getInstalledPackagesAsUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(pm, flags, userId) as List<PackageInfo>
        } catch (e: Throwable) {
            Log.e(TAG, "err", e)
            ArrayList()
        }
    }

    private inner class Stub : ICamRootService.Stub() {
        override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
            val list = getInstalledPackagesAll(flags)
            Log.i(TAG, "getPackages: ${list.size}")
            return ParcelableListSlice(list)
        }

        override fun getUserIds(): IntArray {
            val ids = getAllUserIds()
            Log.i(TAG, "getUserIds: ${ids.contentToString()}")
            return ids
        }

        override fun getVersion(): Int = CamNative.version
        override fun getKernelUapiVersion(): Int = CamNative.kernelUAPIVersion
        override fun isSafeMode(): Boolean = CamNative.isSafeMode
        override fun isLkmMode(): Boolean = CamNative.isLkmMode
        override fun isLkmBundled(): Boolean = CamNative.isLkmBundled
        override fun isLateLoadMode(): Boolean = CamNative.isLateLoadMode
        override fun isPrBuild(): Boolean = CamNative.isPrBuild

        override fun uidShouldUmount(uid: Int): Boolean = CamNative.uidShouldUmount(uid)

        override fun getAppProfile(key: String?, uid: Int): Bundle =
            Bundle().apply { putParcelable(CamRootClient.PROFILE_KEY, CamNative.getAppProfile(key, uid)) }

        override fun setAppProfile(profile: Bundle): Boolean =
            CamNative.setAppProfile(CamRootClient.readProfile(profile))

        override fun isSuEnabled(): Boolean = CamNative.isSuEnabled()
        override fun setSuEnabled(enabled: Boolean): Boolean = CamNative.setSuEnabled(enabled)
        override fun isKernelUmountEnabled(): Boolean = CamNative.isKernelUmountEnabled()
        override fun setKernelUmountEnabled(enabled: Boolean): Boolean = CamNative.setKernelUmountEnabled(enabled)
        override fun isSelinuxHideEnabled(): Boolean = CamNative.isSelinuxHideEnabled()
        override fun setSelinuxHideEnabled(enabled: Boolean): Int = CamNative.setSelinuxHideEnabled(enabled)

        override fun getSuperuserCount(): Int = CamNative.getSuperuserCount()
    }
}
