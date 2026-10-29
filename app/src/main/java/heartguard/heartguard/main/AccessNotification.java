package heartguard.heartguard.main;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

/** A notification and its immutable action refer to one app and one destination. */
final class AccessNotification {
    static final int ID = 1;
    static final String ALLOW = BuildConfig.APPLICATION_ID + ".ALLOW_DOMAIN";
    static final String OPTIONS = BuildConfig.APPLICATION_ID + ".ALLOW_DOMAIN_OPTIONS";
    static final String NOTIFY_GLOBAL_BLOCKS = "notify_global_blocks";
    private static final String PREFIX = "domain-access:";
    private static final String ADDRESS = BuildConfig.APPLICATION_ID + ".notification.address";
    private static final String PENDING = BuildConfig.APPLICATION_ID + ".notification.pending";

    private enum State { BLOCKED, PENDING, ALLOWED }

    static synchronized void show(Context context, int uid, String destination, String address) {
        String host;
        try { host = DomainInput.normalize(destination); }
        catch (IllegalArgumentException invalidName) {
            try { host = DomainInput.normalize(address); }
            catch (IllegalArgumentException invalidAddress) { return; }
        }
        if (TextUtils.isEmpty(address)) address = host;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        // Android attributes shared-UID traffic to several apps; require review for that case.
        String packageName = packages != null && packages.length == 1 ? packages[0] : "";
        if (shouldHideGlobalBlock(context, host)) {
            NotificationGroup.cancel(context, tag(uid, packageName, host), ID);
            return;
        }
        State state = state(context, uid, host, address);
        if (state == State.ALLOWED) {
            NotificationGroup.cancel(context, tag(uid, packageName, host), ID);
            return;
        }
        NotificationGroup.notify(context, tag(uid, packageName, host), ID,
                accessBuilder(context, uid, packageName, host, address, state).build());
    }

