package heartguard.heartguard.main;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.net.VpnService;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NotificationGroupDeviceTest {
    private static final String HOST = "group-notification-test.invalid";
    private static final String TAG = "notification-group-test";
    private Context context;
    private NotificationManager manager;
    private DatabaseHelper db;
    private RulesManager rules;
    private UiDevice device;
    private int uid;
    private boolean enabledByTest;

    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertTrue(Build.VERSION.SDK_INT >= 24);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        manager = context.getSystemService(NotificationManager.class);
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uid = android.os.Process.myUid();
        assertEquals(0, RuleDelay.read(context).seconds);
    }

    @After public void cleanup() {
        device.pressHome();
        NotificationGroup.cancel(context, AccessNotification.tag(uid, context.getPackageName(), HOST), AccessNotification.ID);
        NotificationGroup.cancel(context, AccessNotification.tag(uid, context.getPackageName(), "second." + HOST), AccessNotification.ID);
        for (int id : new int[]{1, 2, 3}) NotificationGroup.cancel(context, TAG, id);
        db.getWritableDatabase().delete("access", "daddr = ?", new String[]{HOST});
        if (enabledByTest) {
            List<Long> ids = new ArrayList<>();
            try (Cursor c = db.getRuleMatchingRuletext("feature enabled")) {
                while (c.moveToNext()) ids.add(c.getLong(c.getColumnIndexOrThrow("_id")));
            }
            db.removeRulesById(ids.toArray(new Long[0]));
            rules.startTrueTimeRequest(context);
            await(() -> !rules.getPreferenceEnabled(context));
            ServiceSinkhole.stop("notification test cleanup", context, false);
        }
    }

    // Test: Clear all from the real shade dismisses alerts while the VPN status, active work and logs survive.
    // Why: Notification dismissal must never weaken enforcement, delete rules, or cancel an active operation.
    @Test public void clearFromShadePreservesProtectionRulesAndHistory() throws Exception {
        device.executeShellCommand("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
        assertNull(VpnService.prepare(context));
        enabledByTest = !rules.getPreferenceEnabled(context);
        if (enabledByTest) {
            rules.queueRuleText(context, "feature enabled");
            await(() -> rules.getPreferenceEnabled(context));
        }
        ServiceSinkhole.start("notification group test", context);
        await(() -> foreground() != null);
        Packet packet = new Packet(); packet.uid = uid; packet.time = System.currentTimeMillis();
        packet.version = 4; packet.protocol = 6; packet.daddr = "192.0.2.91"; packet.dport = 443;
        db.updateAccess(context, packet, HOST);
        String history = history();
        assertFalse("Traffic fixture exists", history.isEmpty());
        String savedRules = savedRules();
        AccessNotification.show(context, uid, HOST, HOST);
        AccessNotification.show(context, uid, "second." + HOST, "192.0.2.91");
        NotificationGroup.notify(context, TAG, 1, NotificationGroup.builder(context, "notify")
                .setSmallIcon(R.drawable.ic_file_download_white_24dp).setContentTitle("Test download")
                .setOngoing(true).build());
        manager.notify(TAG, 2, new NotificationCompat.Builder(context, "notify")
                .setSmallIcon(R.drawable.ic_security_white_24dp).setContentTitle("Unrelated test notification").build());
        await(() -> summary() != null && alert(HOST) != null && alert("second." + HOST) != null);
        assertEquals(NotificationGroup.KEY, foreground().getNotification().getGroup());
        assertFalse(context.getPackageManager().getReceiverInfo(new ComponentName(context, ReceiverNotificationGroup.class), 0).exported);
        device.pressHome();
        assertTrue(device.openNotification());
        UiObject2 button = NotificationShade.action(device, context.getString(R.string.notification_clear_all));
        device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "grouped-notifications.xml"));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "grouped-notifications.png"));
        assertNotNull("Clear all must be reachable in the notification shade", button);
        button.click();
        await(() -> alert(HOST) == null && alert("second." + HOST) == null);
        assertNotNull("Foreground status remains", foreground());
        assertNotNull("Active download remains", find(TAG, 1));
        assertNotNull("Other notification groups remain", find(TAG, 2));
        assertTrue(rules.getPreferenceEnabled(context));
        assertEquals(savedRules, savedRules());
        assertEquals(history, history());
        AccessNotification.show(context, uid, HOST, HOST);
        await(() -> alert(HOST) != null);
        ServiceSinkhole.reload("notification group reload test", context, false);
        await(() -> foreground() != null);
        assertNotNull("Reload does not dismiss alerts", alert(HOST));
    }

    // Test: A group without a service disappears after its final alert is dismissed, and can be recreated.
    // Why: The extra Android group summary must not become an orphaned sticky notification.
    @Test public void summaryFollowsAlertDismissal() throws Exception {
        assertFalse("This fixture expects protection off", rules.getPreferenceEnabled(context));
        AccessNotification.show(context, uid, HOST, HOST);
        await(() -> summary() != null && alert(HOST) != null);
        Notification dismissed = alert(HOST).getNotification();
        manager.cancel(alert(HOST).getTag(), AccessNotification.ID);
        dismissed.deleteIntent.send();
        await(() -> summary() == null);
        AccessNotification.show(context, uid, HOST, HOST);
        await(() -> summary() != null && alert(HOST) != null);
        Notification summary = summary().getNotification();
        assertNotNull(summary.actions);
        summary.actions[0].actionIntent.send();
        await(() -> alert(HOST) == null && summary() == null);
    }

    // Test: A group containing access alerts inherits their channel visibility and changes back when only quiet work remains.
    // Why: A minimum-importance summary hides the status-bar icon for its otherwise visible children.
    @Test public void summaryPreservesAlertVisibility() throws Exception {
        assertTrue(Build.VERSION.SDK_INT >= 26);
        NotificationGroup.notify(context, TAG, 3, NotificationGroup.statusBuilder(context, "foreground")
                .setSmallIcon(R.drawable.ic_security_white_24dp).setContentTitle("Test protection")
                .setOngoing(true).build());
        await(() -> summary() != null);
        assertEquals("foreground", summary().getNotification().getChannelId());
        AccessNotification.show(context, uid, HOST, HOST);
        AccessNotification.show(context, uid, "second." + HOST, "192.0.2.91");
        String twoAlerts = context.getResources().getQuantityString(R.plurals.notification_alert_count, 2, 2);
        await(() -> summary() != null && twoAlerts.contentEquals(
                summary().getNotification().extras.getCharSequence(Notification.EXTRA_TEXT)));
        device.pressHome();
        device.takeScreenshot(new File(context.getExternalFilesDir(null),
                "notification-visibility-" + summary().getNotification().getChannelId() + ".png"));
        assertEquals("The summary must not hide its access alerts", "access", summary().getNotification().getChannelId());
        assertEquals(manager.getNotificationChannel("access").getImportance(),
                manager.getNotificationChannel(summary().getNotification().getChannelId()).getImportance());

        // The text/count are unchanged: channel changes must still refresh the summary.
        for (String host : new String[]{HOST, "second." + HOST})
            NotificationGroup.notify(context, AccessNotification.tag(uid, context.getPackageName(), host), AccessNotification.ID,
                    NotificationGroup.builder(context, "foreground").setSmallIcon(R.drawable.ic_security_white_24dp)
                            .setContentTitle("Quiet test alert").build());
        await(() -> "foreground".equals(summary().getNotification().getChannelId()));
        AccessNotification.show(context, uid, HOST, HOST);
        await(() -> "access".equals(summary().getNotification().getChannelId()));
        NotificationGroup.clear(context);
        await(() -> alert(HOST) == null && alert("second." + HOST) == null
                && summary() != null && "foreground".equals(summary().getNotification().getChannelId()));
        assertNotNull("Protection remains", find(TAG, 3));
    }

    private StatusBarNotification foreground() {
        for (StatusBarNotification entry : manager.getActiveNotifications())
            if (entry.getTag() == null && entry.getId() == 1
                    && (entry.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return entry;
        return null;
    }
    private StatusBarNotification summary() { return find(NotificationGroup.SUMMARY_TAG, NotificationGroup.SUMMARY_ID); }
    private StatusBarNotification alert(String host) { return find(AccessNotification.tag(uid, context.getPackageName(), host), AccessNotification.ID); }
    private StatusBarNotification find(String tag, int id) {
        for (StatusBarNotification entry : manager.getActiveNotifications())
            if (id == entry.getId() && tag.equals(entry.getTag())) return entry;
        return null;
    }
    private String history() {
        try (Cursor c = db.getAccess(uid, false, HOST, ListSort.RECENT)) { return snapshot(c); }
    }
    private String savedRules() { try (Cursor c = db.getAllRules()) { return snapshot(c); } }
    private String snapshot(Cursor cursor) {
        StringBuilder text = new StringBuilder();
        while (cursor.moveToNext()) {
            for (int i = 0; i < cursor.getColumnCount(); i++) text.append(cursor.getString(i)).append('|');
            text.append('\n');
        }
        return text.toString();
    }
    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue("Expected state before timeout", condition.getAsBoolean());
    }
}
