package heartguard.heartguard.main;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StagedGlobalAllowDeviceTest {
    private static final String APP = "com.android.shell";
    private static final String HOST = "staged-allow.invalid";
    private Context context;
    private DatabaseHelper db;
    private RulesManager rules;
    private WhitelistManager whitelist;
    private List<RulesBackup.Row> original;

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context); rules = RulesManager.getInstance(context);
        whitelist = WhitelistManager.getInstance(context);
        assertEquals(0, rules.getDefaultDelay());
        assertFalse(rules.hasCustomDelay(APP));
        assertFalse(rules.getPreferenceEnabled(context));
        original = snapshot();
    }

    @After public void cleanup() throws Exception {
        if (original != null) { db.restoreRules(original); reload(); }
    }

    // Test: A global allow starts after one second while the six-hour app remains pending, then matures without another rule.
    // Why: Independent eligibility must be enforced by the real rule engine, without clearing traffic or restarting a wait.
    @Test public void globalActivationKeepsLongerAppWaiting() throws Exception {
        add("delay 1"); add("delay package:" + APP + " 21600");
        String text = "allow host:" + HOST;
        assertEquals(1, rules.previewDelay(context, text));
        add(text); awaitActive(text);
        RulesBackup.Row saved = row(text);
        assertEquals(saved.created + 21600000, GlobalAllowSchedule.parse(saved.globalWaits).readyAt(new String[]{APP}));
        assertNotNull(whitelist.isAllowed(context, HOST, context.getApplicationInfo().uid));
        assertNull(whitelist.isAllowed(context, HOST, 2000));
        assertNotNull(whitelist.isPendingAllowed(context, HOST, 2000));
        assertFalse(rules.isGlobalAllowReady(context, text, 2000));
        assertTrue(rules.getRulesToReset(context, 2000, HOST, 1).contains("- " + text));
        int records = countAccess();
        ContentValues values = new ContentValues();
        values.put("global_waits", GlobalAllowSchedule.capture(System.currentTimeMillis() - 21601000,
                1, Collections.singletonMap(APP, 21600)).encode());
        update(text, values); reload();
        assertNotNull(whitelist.isAllowed(context, HOST, 2000));
        assertNull(row(text).globalWaits);
        assertEquals(records, countAccess());
        assertEquals(saved.enactAt, row(text).enactAt);
        add("block package:" + APP + " host:" + HOST);
        assertEquals(0, whitelist.isAllowed(context, HOST, 2000).allowed);
    }

    // Test: Temporary global expiry is shared; a four-hour window excludes a six-hour app, an eight-hour window leaves two hours.
    // Why: Late apps must never receive a restarted full duration or a false pending allowance that can never activate.
    @Test public void sharedExpiryAndAppSpecificFullDuration() throws Exception {
        add("delay 1"); add("delay package:" + APP + " 21600");
        String four = TemporaryRuleText.text("allow host:" + HOST, 14400);
        add(four); awaitActive(four);
        RuleAndPackage global = parsed(four);
        assertNull(whitelist.isAllowed(context, HOST, 2000));
        assertNull(whitelist.isPendingAllowed(context, HOST, 2000));
        assertFalse(global.appliesNow(context, 2000, global.readyAt(context, 2000)));
        String eight = TemporaryRuleText.text("allow host:long." + HOST, 28800);
        add(eight); awaitActive(eight);
        global = parsed(eight);
        long ready = global.readyAt(context, 2000);
        assertFalse(global.appliesNow(context, 2000, ready - 1));
        assertTrue(global.appliesNow(context, 2000, ready));
        assertTrue(global.expiresAt() - ready >= 7200000 && global.expiresAt() - ready < 7210000);
        assertFalse(global.appliesNow(context, 2000, global.expiresAt()));
        String local = TemporaryRuleText.text("allow package:" + APP + " host:local." + HOST, 14400);
        add(local);
        assertEquals(21600000, row(local).enactAt - row(local).created);
        ContentValues due = new ContentValues(); due.put("enact_time", System.currentTimeMillis() - 1);
        update(local, due);
        rules.startTrueTimeRequest(context); barrier();
        assertEquals(1, row(local).enacted);
        assertEquals(14400000, parsed(local).expiresAt() - row(local).enactAt);
        assertNotNull(whitelist.isAllowed(context, "local." + HOST, 2000));
    }

    // Test: Restoring absolute deadlines by package preserves waits, missing apps and a single expiry; old rules keep old timing.
    // Why: Restarts/restores and legacy upgrades must neither grant early access nor silently lengthen existing timers.
    @Test public void restoreAndLegacyRulesKeepTheirTiming() throws Exception {
        add("delay 1"); add("delay package:" + APP + " 21600");
        add("delay package:missing.staged.app 25200");
        String text = TemporaryRuleText.text("allow host:" + HOST, 28800);
        add(text); awaitActive(text);
        RulesBackup.Row saved = row(text);
        byte[] backup = RulesBackup.encode(snapshot());
        db.restoreRules(Collections.emptyList()); reload();
        db.restoreRules(RulesBackup.decode(backup)); reload();
        assertEquals(saved.globalWaits, row(text).globalWaits);
        assertEquals(saved.enactAt, row(text).enactAt);
        assertNull(whitelist.isAllowed(context, HOST, 2000));
        assertFalse(rules.isGlobalAllowReady(context, text, 2000));
        assertNull(whitelist.isAllowed(context, HOST, -1));
        assertNotNull(whitelist.isAllowed(context, HOST, context.getApplicationInfo().uid));
        assertEquals(saved.created + 25200000, parsed(text).readyAt(context, -1));
        String legacy = "allow host:legacy." + HOST;
        List<RulesBackup.Row> rows = snapshot();
        rows.add(new RulesBackup.Row(legacy, saved.created, saved.enactAt, 1, MyRule.MAJOR_CATEGORY_ALLOW, 0));
        String pending = "allow host:pending." + HOST;
        long deadline = System.currentTimeMillis() + 21600000;
        rows.add(new RulesBackup.Row(pending, saved.created, deadline, 0, MyRule.MAJOR_CATEGORY_ALLOW, 0));
        db.restoreRules(rows); reload();
        assertNotNull(whitelist.isAllowed(context, "legacy." + HOST, 2000));
        assertNull(whitelist.isAllowed(context, "pending." + HOST, 2000));
        assertEquals(deadline, row(pending).enactAt);
        ContentValues invalid = new ContentValues(); invalid.put("global_waits", "corrupt");
        update(text, invalid); reload();
        assertFalse("Corrupt saved timing fails closed", rules.isGlobalAllowReady(context, text, 2000));
    }

    private void add(String text) throws Exception { rules.queueRuleText(context, text); barrier(); }
    private void barrier() throws Exception {
        CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }
    private void reload() throws Exception { rules.reloadAfterRestore(context); barrier(); barrier(); }
    private void awaitActive(String text) throws Exception {
        long end = SystemClock.elapsedRealtime() + 7000;
        while (row(text).enacted == 0 && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(50); rules.startTrueTimeRequest(context); barrier();
        }
        assertEquals(1, row(text).enacted);
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
    private RuleAndPackage parsed(String text) {
        RuleAndPackage rule = (RuleAndPackage) MyRule.getRuleFromText(context, text);
        try (Cursor c = db.getRuleMatchingRuletext(text)) { assertTrue(c.moveToFirst()); rule.readGlobalTiming(c); }
        return rule;
    }
    private void update(String text, ContentValues values) {
        try (Cursor c = db.getRuleMatchingRuletext(text)) {
            assertTrue(c.moveToFirst()); db.updateRuleWithCV(c.getString(c.getColumnIndexOrThrow("_id")), values);
        }
    }
    private int countAccess() {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM access", null)) {
            assertTrue(c.moveToFirst()); return c.getInt(0);
        }
    }
}
