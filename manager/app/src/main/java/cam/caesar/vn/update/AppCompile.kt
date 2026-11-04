package cam.su.kernel.update

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cam.su.kernel.BuildConfig
import cam.su.kernel.ui.util.getRootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The Manager is installed outside Play (OTA, adb), so Android has no cloud profile for it and
 * runs a fresh install straight from the APK (interpreter and JIT) until idle dexopt, often
 * hours later: the first launches stutter. With root we compile it ourselves, once per version,
 * a little after start-up so the compile does not compete with the first frames. The profile it
 * compiles against is the libraries' baseline profiles (installed by ProfileInstaller) plus
 * what JIT saw so far.
 */
object AppCompile {
    private const val PREFS = "app_compile"
    private const val KEY_VERSION = "compiledVersion"
    const val FILTER = "speed-profile"

    /** The shell command; also run by the OTA install script before it restarts the app. */
    fun command(packageName: String) = "cmd package compile -m $FILTER -f $packageName"

    fun ensureCompiled(context: Context, scope: CoroutineScope) {
        // debuggable builds always run interpreted; nothing to gain
        if (BuildConfig.DEBUG) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_VERSION, -1) == BuildConfig.VERSION_CODE.toLong()) return
        scope.launch(Dispatchers.IO) {
            delay(20.seconds)
            val result = getRootShell().newJob().add(command(context.packageName)).exec()
            Log.i("AppCompile", "compile ${BuildConfig.VERSION_CODE}: ${result.isSuccess} ${result.out.lastOrNull()}")
            if (result.isSuccess) prefs.edit { putLong(KEY_VERSION, BuildConfig.VERSION_CODE.toLong()) }
        }
    }
}
