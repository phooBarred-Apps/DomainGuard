package heartguard.heartguard.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReceiverAllowDomain extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        AccessNotification.allow(context, intent);
    }
}
