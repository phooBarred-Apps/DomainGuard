package heartguard.heartguard.main;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReceiverNotificationGroup extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (NotificationGroup.CLEAR.equals(intent.getAction())) NotificationGroup.clear(context);
        else if (NotificationGroup.DISMISSED.equals(intent.getAction())) NotificationGroup.refresh(context);
    }
}
