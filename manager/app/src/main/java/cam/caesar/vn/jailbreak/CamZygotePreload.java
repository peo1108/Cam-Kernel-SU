package cam.su.kernel.jailbreak;

import android.app.ZygotePreload;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;

public class CamZygotePreload implements ZygotePreload {
    public static final String TAG = "CamJailbreak";

    private static native void forkDontCareAndExecCamd(String camdPath, String packageName);

    @Override
    public void doPreload(@NonNull ApplicationInfo appInfo) {
        File f = new File(appInfo.nativeLibraryDir, "libcamd.so");
        try {
            System.loadLibrary("camjni");
            Log.d(TAG, "executing jailbreak ...");
            forkDontCareAndExecCamd(f.getAbsolutePath(), appInfo.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "failed to late load", t);
        }
    }
}
