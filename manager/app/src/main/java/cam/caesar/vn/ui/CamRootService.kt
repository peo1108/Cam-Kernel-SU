package cam.caesar.vn.ui

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import cam.caesar.vn.ICamRootService
import cam.caesar.vn.CamRootClient
import cam.caesar.vn.CamNative
import cam.caesar.vn.attest.AttestPaths
import cam.caesar.vn.attest.atomicWrite
import org.json.JSONObject
import rikka.parcelablelist.ParcelableListSlice
import java.io.File

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
        override fun getSourceId(): String = CamNative.sourceId

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

        override fun writeAttestConfig(bootJson: String, pushJson: String): Boolean {
            File(AttestPaths.DIR).mkdirs()
            val boot = atomicWrite(File(AttestPaths.BOOT_JSON)) { it.writeText(bootJson) }
            val push = atomicWrite(File(AttestPaths.PUSH_JSON)) { it.writeText(pushJson) }
            return boot && push
        }

        override fun setAttestEnabled(enabled: Boolean): Boolean = try {
            val flag = File(AttestPaths.FLAG)
            if (enabled) flag.writeText("") else flag.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "setAttestEnabled failed", e)
            false
        }

        override fun attestStatus(): String = JSONObject().apply {
            put("enabled", File(AttestPaths.FLAG).exists())
            put("libDeployed", File(AttestPaths.LIB).exists())
            put("injectDeployed", File(AttestPaths.INJECT).exists())
            put("pushConfigured", File(AttestPaths.PUSH_JSON).exists())
        }.toString()

        /** Copy the packaged interceptor + loader from the app's native lib dir to /data/adb/cam. */
        override fun deployTeesim(): Boolean = try {
            val src = applicationInfo.nativeLibraryDir
            File("/data/adb/cam/lib").mkdirs()
            File("/data/adb/cam/bin").mkdirs()
            File(src, AttestPaths.LIB_ASSET).copyTo(File(AttestPaths.LIB), overwrite = true)
            File(src, AttestPaths.INJECT_ASSET).copyTo(File(AttestPaths.INJECT), overwrite = true)
            exec("chmod", "0600", AttestPaths.LIB)
            exec("chmod", "0700", AttestPaths.INJECT)
            exec("restorecon", "-R", "/data/adb/cam/lib", "/data/adb/cam/bin")
            File(AttestPaths.LIB).exists() && File(AttestPaths.INJECT).exists()
        } catch (e: Exception) {
            Log.e(TAG, "deployTeesim failed", e)
            false
        }

        override fun purgeTargetAttestKeys(uids: IntArray): Int {
            if (uids.isEmpty()) return 0
            // Restart keystore2: the daemon re-injects it and the targets re-attest through the TA on
            // next use. Conservative (no per-key deletion), so an unrelated app's keys are untouched.
            exec("sh", "-c", "kill \$(pidof keystore2)")
            return uids.size
        }

        /**
         * The real ro.boot.vbmeta.* values (hex) the harvest stands in with when the TEE reports an
         * all-zero verified-boot key/hash. `digest` prefers camd's own computed vbmeta digest
         * (`camd hide-bootloader status`, from the partitions) over the live, spoofable property;
         * `publicKeyDigest` is read straight from the property. Both run as root here, where the
         * boot properties and partitions are readable.
         */
        override fun vbmetaBootProps(): String = JSONObject().apply {
            put("publicKeyDigest", readProp("ro.boot.vbmeta.public_key_digest"))
            put("digest", computedVbmetaDigest().ifBlank { readProp("ro.boot.vbmeta.digest") })
        }.toString()

        /** `getprop <name>`, trimmed; "" on any failure. */
        private fun readProp(name: String): String = runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream
                .bufferedReader().use { it.readText() }.trim()
        }.getOrDefault("")

        /** camd's computed AVB vbmeta digest (hex) from the on-device partitions, or "" if it fails. */
        private fun computedVbmetaDigest(): String = runCatching {
            val camd = File(applicationInfo.nativeLibraryDir, "libcamd.so").absolutePath
            val out = Runtime.getRuntime().exec(arrayOf(camd, "hide-bootloader", "status"))
                .inputStream.bufferedReader().use { it.readText() }
            JSONObject(out).optString("computedVbmetaDigest")
        }.getOrDefault("")

        private fun exec(vararg cmd: String) {
            runCatching { Runtime.getRuntime().exec(cmd).waitFor() }
                .onFailure { Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", it) }
        }
    }
}
