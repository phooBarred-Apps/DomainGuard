package heartguard.heartguard.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Opt-in fixtures for a real Android local-transport backup/restore round trip. */
@RunWith(AndroidJUnit4.class)
public class BackupDeviceTest {
    private Context context;
    private static final String MARKER = "mw_backup_test_marker";
    private static final String RULE = "allow host:mw-backup-test.invalid";
    private static final String MISSING = "invalid.domainguard.backup.missing";
    private static final String INSTALLED = "com.android.shell";
    private static final String HOSTS = "127.0.0.1 hosts-backup.invalid\n";

    private static List<String> activeRules() {
        return Arrays.asList("delay 21600", "delay package:" + INSTALLED + " 25200",
                "delay package:" + MISSING + " 14400", "allow package:" + MISSING,
                "allow package:" + MISSING + " host:missing-allow.invalid",
                "block package:" + MISSING + " host:missing-block.invalid",
                "allow package:" + INSTALLED + " host:installed-allow.invalid",
                "block package:" + INSTALLED + " host:installed-block.invalid",
                "allow host:global-allow.invalid", "blockdomain global-block.invalid",
                TemporaryRuleText.text("allow host:staged-backup.invalid", 86400),
                TemporaryRuleText.text("allow package:" + MISSING + " host:temporary-backup.invalid", 86400),
                AppPauseRule.text(MISSING, 86400));
    }

