package cam.su.kernel.jailbreak;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;

public class CamJailbreakService extends Service {
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }
}