    private static NotificationCompat.Builder accessBuilder(Context context, int uid, String packageName,
                                                            String host, String address, State state) {
        boolean pending = state == State.PENDING;
        String text = context.getString(pending ? R.string.notification_allow_queued : R.string.notification_domain_blocked, host);
        NotificationCompat.Builder builder = builder(context, uid, packageName, host, address, text, pending);
        if (!packageName.isEmpty()) {
            if (!pending)
                builder.addAction(R.drawable.ic_check_white_24dp, context.getString(R.string.notification_allow_app,
                                DomainRuleDialog.appName(context, packageName)),
                        allowIntent(context, uid, packageName, host, address));
            addOptions(context, builder, uid, packageName, host, address);
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\n"
                    + context.getString(R.string.notification_shared_uid)));
        }
        return builder;
    }

    static PendingIntent allowIntent(Context context, int uid, String packageName, String host, String address) {
        Intent allow = new Intent(context, ReceiverAllowDomain.class)
                .setAction(ALLOW).setData(identity(uid, packageName, host))
                .putExtra("uid", uid).putExtra("package", packageName)
                .putExtra("host", host).putExtra("address", address);
        return PendingIntent.getBroadcast(context, 0, allow, flags());
    }

    static synchronized void allow(Context context, Intent intent) {
        if (!ALLOW.equals(intent.getAction()) || !validTarget(context, intent)) return;
        int uid = intent.getIntExtra("uid", -1);
        String packageName = intent.getStringExtra("package");
        String host = intent.getStringExtra("host");
        String address = intent.getStringExtra("address");

        if (shouldHideGlobalBlock(context, host)) {
            NotificationGroup.cancel(context, tag(uid, packageName, host), ID);
            return;
        }
        WhitelistManager whitelist = WhitelistManager.getInstance(context);
        boolean explicitBlock = whitelist.getExplicitBlock(context, address, uid) != null
                || whitelist.getExplicitBlock(context, host, uid) != null;
        for (String blocked : RulesManager.getInstance(context).getBlockedDomains())
            explicitBlock |= Util.isAddressInsideDomain(host, blocked);
        if (state(context, uid, host, address) == State.ALLOWED) {
            NotificationGroup.cancel(context, tag(uid, packageName, host), ID);
            return;
        }
        String message = context.getString(explicitBlock ? R.string.notification_review_block
                : R.string.notification_allow_queued, host);
        NotificationCompat.Builder result = builder(context, uid, packageName, host, address, message, !explicitBlock);
        addOptions(context, result, uid, packageName, host, address);
        NotificationGroup.notify(context, tag(uid, packageName, host), ID, result.build());
        // Post feedback before queueing: immediate activation must not be followed by a stale alert.
        if (!explicitBlock)
            RulesManager.getInstance(context).queueRuleText(context,
                    "allow package:" + packageName + " " + DomainInput.destination(host));
    }

    /** Called after the rule worker has committed, activated or removed rules and rebuilt its policy. */
    static synchronized void refresh(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            String tag = entry.getTag();
            if (entry.getId() != ID || tag == null || !tag.startsWith(PREFIX)) continue;
            String[] identity = tag.substring(PREFIX.length()).split(":", 3);
            if (identity.length != 3) continue;
            int uid;
            try { uid = Integer.parseInt(identity[0]); }
            catch (NumberFormatException invalid) { continue; }
            String pkg = identity[1], host = identity[2];
            if (!pkg.isEmpty()) try {
                if (context.getPackageManager().getApplicationInfo(pkg, 0).uid != uid) continue;
            } catch (PackageManager.NameNotFoundException removed) { continue; }
            Notification current = entry.getNotification();
            // Notifications posted before this feature still identify their app and domain in the tag.
            String address = current.extras.getString(ADDRESS, host);
            State state = state(context, uid, host, address);
            if (state == State.ALLOWED || shouldHideGlobalBlock(context, host)) {
                NotificationGroup.cancel(context, tag, ID);
            } else if (current.extras.getBoolean(PENDING, false) != (state == State.PENDING)) {
                NotificationCompat.Builder updated = accessBuilder(context, uid, pkg, host, address, state)
                        .setWhen(current.when);
                NotificationGroup.notify(context, tag, ID, updated.build());
            }
        }
    }

    private static State state(Context context, int uid, String host, String address) {
        RulesManager rules = RulesManager.getInstance(context);
        WhitelistManager whitelist = WhitelistManager.getInstance(context);
        if (rules.isUidPaused(uid)) return State.ALLOWED;
        if (rules.isGlobalDomainBlocked(host)
                || whitelist.getExplicitBlock(context, host, uid) != null
                || whitelist.getExplicitBlock(context, address, uid) != null) return State.BLOCKED;
        RuleAllowData hostRule = whitelist.isAllowed(context, host, uid);
        RuleAllowData addressRule = host.equals(address) ? hostRule : whitelist.isAllowed(context, address, uid);
        if ((hostRule != null && hostRule.allowed == 1) || (addressRule != null && addressRule.allowed == 1))
            return State.ALLOWED;
        if (whitelist.isPendingAllowed(context, host, uid) != null
                || (!host.equals(address) && whitelist.isPendingAllowed(context, address, uid) != null))
            return State.PENDING;
        return State.BLOCKED;
    }

    /** A newly activated block addresses existing alerts; future attempts may still notify. */
    static synchronized void dismissForBlock(Context context, MyRule rule) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (!(rule instanceof BlockedDomainRule)
                && !(rule instanceof RuleAndPackage && ((RuleAndPackage) rule).isBlocked())) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            String tag = entry.getTag();
            if (entry.getId() != ID || tag == null || !tag.startsWith(PREFIX)) continue;
            String[] identity = tag.substring(PREFIX.length()).split(":", 3);
            if (identity.length != 3) continue;
            int uid;
            try { uid = Integer.parseInt(identity[0]); }
            catch (NumberFormatException invalid) { continue; }
            String host = identity[2];
            String address = entry.getNotification().extras.getString(ADDRESS, host);
            boolean matches = rule instanceof BlockedDomainRule
                    ? Util.isAddressInsideDomain(host, ((BlockedDomainRule) rule).getDomainName())
                    : ((RuleAndPackage) rule).appliesToAccess(uid, java.util.Arrays.asList(host, address));
            if (matches) NotificationGroup.cancel(context, tag, ID);
        }
    }

    static boolean validTarget(Context context, Intent intent) {
        int uid = intent.getIntExtra("uid", -1);
        String packageName = intent.getStringExtra("package");
        String host = intent.getStringExtra("host");
        if (uid < 0 || TextUtils.isEmpty(packageName) || host == null || intent.getStringExtra("address") == null) return false;
        try {
            return context.getPackageManager().getApplicationInfo(packageName, 0).uid == uid
                    && host.equals(DomainInput.normalize(host))
                    && identity(uid, packageName, host).equals(intent.getData());
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException invalid) { return false; }
    }

    private static void addOptions(Context context, NotificationCompat.Builder builder,
                                   int uid, String packageName, String host, String address) {
        // Launch the dialog activity directly; Android 12 disallows receiver-to-activity trampolines.
        Intent options = new Intent(context, ActivityAllowDomain.class)
                .setAction(OPTIONS).setData(identity(uid, packageName, host))
                .putExtra("uid", uid).putExtra("package", packageName)
                .putExtra("host", host).putExtra("address", address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        builder.addAction(R.drawable.ic_settings_white_24dp, context.getString(R.string.notification_allow_options),
                PendingIntent.getActivity(context, 0, options, flags()));
    }

    private static boolean shouldHideGlobalBlock(Context context, String host) {
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NOTIFY_GLOBAL_BLOCKS, false)) return false;
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
            if (identity.length == 3 && shouldHideGlobalBlock(context, identity[2])) NotificationGroup.cancel(context, tag, ID);
        }
    }

    private static NotificationCompat.Builder builder(Context context, int uid, String packageName, String host,
                                                      String address, String text, boolean pending) {
        Intent main = new Intent(context, ActivityMain.class)
                .setData(identity(uid, packageName, host))
                .putExtra(ActivityMain.EXTRA_SEARCH, packageName.isEmpty() ? Integer.toString(uid) : packageName);
        PendingIntent review = PendingIntent.getActivity(context, 0, main, flags());
        String names = TextUtils.join(", ", Util.getApplicationNames(uid, context));
        Bundle metadata = new Bundle();
        metadata.putString(ADDRESS, address);
        metadata.putBoolean(PENDING, pending);
        NotificationCompat.Builder builder = NotificationGroup.builder(context, "access")
                .addExtras(metadata)
                .setSmallIcon(R.drawable.ic_cloud_upload_white_24dp)
                .setContentTitle(names.isEmpty() ? Integer.toString(uid) : names)
                .setContentText(text).setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(review).setOnlyAlertOnce(true).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);
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
