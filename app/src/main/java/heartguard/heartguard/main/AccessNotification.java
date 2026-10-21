package heartguard.heartguard.main;

import android.app.PendingIntent;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

/** A notification and its immutable action refer to one app and one destination. */
final class AccessNotification {
    static final int ID = 1;
    static final String ALLOW = BuildConfig.APPLICATION_ID + ".ALLOW_DOMAIN";
    static final String HIDE_GLOBAL_BLOCKS = "hide_global_block_notifications";
    private static final String PREFIX = "domain-access:";

    static void show(Context context, int uid, String destination, String address) {
        String host;
        try { host = DomainInput.normalize(destination); }
        catch (IllegalArgumentException invalidName) {
            try { host = DomainInput.normalize(address); }
            catch (IllegalArgumentException invalidAddress) { return; }
        }
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        // Android attributes shared-UID traffic to several apps; require review for that case.
        String packageName = packages != null && packages.length == 1 ? packages[0] : "";
        if (shouldHideGlobalBlock(context, host)) {
            NotificationManagerCompat.from(context).cancel(tag(uid, packageName, host), ID);
            return;
        }
        String text = context.getString(R.string.notification_domain_blocked, host);
        NotificationCompat.Builder builder = builder(context, uid, packageName, host, text);
        if (!packageName.isEmpty()) {
            builder.addAction(R.drawable.ic_check_white_24dp, context.getString(R.string.notification_allow_app),
                    allowIntent(context, uid, packageName, host, address));
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\n"
                    + context.getString(R.string.notification_shared_uid)));
        }
        NotificationManagerCompat.from(context).notify(tag(uid, packageName, host), ID, builder.build());
    }

    static PendingIntent allowIntent(Context context, int uid, String packageName, String host, String address) {
        Intent allow = new Intent(context, ReceiverAllowDomain.class)
                .setAction(ALLOW).setData(identity(uid, packageName, host))
                .putExtra("uid", uid).putExtra("package", packageName)
                .putExtra("host", host).putExtra("address", address);
        return PendingIntent.getBroadcast(context, 0, allow, flags());
    }

    static void allow(Context context, Intent intent) {
        if (!ALLOW.equals(intent.getAction())) return;
        int uid = intent.getIntExtra("uid", -1);
        String packageName = intent.getStringExtra("package");
        String host = intent.getStringExtra("host");
        String address = intent.getStringExtra("address");
        if (uid < 0 || packageName == null || host == null || address == null) return;
        try {
            if (context.getPackageManager().getApplicationInfo(packageName, 0).uid != uid) return;
            if (!host.equals(DomainInput.normalize(host)) || !identity(uid, packageName, host).equals(intent.getData())) return;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException invalid) { return; }

        if (shouldHideGlobalBlock(context, host)) {
            NotificationManagerCompat.from(context).cancel(tag(uid, packageName, host), ID);
            return;
        }
        boolean explicitBlock = WhitelistManager.getInstance(context).getExplicitBlock(context, address, uid) != null;
        for (String blocked : RulesManager.getInstance(context).getBlockedDomains())
            explicitBlock |= Util.isAddressInsideDomain(host, blocked);
        String message;
        if (explicitBlock) {
            message = context.getString(R.string.notification_review_block, host);
        } else {
            RulesManager.getInstance(context).queueRuleText(context,
                    "allow package:" + packageName + " " + DomainInput.destination(host));
            message = context.getString(R.string.notification_allow_queued, host);
        }
        NotificationManagerCompat.from(context).notify(tag(uid, packageName, host), ID,
                builder(context, uid, packageName, host, message).build());
    }

    private static boolean shouldHideGlobalBlock(Context context, String host) {
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(HIDE_GLOBAL_BLOCKS, false)) return false;
        for (String blocked : RulesManager.getInstance(context).getBlockedDomains())
            if (Util.isAddressInsideDomain(host, blocked)) return true;
        return false;
    }

    static void hideExistingGlobalBlocks(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            String tag = notification.getTag();
            if (notification.getId() != ID || tag == null || !tag.startsWith(PREFIX)) continue;
            // Limit the split so an IPv6 destination remains intact.
            String[] identity = tag.substring(PREFIX.length()).split(":", 3);
            if (identity.length == 3 && shouldHideGlobalBlock(context, identity[2])) manager.cancel(tag, ID);
        }
    }

    private static NotificationCompat.Builder builder(Context context, int uid, String packageName, String host, String text) {
        Intent main = new Intent(context, ActivityMain.class)
                .setData(identity(uid, packageName, host))
                .putExtra(ActivityMain.EXTRA_SEARCH, packageName.isEmpty() ? Integer.toString(uid) : packageName);
        PendingIntent review = PendingIntent.getActivity(context, 0, main, flags());
        String names = TextUtils.join(", ", Util.getApplicationNames(uid, context));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "access")
                .setSmallIcon(R.drawable.ic_cloud_upload_white_24dp)
                .setContentTitle(names.isEmpty() ? Integer.toString(uid) : names)
                .setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(review).setOnlyAlertOnce(true).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setGroup("AccessAttempt");
        return builder;
    }

    static String tag(int uid, String packageName, String host) {
        return PREFIX + uid + ":" + packageName + ":" + host;
    }

    private static Uri identity(int uid, String packageName, String host) {
        return new Uri.Builder().scheme("heartguard").authority("domain-access")
                .appendPath(Integer.toString(uid)).appendPath(packageName).appendPath(host).build();
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
}