    @Before public void setup() {
        assertEquals("Run using the disposable backup fixture script", "true",
                InstrumentationRegistry.getArguments().getString("runBackupFixture"));
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    // Test: Back up settings, global/per-app rules, delays, timers and an absent app.
    // Why: A few marker values cannot establish that a new phone retains the actual configuration.
    @Test public void prepareBackup() throws Exception {
        assertMissingApp();
        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).contains(MARKER));
        long now = System.currentTimeMillis();
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(MARKER, "round-trip")
                .putLong(MARKER + "_created", now).putFloat(MARKER + "_float", 1.25f)
                .putStringSet(MARKER + "_set", new HashSet<>(Arrays.asList("one", "two")))
                .putBoolean("dark_theme", true).putString("theme", "teal")
                .putBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH, true)
                .putBoolean(CompactDomainLists.ENABLED, true).putInt(CompactDomainLists.ROWS, 7)
                .putInt(DomainFilters.ALLOWED_KEY, DomainFilters.HIDE_GLOBAL)
                .putInt(DomainFilters.BLOCKED_KEY, DomainFilters.HIDE_ALL)
                .putBoolean(DomainFilters.CURRENT_KEY, true)
                .putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, true).commit();
        for (String name : new String[]{"apply", "roaming", "lockdown"})
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putBoolean(MISSING, true).commit();
        for (String text : activeRules()) addRule(text, now, now, 1);
        try (Cursor row = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(
                TemporaryRuleText.text("allow host:staged-backup.invalid", 86400))) {
            assertTrue(row.moveToFirst());
            java.util.Map<String, Integer> delays = new java.util.HashMap<>();
            delays.put(INSTALLED, 25200); delays.put(MISSING, 28800);
            android.content.ContentValues timing = new android.content.ContentValues();
            timing.put("global_waits", GlobalRuleSchedule.capture(now, 21600, delays).encode());
            DatabaseHelper.getInstance(context).updateRuleWithCV(row.getString(row.getColumnIndexOrThrow("_id")), timing);
        }
        addRule(RULE, now, now + 86400000, 0);
        java.util.Map<String, Integer> stages = new java.util.HashMap<>();
        stages.put(INSTALLED, 25200); stages.put(MISSING, 28800);
        String savedStages = GlobalRuleSchedule.capture(now, 21600, stages).encode();
        DatabaseHelper.getInstance(context).addNewRule("- blockdomain global-block.invalid", now, now + 28800000,
                0, 0, 0, savedStages);
        DatabaseHelper.getInstance(context).addNewRule(AppPauseRule.text(null, 86400), now, now + 21600000,
                0, 0, 0, savedStages);
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "hosts.txt"))) {
            output.write(HOSTS.getBytes(StandardCharsets.UTF_8));
        }
        // Backup choice is portable, while the request timestamp belongs to this installation.
        SettingsBackup.controls(context).edit().putBoolean("automatic", automaticBackup()).putLong("last_requested", now).commit();
    }

    // Test: Remove synthetic data after verification.
    // Why: The disposable backup fixture must not leave active delay or pause rules behind.
    @Test public void removeFixture() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(MARKER).commit();
        for (String name : new String[]{"apply", "roaming", "lockdown"})
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(MISSING).commit();
        List<Long> ids = new ArrayList<>();
        List<String> owned = new ArrayList<>(activeRules()); owned.add(RULE);
        owned.add("- blockdomain global-block.invalid"); owned.add(AppPauseRule.text(null, 86400));
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRules()) {
            while (cursor.moveToNext())
                if (owned.contains(cursor.getString(cursor.getColumnIndexOrThrow("ruletext"))))
                    ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        }
        DatabaseHelper.getInstance(context).removeRulesById(ids.toArray(new Long[0]));
    }

    // Test: Recover all supported setting stores and rules into an empty installation, with missing apps.
    // Why: Restore must preserve timer deadlines and app identity without crashing or widening an absent app's rules.
    @Test public void verifyRestored() throws Exception {
        assertMissingApp();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        assertEquals("round-trip", prefs.getString(MARKER, ""));
        long created = prefs.getLong(MARKER + "_created", 0);
        assertTrue(created > 0);
        assertEquals(1.25f, prefs.getFloat(MARKER + "_float", 0), 0);
        assertEquals(new HashSet<>(Arrays.asList("one", "two")), prefs.getStringSet(MARKER + "_set", null));
        assertTrue(prefs.getBoolean("dark_theme", false));
        assertEquals("teal", prefs.getString("theme", ""));
        assertTrue(prefs.getBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH, false));
        assertTrue(prefs.getBoolean(CompactDomainLists.ENABLED, false));
        assertEquals(7, CompactDomainLists.rows(prefs));
        assertEquals(DomainFilters.HIDE_GLOBAL, prefs.getInt(DomainFilters.ALLOWED_KEY, -1));
        assertEquals(DomainFilters.HIDE_ALL, prefs.getInt(DomainFilters.BLOCKED_KEY, -1));
        assertTrue(prefs.getBoolean(DomainFilters.CURRENT_KEY, false));
        assertTrue(prefs.getBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, false));
        for (String name : new String[]{"apply", "roaming", "lockdown"})
            assertTrue(name, context.getSharedPreferences(name, Context.MODE_PRIVATE).getBoolean(MISSING, false));
        assertEquals(HOSTS, new String(java.nio.file.Files.readAllBytes(
                new File(context.getFilesDir(), "hosts.txt").toPath()), StandardCharsets.UTF_8));
        for (String text : activeRules()) {
            try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(text)) {
                assertTrue("Missing saved rule: " + text, cursor.moveToFirst());
                assertEquals(created, cursor.getLong(cursor.getColumnIndexOrThrow("create_time")));
                assertEquals("Timer activation must not restart", created, cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")));
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            }
        }
        try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(RULE)) {
            assertTrue("Rule was not restored", cursor.moveToFirst());
            assertEquals("Pending rule became active", 0, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            assertEquals(created + 86400000, cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")));
        }
        assertTrue("The backup choice must be restored even when off", SettingsBackup.controls(context).contains("automatic"));
        assertEquals(automaticBackup(), SettingsBackup.controls(context).getBoolean("automatic", !automaticBackup()));
        assertNotEquals("A request timestamp from the old installation must not be reused", created,
                SettingsBackup.controls(context).getLong("last_requested", 0));
        RulesManager rules = RulesManager.getInstance(context);
        rules.startTrueTimeRequest(context);
        CountDownLatch ready = new CountDownLatch(1); rules.handler.post(ready::countDown);
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        WhitelistManager.getInstance(context).updateRulesFromRulesManager(context);
        assertEquals(21600, RuleDelay.read(context).seconds);
        assertEquals(25200, RuleDelay.read(context, INSTALLED).seconds);
        assertEquals(14400, RuleDelay.read(context, MISSING).seconds);
        assertTrue(rules.isGlobalDomainBlocked("global-block.invalid"));
        RuleAndPackage missing = (RuleAndPackage) MyRule.getRuleFromText(context,
                "allow package:" + MISSING + " host:missing-allow.invalid");
        assertEquals(RuleAndPackage.UID_NOT_FOUND, missing.getUid());
        assertNotEquals(RuleAndPackage.UID_GLOBAL, missing.getUid());
        RuleAndPackage installed = (RuleAndPackage) MyRule.getRuleFromText(context,
                "allow package:" + INSTALLED + " host:installed-allow.invalid");
        assertEquals(context.getPackageManager().getApplicationInfo(INSTALLED, 0).uid, installed.getUid());
        assertFalse("Missing-app pause must not pause an installed app", rules.isUidPaused(installed.getUid()));
        for (String stagedText : new String[]{"- blockdomain global-block.invalid", AppPauseRule.text(null, 86400)}) {
            try (Cursor row = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(stagedText)) {
                assertTrue("Staged operation restored", row.moveToFirst());
                assertEquals(0, row.getInt(row.getColumnIndexOrThrow("enacted")));
                GlobalRuleSchedule waits = RulesManager.schedule(row);
                assertEquals(created + 21600000, waits.defaultAt());
                assertEquals(created + 25200000, waits.readyAt(new String[]{INSTALLED}));
                assertEquals(created + 28800000, waits.readyAt(new String[]{MISSING}));
            }
        }
        String staged = TemporaryRuleText.text("allow host:staged-backup.invalid", 86400);
        try (Cursor row = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(staged)) {
            assertTrue(row.moveToFirst());
            GlobalRuleSchedule waits = GlobalRuleSchedule.parse(row.getString(row.getColumnIndexOrThrow("global_waits")));
            assertEquals(created + 25200000, waits.readyAt(new String[]{INSTALLED}));
            assertEquals(created + 28800000, waits.readyAt(new String[]{MISSING}));
        }
        assertFalse("A restored staged allow cannot bypass the app's wait", rules.isGlobalAllowReady(context, staged, installed.getUid()));
        assertNull(WhitelistManager.getInstance(context).isAllowed(context, "staged-backup.invalid", installed.getUid()));
        android.app.Activity main = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new android.content.Intent(context, ActivityMain.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(main::finish);
    }

    private void addRule(String text, long created, long enactAt, int enacted) {
        MyRule rule = MyRule.getRuleFromText(context, text);
        assertNotNull(text, rule);
        DatabaseHelper.getInstance(context).addNewRule(text, created, enactAt, enacted,
                rule.getMajorCategory(), rule.getMinorCategory());
    }

    private void assertMissingApp() throws Exception {
        try {
            context.getPackageManager().getApplicationInfo(MISSING, 0);
            fail("Fixture package must not be installed");
        } catch (PackageManager.NameNotFoundException expected) { }
    }

    private boolean automaticBackup() {
        return Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("backupAutomatic", "true"));
    }
}
