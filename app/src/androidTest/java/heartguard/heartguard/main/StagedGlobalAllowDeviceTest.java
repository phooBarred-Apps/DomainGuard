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
        assertEquals(saved.created + 21600000, GlobalRuleSchedule.parse(saved.globalWaits).readyAt(new String[]{APP}));
        assertNotNull(whitelist.isAllowed(context, HOST, context.getApplicationInfo().uid));
        assertNull(whitelist.isAllowed(context, HOST, 2000));
        assertNotNull(whitelist.isPendingAllowed(context, HOST, 2000));
        assertFalse(rules.isGlobalAllowReady(context, text, 2000));
        assertTrue(rules.getRulesToReset(context, 2000, HOST, 1).contains("- " + text));
        int records = countAccess();
        ContentValues values = new ContentValues();
        values.put("global_waits", GlobalRuleSchedule.capture(System.currentTimeMillis() - 21601000,
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
        add(UnknownAppDelay.RULE);
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

    // Test: Global off leaves the longer-delay app protected, then stops fully; cancel restores protection immediately.
    // Why: Removing feature enabled must obey the same per-app staging as a global domain allow.
    @Test public void globalOffStagesAndCancellationRestoresProtection() throws Exception {
        add("delay 600"); add("delay package:" + APP + " 21600"); add("feature enabled");
        add("- feature enabled");
        String off = "- feature enabled";
        RulesBackup.Row saved = row(off);
        assertEquals(21600000, saved.enactAt - saved.created);
        assertEquals(saved.created + 600000, GlobalRuleSchedule.parse(saved.globalWaits).defaultAt());
        assertFalse(rules.isUidPaused(context.getApplicationInfo().uid));
        age(off, 601000); reload();
        assertTrue("VPN still needed for the long app", rules.getPreferenceEnabled(context));
        assertTrue(rules.isUidPaused(context.getApplicationInfo().uid));
        assertFalse(rules.isUidPaused(2000));
        assertTrue(rules.getPreferenceCaptureAllTraffic(context));
        assertTrue("Unknown apps use the default global shutdown wait", rules.isUidPaused(-1));
        add(UnknownAppDelay.RULE);
        assertFalse("Opting in immediately keeps unknown apps protected", rules.isUidPaused(-1));
        GlobalProtectionStatus.Snapshot status = GlobalProtectionStatus.read(context);
        assertNotNull(status); assertTrue(status.starts <= System.currentTimeMillis());
        add("abandon - feature enabled");
        assertFalse(rules.isUidPaused(context.getApplicationInfo().uid));
        assertFalse(rules.isUidPaused(2000));
        assertTrue(rules.getPreferenceEnabled(context));
        add(off); age(off, 21601000); reload();
        assertFalse("Only after the last wait may the VPN stop", rules.getPreferenceEnabled(context));
        assertNull(GlobalProtectionStatus.read(context));
    }

    // Test: A global pause uses one default-delay floor and one shared end; shorter overrides never start it early.
    // Why: The user's 10-minute/1-minute/6-hour example must survive restart and never trigger after expiry.
    @Test public void globalPauseUsesOneWindowAndNoLateActivation() throws Exception {
        String shortApp = context.getPackageName();
        List<RulesBackup.Row> fixture = snapshot();
        long now = System.currentTimeMillis();
        fixture.add(new RulesBackup.Row("delay 600", now, now, 1, MyRule.MAJOR_CATEGORY_DELAY, 100));
        fixture.add(new RulesBackup.Row("delay package:" + shortApp + " 60", now, now, 1, MyRule.MAJOR_CATEGORY_DELAY, 200));
        fixture.add(new RulesBackup.Row("delay package:" + APP + " 21600", now, now, 1, MyRule.MAJOR_CATEGORY_DELAY, 200));
        db.restoreRules(fixture); reload();
        assertEquals(60, rules.previewDelay(context, AppPauseRule.text(shortApp, 14400)));
        String pause = AppPauseRule.text(null, 14400);
        add(pause); age(pause, 61000); reload();
        assertFalse(rules.isUidPaused(context.getApplicationInfo().uid));
        age(pause, 540000); reload();
        assertTrue(rules.isUidPaused(context.getApplicationInfo().uid));
        assertFalse(rules.isUidPaused(2000));
        long end = AppPause.read(context, null).get(0).time;
        assertEquals(row(pause).created + 15000000, end);
        byte[] backup = RulesBackup.encode(snapshot());
        db.restoreRules(RulesBackup.decode(backup)); reload();
        assertEquals(end, AppPause.read(context, null).get(0).time);
        age(pause, 21600000); reload();
        assertFalse(rules.isUidPaused(2000));
        assertFalse(rules.isUidPaused(context.getApplicationInfo().uid));
        assertTrue(AppPause.read(context, null).isEmpty());
        String eight = AppPauseRule.text(null, 28800);
        add(eight); age(eight, 21601000); reload();
        assertTrue(rules.isUidPaused(2000));
        long remaining = AppPause.read(context, null).get(0).time - System.currentTimeMillis();
        assertTrue("Six-hour app gets the remaining shared window", remaining > 7790000 && remaining <= 7800000);
        AppPause.resume(context, null); barrier(); reload();
        assertFalse(rules.isUidPaused(2000));
        assertTrue(AppPause.read(context, null).isEmpty());
    }

    // Test: Resetting a global DNS block and disabling global protection features change only eligible apps; cancellation restores them.
    // Why: Reset, raw commands, icons and enforcement must agree during partial removal without erasing traffic.
    @Test public void globalBlockRemovalUsesDefaultThenAppWaits() throws Exception {
        add("delay 600"); add("delay package:" + APP + " 21600");
        add("blockdomain " + HOST);
        assertEquals(Collections.singletonList("- blockdomain " + HOST), rules.getRulesToReset(context, -1, HOST, 0));
        String removal = "- blockdomain " + HOST;
        assertEquals(600, rules.previewDelay(context, removal));
        add(removal); age(removal, 601000); reload();
        int normal = context.getApplicationInfo().uid;
        assertFalse(rules.isGlobalDomainBlocked(HOST, normal));
        assertTrue(rules.isGlobalDomainBlocked(HOST, 2000));
        assertFalse(rules.isGlobalDomainBlocked(HOST, -1));
        assertDns(false, -1);
        add(UnknownAppDelay.RULE);
        assertTrue(rules.isGlobalDomainBlocked(HOST, -1));
        assertDns(true, -1);
        GlobalRuleIndicators indicators = db.getGlobalRuleIndicators();
        assertEquals(GlobalRuleIndicators.LOCAL, indicators.scope(1, "", HOST, normal));
        assertEquals(GlobalRuleIndicators.BLOCKED, indicators.scope(1, "", HOST, 2000));
        try (Cursor c = db.getRuleMatchingRuletext(removal)) {
            assertTrue(c.moveToFirst()); assertTrue(ActivityRulesList.getDisplayTextFromRuleCursor(c).contains("global removal applied"));
        }
        byte[] backup = RulesBackup.encode(snapshot()); db.restoreRules(RulesBackup.decode(backup)); reload();
        assertFalse(rules.isGlobalDomainBlocked(HOST, normal)); assertTrue(rules.isGlobalDomainBlocked(HOST, 2000));
        add("abandon " + removal); assertTrue(rules.isGlobalDomainBlocked(HOST, normal));
        add("feature lockdown"); add("- feature lockdown");
        age("- feature lockdown", 601000); reload();
        assertFalse(rules.isFeatureEffectiveForUid(FeatureRule.FEATURE_NAME_LOCKDOWN, normal));
        assertTrue(rules.isFeatureEffectiveForUid(FeatureRule.FEATURE_NAME_LOCKDOWN, 2000));
        add("abandon - feature lockdown");
        assertTrue(rules.isFeatureEffectiveForUid(FeatureRule.FEATURE_NAME_LOCKDOWN, normal));
        add("feature manage_system"); add("- feature manage_system");
        age("- feature manage_system", 601000); reload();
        assertFalse(rules.isFeatureEffectiveForUid(FeatureRule.FEATURE_NAME_MANAGE_SYSTEM, normal));
        assertTrue(rules.isFeatureEffectiveForUid(FeatureRule.FEATURE_NAME_MANAGE_SYSTEM, 2000));

    }

    // Test: Every parsed rule family uses the intended global/local delay or existing restriction/reduction policy.
    // Why: The prior release covered only global allows and missed other permissive entry points.
    @Test public void ruleFamilyTimingAudit() throws Exception {
        add("delay 600"); add("delay package:" + APP + " 21600");
        for (String text : new String[]{"allow host:" + HOST, "allow ip:192.0.2.42", "- blockdomain " + HOST,
                "- feature enabled", "- feature manage_system",
                "- feature lockdown", "- feature capture_all_traffic", "- feature no_try", "- feature no_static_ip",
                "feature allow_logging", "dns 192.0.2.53 192.0.2.54"}) assertEquals(text, 600, rules.previewDelay(context, text));
        for (String text : new String[]{"allow package:" + APP, "allow uid:2000", "allow package:" + APP + " host:" + HOST,
                "- block package:" + APP + " host:" + HOST, "- allow package:" + APP + " sticky",
                "- allow uid:2000 sticky", "- allow package:" + APP + " host:" + HOST + " sticky", AppPauseRule.text(APP, 60)})
            assertEquals(text, 21600, rules.previewDelay(context, text));
        for (String text : new String[]{"blockdomain " + HOST, "feature enabled",
                "feature manage_system", "feature lockdown", "- allow host:" + HOST, "- " + AppPauseRule.text(null, 60)})
            assertEquals(text, 0, rules.previewDelay(context, text));
        assertEquals(7200, rules.previewDelay(context, "delay package:" + APP + " 14400"));
    }

    // Test: Unknown traffic follows the global wait by default; opting in immediately tightens all active staged operations.
    // Why: A five-hour pause and a one-second global reset must not inherit another app's six hours without consent.
    @Test public void unknownDelayPolicyAppliesToExistingGlobalChanges() throws Exception {
        add("delay 1"); add("delay package:" + APP + " 21600");
        assertFalse(rules.useStrictUnknownDelay());
        String allow = "allow host:" + HOST;
        add(allow); awaitActive(allow);
        assertTrue(rules.isGlobalAllowReady(context, allow, -1));
        assertTrue(rules.isGlobalAllowReady(context, allow, 98765));
        assertTrue(rules.isGlobalAllowReady(context, allow, 987654));
        assertNotNull(whitelist.isAllowed(context, HOST, -1));
        assertFalse(rules.isGlobalAllowReady(context, allow, 2000));
        String pause = AppPauseRule.text(null, 18000);
        add(pause); awaitActive(pause);
        long end = AppPause.read(context, null).get(0).time;
        assertTrue(rules.isUidPaused(-1));
        assertTrue(rules.isUidPaused(context.getApplicationInfo().uid));
        assertFalse(rules.isUidPaused(2000));
        assertEquals(0, rules.previewDelay(context, UnknownAppDelay.RULE));
        add(UnknownAppDelay.RULE);
        assertTrue(rules.useStrictUnknownDelay());
        assertFalse(rules.isUidPaused(-1));
        assertTrue(rules.isUidPaused(context.getApplicationInfo().uid));
        assertFalse(rules.isGlobalAllowReady(context, allow, -1));
        assertFalse(rules.isGlobalAllowReady(context, allow, 98765));
        assertFalse(rules.isGlobalAllowReady(context, allow, 987654));
        assertNull(whitelist.isAllowed(context, HOST, -1));
        assertEquals(end, AppPause.read(context, null).get(0).time);
        int records = countAccess();
        age(pause, 21601000); reload();
        assertFalse("No late pause after its shared end", rules.isUidPaused(-1));
        assertEquals(records, countAccess());
    }

    // Test: Turning strict unknown delay off waits the longest configured delay, survives restore, and can be canceled.
    // Why: A Settings or raw-rule removal must not silently bypass the user-enabled restriction or restart its timer.
    @Test public void strictUnknownTurnOffWaitsAndSurvivesRestore() throws Exception {
        add("delay 1"); add("delay package:" + APP + " 21600"); add(UnknownAppDelay.RULE);
        assertEquals(21600, rules.previewDelay(context, UnknownAppDelay.REMOVE));
        add(UnknownAppDelay.REMOVE);
        assertEquals(21600000, row(UnknownAppDelay.REMOVE).enactAt - row(UnknownAppDelay.REMOVE).created);
        assertNull("This is one protected setting change, not a staged global removal", row(UnknownAppDelay.REMOVE).globalWaits);
        assertTrue(rules.useStrictUnknownDelay());
        add("abandon " + UnknownAppDelay.REMOVE);
        assertEquals(0, UnknownAppDelay.pending(context));
        assertTrue(rules.useStrictUnknownDelay());
        add(UnknownAppDelay.REMOVE);
        long deadline = row(UnknownAppDelay.REMOVE).enactAt;
        byte[] backup = RulesBackup.encode(snapshot());
        db.restoreRules(Collections.emptyList()); reload();
        assertFalse(rules.useStrictUnknownDelay());
        db.restoreRules(RulesBackup.decode(backup)); reload();
        assertTrue(rules.useStrictUnknownDelay());
        assertEquals(deadline, UnknownAppDelay.pending(context));
        ContentValues due = new ContentValues(); due.put("enact_time", System.currentTimeMillis() - 1);
        update(UnknownAppDelay.REMOVE, due); reload();
        assertFalse(rules.useStrictUnknownDelay());
        assertEquals(0, UnknownAppDelay.pending(context));
    }

    private void age(String text, long milliseconds) {
        RulesBackup.Row saved = row(text);
        org.json.JSONObject json;
        try {
            json = new org.json.JSONObject(saved.globalWaits);
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) { String key = keys.next(); json.put(key, json.getLong(key) - milliseconds); }
        } catch (org.json.JSONException error) { throw new AssertionError(error); }
        ContentValues values = new ContentValues();
        values.put("global_waits", json.toString()); values.put("enact_time", saved.enactAt - milliseconds);
        values.put("create_time", saved.created - milliseconds); update(text, values);
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

    private void assertDns(boolean blocked, int uid) throws Exception {
        HeartGuardDeviceTest.TestService[] service = new HeartGuardDeviceTest.TestService[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            service[0] = new HeartGuardDeviceTest.TestService(); service[0].attach(context);
        });
        java.lang.reflect.Method check = ServiceSinkhole.class.getDeclaredMethod("isDnsBlocked", String.class, int.class, boolean.class);
        check.setAccessible(true);
        assertEquals(blocked, check.invoke(service[0], HOST, uid, false));
    }
}
