package cam.su.kernel.ui.intro

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import cam.su.kernel.R

/**
 * The system splash is plain black (the manifest's), for the start intro to take over from.
 * Without the intro the logo is better than a blank screen: from Android 13 the splash theme can
 * be switched, and the system keeps the choice for the next starts.
 */
internal object IntroSplash {
    fun apply(activity: Activity, intro: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            activity.splashScreen.setSplashScreenTheme(if (intro) Resources.ID_NULL else R.style.Theme_Cam_Starting_Logo)
        }
    }
}
