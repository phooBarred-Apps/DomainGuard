package heartguard.heartguard.main;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AccessNotificationDeviceTest {
    private static final String FIRST = "first.mw-notification-test.invalid";
    private static final String SECOND = "second.mw-notification-test.invalid";
    private static final String SUBDOMAIN = "cdn." + FIRST;
    private static final String LOOKALIKE = "not" + FIRST;
    private Context context;
    private DatabaseHelper db;
    private RulesManager rules;
    private NotificationManager notifications;
    private int uid;
    private String pkg;
    private SharedPreferences prefs;
    private Boolean previousNotify;
    private Activity settings;
    private final List<String> owned = new ArrayList<>();

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        uid = android.os.Process.myUid();
        pkg = context.getPackageName();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        previousNotify = prefs.contains(AccessNotification.NOTIFY_GLOBAL_BLOCKS)
                ? prefs.getBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, false) : null;
        prefs.edit().remove(AccessNotification.NOTIFY_GLOBAL_BLOCKS).commit();
        assertEquals(0, RuleDelay.read(context).seconds);
        assertFalse(exists(allow(FIRST)));
        assertFalse(exists(allow(SECOND)));
        owned.add(allow(FIRST));
        owned.add(allow(SECOND));
    }

    @After public void cleanup() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome();
        if (settings != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> settings.finish());
        if (previousNotify == null) prefs.edit().remove(AccessNotification.NOTIFY_GLOBAL_BLOCKS).commit();
        else prefs.edit().putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, previousNotify).commit();
        notifications.cancel(AccessNotification.tag(uid, pkg, FIRST), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(uid, pkg, SECOND), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(uid, pkg, SUBDOMAIN), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(uid, pkg, LOOKALIKE), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(99195, "", FIRST), AccessNotification.ID);
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getAllRules()) {
            while (cursor.moveToNext())
                if (owned.contains(cursor.getString(cursor.getColumnIndexOrThrow("ruletext"))))
                    ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        }
        db.removeRulesById(ids.toArray(new Long[0]));
        rules.startTrueTimeRequest(context);
        await(() -> RuleDelay.read(context).seconds == 0);
    }

    // Test: Two destinations have independent real notification actions; filling in another host cannot retarget a tap.
    // Why: A newer connection must not silently change the destination allowed from an older notification.
    @Test public void actionTargetsOneDomainAndOneApp() throws Exception {
        AccessNotification.show(context, uid, FIRST, FIRST);
        Notification first = notification(FIRST);
        AccessNotification.show(context, uid, SECOND, SECOND);
        Notification second = notification(SECOND);
        assertEquals(context.getString(R.string.notification_allow_app), first.actions[0].title.toString());
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent);
        assertFalse(context.getPackageManager().getReceiverInfo(new ComponentName(context, ReceiverAllowDomain.class), 0).exported);
        first.actions[0].actionIntent.send(context, 0, new Intent().putExtra("host", SECOND));
        await(() -> exists(allow(FIRST)));
        assertFalse(exists(allow(SECOND)));
        assertFalse(exists("allow host:" + FIRST));
        await(() -> WhitelistManager.getInstance(context).isAllowed(context, FIRST, uid) != null);
        assertEquals(1, WhitelistManager.getInstance(context).isAllowed(context, FIRST, uid).allowed);
        assertNull(WhitelistManager.getInstance(context).isAllowed(context, FIRST, 99195));
    }

    // Test: Tapping the visible notification Allow button stays pending under the existing rule delay.
    // Why: The real notification-shade control must work without opening HeartGuard or bypassing its delay.
    @Test public void notificationAllowRespectsDelay() throws Exception {
        owned.add("delay 60");
        rules.queueRuleText(context, "delay 60");
        await(() -> RuleDelay.read(context).seconds == 60);
        AccessNotification.show(context, uid, FIRST, FIRST);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.pressHome();
        assertTrue(device.openNotification());
        assertTrue(device.wait(Until.hasObject(By.textContains(FIRST)), 5000));
        java.util.regex.Pattern buttonText = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(context.getString(R.string.notification_allow_app)),
                java.util.regex.Pattern.CASE_INSENSITIVE);
        if (!device.hasObject(By.text(buttonText))) {
            UiObject2 expand = device.wait(Until.findObject(By.res("android", "expand_button")), 3000);
            assertNotNull("Notification must be expandable", expand);
            expand.click();
        }
        UiObject2 action = device.wait(Until.findObject(By.text(buttonText)), 5000);
        assertNotNull("Allow button must be visible in the notification shade", action);
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "domain-notifications.png"));
        action.click();
        await(() -> exists(allow(FIRST)));
        try (Cursor cursor = db.getRuleMatchingRuletext(allow(FIRST))) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")) > System.currentTimeMillis() + 30000);
        }
        assertNull(WhitelistManager.getInstance(context).isAllowed(context, FIRST, uid));
    }

    // Test: App/global blocks remain intact; unidentified/shared app identities have no one-tap allow action.
    // Why: The shortcut must not broaden a rule or suggest that it overrides explicit blocking rules.
    @Test public void explicitBlocksAndAmbiguousAppsRequireReview() throws Exception {
        String block = "block package:" + pkg + " host:" + FIRST;
        owned.add(block);
        rules.queueRuleText(context, block);
        await(() -> WhitelistManager.getInstance(context).getExplicitBlock(context, FIRST, uid) != null);
        AccessNotification.show(context, uid, FIRST, FIRST);
        notification(FIRST).actions[0].actionIntent.send();
        await(() -> hasNoActions(FIRST));
        assertFalse(exists(allow(FIRST)));
        assertTrue(exists(block));
        prefs.edit().putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, true).commit();
        String globalBlock = "blockdomain " + SECOND;
        owned.add(globalBlock);
        rules.queueRuleText(context, globalBlock);
        await(() -> rules.getBlockedDomains().contains(SECOND));
        AccessNotification.show(context, uid, SECOND, SECOND);
        notification(SECOND).actions[0].actionIntent.send();
        await(() -> hasNoActions(SECOND));
        assertFalse(exists(allow(SECOND)));
        assertTrue(exists(globalBlock));
        AccessNotification.show(context, 99195, FIRST, FIRST);
        await(() -> find(AccessNotification.tag(99195, "", FIRST)) != null);
        Notification unknown = find(AccessNotification.tag(99195, "", FIRST));
        assertNotNull(unknown);
        assertTrue(unknown.actions == null || unknown.actions.length == 0);
    }

    private boolean hasNoActions(String host) {
        // Inspect one snapshot: the receiver can replace the notification between reads.
        Notification notification = notification(host);
        return notification.actions == null || notification.actions.length == 0;
    }

    // Test: Global blocks are quiet by default, can be included, and are removed immediately when disabled.
    // Why: The option must cover subdomains while retaining app-only block alerts, history and enforcement.
    @Test public void globalBlockNotificationsAreOptIn() throws Exception {
        String global = "blockdomain " + FIRST;
        String local = "block package:" + pkg + " host:" + SECOND;
        owned.add(global); owned.add(local);
        rules.queueRuleText(context, global); rules.queueRuleText(context, local);
        await(() -> rules.getBlockedDomains().contains(FIRST)
                && WhitelistManager.getInstance(context).getExplicitBlock(context, SECOND, uid) != null);
        assertFalse(prefs.getBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, false));
        for (String host : new String[]{FIRST, SUBDOMAIN, SECOND, LOOKALIKE}) AccessNotification.show(context,uid,host,host);
        assertNull(find(AccessNotification.tag(uid,pkg,FIRST)));
        assertNull(find(AccessNotification.tag(uid,pkg,SUBDOMAIN)));
        notification(SECOND); notification(LOOKALIKE);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        settings=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,ActivitySettings.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.wait(Until.findObject(By.text(context.getString(R.string.setting_options))),5000).click();
        String title=context.getString(R.string.notification_global_blocks);
        if (device.wait(Until.hasObject(By.text(title)), 2000) != Boolean.TRUE)
            new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(title));
        device.wait(Until.findObject(By.text(title)),5000).click();
        await(() -> prefs.getBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS,false));
        for(String host:new String[]{FIRST,SUBDOMAIN}) { AccessNotification.show(context,uid,host,host); notification(host); }
        device.takeScreenshot(new File(context.getExternalFilesDir(null),"notify-global-blocks.png"));
        device.findObject(By.text(title)).click();
        await(() -> !prefs.getBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS,true)
                && find(AccessNotification.tag(uid,pkg,FIRST))==null && find(AccessNotification.tag(uid,pkg,SUBDOMAIN))==null);
        notification(SECOND); notification(LOOKALIKE);
        assertTrue(exists(global)); assertTrue(exists(local));
    }

    private String allow(String host) { return "allow package:" + pkg + " host:" + host; }
    private boolean exists(String rule) {
        try (Cursor cursor = db.getRuleMatchingRuletext(rule)) { return cursor.moveToFirst(); }
    }
    private Notification notification(String host) {
        await(() -> find(AccessNotification.tag(uid, pkg, host)) != null);
        Notification value = find(AccessNotification.tag(uid, pkg, host));
        assertNotNull(value);
        return value;
    }
    private Notification find(String tag) {
        for (StatusBarNotification entry : notifications.getActiveNotifications())
            if (tag.equals(entry.getTag())) return entry.getNotification();
        return null;
    }
    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue(condition.getAsBoolean());
    }
}
