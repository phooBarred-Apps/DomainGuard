package heartguard.heartguard.main;

import android.content.Context;
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
import static org.junit.Assert.*;

/** Opt-in fixtures for a real Android local-transport backup/restore round trip. */
@RunWith(AndroidJUnit4.class)
public class BackupDeviceTest {
    private Context context;
    private static final String MARKER = "mw_backup_test_marker";
    private static final String RULE = "allow host:mw-backup-test.invalid";

    @Before public void setup() {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("runBackupFixture")));
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    // Test: Prepare preferences and a pending rule for the system backup round trip.
    // Why: Real Android helpers must serialize both settings and rule metadata.
    @Test public void prepareBackup() {
        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).contains(MARKER));
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(MARKER, "round-trip").commit();
        context.getSharedPreferences("lockdown", Context.MODE_PRIVATE).edit().putBoolean(MARKER, true).commit();
        long now = System.currentTimeMillis();
        DatabaseHelper.getInstance(context).addNewRule(RULE, now, now + 86400000, 0, 200, 10);
    }

    // Test: Remove only this test's markers and synthetic rule.
    // Why: A subsequent restore must recover data from the backup, not leftover test values.
    @Test public void removeFixture() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(MARKER).commit();
        context.getSharedPreferences("lockdown", Context.MODE_PRIVATE).edit().remove(MARKER).commit();
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(RULE)) {
            while (cursor.moveToNext()) ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        }
        DatabaseHelper.getInstance(context).removeRulesById(ids.toArray(new Long[0]));
    }

    // Test: Recover both preference files and preserve the pending rule's future activation time.
    // Why: Android's restore lifecycle must produce usable settings without bypassing the delay.
    @Test public void verifyRestored() {
        assertEquals("round-trip", PreferenceManager.getDefaultSharedPreferences(context).getString(MARKER, ""));
        assertTrue(context.getSharedPreferences("lockdown", Context.MODE_PRIVATE).getBoolean(MARKER, false));
        try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(RULE)) {
            assertTrue("Rule was not restored", cursor.moveToFirst());
            assertEquals("Pending rule became active", 0, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")) > System.currentTimeMillis());
        }
        assertFalse("Automatic backup requests must default to off",
                SettingsBackup.controls(context).getBoolean("automatic", false));
    }
}
