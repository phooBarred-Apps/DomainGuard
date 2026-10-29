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
import android.provider.Settings;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private Boolean previousDark;
    private Activity settings;
    private int otherUid = -1;
    private final List<String> owned = new ArrayList<>();

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        barrier();
        notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        uid = android.os.Process.myUid();
        pkg = context.getPackageName();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        previousNotify = prefs.contains(AccessNotification.NOTIFY_GLOBAL_BLOCKS)
                ? prefs.getBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, false) : null;
        previousDark = prefs.contains("dark_theme") ? prefs.getBoolean("dark_theme", false) : null;
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
        if (previousDark == null) prefs.edit().remove("dark_theme").commit();
        else prefs.edit().putBoolean("dark_theme", previousDark).commit();
        notifications.cancel(AccessNotification.tag(uid, pkg, FIRST), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(uid, pkg, SECOND), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(uid, pkg, SUBDOMAIN), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(uid, pkg, LOOKALIKE), AccessNotification.ID);
        notifications.cancel(AccessNotification.tag(99195, "", FIRST), AccessNotification.ID);
        if (otherUid >= 0) notifications.cancel(AccessNotification.tag(otherUid, "com.android.chrome", FIRST), AccessNotification.ID);
        NotificationGroup.refresh(context);
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getAllRules()) {
            while (cursor.moveToNext())
                if (owned.contains(cursor.getString(cursor.getColumnIndexOrThrow("ruletext"))))
                    ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        }
        db.removeRulesById(ids.toArray(new Long[0]));
        rules.startTrueTimeRequest(context);
        barrier();
        await(() -> RuleDelay.read(context).seconds == 0);
    }

    // Test: Two destinations have independent real notification actions; filling in another host cannot retarget a tap.
    // Why: A newer connection must not silently change the destination allowed from an older notification.
    @Test public void actionTargetsOneDomainAndOneApp() throws Exception {
        AccessNotification.show(context, uid, FIRST, FIRST);
        Notification first = notification(FIRST);
        AccessNotification.show(context, uid, SECOND, SECOND);
        Notification second = notification(SECOND);
        AccessNotification.show(context, 99195, FIRST, FIRST);
        assertEquals(quickAllowLabel(), first.actions[0].title.toString());
        assertEquals(2, first.actions.length);
        assertEquals(context.getString(R.string.notification_allow_options), first.actions[1].title.toString());
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent);
        assertNotEquals(first.actions[1].actionIntent, second.actions[1].actionIntent);
        assertFalse(context.getPackageManager().getReceiverInfo(new ComponentName(context, ReceiverAllowDomain.class), 0).exported);
        first.actions[0].actionIntent.send(context, 0, new Intent().putExtra("host", SECOND));
        await(() -> exists(allow(FIRST)));
        assertFalse(exists(allow(SECOND)));
        assertFalse(exists("allow host:" + FIRST));
        await(() -> WhitelistManager.getInstance(context).isAllowed(context, FIRST, uid) != null);
        assertEquals(1, WhitelistManager.getInstance(context).isAllowed(context, FIRST, uid).allowed);
        assertNull(WhitelistManager.getInstance(context).isAllowed(context, FIRST, 99195));
        await(() -> find(AccessNotification.tag(uid, pkg, FIRST)) == null);
        assertNotNull("Another app's alert remains", find(AccessNotification.tag(99195, "", FIRST)));
        notification(SECOND);
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
        UiObject2 action = NotificationShade.action(device, quickAllowLabel());
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
        await(() -> hasText(FIRST, R.string.notification_allow_queued));
    }

    // Test: An in-app delayed temporary allow shows Pending, clears on activation, and does not revive an old alert at expiry.
    // Why: Notification cleanup must follow effective policy, not the entry point or the time the user pressed Allow.
    @Test public void inAppTemporaryAllowClearsOnlyAfterActivation() {
        addRule("delay 3");
        AccessNotification.show(context, uid, FIRST, FIRST);
        long posted = notification(FIRST).when;
        String temporary = TemporaryRuleText.text(allow(FIRST), 2);
        addRule(temporary);
        await(() -> hasText(FIRST, R.string.notification_allow_queued));
        assertFalse(rules.isTemporaryRuleActive(temporary));
        assertEquals(posted, notification(FIRST).when);
        await(() -> rules.isTemporaryRuleActive(temporary)
                && find(AccessNotification.tag(uid, pkg, FIRST)) == null);
        await(() -> !rules.isTemporaryRuleActive(temporary) && !exists(temporary));
        assertNull("Expiry must not repost historical alerts", find(AccessNotification.tag(uid, pkg, FIRST)));
        AccessNotification.show(context, uid, FIRST, FIRST);
        await(() -> hasText(FIRST, R.string.notification_domain_blocked));
    }

    // Test: Cancelling a pending allow restores Blocked, and rule processing does not revive a dismissed alert.
    // Why: Pending text cannot remain after cancellation, and reconciliation must update only outstanding notifications.
    @Test public void pendingCancellationAndDismissalRemainConsistent() {
        addRule("delay 60");
        AccessNotification.show(context, uid, FIRST, FIRST);
        addRule(allow(FIRST));
        await(() -> hasText(FIRST, R.string.notification_allow_queued));
        addRule("abandon " + allow(FIRST));
        await(() -> hasText(FIRST, R.string.notification_domain_blocked));
        assertNull(WhitelistManager.getInstance(context).isAllowed(context, FIRST, uid));
        NotificationGroup.cancel(context, AccessNotification.tag(uid, pkg, FIRST), AccessNotification.ID);
        await(() -> find(AccessNotification.tag(uid, pkg, FIRST)) == null);
        addRule(allow(FIRST));
        assertNull("A dismissed alert stays dismissed", find(AccessNotification.tag(uid, pkg, FIRST)));
    }

    // Test: A global parent allow clears matching alerts across apps except explicit app blocks and lookalike domains.
    // Why: Broad rules must reconcile all affected notifications without clearing alerts that remain blocked.
    @Test public void globalParentAllowRespectsAppExceptions() throws Exception {
        otherUid = context.getPackageManager().getApplicationInfo("com.android.chrome", 0).uid;
        addRule("block package:com.android.chrome host:" + FIRST);
        for (String host : new String[]{FIRST, SUBDOMAIN, SECOND, LOOKALIKE})
            AccessNotification.show(context, uid, host, host);
        AccessNotification.show(context, otherUid, FIRST, FIRST);
        addRule("allow host:" + FIRST);
        await(() -> find(AccessNotification.tag(uid, pkg, FIRST)) == null
                && find(AccessNotification.tag(uid, pkg, SUBDOMAIN)) == null);
        assertNotNull(find(AccessNotification.tag(otherUid, "com.android.chrome", FIRST)));
        notification(SECOND); notification(LOOKALIKE);
        addRule("- block package:com.android.chrome host:" + FIRST);
        await(() -> find(AccessNotification.tag(otherUid, "com.android.chrome", FIRST)) == null);
    }

    // Test: Global DNS blocks and per-app IP blocks keep a hostname alert until both blocking rules are removed.
    // Why: A domain allow must not dismiss a notification when another layer still denies the original destination.
    @Test public void globalAndAddressBlocksKeepAlertsUntilResolved() {
        prefs.edit().putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, true).commit();
        String address = "192.0.2.121";
        String globalBlock = "blockdomain " + FIRST;
        String ipBlock = "block package:" + pkg + " ip:" + address;
        addRule(globalBlock); addRule(ipBlock);
        AccessNotification.show(context, uid, FIRST, address);
        addRule("allow host:" + FIRST);
        notification(FIRST);
        addRule("- " + globalBlock);
        notification(FIRST);
        addRule("- " + ipBlock);
        await(() -> find(AccessNotification.tag(uid, pkg, FIRST)) == null);
    }

    private void addRule(String text) {
        if (!owned.contains(text)) owned.add(text);
        rules.queueRuleText(context, text);
        barrier();
    }

    // Test: Explicit global and app blocks clear only matching existing alerts, even when future global alerts are enabled.
    // Why: Addressing a domain in one app must also dismiss outstanding alerts covered by a newly enacted global block.
    @Test public void explicitBlocksAddressExistingAlertsAcrossTheirScope() throws Exception {
        prefs.edit().putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, true).commit();
        otherUid = context.getPackageManager().getApplicationInfo("com.android.chrome", 0).uid;
        for (String host : new String[]{FIRST, SUBDOMAIN, SECOND, LOOKALIKE})
            AccessNotification.show(context, uid, host, host);
        AccessNotification.show(context, otherUid, FIRST, FIRST);
        notification(FIRST); notification(SUBDOMAIN); notification(SECOND); notification(LOOKALIKE);
        await(() -> find(AccessNotification.tag(otherUid, "com.android.chrome", FIRST)) != null);
        addRule("blockdomain " + FIRST);
        await(() -> find(AccessNotification.tag(uid, pkg, FIRST)) == null
                && find(AccessNotification.tag(uid, pkg, SUBDOMAIN)) == null
                && find(AccessNotification.tag(otherUid, "com.android.chrome", FIRST)) == null);
        notification(SECOND); notification(LOOKALIKE);
        AccessNotification.show(context, uid, FIRST, FIRST);
        notification(FIRST);
        addRule("block package:" + pkg + " host:" + SECOND);
        await(() -> find(AccessNotification.tag(uid, pkg, SECOND)) == null);
        notification(FIRST); // A later attempt is not dismissed by unrelated rule processing.
        notification(LOOKALIKE);
    }

    private boolean hasText(String host, int resource) {
        Notification current = find(AccessNotification.tag(uid, pkg, host));
        return current != null && context.getString(resource, host).contentEquals(
                current.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    private void barrier() {
        CountDownLatch completed = new CountDownLatch(1);
        rules.handler.post(completed::countDown);
        try { assertTrue("Rule worker must finish", completed.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
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
        await(() -> hasOnlyOptions(FIRST));
        assertFalse(exists(allow(FIRST)));
        assertTrue(exists(block));
        prefs.edit().putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, true).commit();
        String globalBlock = "blockdomain " + SECOND;
        owned.add(globalBlock);
        rules.queueRuleText(context, globalBlock);
        await(() -> rules.getBlockedDomains().contains(SECOND));
        AccessNotification.show(context, uid, SECOND, SECOND);
        notification(SECOND).actions[0].actionIntent.send();
        await(() -> hasOnlyOptions(SECOND));
        assertFalse(exists(allow(SECOND)));
        assertTrue(exists(globalBlock));
        AccessNotification.show(context, 99195, FIRST, FIRST);
        await(() -> find(AccessNotification.tag(99195, "", FIRST)) != null);
        Notification unknown = find(AccessNotification.tag(99195, "", FIRST));
        assertNotNull(unknown);
        assertTrue(unknown.actions == null || unknown.actions.length == 0);
    }

    private boolean hasOnlyOptions(String host) {
        // Inspect one snapshot: the receiver can replace the notification between reads.
        Notification notification = notification(host);
        return notification.actions != null && notification.actions.length == 1
                && context.getString(R.string.notification_allow_options).contentEquals(notification.actions[0].title);
    }

    // Test: The second real shade action opens the shared dialog over the previous app and Cancel/Back save nothing.
    // Why: Reviewing a rule must not enable access or leave the user in DomainGuard's main screen.
    @Test public void optionsCancelReturnsToPreviousAppWithoutChanges() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertFalse(context.getPackageManager().getActivityInfo(new ComponentName(context, ActivityAllowDomain.class), 0).exported);
        device.pressHome();
        context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 5000));
        List<String> previousRules = ruleValues();
        for (boolean back : new boolean[]{false, true}) {
            prefs.edit().putBoolean("dark_theme", back).commit();
            openOptions(device);
            assertEquals(FIRST, find(device, "allow_new_access_daddr").getText());
            assertEquals(context.getString(R.string.scope_off), find(device, "allow_new_access_global").getContentDescription());
            assertFalse(find(device, "temporary_rule_enabled").isChecked());
            assertFalse(device.hasObject(By.pkg("com.google.android.inputmethod.latin")));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "notification-allow-options-" + (back ? "dark" : "light") + ".png"));
            if (back) device.pressBack();
            else device.wait(Until.findObject(By.res("android", "button2")), 5000).click();
            assertTrue(device.wait(Until.gone(By.res(pkg, "allow_new_access_daddr")), 5000));
            await(() -> "com.android.settings".equals(device.getCurrentPackageName()));
            assertEquals(previousRules, ruleValues());
        }
    }

    // Test: Options can choose a parent and queue a temporary global allow using the existing delay.
    // Why: The notification entry point must preserve domain identity, scope defaults, timing and duration.
    @Test public void optionsQueuesParentGlobalTemporaryRuleAfterDelay() throws Exception {
        owned.add("delay 60");
        rules.queueRuleText(context, "delay 60");
        await(() -> RuleDelay.read(context).seconds == 60);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.pressHome();
        openOptions(device);
        AccessNotification.show(context, uid, SECOND, SECOND);
        assertEquals("Later traffic cannot retarget the dialog", FIRST, find(device, "allow_new_access_daddr").getText());
        find(device, "domain_parent_choices").click();
        UiObject2 parent = device.wait(Until.findObject(By.text("mw-notification-test.invalid")), 5000);
        assertNotNull(parent); parent.click();
        find(device, "allow_new_access_global").click();
        assertEquals(context.getString(R.string.scope_on), find(device, "allow_new_access_global").getContentDescription());
        assertEquals(context.getString(R.string.domain_delay, RuleDelay.duration(context, 60)),
                find(device, "domain_action_delay").getText());
        find(device, "temporary_rule_enabled").click();
        assertEquals("5", find(device, "temporary_rule_amount").getText());
        String text = TemporaryRuleText.text("allow host:mw-notification-test.invalid", 300);
        owned.add(text);
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "notification-allow-options-global.png"));
        device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
        await(() -> exists(text));
        try (Cursor cursor = db.getRuleMatchingRuletext(text)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")) > System.currentTimeMillis() + 30000);
        }
        assertFalse(exists(allow(FIRST)));
        assertFalse(exists(allow(SECOND)));
        assertFalse(rules.isTemporaryRuleActive(text));
        await(() -> hasText(FIRST, R.string.notification_allow_queued)
                && hasText(SECOND, R.string.notification_allow_queued));
    }

    private void openOptions(UiDevice device) throws Exception {
        AccessNotification.show(context, uid, FIRST, FIRST);
        notification(FIRST);
        assertTrue(device.openNotification());
        UiObject2 options = NotificationShade.action(device, context.getString(R.string.notification_allow_options));
        assertNotNull("Allow options must be reachable beside quick Allow", options);
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "notification-two-buttons.png"));
        options.click();
        // The emulator can take over five seconds to launch a task from the shade after earlier UI tests.
        assertNotNull("Allow options must open the rule dialog",
                device.wait(Until.findObject(By.res(pkg, "allow_new_access_daddr")), 15000));
    }

    private UiObject2 find(UiDevice device, String id) {
        UiObject2 view = device.wait(Until.findObject(By.res(pkg, id)), 5000);
        assertNotNull(id, view);
        return view;
    }

    private String quickAllowLabel() {
        return context.getString(R.string.notification_allow_app, DomainRuleDialog.appName(context, pkg));
    }

    private List<String> ruleValues() {
        List<String> values = new ArrayList<>();
        try (Cursor cursor = db.getAllRules()) {
            while (cursor.moveToNext())
                for (int column = 0; column < cursor.getColumnCount(); column++) values.add(cursor.getString(column));
        }
        return values;
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
        device.pressHome();
        settings=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,ActivitySettings.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiObject2 options = device.wait(Until.findObject(By.res("android", "title")
                .text(context.getString(R.string.setting_options))),10000);
        assertNotNull("Settings categories must be visible", options);
        options.click();
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
