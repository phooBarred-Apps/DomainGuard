package heartguard.heartguard.main;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Notification presentation only: clearing alerts never changes rules, logs, or services. */
final class NotificationGroup {
    static final String KEY = BuildConfig.APPLICATION_ID + ".notifications";
    static final String CLEAR = BuildConfig.APPLICATION_ID + ".CLEAR_NOTIFICATIONS";
    static final String DISMISSED = BuildConfig.APPLICATION_ID + ".NOTIFICATION_DISMISSED";
    static final String SUMMARY_TAG = "domainguard-summary";
    static final int SUMMARY_ID = 0;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static boolean refreshPending;

    private NotificationGroup() { }

    static NotificationCompat.Builder builder(Context context, String channel) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channel);
        // Older Android versions hide grouped child notifications and their individual actions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setGroup(KEY).setSortKey("1-alert").setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setDeleteIntent(action(context, DISMISSED));
        return builder;
    }

    static NotificationCompat.Builder statusBuilder(Context context, String channel) {
        NotificationCompat.Builder builder = builder(context, channel);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setSortKey("0-status");
            addClearAction(context, builder);
        }
        return builder;
    }

    static void notify(Context context, int id, Notification notification) {
        notify(context, null, id, notification);
    }

    static void notify(Context context, String tag, int id, Notification notification) {
        NotificationManagerCompat.from(context).notify(tag, id, notification);
        refresh(context);
    }

    static void cancel(Context context, int id) {
        cancel(context, null, id);
    }

    static void cancel(Context context, String tag, int id) {
        NotificationManagerCompat.from(context).cancel(tag, id);
        refresh(context);
    }

    static void clear(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            Notification notification = entry.getNotification();
            if (KEY.equals(notification.getGroup()) && isClearable(notification))
                manager.cancel(entry.getTag(), entry.getId());
        }
        refresh(context);
    }

    private static boolean isClearable(Notification notification) {
        int protectedFlags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR
                | Notification.FLAG_GROUP_SUMMARY;
        return (notification.flags & protectedFlags) == 0;
    }

    static void refresh(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Context application = context.getApplicationContext();
        synchronized (NotificationGroup.class) {
            if (refreshPending) return;
            refreshPending = true;
        }
        // NotificationManager queues changes. Coalesce bursts without postponing indefinitely.
        handler.postDelayed(() -> {
            synchronized (NotificationGroup.class) { refreshPending = false; }
            updateSummary(application);
        }, 200);
    }

    private static void updateSummary(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        int children = 0;
        int alerts = 0;
        boolean ongoing = false;
        Notification existingSummary = null;
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            Notification notification = entry.getNotification();
            if (SUMMARY_TAG.equals(entry.getTag()) && entry.getId() == SUMMARY_ID) existingSummary = notification;
            if (!KEY.equals(notification.getGroup())
                    || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            children++;
            if (isClearable(notification)) alerts++;
            else ongoing = true;
        }
        if (children == 0) {
            manager.cancel(SUMMARY_TAG, SUMMARY_ID);
            return;
        }
        String title = context.getString(R.string.app_name);
        String text = context.getResources().getQuantityString(R.plurals.notification_alert_count, alerts, alerts);
        // Android updates the children itself; avoid reposting an unchanged summary on every attempt.
        if (existingSummary != null
                && ((existingSummary.flags & Notification.FLAG_ONGOING_EVENT) != 0) == ongoing
                && TextUtils.equals(existingSummary.extras.getCharSequence(Notification.EXTRA_TITLE), title)
                && TextUtils.equals(existingSummary.extras.getCharSequence(Notification.EXTRA_TEXT), text)) return;
        Intent main = new Intent(context, ActivityMain.class);
        PendingIntent open = PendingIntent.getActivity(context, 0, main, PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder summary = builder(context, "foreground")
                .setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open).setGroupSummary(true).setOngoing(ongoing)
                .setOnlyAlertOnce(true).setShowWhen(false).setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        if (alerts > 0) addClearAction(context, summary);
        manager.notify(SUMMARY_TAG, SUMMARY_ID, summary.build());
    }

    private static void addClearAction(Context context, NotificationCompat.Builder builder) {
        builder.addAction(R.drawable.ic_delete_white_24dp, context.getString(R.string.notification_clear_all),
                action(context, CLEAR));
    }

    private static PendingIntent action(Context context, String action) {
        Intent intent = new Intent(context, ReceiverNotificationGroup.class).setAction(action);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
