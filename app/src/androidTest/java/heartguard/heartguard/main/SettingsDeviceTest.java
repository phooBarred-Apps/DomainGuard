package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsDeviceTest {
    private Context context;
    private UiDevice device;
    private Activity activity;
    private final Set<Long> originalIds = new HashSet<>();

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals("Tests require no active delay", 0, RuleDelay.read(context).seconds);
        assertTrue(RuleDelay.read(context).pending.isEmpty());
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRules()) {
            while (cursor.moveToNext()) originalIds.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        }
    }

    @After public void cleanup() {
        if (activity != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRules()) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                if (!originalIds.contains(id) && cursor.getString(cursor.getColumnIndexOrThrow("ruletext")).matches("(abandon )?(- )?delay [0-9]+"))
                    ids.add(id);
            }
        }
        DatabaseHelper.getInstance(context).removeRulesById(ids.toArray(new Long[0]));
        RulesManager.getInstance(context).startTrueTimeRequest(context);
        SystemClock.sleep(300);
    }

    // Test: Seconds convert to readable durations and an out-of-range value is rejected.
    // Why: The new settings field must not silently save a different waiting time.
    @Test public void readablePreviewAndValidation() {
        openDelay();
        UiObject2 input = find("rule_delay_input");
        for (String[] pair : new String[][]{{"60", "1 minute"}, {"90", "1 minute 30 seconds"},
                {"3600", "1 hour"}, {"3661", "1 hour 1 minute 1 second"}, {"604800", "168 hours"}, {"0", "No delay"}}) {
            input.setText(pair[0]);
            device.waitForIdle();
            assertEquals(pair[1], find("rule_delay_preview").getText());
        }
        input.setText("604801");
        device.waitForIdle();
        device.findObject(By.res("android", "button1")).click();
        assertNotNull(find("rule_delay_input"));
        assertEquals(0, RuleDelay.read(context).seconds);
    }

    // Test: A delay reduction stays pending and cancelling it preserves the active delay.
    // Why: The settings shortcut must obey the existing rule engine's waiting period.
    @Test public void savingUsesRulesAndPendingReductionCanBeCancelled() {
        openDelay();
        find("rule_delay_input").setText("60");
        device.findObject(By.res("android", "button1")).click();
        await(() -> RuleDelay.read(context).seconds == 60);
        device.wait(Until.findObject(By.text(context.getString(R.string.rule_delay_title))), 5000).click();
        find("rule_delay_input").setText("0");
        assertTrue(find("rule_delay_preview").getText().contains("after 1 minute"));
        device.findObject(By.res("android", "button1")).click();
        await(() -> !RuleDelay.read(context).pending.isEmpty());
        assertEquals(60, RuleDelay.read(context).seconds);
        assertEquals("delay 0", RuleDelay.read(context).pending.get(0).rule);
        device.wait(Until.findObject(By.text(context.getString(R.string.rule_delay_title))), 5000).click();
        assertFalse(find("rule_delay_input").isEnabled());
        assertTrue(find("rule_delay_current").getText().contains("Pending: No delay"));
        find("rule_delay_cancel_pending").click();
        await(() -> RuleDelay.read(context).pending.isEmpty());
        assertEquals(60, RuleDelay.read(context).seconds);
    }

    // Test: A delay entered through the rule engine appears in the settings dialog.
    // Why: A separate saved preference could disagree with the actual enforced delay.
    @Test public void manuallyEnteredDelayAppearsInSettings() {
        RulesManager.getInstance(context).queueRuleText(context, "delay 90");
        await(() -> RuleDelay.read(context).seconds == 90);
        openDelay();
        assertEquals("90", find("rule_delay_input").getText());
        assertEquals("Current delay: 1 minute 30 seconds", find("rule_delay_current").getText());
    }

    // Test: Former Pro settings and the log open without billing permission or a purchase activity.
    // Why: Removing the purchase screen alone would leave the old entitlement checks active.
    @Test public void formerProSettingsAndLogWorkWithoutPurchases() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES);
        for (String permission : info.requestedPermissions) assertNotEquals("com.android.vending.BILLING", permission);
        for (android.content.pm.ActivityInfo entry : info.activities) assertFalse(entry.name.endsWith(".ActivityPro"));
        openSettings();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.setting_stats_category))), 5000));
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean oldInstall = prefs.getBoolean("install", false);
        boolean oldStats = prefs.getBoolean("show_stats", false);
        String oldTheme = prefs.getString("theme", "teal");
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    prefs.edit().putBoolean("install", true).putBoolean("show_stats", true).putString("theme", "blue").apply());
            device.waitForIdle();
            assertTrue(prefs.getBoolean("install", false));
            assertTrue(prefs.getBoolean("show_stats", false));
            assertEquals("blue", prefs.getString("theme", ""));
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                    new Intent(context, ActivityLog.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertFalse(activity.isFinishing());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    prefs.edit().putBoolean("install", oldInstall).putBoolean("show_stats", oldStats).putString("theme", oldTheme).apply());
        }
    }

    // Test: Backup requests update their timestamp, automatic requests are optional, and phone backup settings open.
    // Why: Android controls backup completion; the UI must report a request rather than claim an upload succeeded.
    @Test public void backupControlsRequestWithoutClaimingCompletion() throws Exception {
        SharedPreferences controls = SettingsBackup.controls(context);
        boolean hadAuto = controls.contains("automatic");
        boolean oldAuto = controls.getBoolean("automatic", false);
        boolean hadLast = controls.contains("last_requested");
        long oldLast = controls.getLong("last_requested", 0);
        try {
            controls.edit().remove("automatic").remove("last_requested").commit();
            openSettings();
            device.wait(Until.findObject(By.text(context.getString(R.string.settings_backup_title))), 5000).click();
            clickPreference(R.string.settings_backup_request);
            assertTrue(controls.getLong("last_requested", 0) > 0);
            assertFalse(controls.getBoolean("automatic", false));
            clickPreference(R.string.settings_backup_auto);
            assertTrue(controls.getBoolean("automatic", false));
            clickPreference(R.string.settings_backup_auto);
            assertFalse(controls.getBoolean("automatic", true));
            clickPreference(R.string.settings_backup_open);
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.gms")), 5000));
            device.pressBack();
        } finally {
            SharedPreferences.Editor edit = controls.edit();
            if (hadAuto) edit.putBoolean("automatic", oldAuto); else edit.remove("automatic");
            if (hadLast) edit.putLong("last_requested", oldLast); else edit.remove("last_requested");
            edit.commit();
        }
    }

    private void clickPreference(int title) throws Exception {
        String text = context.getString(title);
        if (device.wait(Until.findObject(By.text(text)), 2000) == null)
            new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(text));
        device.wait(Until.findObject(By.text(text)), 5000).click();
        device.waitForIdle();
    }

    private void openSettings() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivitySettings.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
    }

    private void openDelay() {
        openSettings();
        device.wait(Until.findObject(By.text(context.getString(R.string.setting_options))), 5000).click();
        device.wait(Until.findObject(By.text(context.getString(R.string.rule_delay_title))), 5000).click();
        assertNotNull(find("rule_delay_input"));
    }

    private UiObject2 find(String id) {
        return device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
    }

    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue("Condition did not become true", condition.getAsBoolean());
    }
}
