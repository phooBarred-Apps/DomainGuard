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
    private boolean ownedFeature, originalLogging;
    private Boolean originalDark;
    private int uid;

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        originalLogging = preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, false);
        originalDark = preferences.contains("dark_theme") ? preferences.getBoolean("dark_theme", false) : null;
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uid = context.getApplicationInfo().uid;
        clearFixtures();
        if (!rules.getPreferenceAllowLogging(context)) {
            try (Cursor cursor = db.getRuleMatchingRuletext(FEATURE)) {
                assertFalse("Do not overwrite an existing pending logging rule", cursor.moveToFirst());
            }
            assertEquals("Fixture must use the current zero-delay rule queue", 0, RuleDelay.read(context).seconds);
            ownedFeature = true;
            rules.queueRuleText(context, FEATURE);
            barrier();
            await(() -> rules.getPreferenceAllowLogging(context));
        }
        preferences.edit().putBoolean(Rule.PREFERENCE_STRING_LOG, false).commit();
    }

    @After public void cleanup() throws Exception {
        if (activity != null) onMain(() -> activity.finish());
        clearFixtures();
        if (ownedFeature) {
            List<Long> ids = new ArrayList<>();
            try (Cursor cursor = db.getRuleMatchingRuletext(FEATURE)) {
                while (cursor.moveToNext()) ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
            db.removeRulesById(ids.toArray(new Long[0]));
            reloadRules();
        }
        SharedPreferences.Editor restore = preferences.edit().putBoolean(Rule.PREFERENCE_STRING_LOG, originalLogging);
        if (originalDark == null) restore.remove("dark_theme"); else restore.putBoolean("dark_theme", originalDark);
        restore.commit();
    }

    // Test: SQLite preserves repeats and equal-time IDs, applies cutoff/hiding, and sorts/limits attempts.
    // Why: The existing per-app history coalesces requests and cannot supply the new timeline's semantics.
    @Test public void queryPreservesIndividualAttemptsAndScopes() {
        long now = System.currentTimeMillis();
        insert(uid, "old", now - 240000, false);
        insert(uid, "repeat", now - 1000, false);
        insert(uid, "repeat", now - 1000, false);
        insert(99191, "alpha", now, true);
        insert(-1, "unknown", now, false);
        List<Long> recent = ids(now - 180000, Collections.emptySet(), ListSort.RECENT, 100);
        assertEquals(4, recent.size());
        assertNotEquals(recent.get(2), recent.get(3));
        assertTrue(recent.get(2) > recent.get(3));
        List<Long> oldest = ids(now - 180000, Collections.emptySet(), ListSort.OLDEST, 100);
        Collections.reverse(oldest);
        assertEquals(recent, oldest);
        assertEquals(2, ids(now - 180000, Collections.singleton(uid), ListSort.RECENT, 100).size());
        assertEquals(1, ids(now - 180000, new HashSet<>(Arrays.asList(uid, -1)), ListSort.RECENT, 100).size());
        assertEquals(recent.subList(0, 2), ids(now - 180000, Collections.emptySet(), ListSort.RECENT, 2));
        try (Cursor cursor = db.getRecentTraffic(now - 180000, Collections.emptySet(), ListSort.NAME, 100)) {
            assertTrue(cursor.moveToFirst()); assertEquals("alpha." + HOST, cursor.getString(3));
        }
        try (Cursor cursor = db.getRecentTraffic(now - 180000, Collections.emptySet(), ListSort.ALLOWED, 100)) {
            assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(4));
        }
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
        await(() -> count() == 3);
        assertTrue(find("recent_window").getText().contains("3 minutes"));
        assertTrue(find("recent_recording_status").getText().contains("recording is off"));
        insert(uid, "live", System.currentTimeMillis(), false);
        await(() -> count() == 4);
        clickRowControl(uid, R.id.recent_hide_app);
        await(() -> count() == 1);
        assertFalse(preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, true));
        assertEquals(5, fixtureCount());
        find("recent_hidden").click();
        UiObject2 restore = device.wait(Until.findObject(By.descStartsWith("Show traffic from ")), 5000);
        assertNotNull(restore); restore.click();
        device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
        await(() -> count() == 4);
        setWindow("5", RuleDelay.Unit.MINUTES);
        await(() -> count() == 5);
        for (boolean dark : new boolean[]{false, true}) {
            preferences.edit().putBoolean("dark_theme", dark).commit();
            recreate(); await(() -> count() == 5);
            device.waitForIdle();
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "recent-traffic-" + (dark ? "dark" : "light") + ".png"));
        }
        setWindow("1", RuleDelay.Unit.SECONDS);
        await(() -> count() == 0);
        insert(uid, "fresh", System.currentTimeMillis(), true);
        await(() -> count() == 1);
        onMain(() -> assertEquals("1 attempt shown", ((TextView) activity.findViewById(R.id.recent_count)).getText().toString()));
        await(() -> count() == 0);
    }

    // Test: A bounded first page can be expanded without merging repeats or silently dropping the remaining attempts.
    // Why: Busy apps must not create an unbounded initial UI load or misleadingly appear to have only 250 attempts.
    @Test public void busyTimelineLoadsAdditionalAttempts() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 255; i++) insert(uid, "repeat", now - i, false);
        open();
        await(() -> count() == 250);
        find("recent_more").click();
        await(() -> count() == 255);
        onMain(() -> assertEquals(View.GONE, activity.findViewById(R.id.recent_more).getVisibility()));
    }

    // Test: Revoking logging permission removes displayed history and does not enable recording to populate the view.
    // Why: The new screen must honor the same existing rule protection as the detailed log.
    @Test public void recordingPolicyIsNotBypassed() throws Exception {
        insert(uid, "secret", System.currentTimeMillis(), true);
        open(); await(() -> count() == 1);
        List<ContentValues> saved = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getRuleMatchingRuletext(FEATURE)) {
            while (cursor.moveToNext()) {
                ContentValues row = new ContentValues(); DatabaseUtils.cursorRowToContentValues(cursor, row);
                saved.add(row); ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
        }
        try {
            db.removeRulesById(ids.toArray(new Long[0])); reloadRules();
            await(() -> count() == 0);
            assertEquals("Open Rules", find("recent_recording_action").getText());
            assertFalse(preferences.getBoolean(Rule.PREFERENCE_STRING_LOG, true));
            assertEquals(1, fixtureCount());
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
            RecentTrafficAdapter.Entry first = new RecentTrafficAdapter.Entry(1, 1, uid, "one." + HOST, false, app);
            RecentTrafficAdapter.Entry second = new RecentTrafficAdapter.Entry(2, 2, 99191, "two." + HOST, true, app);
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
                    for (int id : new int[]{R.id.recent_window, R.id.recent_sort, R.id.recent_hidden, R.id.recent_help}) {
                        View control = screen.findViewById(id);
                        assertTrue(control.getWidth() >= CompactUi.dp(themed, 48));
                        assertTrue(control.getRight() <= ((View) control.getParent()).getWidth());
                    }
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
        db.insertLog(packet, prefix + "." + HOST, 1, true);
    }
    private List<Long> ids(long cutoff, java.util.Set<Integer> hidden, String sort, int limit) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getRecentTraffic(cutoff, hidden, sort, limit)) {
            while (cursor.moveToNext()) ids.add(cursor.getLong(0));
        }
        return ids;
    }
    private int fixtureCount() {
        try (Cursor cursor = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM log WHERE dname LIKE ?", new String[]{"%." + HOST})) {
            cursor.moveToFirst(); return cursor.getInt(0);
        }
    }
    private void clearFixtures() { db.getWritableDatabase().delete("log", "dname LIKE ?", new String[]{"%." + HOST}); }
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
