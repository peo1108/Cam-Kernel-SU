package cam.su.kernel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import cam.su.kernel.data.repository.SettingsRepositoryImpl
import cam.su.kernel.update.UpdateInstaller
import cam.su.kernel.update.UpdateNotifier
import cam.su.kernel.update.UpdateScheduler
import cam.su.kernel.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

lateinit var camApp: CamApplication

class CamApplication : Application(), ViewModelStoreOwner {

    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        super.onCreate()
        camApp = this

        // isolated services (the hiding probe, jailbreak) have no app data and no root: set up nothing
        if (Process.isIsolated() || !isUserUnlocked()) {
            return
        }

        // the boot receiver was renamed, so its enabled state starts over; restore it from the setting
        SettingsRepositoryImpl().run { if (autoJailbreak) autoJailbreak = true }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = SettingsRepositoryImpl().enablePredictiveBack
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }

        val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
        superUserViewModel.loadAppList()

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // a finished or abandoned update download is never needed again; only the main process
        // downloads, so another process (:jailbreak_boot) must not delete its file
        if (getProcessName() == packageName) {
            UpdateInstaller.cleanup(this)
            UpdateNotifier.createChannel(this)
            UpdateScheduler.apply(this, SettingsRepositoryImpl().checkUpdate)
        }

        // Provide working env for rust's temp_dir()
        Os.setenv("TMPDIR", cacheDir.absolutePath, true)

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "CamSU/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
