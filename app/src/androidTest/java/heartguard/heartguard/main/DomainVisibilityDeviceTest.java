package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DomainVisibilityDeviceTest {
    private static final int FIRST_UID = 99191;
    private static final int SECOND_UID = 99192;
    private static final String KEY = Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS;
    private Context context;
    private DatabaseHelper db;
    private SharedPreferences prefs;
    private boolean hadPreference, oldPreference;
    private Activity activity;
    private RecyclerView list;
    private AdapterRule adapter;
    private List<Rule> apps;

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        hadPreference = prefs.contains(KEY);
        oldPreference = prefs.getBoolean(KEY, false);
        for (int uid : new int[]{FIRST_UID, SECOND_UID}) {
            try (Cursor cursor = db.getAccess(uid)) {
                assertEquals("Synthetic test UID already has traffic", 0, cursor.getCount());
            }
        }
    }

    @After public void cleanup() {
        if (activity != null) onMain(() -> activity.finish());
        db.getWritableDatabase().delete("access",
                "uid IN (?, ?) AND daddr LIKE ?", new String[]{
                        Integer.toString(FIRST_UID), Integer.toString(SECOND_UID), "%.mw-visibility-test.invalid"});
        onMain(() -> {
            SharedPreferences.Editor edit = prefs.edit();
            if (hadPreference) edit.putBoolean(KEY, oldPreference); else edit.remove(KEY);
            edit.commit();
        });
    }

    // Test: Hide both permitted traffic and active allows, retain blocked/pending/unknown rows, newest first.
    // Why: Filtering after LIMIT 250 would hide older blocked domains when allowed traffic fills the list.
    @Test public void filteringKeepsBlockedAndPendingRowsBeforeApplyingLimit() {
        insert(FIRST_UID, "blocked", 100, 0, 1, 0);
        insert(FIRST_UID, "pending", 200, 0, 1, 1);
        insert(FIRST_UID, "unknown", 300, -1, -1, 0);
        insert(FIRST_UID, "active-allow", 400, -1, 0, 0);
        for (int i = 0; i < 260; i++)
            insert(FIRST_UID, "permitted-" + i, 500 + i, 1, -1, 0);
        try (Cursor cursor = db.getAccess(FIRST_UID, true)) {
            assertEquals(3, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals("unknown.mw-visibility-test.invalid", cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            assertTrue(cursor.moveToNext());
            assertEquals("pending.mw-visibility-test.invalid", cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            assertTrue(cursor.moveToNext());
            assertEquals("blocked.mw-visibility-test.invalid", cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
        }
        try (Cursor cursor = db.getAccess(FIRST_UID)) {
            assertEquals("Unfiltered callers must retain their existing limit", 250, cursor.getCount());
        }
    }

    // Test: Each app's single toggle overrides the saved default for this adapter session only.
    // Why: RecyclerView recycling and traffic refreshes must not reset overrides or apply them to another app.
    @Test public void singleToggleIsIndependentSurvivesRefreshAndResetsNextSession() {
        for (int uid : new int[]{FIRST_UID, SECOND_UID}) {
            insert(uid, "allowed", 100, 1, 0, 0);
            insert(uid, "blocked", 200, 0, 1, 0);
        }
        onMain(() -> prefs.edit().putBoolean(KEY, true).commit());
        apps = new ArrayList<>(Rule.getRules(true, context).subList(0, 2));
        apps.get(0).uid = FIRST_UID;
        apps.get(1).uid = SECOND_UID;
        for (Rule app : apps) app.expanded = true;
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityGlobalWhitelist.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            list = new RecyclerView(activity);
            list.setLayoutManager(new LinearLayoutManager(activity));
            list.setItemAnimator(null);
            activity.setContentView(list);
            newSession();
        });
        assertRow(0, true, 1);
        onMain(() -> holder(0).cbHideAllowedDomains.performClick());
        assertRow(0, false, 2);
        assertRow(1, true, 1);
        onMain(() -> adapter.set(new ArrayList<>(apps)));
        assertRow(0, false, 2);
        assertTrue("App toggle must not change global preference", prefs.getBoolean(KEY, false));
        onMain(this::newSession);
        assertRow(0, true, 1);
        onMain(() -> {
            prefs.edit().putBoolean(KEY, false).commit();
            adapter.notifyDataSetChanged();
        });
        assertRow(0, false, 2);
        onMain(() -> holder(0).cbHideAllowedDomains.performClick());
        assertRow(0, true, 1);
        assertRow(1, false, 2);
        onMain(this::newSession);
        assertRow(0, false, 2);
        assertFalse(prefs.getBoolean(KEY, true));
    }

    private void newSession() {
        adapter = new AdapterRule(activity, list);
        list.setAdapter(adapter);
        android.os.Bundle state = new android.os.Bundle();
        state.putString("sort", ListSort.UID);
        java.util.ArrayList<String> expanded = new java.util.ArrayList<>();
        for (Rule app : apps) expanded.add(app.packageName);
        state.putStringArrayList("expanded", expanded);
        adapter.restoreViewState(state);
        adapter.set(new ArrayList<>(apps));
    }

    private AdapterRule.ViewHolder holder(int position) {
        return (AdapterRule.ViewHolder) list.findViewHolderForAdapterPosition(position);
    }

    private void assertRow(int position, boolean hidden, int count) {
        onMain(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, 0));
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean[] matches = {false};
        do {
            onMain(() -> {
                AdapterRule.ViewHolder row = holder(position);
                matches[0] = row != null && row.cbHideAllowedDomains.isChecked() == hidden
                        && row.lvAccess.getAdapter() != null && row.lvAccess.getAdapter().getItemCount() == count;
            });
            if (!matches[0]) SystemClock.sleep(50);
        } while (!matches[0] && SystemClock.elapsedRealtime() < deadline);
        assertTrue("App " + position + " should have hidden=" + hidden + " and " + count + " visible domains", matches[0]);
    }

    private void insert(int uid, String name, long time, int allowed, int block, int pending) {
        ContentValues row = new ContentValues();
        row.put("uid", uid);
        row.put("version", 4);
        row.put("protocol", 6);
        row.put("daddr", name + ".mw-visibility-test.invalid");
        row.put("dport", 443);
        row.put("time", time);
        row.put("allowed", allowed);
        row.put("block", block);
        row.put("pending_allow", pending);
        db.getWritableDatabase().insertOrThrow("access", null, row);
    }

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
