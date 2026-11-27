package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
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
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RulesRecoveryDeviceTest {
    private Context context;
    private DatabaseHelper db;
    private RulesManager rules;
    private List<RulesBackup.Row> original;
    private Activity activity;
    private File seeded, preserved;

    @Before public void setup() throws Exception {
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context); rules = RulesManager.getInstance(context);
        barrier(); original = snapshot(); db.restoreRules(Collections.emptyList());
    }

    @After public void cleanup() throws Exception {
        if (activity != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        barrier();
        if (original != null) { db.restoreRules(original); rules.reloadAfterRestore(context); barrier(); }
        if (seeded != null && seeded.exists()) assertTrue(seeded.delete());
        if (preserved != null && preserved.exists()) assertTrue(preserved.delete());
    }

    private List<RulesBackup.Row> saved() {
        long now = System.currentTimeMillis();
        return Arrays.asList(new RulesBackup.Row("delay 21600", now, now, 1, 0, 0),
                new RulesBackup.Row("allow host:local-recovery.invalid", now, now, 1, 0, 0),
                new RulesBackup.Row("allow package:missing.recovery.app host:pending.invalid", now, now + 21600000, 0, 0, 0));
    }

    // Test: Use the real recovery screen to restore saved rules into an empty database.
    // Why: Confirms the user's exact recovery path, including preserved pending deadlines and an absent app.
    @Test public void recoverFromScreenPreservesRulesAndDeadlines() throws Exception {
        File input = new File(context.getFilesDir(), "backup/rules.json");
        File copy = new File(context.getNoBackupFilesDir(), "rules-recovery/rules.json");
        assertFalse("Dedicated fixture must have no existing snapshot", input.exists());
        assertFalse(copy.exists());
        seeded = input; preserved = copy;
        assertTrue(input.getParentFile().isDirectory() || input.getParentFile().mkdirs());
        List<RulesBackup.Row> rows = saved(); byte[] bytes = RulesBackup.encode(rows);
        try (FileOutputStream output = new FileOutputStream(input)) { output.write(bytes); }
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityDeviceBackup.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wait(Until.findObject(By.text(context.getString(R.string.backup_recover))), 5000).click();
        device.waitForIdle();
        device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "recovery-ui.xml"));
        assertNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?s)3 saved rules.*"))), 5000));
        device.findObject(By.text(context.getString(R.string.backup_recover_restore))).click();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.backup_recover_success))), 10000));
        barrier();
        List<RulesBackup.Row> restored = snapshot(); assertEquals(3, restored.size());
        assertEquals(rows.get(2).enactAt, restored.get(2).enactAt);
        assertEquals(0, restored.get(2).enacted);
        assertEquals(21600, rules.getDefaultDelay());
        assertTrue(input.isFile()); assertTrue(copy.isFile());
        assertArrayEquals(bytes, RulesRecovery.of(context).available().get(0).bytes);
    }

    // Test: Recovery cannot replace restrictions added after the confirmation was opened.
    // Why: Restoring a stale snapshot must not bypass the user's existing delay policy.
    @Test public void existingRulesBlockReplacementAtomically() throws Exception {
        List<RulesBackup.Row> rows = saved();
        db.restoreRules(Collections.singletonList(rows.get(0)));
        try { db.restoreRules(rows, true); fail("Existing restriction was replaced"); }
        catch (IllegalStateException expected) { assertEquals(1, snapshot().size()); }
        assertEquals("delay 21600", snapshot().get(0).text);
    }

    // Test: Re-enabling protection after loss still allows recovery of the original rules.
    // Why: The user already toggled DomainGuard back on before discovering the empty list.
    @Test public void enabledOnlyListCanRecover() throws Exception {
        db.restoreRules(Collections.singletonList(new RulesBackup.Row("feature enabled", 1, 1, 1, 0, 0)));
        db.restoreRules(saved(), true);
        assertEquals(3, snapshot().size());
    }

    // Test: An Android backup callback with no saved rules aborts before writing a replacement.
    // Why: A queued backup after loss must not upload the empty state over the user's cloud copy.
    @Test public void emptyBackupCallbackIsHeld() throws Exception {
        class Agent extends SettingsBackupAgent { Agent() { attachBaseContext(context); } }
        try { new Agent().onBackup(null, null, null); fail("Empty backup was not held"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("No saved rules")); }
        assertTrue(snapshot().isEmpty());
    }

    private void barrier() throws Exception {
        CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    private List<RulesBackup.Row> snapshot() {
        List<RulesBackup.Row> result = new ArrayList<>();
        try (Cursor c = db.getAllRules()) {
            while (c.moveToNext()) result.add(new RulesBackup.Row(c.getString(c.getColumnIndexOrThrow("ruletext")),
                    c.getLong(c.getColumnIndexOrThrow("create_time")), c.getLong(c.getColumnIndexOrThrow("enact_time")),
                    c.getInt(c.getColumnIndexOrThrow("enacted")), c.getInt(c.getColumnIndexOrThrow("major_category")),
                    c.getInt(c.getColumnIndexOrThrow("minor_category")), c.getString(c.getColumnIndexOrThrow("global_waits"))));
        }
        return result;
    }
}
