package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.os.Build;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NewAppDelayDeviceTest {
    private static final String BROWSER = "test.domainguard.newbrowser";
    private static final String POLICY = NewAppDelay.PREFIX + "21600";
    private Context context;
    private RulesManager rules;
    private DatabaseHelper db;
    private SharedPreferences prefs;
    private List<RulesBackup.Row> original;
    private Set<String> known;
    private Activity activity;
    private boolean darkTheme;

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        rules = RulesManager.getInstance(context); db = DatabaseHelper.getInstance(context);
        barrier();
        assertEquals(0, rules.getDefaultDelay());
        assertFalse(rules.getPreferenceEnabled(context));
        original = snapshot();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        darkTheme = prefs.getBoolean("dark_theme", false);
        known = new HashSet<>(prefs.getStringSet(NewAppDelay.KNOWN_PACKAGES, new HashSet<>()));
    }

    @After public void cleanup() throws Exception {
        if (activity != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        barrier();
        if (original != null) {
            db.restoreRules(original);
            prefs.edit().putStringSet(NewAppDelay.KNOWN_PACKAGES, known).commit();
            prefs.edit().putBoolean("dark_theme", darkTheme).commit();
            reload();
        }
    }

    // Test: Existing apps keep their configuration; a new install gets six hours and retains later explicit choices.
    // Why: This default is for new apps, not a retroactive change or a reinstall that restarts/overwrites an app's delay.
    @Test public void assignsOnlyNewAppsAndPreservesOverrides() throws Exception {
        add("delay 1"); add("delay package:com.android.shell 3600"); add(POLICY);
        assertEquals(3600, rules.getPauseDelay("com.android.shell"));
        assertFalse(rules.hasCustomDelay(context.getPackageName()));
        long now = System.currentTimeMillis() + 100;
        reconcile(app(BROWSER, now), now);
        assertEquals(21600, rules.getPauseDelay(BROWSER));
        assertEquals(21600, rules.previewDelay(context, "allow package:" + BROWSER));
        assertEquals(1, rules.getDefaultDelay());
        long created = row("delay package:" + BROWSER + " 21600").created;
        reconcile(app(BROWSER, now + 5000), now + 5000);
        assertEquals(created, row("delay package:" + BROWSER + " 21600").created);
        add("delay package:" + BROWSER + " 25200");
        reconcile(app(BROWSER, now + 10000), now + 10000);
        assertEquals(25200, rules.getPauseDelay(BROWSER));
        assertEquals(0, rules.previewDelay(context, "block package:" + BROWSER + " host:example.org"));
    }

    // Test: Off and reductions are protected through the raw-rule engine; cancellation preserves the active policy.
    // Why: A plain preference or a UI-only restriction would allow disabling the default immediately before an install.
    @Test public void protectsTemplateChangesAndCancellation() throws Exception {
        add(POLICY);
        add(NewAppDelay.PREFIX + "14400");
        RulesBackup.Row reduction = row(NewAppDelay.PREFIX + "14400");
        assertEquals(7200000, reduction.enactAt - reduction.created);
        assertEquals(21600, NewAppDelay.read(context).seconds);
        add("abandon " + reduction.text);
        assertTrue(NewAppDelay.read(context).pending.isEmpty());
        add("- " + POLICY);
        RulesBackup.Row off = row("- " + POLICY);
        assertEquals(21600000, off.enactAt - off.created);
        assertNull(off.globalWaits);
        long now = System.currentTimeMillis() + 100;
        reconcile(app(BROWSER, now), now);
        assertEquals(21600, rules.getPauseDelay(BROWSER));
        byte[] backup = RulesBackup.encode(snapshot());
        db.restoreRules(RulesBackup.decode(backup)); reload();
        assertEquals(off.enactAt, row("- " + POLICY).enactAt);
        assertEquals(21600, rules.getPauseDelay(BROWSER));
        add("abandon - " + POLICY);
        assertTrue(NewAppDelay.read(context).pending.isEmpty());
        assertNull(MyRule.getRuleFromText(context, "newappdelay 604801"));
        assertNull(MyRule.getRuleFromText(context, "newappdelay -1"));
    }

    // Test: Missed installs use the policy at installation when an off deadline passed while DomainGuard was stopped.
    // Why: Startup catch-up must neither miss an install during the wait nor assign an obsolete policy after it ended.
    @Test public void missedInstallsRespectPendingOffDeadline() throws Exception {
        long now = System.currentTimeMillis();
        onWorker(() -> {
            db.addNewRule(POLICY, now - 86400000, now - 86400000, 1, 100, 150);
            db.addNewRule("- " + POLICY, now - 25200000, now - 3600000, 0, 0, 0);
            NewAppDelay.reconcile(context, Arrays.asList(app(BROWSER, now - 3601000),
                    app(BROWSER + ".after", now - 3599000)), now);
        });
        reload();
        assertEquals(21600, rules.getPauseDelay(BROWSER));
        assertFalse(rules.hasCustomDelay(BROWSER + ".after"));
        assertFalse(NewAppDelay.read(context).overridden);
    }

    // Test: Pre-entering a zero delay for a not-yet-installed browser waits six hours and does not prevent enrollment.
    // Why: Raw rule editing must not create a shortcut around the new-install policy.
    @Test public void pendingOverrideCannotBypassEnrollment() throws Exception {
        add(POLICY);
        add("delay package:" + BROWSER + " 0");
        RulesBackup.Row pending = row("delay package:" + BROWSER + " 0");
        assertEquals(21600000, pending.enactAt - pending.created);
        long now = System.currentTimeMillis() + 100;
        reconcile(app(BROWSER, now), now);
        assertEquals(21600, rules.getPauseDelay(BROWSER));
        assertEquals(pending.enactAt, row(pending.text).enactAt);
    }

    // Test: Use the actual dialog to enable six hours, request Off and cancel the pending change.
    // Why: The active setting, pending wait and controls must stay usable on a short screen with enlarged text.
    @Test public void dialogEnablesAndExplainsPendingOff() throws Exception {
        prefs.edit().putBoolean("dark_theme", "true".equals(InstrumentationRegistry.getArguments().getString("newAppDark"))).commit();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivitySettings.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        show();
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.new_app_delay_title))), 5000));
        assertFalse(device.findObject(By.res(context.getPackageName(), "rule_delay_inherit")).isChecked());
        device.findObject(By.res(context.getPackageName(), "rule_delay_inherit")).click();
        device.waitForIdle();
        if (!device.hasObject(By.res(context.getPackageName(), "rule_delay_input")))
            new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector().scrollable(true))
                    .scrollIntoView(new androidx.test.uiautomator.UiSelector().resourceId(context.getPackageName() + ":id/rule_delay_input"));
        assertEquals("6", device.findObject(By.res(context.getPackageName(), "rule_delay_input")).getText());
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "new-app-delay-enabled.png"));
        device.findObject(By.res("android", "button1")).click();
        await(() -> NewAppDelay.read(context).overridden);
        assertEquals(21600, NewAppDelay.read(context).seconds);
        show();
        device.wait(Until.findObject(By.res(context.getPackageName(), "rule_delay_inherit")), 5000).click();
        assertTrue(device.wait(Until.hasObject(By.textContains("Turning this off takes effect after 6 hours")), 5000));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "new-app-delay-off-wait.png"));
        device.findObject(By.res("android", "button1")).click();
        await(() -> !NewAppDelay.read(context).pending.isEmpty());
        assertEquals(1, NewAppDelay.read(context).pending.size());
        show();
        if (device.wait(Until.findObject(By.res(context.getPackageName(), "rule_delay_cancel_pending")), 5000) == null)
            new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector().scrollable(true))
                    .scrollIntoView(new androidx.test.uiautomator.UiSelector().resourceId(context.getPackageName() + ":id/rule_delay_cancel_pending"));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "new-app-delay-pending.png"));
        assertTrue(device.findObject(By.res(context.getPackageName(), "rule_delay_inherit")).isChecked());
        device.wait(Until.findObject(By.res(context.getPackageName(), "rule_delay_cancel_pending")), 5000).click();
        await(() -> NewAppDelay.read(context).pending.isEmpty());
        assertTrue(NewAppDelay.read(context).pending.isEmpty());
        assertTrue(NewAppDelay.read(context).overridden);
    }

    private void show() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> RuleDelayDialog.forNewApps()
                .show(((AppCompatActivity) activity).getSupportFragmentManager(), NewAppDelay.KEY));
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle();
    }
    private void await(java.util.concurrent.Callable<Boolean> ready) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 10000;
        while (!ready.call() && android.os.SystemClock.elapsedRealtime() < end) android.os.SystemClock.sleep(50);
        assertTrue(ready.call());
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle();
        barrier();
    }
    private static PackageInfo app(String name, long time) {
        PackageInfo app = new PackageInfo(); app.packageName = name; app.firstInstallTime = time; return app;
    }
    private void reconcile(PackageInfo app, long now) throws Exception {
        onWorker(() -> NewAppDelay.reconcile(context, Arrays.asList(app), now)); reload();
    }
    private void add(String text) throws Exception { rules.queueRuleText(context, text); barrier(); }
    private void reload() throws Exception { rules.reloadAfterRestore(context); barrier(); barrier(); }
    private void barrier() throws Exception { onWorker(() -> {}); }
    private void onWorker(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        rules.handler.post(() -> { try { task.run(); } catch (Throwable error) { failure.set(error); } finally { done.countDown(); } });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private List<RulesBackup.Row> snapshot() {
        List<RulesBackup.Row> rows = new ArrayList<>();
        try (Cursor c = db.getAllRules()) {
            while (c.moveToNext()) rows.add(new RulesBackup.Row(
                    c.getString(c.getColumnIndexOrThrow("ruletext")), c.getLong(c.getColumnIndexOrThrow("create_time")),
                    c.getLong(c.getColumnIndexOrThrow("enact_time")), c.getInt(c.getColumnIndexOrThrow("enacted")),
                    c.getInt(c.getColumnIndexOrThrow("major_category")), c.getInt(c.getColumnIndexOrThrow("minor_category")),
                    c.getString(c.getColumnIndexOrThrow("global_waits"))));
        }
        return rows;
    }
    private RulesBackup.Row row(String text) {
        for (RulesBackup.Row row : snapshot()) if (row.text.equals(text)) return row;
        throw new AssertionError("Missing rule " + text);
    }
}
