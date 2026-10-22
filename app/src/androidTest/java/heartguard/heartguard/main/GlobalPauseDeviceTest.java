package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GlobalPauseDeviceTest {
    private static final String PKG = "com.android.chrome";
    private static final String HOST = "global-pause-test.invalid";
    private Context context;
    private RulesManager rules;
    private DatabaseHelper db;
    private UiDevice device;
    private Activity activity;
    private final List<String> owned = new ArrayList<>();
    private Boolean oldDark;

    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals(0, RuleDelay.read(context).seconds);
        assertFalse(RuleDelay.read(context, PKG).overridden);
        assertTrue(AppPause.read(context, null).isEmpty());
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        oldDark = prefs.contains("dark_theme") ? prefs.getBoolean("dark_theme", false) : null;
    }

    @After public void cleanup() throws Exception {
        if (activity != null) onMain(() -> activity.finish());
        List<Long> ids = new ArrayList<>();
        try (Cursor c = db.getAllRules()) {
            while (c.moveToNext()) {
                String text = c.getString(c.getColumnIndexOrThrow("ruletext"));
                if (owned.contains(text) || owned.contains(text.replaceFirst("^(abandon )?- ", "")))
                    ids.add(c.getLong(c.getColumnIndexOrThrow("_id")));
            }
        }
        db.removeRulesById(ids.toArray(new Long[0]));
        reload();
        android.content.SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        if (oldDark == null) editor.remove("dark_theme"); else editor.putBoolean("dark_theme", oldDark);
        editor.commit();
    }

    // Test: Longer app delays apply to app/global bypasses; shortening and returning to default wait the difference.
    // Why: An override must not be ignored, or bypassed through the main switch.
    @Test public void overridesAndGlobalActionsRespectEffectiveDelays() throws Exception {
        add("delay 21600");
        assertEquals(7200, rules.previewDelay(context, "delay package:" + PKG + " 14400"));
        assertEquals(0, rules.previewDelay(context, "delay package:" + PKG + " 25200"));
        add("delay package:" + PKG + " 25200");
        assertEquals(25200, RuleDelay.read(context, PKG).seconds);
        assertEquals(25200, rules.previewDelay(context, "allow package:" + PKG));
        assertEquals(25200, rules.previewDelay(context, "allow uid:" + context.getPackageManager().getApplicationInfo(PKG, 0).uid));
        assertEquals(25200, rules.previewDelay(context, AppPauseRule.text(PKG, 60)));
        assertEquals(25200, rules.previewDelay(context, AppPauseRule.text(null, 60)));
        assertEquals(25200, rules.previewDelay(context, "- feature enabled"));
        assertEquals(25200, rules.previewDelay(context, "allow host:" + HOST));
        assertEquals(10800, rules.previewDelay(context, "delay package:" + PKG + " 14400"));
        assertEquals(3600, rules.previewDelay(context, "- delay package:" + PKG + " 25200"));
        add("delay package:" + PKG + " 14400");
        RuleDelay.State pending = RuleDelay.read(context, PKG);
        assertEquals(25200, pending.seconds);
        assertEquals(1, pending.pending.size());
        long remaining = pending.pending.get(0).time - System.currentTimeMillis();
        assertTrue(remaining > 10790000 && remaining <= 10800000);
        assertTrue(RuleDelay.read(context).pending.isEmpty());
    }

    // Test: Pausing either package in a shared Android UID honors the stronger peer delay.
    // Why: Android routes those packages together, so choosing the other package cannot bypass its protection.
    @Test public void sharedUidPauseHonorsStrongerPeer() throws Exception {
        String[] peers = context.getPackageManager().getPackagesForUid(1000);
        assertNotNull(peers);
        assertTrue(peers.length > 1);
        add("delay 21600");
        add("delay package:" + peers[0] + " 25200");
        assertEquals(25200, rules.previewDelay(context, AppPauseRule.text(peers[1], 60)));
        assertEquals(25200, rules.previewDelay(context, "allow package:" + peers[1] + " host:" + HOST));
    }

    // Test: A pending global pause is not active; its duration starts after the delay, and expiry preserves blocks.
    // Why: Pending time must not consume the pause or allow traffic early, and restoring rules must be automatic.
    @Test public void globalPauseWaitsExpiresAndPreservesBlocks() throws Exception {
        add("blockdomain " + HOST);
        add("block package:" + PKG + " host:" + HOST);
        add("delay 2");
        String text = AppPauseRule.text(null, 4);
        add(text);
        assertFalse(rules.isGloballyPaused());
        assertFalse(AppPause.read(context, null).get(0).active);
        assertDns(true, 12345);
        await(rules::isGloballyPaused, 6000);
        long end = AppPause.read(context, null).get(0).time;
        assertTrue(end > System.currentTimeMillis() + 2500);
        assertTrue(rules.isUidPaused(12345));
        assertTrue(rules.isUidPaused(54321));
        assertDns(false, 12345);
        assertDns(false, 54321);
        reload();
        assertEquals(end, AppPause.read(context, null).get(0).time);
        await(() -> !rules.isGloballyPaused(), 7000);
        assertDns(true, 12345);
        assertTrue(exists("blockdomain " + HOST));
        assertTrue(exists("block package:" + PKG + " host:" + HOST));
        for (String invalid : new String[]{"0", "604801", "99999999999", "-1"})
            assertNull(MyRule.getRuleFromText(context, "pause seconds:" + invalid));
        assertNotNull(MyRule.getRuleFromText(context, "pause seconds:604800"));
    }

    // Test: Both themes show the approved off/pause dialog and pending strip; Cancel leaves it pending and Yes cancels.
    // Why: Delay disclosure, compact actions and simple confirmation labels must match the implemented UI.
    @Test public void pendingPauseUiAndConfirmations() throws Exception {
        add("delay 2100");
        if (!rules.getPreferenceEnabled(context)) add("feature enabled");
        for (boolean dark : new boolean[]{false, true}) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("dark_theme", dark).commit();
            launch();
            onMain(() -> GlobalPauseDialog.show(activity));
            assertFalse(find("global_pause_auto_resume").isChecked());
            assertFalse(device.hasObject(By.res(context.getPackageName(), "global_pause_amount")));
            assertTrue(find("delay_notice_timing").getText().contains("35 minutes"));
            shot("off-" + dark);
            find("delay_notice_toggle").click();
            find("delay_notice_explanation");
            shot("off-explained-" + dark);
            find("global_pause_auto_resume").click();
            find("global_pause_amount");
            find("global_pause_unit").click();
            unitChoice("Days").click();
            setAmount("global_pause_amount", "3");
            assertTrue(find("delay_notice_explanation").getText().contains("3 day"));
            shot("pause-form-" + dark);
            owned.add(AppPauseRule.text(null, 259200));
            positive().click();
            assertTrue(device.wait(Until.gone(By.res(context.getPackageName(), "global_pause_auto_resume")), 5000));
            find("globalPauseAction");
            assertTrue(find("globalPauseText").getText().contains("35 min"));
            onMain(() -> assertEquals("Normal font strip stays on one line", 1,
                    ((android.widget.TextView) activity.findViewById(R.id.globalPauseText)).getLineCount()));
            shot("pending-" + dark);
            find("globalPauseAction").click();
            shot("cancel-after-tap-" + dark);
            assertEquals("Yes", positive().getText());
            assertEquals("Cancel", negative().getText());
            shot("cancel-dialog-" + dark);
            device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "global-pause-cancel-" + dark + ".xml"));
            UiObject2 message = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?s).*Cancel the.*"))), 5000);
            assertNotNull("Pending pause confirmation body", message);
            assertTrue(message.getText().matches("(?s).*\\d+/\\d+ \\d{2}:\\d{2}:\\d{2} [AP]M.*"));
            shot("cancel-confirm-" + dark);
            negative().click();
            assertFalse(AppPause.read(context, null).isEmpty());
            find("globalPauseAction").click();
            positive().click();
            await(() -> AppPause.read(context, null).isEmpty(), 5000);
            device.wait(Until.gone(By.res(context.getPackageName(), "globalPauseAction")), 5000);
            onMain(() -> activity.finish()); activity = null;
        }
    }

    // Test: Resume asks Yes/Cancel; Cancel preserves the active pause and Yes restores protection, without deleting other rules.
    // Why: A short inline action must not accidentally end the pause on the first tap.
    @Test public void activePauseResumeAndPermanentOff() throws Exception {
        if (!rules.getPreferenceEnabled(context)) add("feature enabled");
        add("blockdomain " + HOST);
        add(AppPauseRule.text(null, 259200));
        launch();
        assertTrue(find("globalPauseText").getText().contains("3 days left"));
        shot("active");
        find("globalPauseAction").click();
        assertEquals("Yes", positive().getText());
        assertEquals("Cancel", negative().getText());
        shot("resume-confirm");
        negative().click();
        assertTrue(rules.isGloballyPaused());
        find("globalPauseAction").click(); positive().click();
        await(() -> !rules.isGloballyPaused(), 5000);
        assertTrue(exists("blockdomain " + HOST));
        add(AppPauseRule.text(null, 60));
        add("- feature enabled");
        assertTrue("Turning off permanently clears the old global timer", AppPause.read(context, null).isEmpty());
        add("feature enabled");
        assertFalse("Manual re-enable must start protected", rules.isGloballyPaused());
    }

    // Test: Info & controls opens the scoped delay editor, and the allow-app notice shows that same effective delay.
    // Why: A correctly enforced delay is still confusing if the user cannot see or change it in the app UI.
    @Test public void appDelayEditorAndAllowNotice() throws Exception {
        add("delay 21600");
        launch();
        find("appOptionsButton").click();
        find("appDelayButton").click();
        assertTrue(find("rule_delay_inherit").isChecked());
        find("rule_delay_inherit").click();
        find("rule_delay_unit").click(); unitChoice("Hours").click();
        setAmount("rule_delay_input", "7");
        shot("app-delay");
        owned.add("delay package:" + PKG + " 25200");
        positive().click();
        await(() -> RuleDelay.read(context, PKG).seconds == 25200, 5000);
        onMain(() -> {
            Rule app = Rule.getRules(false, context).stream().filter(r -> r.packageName.equals(PKG)).findFirst().get();
            AppAccessDialog.show(activity, app, true);
        });
        assertTrue(find("delay_notice_timing").getText().contains("7 hours"));
        shot("allow-notice");
        find("delay_notice_toggle").click();
        shot("allow-notice-explained");
        negative().click();
        find("appDelayButton").click();
        find("rule_delay_inherit").click();
        assertTrue(find("rule_delay_preview").getText().contains("1 hour"));
        positive().click();
        await(() -> !RuleDelay.read(context, PKG).pending.isEmpty(), 5000);
        assertEquals(25200, rules.getPauseDelay(PKG));
    }

    private void launch() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, ActivityMain.class)
                .putExtra(ActivityMain.EXTRA_SEARCH, "Chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk"))) negative().click();
    }
    private void add(String text) throws Exception { owned.add(text); rules.queueRuleText(context, text); barrier(); }
    private boolean exists(String text) { try (Cursor c = db.getRuleMatchingRuletext(text)) { return c.moveToFirst(); } }
    private void barrier() throws Exception {
        CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }
    private void reload() throws Exception {
        Method method = RulesManager.class.getDeclaredMethod("getAllEnactedRulesFromDb", Context.class);
        method.setAccessible(true); method.invoke(rules, context);
        WhitelistManager.getInstance(context).updateRulesFromRulesManager(context);
        rules.startTrueTimeRequest(context); barrier();
    }
    private void await(BooleanSupplier condition, int millis) {
        long end = SystemClock.elapsedRealtime() + millis;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue(condition.getAsBoolean());
    }
    private UiObject2 find(String id) {
        UiObject2 view = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
        assertNotNull(id, view); return view;
    }
    private UiObject2 positive() {
        UiObject2 button = device.wait(Until.findObject(By.res("android", "button1")), 5000);
        assertNotNull("Positive dialog button", button); return button;
    }
    private UiObject2 negative() {
        UiObject2 button = device.wait(Until.findObject(By.res("android", "button2")), 5000);
        assertNotNull("Negative dialog button", button); return button;
    }
    private UiObject2 unitChoice(String label) {
        UiObject2 list = device.wait(Until.findObject(By.clazz("android.widget.ListView")), 5000);
        assertNotNull("Unit dropdown", list);
        for (UiObject2 child : list.getChildren())
            if (label.equalsIgnoreCase(child.getText())) return child;
        fail("Missing unit " + label); return null;
    }
    private void onMain(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private void setAmount(String id, String value) {
        int resource = context.getResources().getIdentifier(id, "id", context.getPackageName());
        onMain(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                EditText amount = root.findViewById(resource);
                if (amount != null) { amount.setText(value); amount.clearFocus(); }
            }
        });
    }
    private void shot(String name) {
        device.waitForIdle();
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "global-pause-" + name + ".png"));
    }
    private void assertDns(boolean blocked, int uid) throws Exception {
        HeartGuardDeviceTest.TestService[] service = new HeartGuardDeviceTest.TestService[1];
        onMain(() -> { service[0] = new HeartGuardDeviceTest.TestService(); service[0].attach(context); });
        Method check = ServiceSinkhole.class.getDeclaredMethod("isDnsBlocked", String.class, int.class, boolean.class);
        check.setAccessible(true);
        assertEquals(blocked, check.invoke(service[0], HOST, uid, false));
    }
}
