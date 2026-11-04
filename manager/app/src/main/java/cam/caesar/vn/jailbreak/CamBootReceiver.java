package cam.caesar.vn.jailbreak;

import static cam.caesar.vn.jailbreak.CamZygotePreload.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import cam.caesar.vn.ui.util.CamCliKt;

public class CamBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        var action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"cam.caesar.vn.jailbreak.LAUNCH".equals(action)) {
            return;
        }
        if (CamCliKt.rootAvailable()) return;
        try {
            context.startService(new Intent(context, CamJailbreakService.class));
            Log.i(TAG, "CamJailbreakService started from boot action: " + action);
        } catch (Throwable e) {

            Log.e(TAG, "Failed to start CamJailbreakService from boot action: " + action, e);
        }
    }
}
