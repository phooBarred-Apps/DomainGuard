package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ListDisplayDeviceTest {
    private static final int UID = 99193;
    private Context context;
    private DatabaseHelper db;
    private Activity activity;
    private SharedPreferences prefs;
    private String savedSort;

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        try (Cursor cursor = db.getAccess(UID)) { assertEquals(0, cursor.getCount()); }
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        savedSort = prefs.getString(ListSort.APP_DEFAULT, null);
    }
    @After public void cleanup() {
        if (activity != null) onMain(() -> activity.finish());
        db.getWritableDatabase().delete("access", "uid = ? AND daddr LIKE ?", new String[]{"" + UID, "%.mw-list-test.invalid"});
        onMain(() -> {
            SharedPreferences.Editor edit = prefs.edit();
            if (savedSort == null) edit.remove(ListSort.APP_DEFAULT); else edit.putString(ListSort.APP_DEFAULT, savedSort);
            edit.commit();
        });
    }

    // Test: Alphabetical sorting ignores changed/traffic grouping, with independent combined filters.
    // Why: The previous ordering separated blocked apps and promoted them when a domain was recorded.
    @Test public void appSortAndCombinedFiltersUseExplicitChoices() {
        List<Rule> apps = new ArrayList<>(Rule.getRules(true, context).subList(0, 3));
        Rule alpha = apps.get(0), beta = apps.get(1), gamma = apps.get(2);
        alpha.name = "Alpha"; alpha.packageName = "test.alpha"; alpha.uid = 1;
        beta.name = "Beta"; beta.packageName = "test.beta"; beta.uid = 2;
        gamma.name = "Gamma"; gamma.packageName = "test.gamma"; gamma.uid = 3;
        for (Rule app : apps) { app.apply = true; app.enabled = true; app.internet = true; app.system = false; }
        alpha.wifi_blocked = alpha.other_blocked = true; alpha.changed = false;
        beta.wifi_blocked = beta.other_blocked = false; beta.changed = true;
        gamma.wifi_blocked = gamma.other_blocked = true; gamma.system = true;
        Map<Integer, DatabaseHelper.AccessStats> stats = new HashMap<>();
        stats.put(2, new DatabaseHelper.AccessStats(4, 20));
        stats.put(3, new DatabaseHelper.AccessStats(2, 50));
        AppListOptions options = new AppListOptions();
        apps.sort(options.comparator(stats));
        assertEquals(Arrays.asList(alpha, beta, gamma), apps);
        options.sort = ListSort.RECENT; apps.sort(options.comparator(stats));
        assertEquals(Arrays.asList(gamma, beta, alpha), apps);
        options.sort = ListSort.BLOCKED; apps.sort(options.comparator(stats));
        assertEquals(Arrays.asList(alpha, gamma, beta), apps);
        options.access = 2; options.activity = 1; options.type = 2; options.query = "GAM";
        assertTrue(options.matches(gamma, stats.get(3)));
        assertFalse(options.matches(alpha, null)); assertFalse(options.matches(beta, stats.get(2)));
        Bundle saved = options.save(); options.reset(); options.restore(saved);
        assertEquals("GAM", options.query); assertEquals(ListSort.BLOCKED, options.sort);
        assertTrue(options.matches(gamma, stats.get(3)));
    }

    // Test: Domain search is case-insensitive and literal, filters before the cap, and supports alternate sorts.
    // Why: A search must find older blocked entries and must not treat percent/underscore as SQL wildcards.
    @Test public void domainSearchAndSortAreAppliedBeforeTheLimit() {
        insert("needle", 10, 1); insert("literal_%", 11, 0);
        for (int i = 0; i < 260; i++) insert("newer-" + i, 100 + i, 0);
        try (Cursor cursor = db.getAccess(UID, false, "NEEDLE", ListSort.RECENT)) {
            assertEquals(1, cursor.getCount()); cursor.moveToFirst();
            assertEquals("needle.mw-list-test.invalid", cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
        }
        try (Cursor cursor = db.getAccess(UID, false, "_%", ListSort.RECENT)) { assertEquals(1, cursor.getCount()); }
        try (Cursor cursor = db.getAccess(UID, false, "", ListSort.BLOCKED)) {
            assertEquals(250, cursor.getCount()); cursor.moveToFirst();
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("block")));
        }
        assertEquals(262, db.getAccessStats().get(UID).count);
    }

    // Test: Repeated traffic moves an existing row without resetting the list; a tap still opens the touched domain.
    // Why: A row can move or rebind between touch-down and click while live traffic continues.
    @Test public void liveMovesKeepIdentityAndTouchTarget() {
        insert("first", 10, 1); insert("second", 20, 1);
        List<AccessEntry> before = read();
        ContentValues update = new ContentValues(); update.put("time", 30);
        db.getWritableDatabase().update("access", update, "uid=? AND daddr=?", new String[]{"" + UID, "first.mw-list-test.invalid"});
        List<AccessEntry> after = read();
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ComponentTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            String[] clicked = {null}; int[] reset = {0}, moves = {0};
            AdapterAccess adapter = new AdapterAccess(activity, entry -> clicked[0] = entry.address);
            adapter.submit(before);
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged() { reset[0]++; }
                @Override public void onItemRangeMoved(int from, int to, int count) { moves[0] += count; }
            });
            AdapterAccess.Holder holder = adapter.onCreateViewHolder(new FrameLayout(activity), 0);
            holder.itemView.layout(0, 0, 800, 100);
            adapter.onBindViewHolder(holder, 0);
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0);
            holder.itemView.dispatchTouchEvent(down); down.recycle();
            adapter.submit(after);
            adapter.onBindViewHolder(holder, 0);
            holder.itemView.performClick();
            assertEquals("second.mw-list-test.invalid", clicked[0]);
            assertEquals(before.get(1).id, adapter.getItemId(0));
            assertEquals(0, reset[0]); assertTrue(moves[0] > 0);
        });
    }

    // Test: Choosing a sort is temporary; Set as default changes the next session's starting sort.
    // Why: A temporary review order must not silently replace the user's preferred default.
    @Test public void sortDefaultRequiresItsExplicitButton() {
        onMain(() -> prefs.edit().remove(ListSort.APP_DEFAULT).commit());
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ComponentTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String[] chosen = {ListSort.NAME};
        onMain(() -> SortDialog.show(activity, R.string.list_sort_apps, ListSort.APPS, ListSort.NAME,
                ListSort.APP_DEFAULT, sort -> chosen[0] = sort));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wait(Until.findObject(By.text(context.getString(R.string.sort_criterion_access))), 5000).click();
        device.wait(Until.findObject(By.text(context.getString(R.string.list_sort_blocked))), 5000).click();
        device.waitForIdle();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(ListSort.BLOCKED, chosen[0]); assertFalse(prefs.contains(ListSort.APP_DEFAULT));
        device.findObject(By.text(context.getString(R.string.list_set_default))).click();
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!ListSort.BLOCKED.equals(prefs.getString(ListSort.APP_DEFAULT, null)) && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50);
        device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "sort-default.png"));
        assertEquals(ListSort.BLOCKED, prefs.getString(ListSort.APP_DEFAULT, null));
        onMain(() -> assertEquals(ListSort.BLOCKED, new AdapterRule(activity, new FrameLayout(activity)).options.sort));
        device.findObject(By.res("android", "button1")).click();
    }

    private List<AccessEntry> read() {
        List<AccessEntry> rows = new ArrayList<>();
        try (Cursor cursor = db.getAccess(UID)) { while (cursor.moveToNext()) rows.add(new AccessEntry(cursor)); }
        return rows;
    }
    private void insert(String name, long time, int block) {
        ContentValues row = new ContentValues();
        row.put("uid", UID); row.put("version", 4); row.put("protocol", 6);
        row.put("daddr", name + ".mw-list-test.invalid"); row.put("dport", 443);
        row.put("time", time); row.put("allowed", block == 0 ? 1 : 0); row.put("block", block); row.put("pending_allow", 0);
        db.getWritableDatabase().insertOrThrow("access", null, row);
    }
    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
