package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecentTrafficDeviceTest {
    private static final String HOST = "recent-traffic-test.invalid";
    private static final String FEATURE = "feature allow_logging";
    private Context context;
    private DatabaseHelper db;
    private RulesManager rules;
    private SharedPreferences preferences;
    private UiDevice device;
    private Activity activity;
    private boolean originalLogging;
    private Boolean originalDark, originalHideAllowed;
    private int uid;
    private final Map<String, Object> savedFilters = new HashMap<>();

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        originalLogging = preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, false);
        SharedPreferences.Editor clearFilters = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            if (entry.getKey().startsWith("recent_filter_default_")) {
                savedFilters.put(entry.getKey(), entry.getValue());
                clearFilters.remove(entry.getKey());
            }
        clearFilters.commit();
        originalDark = preferences.contains("dark_theme") ? preferences.getBoolean("dark_theme", false) : null;
        originalHideAllowed = preferences.contains(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS)
                ? preferences.getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false) : null;
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uid = context.getApplicationInfo().uid;
        clearFixtures();
        preferences.edit().putBoolean(Rule.PREFERENCE_STRING_LOG, false)
                .putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false).commit();
    }

    @After public void cleanup() throws Exception {
        if (activity != null) onMain(() -> activity.finish());
        clearFixtures();
        SharedPreferences.Editor restore = preferences.edit().putBoolean(Rule.PREFERENCE_STRING_LOG, originalLogging);
        if (originalDark == null) restore.remove("dark_theme"); else restore.putBoolean("dark_theme", originalDark);
        if (originalHideAllowed == null) restore.remove(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS);
        else restore.putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, originalHideAllowed);
        for (String key : preferences.getAll().keySet())
            if (key.startsWith("recent_filter_default_")) restore.remove(key);
        for (Map.Entry<String, Object> entry : savedFilters.entrySet()) {
            if (entry.getValue() instanceof Integer) restore.putInt(entry.getKey(), (Integer) entry.getValue());
            else restore.putBoolean(entry.getKey(), (Boolean) entry.getValue());
        }
        restore.commit();
    }

    // Test: App filters apply before paging and combine with Hide allowed and hidden apps.
    // Why: Busy excluded apps must not consume the first page, including when the app catalog exceeds SQLite's parameter limit.
    @Test public void appFiltersApplyBeforePagingAndKeepSharedUidSemantics() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 255; i++) insert(99191, "excluded" + i, now - i, false);
        insert(uid, "included", now - 1000, false);
        insert(uid, "allowed", now - 500, true);
        java.util.Set<Integer> included = new HashSet<>();
        for (int i = 0; i < 1200; i++) included.add(80000 + i);
        included.add(uid);
        try (Cursor cursor = db.getRecentAccess(now - 10000, Collections.emptySet(), included, true, ListSort.RECENT, 1)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("included." + HOST, cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            assertFalse(cursor.moveToNext());
        }
        for (java.util.Set<Integer> hidden : Arrays.asList(Collections.<Integer>emptySet(), Collections.singleton(uid)))
            try (Cursor cursor = db.getRecentAccess(now - 10000, hidden,
                    hidden.isEmpty() ? Collections.emptySet() : included, false, ListSort.RECENT, 10)) {
                assertEquals(0, cursor.getCount());
            }
        List<Rule> apps = Rule.getRules(true, context);
        assertTrue(apps.size() >= 2);
        Rule first = apps.get(0), second = apps.get(1);
        first.uid = second.uid = 99192;
        first.system = false; first.enabled = true; first.apply = true;
        first.wifi_blocked = first.other_blocked = true;
        second.system = true; second.enabled = true; second.apply = false;
        AppListOptions filter = new AppListOptions(true);
        filter.access = 2; filter.type = 1; filter.includeDisabled = false;
        assertEquals(Collections.singleton(99192), filter.matchingUids(Arrays.asList(first, second)));
        filter.access = 1;
        assertTrue("Different apps sharing a UID must not combine half-matching criteria", filter.matchingUids(Arrays.asList(first, second)).isEmpty());
        filter.type = 2;
        assertEquals(Collections.singleton(99192), filter.matchingUids(Arrays.asList(first, second)));
        second.enabled = false;
        assertTrue(filter.matchingUids(Arrays.asList(first, second)).isEmpty());
    }

    // Test: Apply is temporary, Cancel does nothing, and Set as default/Reset remain independent of the main screen.
    // Why: Recent traffic must keep the familiar dialog without inheriting irrelevant or overwriting saved main-list filters.
    @Test public void filterDialogPreservesSeparateDefaultsAndSessionChoices() throws Exception {
        Map<String, ?> mainDefaults = new HashMap<>(preferences.getAll());
        Rule user = null;
        for (Rule app : Rule.getRules(true, context)) if (!app.system && app.enabled) { user = app; break; }
        assertNotNull("Installed synthetic test app", user);
        insert(user.uid, "user", System.currentTimeMillis(), false);
        insert(0, "system", System.currentTimeMillis(), false);
        open(); await(() -> count() == 2);
        find("recent_filter").click();
        find("filter_type");
        assertFalse(device.hasObject(By.res(context.getPackageName(), "filter_activity")));
        assertFalse(device.hasObject(By.res(context.getPackageName(), "filter_no_internet")));
        chooseFilter("filter_type", R.array.list_type_choices, 1);
        closeFilter("button2"); await(() -> count() == 2);
        find("recent_filter").click();
        chooseFilter("filter_type", R.array.list_type_choices, 1);
        closeFilter("button1"); await(() -> count() == 1);
        assertFalse(preferences.contains("recent_filter_default_type"));
        recreate(); await(() -> count() == 1);
        onMain(() -> assertTrue(activity.findViewById(R.id.recent_filter).isSelected()));
        find("recent_filter").click(); find("filter_default").click();
        assertTrue(device.wait(Until.gone(By.res("android", "button1")), 5000));
        assertEquals(1, preferences.getInt("recent_filter_default_type", 0));
        setWindow("5", RuleDelay.Unit.MINUTES);
        find("recent_sort").click();
        device.wait(Until.findObject(By.text(context.getString(R.string.sort_oldest))), 5000).click();
        find("recent_hide_allowed").click();
        device.waitForIdle();
        find("recent_filter").click();
        chooseFilter("filter_type", R.array.list_type_choices, 2);
        closeFilter("button1"); await(() -> count() == 1);
        find("recent_filter").click(); find("filter_reset").click();
        assertTrue(device.wait(Until.gone(By.res("android", "button1")), 5000));
        await(() -> {
            boolean[] userShown = new boolean[1];
            onMain(() -> {
                RecentTrafficAdapter rows = (RecentTrafficAdapter) ((RecyclerView) activity.findViewById(R.id.recent_list)).getAdapter();
                userShown[0] = rows.getItemCount() == 1 && rows.entry(0).destination.startsWith("user.");
            });
            return userShown[0];
        });
        assertEquals(1, preferences.getInt("recent_filter_default_type", 0));
        assertTrue(find("recent_window").getText().contains("5 minutes"));
        assertEquals(context.getString(R.string.sort_oldest), find("recent_sort").getContentDescription());
        onMain(() -> assertTrue(activity.findViewById(R.id.recent_hide_allowed).isSelected()));
        for (String key : mainDefaults.keySet()) if (key.startsWith("app_filter_default_"))
            assertEquals(mainDefaults.get(key), preferences.getAll().get(key));
        onMain(() -> activity.finish()); open(); await(() -> count() == 1);
        assertEquals(2, fixtureCount());
        for (boolean dark : new boolean[]{false, true}) {
            preferences.edit().putBoolean("dark_theme", dark).commit();
            recreate(); await(() -> count() == 1);
            find("recent_filter").click(); find("filter_type");
            device.waitForIdle();
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "recent-filters-" + (dark ? "dark" : "light") + ".png"));
            closeFilter("button2");
        }
    }

    private void chooseFilter(String id, int labels, int selection) {
        find(id).click();
        UiObject2 choice = device.wait(Until.findObject(By.text(context.getResources().getStringArray(labels)[selection])), 5000);
        assertNotNull(choice); choice.click();
    }

    // Test: Changing an app's default access refreshes the active filter without requiring new traffic.
    // Why: A cached app catalog must not leave records in the wrong Access category after rule changes.
    @Test public void accessFilterRefreshesAfterAppRulesChange() {
        Rule blocked = null;
        for (Rule app : Rule.getRules(true, context))
            if (app.apply && app.wifi_blocked && app.other_blocked) { blocked = app; break; }
        assertNotNull("Blocked app available in disposable fixture", blocked);
        String name = blocked.packageName;
        SharedPreferences apply = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_APPLY, Context.MODE_PRIVATE);
        Boolean saved = apply.contains(name) ? apply.getBoolean(name, true) : null;
        try {
            preferences.edit().putInt("recent_filter_default_access", 1).commit();
            insert(blocked.uid, "changing-app", System.currentTimeMillis(), false);
            open(); find("recent_filter");
            await(() -> context.getString(R.string.recent_traffic_empty_filtered).equals(find("recent_empty").getText()));
            apply.edit().putBoolean(name, false).commit();
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(new Intent(ActivityMain.ACTION_RULES_CHANGED));
            await(() -> count() == 1);
            apply.edit().putBoolean(name, true).commit();
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(new Intent(ActivityMain.ACTION_RULES_CHANGED));
            await(() -> count() == 0);
            assertEquals(1, fixtureCount());
        } finally {
            if (saved == null) apply.edit().remove(name).commit(); else apply.edit().putBoolean(name, saved).commit();
        }
    }

    private void closeFilter(String button) {
        device.wait(Until.findObject(By.res("android", button)), 5000).click();
        assertTrue(device.wait(Until.gone(By.res("android", "button1")), 5000));
    }

    // Test: The combined view uses the per-app records, including repeated updates, cutoff, sorting and hiding.
    // Why: Recent traffic must use access history without creating or depending on a second detailed log.
    @Test public void queryUsesExistingAccessRecordsAndLatestTimes() {
        long now = System.currentTimeMillis();
        insert(uid, "old", now - 240000, false);
        insert(uid, "repeat", now - 1000, false);
        long repeatedId = ids(now - 180000, Collections.emptySet(), ListSort.RECENT, 100).get(0);
        insert(uid, "repeat", now - 500, false);
        insert(99191, "alpha", now, true);
        insert(-1, "unknown", now, false);
        List<Long> recent = ids(now - 180000, Collections.emptySet(), ListSort.RECENT, 100);
        assertEquals(3, recent.size());
        assertEquals(repeatedId, (long) recent.get(2));
        assertEquals(recent.get(2), ids(now - 180000, Collections.emptySet(), ListSort.OLDEST, 100).get(0));
        assertEquals(2, ids(now - 180000, Collections.singleton(uid), ListSort.RECENT, 100).size());
        assertEquals(1, ids(now - 180000, new HashSet<>(Arrays.asList(uid, -1)), ListSort.RECENT, 100).size());
        assertEquals(recent.subList(0, 2), ids(now - 180000, Collections.emptySet(), ListSort.RECENT, 2));
        try (Cursor cursor = db.getRecentAccess(now - 180000, Collections.emptySet(), null, false, ListSort.NAME, 100)) {
            assertTrue(cursor.moveToFirst()); assertEquals("alpha." + HOST, cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
        }
        try (Cursor cursor = db.getRecentAccess(now - 180000, Collections.singleton(uid), null, true, ListSort.RECENT, 1)) {
            assertTrue(cursor.moveToFirst()); assertEquals("unknown." + HOST, cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            assertFalse(cursor.moveToNext());
        }
        insert(uid, "repeat", now + 1, false);
        assertEquals(repeatedId, (long) ids(now - 180000, Collections.emptySet(), ListSort.RECENT, 100).get(0));
    }

    // Test: Hide allowed uses the global default, filters before paging, and keeps its session state on recreation.
    // Why: Allowed traffic must not crowd blocked attempts out of the page or change recording, defaults or saved history.
    @Test public void hideAllowedScopesPagesLiveUpdatesAndSessionState() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 255; i++) insert(uid, "allowed" + i, now - i, true);
        insert(uid, "blocked", now - 1000, false);
        insert(99191, "helper-blocked", now - 2000, false);
        preferences.edit().putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, true).commit();
        open(); await(() -> count() == 2);
        onMain(() -> {
            View hide = activity.findViewById(R.id.recent_hide_allowed);
            assertTrue(hide.isSelected());
            assertEquals(context.getString(R.string.hide_allowed_domains) + ": " + context.getString(R.string.control_on),
                    hide.getContentDescription().toString());
            assertEquals(View.GONE, activity.findViewById(R.id.recent_more).getVisibility());
        });
        insert(uid, "new-allowed", System.currentTimeMillis(), true);
        insert(uid, "new-blocked", System.currentTimeMillis(), false);
        await(() -> count() == 3);
        onMain(() -> {
            RecentTrafficAdapter adapter = (RecentTrafficAdapter) ((RecyclerView) activity.findViewById(R.id.recent_list)).getAdapter();
            for (int i = 0; i < adapter.getItemCount(); i++) assertTrue(adapter.entry(i).access.allowed <= 0 && adapter.entry(i).access.block != 0);
        });
        clickRowControl(99191, R.id.recent_hide_app);
        await(() -> count() == 2);
        find("recent_hide_allowed").click();
        await(() -> count() == 250);
        find("recent_more").click();
        await(() -> count() == 258);
        recreate(); await(() -> count() == 258);
        onMain(() -> assertFalse(activity.findViewById(R.id.recent_hide_allowed).isSelected()));
        assertTrue(preferences.getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false));
        assertFalse(preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, true));
        assertEquals(259, fixtureCount());
        find("recent_hide_allowed").click();
        await(() -> count() == 2);
        clickRowControl(uid, R.id.recent_hide_app);
        await(() -> count() == 0);
        onMain(() -> assertEquals(context.getString(R.string.recent_traffic_empty_blocked),
                ((TextView) activity.findViewById(R.id.recent_empty)).getText().toString()));
    }

    // Test: Both views hide allowed rules while retaining pending/unknown records, and rule changes refresh the combined view.
    // Why: The usual filter must behave the same before and after a saved rule changes, even without new traffic.
    @Test public void accessFiltersMatchTheMainListsAfterRuleChanges() {
        long now = System.currentTimeMillis();
        for (String name : new String[]{"blocked", "allowed-rule", "pending", "unknown"}) insert(uid, name, now, false);
        long allowedId = 0, pendingId = 0, unknownId = 0;
        try (Cursor cursor = db.getAccess(uid)) {
            while (cursor.moveToNext()) {
                String address = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("ID"));
                if (address.startsWith("allowed-rule.")) allowedId = id;
                else if (address.startsWith("pending.")) pendingId = id;
                else if (address.startsWith("unknown.")) unknownId = id;
            }
        }
        assertTrue(allowedId > 0 && pendingId > 0 && unknownId > 0);
        RuleAllowData allow = new RuleAllowData();
        allow.allowed = 1; allow.ruletext = "allow host:allowed-rule." + HOST;
        db.setAccess(allowedId, allow, "Synthetic test rule");
        db.setAccessPending(Long.toString(pendingId), 1);
        db.setAccess(unknownId, null, "Synthetic unknown outcome");
        List<Long> expected = new ArrayList<>(), combined = new ArrayList<>();
        try (Cursor cursor = db.getAccess(uid, true)) {
            while (cursor.moveToNext()) expected.add(cursor.getLong(cursor.getColumnIndexOrThrow("ID")));
        }
        try (Cursor cursor = db.getRecentAccess(now - 1000, Collections.emptySet(), null, true, ListSort.RECENT, 100)) {
            while (cursor.moveToNext()) combined.add(cursor.getLong(cursor.getColumnIndexOrThrow("ID")));
        }
        assertEquals(3, expected.size());
        assertEquals(expected, combined);
        preferences.edit().putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, true).commit();
        open(); await(() -> count() == 3);
        db.setAccess(allowedId, null, "Synthetic rule removed");
        await(() -> count() == 4);
        assertFalse(preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, true));
    }

    // Test: The real screen defaults to three minutes, follows inserts, hides/restores by app, and extends its window.
    // Why: Hiding is a reversible view filter, not a logging change or destructive history operation.
    @Test public void liveTimelineHidesRestoresAndChangesWindow() throws Exception {
        long now = System.currentTimeMillis();
        insert(uid, "repeat", now - 1000, false);
        insert(uid, "repeat", now, false);
        insert(99191, "helper", now, true);
        insert(uid, "older", now - 240000, true);
        open();
        await(() -> count() == 2);
        assertTrue(find("recent_window").getText().contains("3 minutes"));
        insert(uid, "live", System.currentTimeMillis(), false);
        await(() -> count() == 3);
        clickRowControl(uid, R.id.recent_hide_app);
        await(() -> count() == 1);
        assertFalse(preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, true));
        assertEquals(4, fixtureCount());
        find("recent_hidden").click();
        UiObject2 restore = device.wait(Until.findObject(By.descStartsWith("Show traffic from ")), 5000);
        assertNotNull(restore); restore.click();
        device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
        await(() -> count() == 3);
        setWindow("5", RuleDelay.Unit.MINUTES);
        await(() -> count() == 4);
        for (boolean dark : new boolean[]{false, true}) {
            preferences.edit().putBoolean("dark_theme", dark).commit();
            recreate(); await(() -> count() == 4);
            device.waitForIdle();
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "recent-traffic-" + (dark ? "dark" : "light") + ".png"));
        }
        setWindow("1", RuleDelay.Unit.SECONDS);
        await(() -> count() == 0);
        insert(uid, "fresh", System.currentTimeMillis(), true);
        await(() -> count() == 1);
        onMain(() -> assertEquals("1 access record shown", ((TextView) activity.findViewById(R.id.recent_count)).getText().toString()));
        await(() -> count() == 0);
    }

    // Test: A bounded first page can be expanded without silently dropping the remaining attempts.
    // Why: Busy apps must not create an unbounded initial UI load or misleadingly appear to have only 250 attempts.
    @Test public void busyTimelineLoadsAdditionalAttempts() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 255; i++) insert(uid, "record" + i, now - i, false);
        open();
        await(() -> count() == 250);
        find("recent_more").click();
        await(() -> count() == 255);
        onMain(() -> assertEquals(View.GONE, activity.findViewById(R.id.recent_more).getVisibility()));
    }

    // Test: Existing access rows remain available with the detailed logging rule absent and its switch off.
    // Why: Combining the main app lists must not introduce an extra recording prerequisite or change the user's rules.
    @Test public void accessRecordsDoNotRequireDetailedLogging() throws Exception {
        List<ContentValues> saved = new ArrayList<>();
        List<Long> ruleIds = new ArrayList<>();
        try (Cursor cursor = db.getRuleMatchingRuletext(FEATURE)) {
            while (cursor.moveToNext()) {
                ContentValues row = new ContentValues(); DatabaseUtils.cursorRowToContentValues(cursor, row);
                saved.add(row); ruleIds.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
        }
        try {
            db.removeRulesById(ruleIds.toArray(new Long[0])); reloadRules();
            assertFalse(rules.getPreferenceAllowLogging(context));
            insert(uid, "available", System.currentTimeMillis(), false);
            open(); await(() -> count() == 1);
            assertFalse(preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, true));
            assertFalse(rules.getPreferenceAllowLogging(context));
            insert(uid, "available", System.currentTimeMillis() + 1, true);
            await(() -> {
                boolean[] allowed = new boolean[1];
                onMain(() -> allowed[0] = ((RecentTrafficAdapter) ((RecyclerView) activity.findViewById(R.id.recent_list))
                        .getAdapter()).entry(0).access.allowed > 0);
                return allowed[0];
            });
            assertEquals(1, count());
            assertEquals(1, fixtureCount());
            try (Cursor cursor = db.getAccess(uid)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("available." + HOST, cursor.getString(cursor.getColumnIndexOrThrow("daddr")));
            }
        } finally {
            for (ContentValues row : saved) db.getWritableDatabase().insertOrThrow("rules", null, row);
            reloadRules();
        }
    }

    // Test: A row and its hide control retain the originally pressed event when the holder is rebound mid-touch.
    // Why: Live reordering must never apply a domain action or app filter to the next item under the finger.
    @Test public void activeTouchesKeepTheirOriginalTargets() {
        open();
        onMain(() -> {
            RecentTrafficAdapter.Entry[] clicked = new RecentTrafficAdapter.Entry[2];
            RecentTrafficAdapter subject = new RecentTrafficAdapter(new RecentTrafficAdapter.Actions() {
                public void open(RecentTrafficAdapter.Entry entry) { clicked[0] = entry; }
                public void hide(RecentTrafficAdapter.Entry entry) { clicked[1] = entry; }
            });
            RecentTrafficAdapter.App app = new RecentTrafficAdapter.App("Synthetic app", context.getPackageManager().getDefaultActivityIcon(), new String[0]);
            RecentTrafficAdapter.Entry first = fixtureEntry(uid, "one", false, app);
            RecentTrafficAdapter.Entry second = fixtureEntry(99191, "two", true, app);
            subject.submit(Arrays.asList(first, second));
            RecentTrafficAdapter.Holder holder = subject.onCreateViewHolder(activity.findViewById(R.id.recent_list), 0);
            for (int target = 0; target < 2; target++) {
                subject.onBindViewHolder(holder, 0);
                View view = target == 0 ? holder.itemView : holder.itemView.findViewById(R.id.recent_hide_app);
                MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0);
                view.dispatchTouchEvent(down); down.recycle();
                subject.onBindViewHolder(holder, 1);
                view.performClick();
                assertSame(first, clicked[target]);
            }
        });
    }

    // Test: Live arrivals preserve an older visible row, and recreation retains hidden apps, window and sorting.
    // Why: Scrolling or rotating while investigating traffic must not reset the investigation or jump to new events.
    @Test public void reviewPositionAndSessionChoicesSurviveUpdates() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 40; i++) insert(uid, "event" + i, now - i * 1000L, false);
        insert(99191, "helper", now - 240000, true);
        open(); await(() -> count() == 40);
        long[] anchor = new long[1];
        onMain(() -> {
            RecyclerView list = activity.findViewById(R.id.recent_list);
            anchor[0] = list.getAdapter().getItemId(15);
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(15, -5);
        });
        await(() -> firstVisibleId() == anchor[0]);
        insert(uid, "arrival", System.currentTimeMillis(), false);
        await(() -> count() == 41);
        assertEquals(anchor[0], firstVisibleId());
        setWindow("5", RuleDelay.Unit.MINUTES);
        await(() -> count() == 42);
        find("recent_sort").click();
        UiObject2 oldest = device.wait(Until.findObject(By.text(context.getString(R.string.sort_oldest))), 5000);
        assertNotNull(oldest); oldest.click();
        await(() -> {
            boolean[] matched = new boolean[1];
            onMain(() -> {
                RecyclerView list = activity.findViewById(R.id.recent_list);
                matched[0] = ((RecentTrafficAdapter) list.getAdapter()).entry(0).destination.equals("helper." + HOST)
                        && ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition() == 0;
            });
            return matched[0];
        });
        clickRowControl(99191, R.id.recent_hide_app);
        await(() -> count() == 41);
        recreate(); await(() -> count() == 41);
        assertTrue(find("recent_window").getText().contains("5 minutes"));
        assertEquals(context.getString(R.string.sort_oldest), find("recent_sort").getContentDescription());
        assertEquals("Hidden apps (1)", find("recent_hidden").getContentDescription());
    }

    // Test: Long domains, toolbar controls and window fields fit 320dp layouts with larger text in all themes.
    // Why: The combined app/icon/eye row must remain readable and usable without clipping on a phone.
    @Test public void narrowLayoutsSupportLongContentAndAllThemes() {
        onMain(() -> {
            for (int theme : new int[]{R.style.AppThemeTeal, R.style.AppThemeTealDark,
                    R.style.AppThemeBlue, R.style.AppThemeBlueDark, R.style.AppThemePurple, R.style.AppThemePurpleDark,
                    R.style.AppThemeAmber, R.style.AppThemeAmberDark, R.style.AppThemeOrange, R.style.AppThemeOrangeDark,
                    R.style.AppThemeGreen, R.style.AppThemeGreenDark}) {
                for (float scale : new float[]{1, 1.5f, 2}) {
                    Configuration config = new Configuration(context.getResources().getConfiguration()); config.fontScale = scale;
                    Context themed = new ContextThemeWrapper(context.createConfigurationContext(config), theme);
                    View screen = LayoutInflater.from(themed).inflate(R.layout.recent_traffic, null);
                    ((TextView) screen.findViewById(R.id.recent_window)).setText("Last 3 minutes");
                    measure(screen, CompactUi.dp(themed, 320), CompactUi.dp(themed, 640));
                    for (int id : new int[]{R.id.recent_window, R.id.recent_hide_allowed, R.id.recent_sort, R.id.recent_hidden, R.id.recent_help}) {
                        View control = screen.findViewById(id);
                        assertTrue(control.getWidth() >= CompactUi.dp(themed, 48));
                        assertTrue(control.getRight() <= ((View) control.getParent()).getWidth());
                    }
                    assertTextFits(screen.findViewById(R.id.recent_window));
                    View row = LayoutInflater.from(themed).inflate(R.layout.recent_traffic_row, null);
                    ((TextView) row.findViewById(R.id.recent_app_name)).setText("Example application with a long name");
                    ((TextView) row.findViewById(R.id.recent_domain)).setText("a-very-long-subdomain.for-a-synthetic-application." + HOST);
                    ((TextView) row.findViewById(R.id.recent_time)).setText("25 · 11:59:59 PM");
                    measure(row, CompactUi.dp(themed, 320), 0);
                    assertTextFits(row);
                    View duration = LayoutInflater.from(themed).inflate(R.layout.recent_traffic_window, null);
                    new DurationInput(duration.findViewById(R.id.recent_window_amount), duration.findViewById(R.id.recent_window_unit), 180, () -> {});
                    measure(duration, CompactUi.dp(themed, 288), 0);
                    View unit = duration.findViewById(R.id.recent_window_unit);
                    assertTrue("Unit picker fits dialog", unit.getRight() <= ((View) unit.getParent()).getWidth());
                }
            }
        });
    }

    // Test: Light/dark launches retain the screen title and back arrow, including after closing Help.
    // Why: The new full-screen view must remain identifiable and navigable in both theme modes.
    @Test public void screenNavigationWorksInBothThemeModes() throws Exception {
        insert(uid, "navigation", System.currentTimeMillis(), false);
        for (boolean dark : new boolean[]{false, true}) {
            preferences.edit().putBoolean("dark_theme", dark).commit();
            open(); await(() -> count() == 1);
            find("recent_help").click();
            UiObject2 done = device.wait(Until.findObject(By.res("android", "button1")), 5000);
            assertNotNull(done); done.click();
            assertTrue(device.wait(Until.hasObject(By.text("Recent traffic")), 5000));
            UiObject2 back = device.wait(Until.findObject(By.desc("Navigate up")), 5000);
            assertNotNull("Back arrow", back);
            device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "recent-navigation-" + dark + ".xml"));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "recent-navigation-" + dark + ".png"));
            back.click();
            await(() -> activity.isFinishing());
        }
    }

    private void assertTextFits(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            assertEquals(text.length(), text.getLayout().getLineEnd(text.getLineCount() - 1));
            assertTrue(text.getHeight() >= text.getLayout().getHeight());
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTextFits(group.getChildAt(i));
        }
    }
    private void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, height == 0 ? View.MeasureSpec.UNSPECIFIED : View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }
    private long firstVisibleId() {
        long[] id = {-1};
        onMain(() -> {
            RecyclerView list = activity.findViewById(R.id.recent_list);
            int position = ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
            if (position >= 0) id[0] = list.getAdapter().getItemId(position);
        });
        return id[0];
    }

    private void open() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityRecentTraffic.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void recreate() {
        Activity previous = activity;
        onMain(previous::recreate);
        await(() -> {
            onMain(() -> {
                for (Activity open : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (open instanceof ActivityRecentTraffic && open != previous) activity = open;
            });
            return activity != previous;
        });
    }
    private void setWindow(String amount, RuleDelay.Unit unit) {
        find("recent_window").click();
        find("recent_window_amount");
        onMain(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                EditText field = root.findViewById(R.id.recent_window_amount);
                if (field != null) {
                    field.setText(amount);
                    ((Spinner) root.findViewById(R.id.recent_window_unit)).setSelection(unit.ordinal());
                }
            }
        });
        device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
    }
    private void clickRowControl(int appUid, int control) {
        onMain(() -> {
            RecyclerView list = activity.findViewById(R.id.recent_list);
            RecentTrafficAdapter adapter = (RecentTrafficAdapter) list.getAdapter();
            for (int i = 0; i < adapter.getItemCount(); i++) if (adapter.entry(i).uid == appUid) {
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(i);
                assertNotNull(holder); holder.itemView.findViewById(control).performClick(); return;
            }
            fail("Fixture app is missing");
        });
    }
    private int count() {
        int[] result = new int[1];
        onMain(() -> result[0] = ((RecyclerView) activity.findViewById(R.id.recent_list)).getAdapter().getItemCount());
        return result[0];
    }
    private void insert(int appUid, String prefix, long time, boolean allowed) {
        Packet packet = new Packet(); packet.uid = appUid; packet.time = time; packet.allowed = allowed;
        packet.daddr = "192.0.2.91"; packet.version = 4; packet.protocol = 6; packet.dport = 443;
        db.updateAccess(context, packet, prefix + "." + HOST);
    }
    private List<Long> ids(long cutoff, java.util.Set<Integer> hidden, String sort, int limit) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getRecentAccess(cutoff, hidden, null, false, sort, limit)) {
            while (cursor.moveToNext()) ids.add(cursor.getLong(0));
        }
        return ids;
    }
    private RecentTrafficAdapter.Entry fixtureEntry(int appUid, String prefix, boolean allowed, RecentTrafficAdapter.App app) {
        insert(appUid, prefix, System.currentTimeMillis(), allowed);
        try (Cursor cursor = db.getAccess(appUid)) {
            assertTrue(cursor.moveToFirst());
            return new RecentTrafficAdapter.Entry(appUid, new AccessEntry(cursor), app);
        }
    }
    private int fixtureCount() {
        try (Cursor cursor = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM access WHERE daddr LIKE ?", new String[]{"%." + HOST})) {
            cursor.moveToFirst(); return cursor.getInt(0);
        }
    }
    private void clearFixtures() { db.getWritableDatabase().delete("access", "daddr LIKE ?", new String[]{"%." + HOST}); }
    private void reloadRules() throws Exception {
        Method method = RulesManager.class.getDeclaredMethod("getAllEnactedRulesFromDb", Context.class);
        method.setAccessible(true); method.invoke(rules, context); barrier();
    }
    private void barrier() throws Exception {
        CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }
    private void await(BooleanSupplier check) {
        long end = SystemClock.elapsedRealtime() + 7000;
        while (!check.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        assertTrue("Expected state before timeout", check.getAsBoolean());
    }
    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private UiObject2 find(String id) {
        UiObject2 view = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
        assertNotNull(id, view); return view;
    }
}
