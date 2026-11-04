package cam.su.kernel.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cam.su.kernel.BuildConfig

/**
 * The reliable "update done" signal: reopening the app from the install script is best effort
 * (see rootInstallScript), but Android always sends MY_PACKAGE_REPLACED to the new version.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateNotifier.notifyUpdated(context, BuildConfig.VERSION_NAME)
    }
}
