package cam.su.kernel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/** ReceiverPermissionTest.seesMethodReferences: a use of registerReceiver with no invokestatic. */
final class ReceiverMethodRef {
    interface Register {
        Intent register(Context context, BroadcastReceiver receiver, IntentFilter filter, int flags);
    }

    static final Register REF = ContextCompat::registerReceiver;

    private ReceiverMethodRef() {
    }
}
