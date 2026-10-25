package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DomainSearchDeviceTest {
    private static final String SUFFIX = ".mw-search-test.invalid";
    private Context context;
    private DatabaseHelper db;
    private UiDevice device;
    private Activity activity;
    private SharedPreferences prefs;
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private final List<Rule> apps = new ArrayList<>();

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        db = DatabaseHelper.getInstance(context);
        try (Cursor cursor = db.searchAccessDomains(new DomainSearchQuery(SUFFIX))) {
            assertEquals("Test domains must not already have traffic", 0, cursor.getCount());
        }
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // Use installed apps with distinct UIDs, not synthetic UIDs absent from the main list.
        List<Rule> installed = Rule.getRules(true, context);
        for (Rule candidate : installed) {
            long owners = installed.stream().filter(app -> app.uid == candidate.uid).count();
            if (candidate.uid >= 10000 && owners == 1) apps.add(candidate);
            if (apps.size() == 2) break;
        }
        assertEquals("Emulator needs two apps with distinct UIDs", 2, apps.size());
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false);
        defaults.put("app_filter_default_activity", 0);
        defaults.put("app_filter_default_access", 0);
        defaults.put("app_filter_default_type", 0);
        defaults.put("app_filter_default_no_internet", true);
        defaults.put("app_filter_default_disabled", true);
        defaults.put(CompactDomainLists.ENABLED, true);
        defaults.put(CompactDomainLists.ROWS, 2);
        SharedPreferences.Editor edit = prefs.edit();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            savedPreferences.put(entry.getKey(), prefs.getAll().get(entry.getKey()));
            put(edit, entry.getKey(), entry.getValue());
        }
        edit.commit();
    }

    @After public void cleanup() {
        onMain(() -> {
            for (Activity open : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)))
                if (open instanceof ActivityMain) open.finish();
            if (activity != null) activity.finish();
            SharedPreferences.Editor edit = prefs.edit();
            for (Map.Entry<String, Object> entry : savedPreferences.entrySet())
                put(edit, entry.getKey(), entry.getValue());
            edit.commit();
        });
        db.getWritableDatabase().delete("access", "daddr LIKE ?", new String[]{"%" + SUFFIX});
    }

    // Test: Main-screen search finds older domains, keeps app membership with local filters,
    // and refreshes pending outcomes while preserving its query through recreation.
    // Why: The removed screen's tests must exercise the current app rows and live domain adapters.
    @Test public void searchesMainScreenAndKeepsLiveOutcomes() {
        String domain = "images" + SUFFIX;
        insert(apps.get(0).uid, domain, 443, 100, 1);
        long latest = insert(apps.get(1).uid, domain, 443, 300, 0);
        insert(apps.get(1).uid, domain, 80, 200, 1);
        for (int i = 0; i < 260; i++) insert(apps.get(0).uid, "unrelated-" + i + SUFFIX, 443, 1000 + i, 1);
        openSearch();
        query("HTTPS://IMAGES.MW-SEARCH-TEST.INVALID:443/path");
        await(() -> displayedApps().size() == 2);
        showApp(apps.get(0));
        await(() -> entries(apps.get(0)).size() == 1);
        assertEquals(1, entries(apps.get(0)).get(0).allowed);
        showApp(apps.get(1));
        await(() -> entries(apps.get(1)).size() == 2);
        assertEquals(0, entries(apps.get(1)).get(0).allowed);
        db.setAccessPending(Long.toString(latest), 1);
        await(() -> entries(apps.get(1)).get(0).pending == 1);
        onMain(() -> holder(apps.get(1)).domainSearch.setText("no-local-match"));
        await(() -> entries(apps.get(1)).isEmpty());
        assertEquals("A local search must not remove the matching app", 2, displayedApps().size());
        onMain(() -> assertEquals(context.getString(R.string.list_no_domains_query_main, "no-local-match"),
                holder(apps.get(1)).domainStatus.getText().toString()));
        query("no-match" + SUFFIX);
        await(() -> displayedApps().isEmpty());
        findText(context.getString(R.string.list_no_domains_across_apps));
        query(domain);
        await(() -> displayedApps().size() == 2);
        Activity beforeRecreation = activity;
        onMain(() -> activity.recreate());
        await(() -> {
            onMain(() -> {
                for (Activity open : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (open instanceof ActivityMain && open != beforeRecreation) activity = open;
            });
            return activity != beforeRecreation && displayedApps().size() == 2;
        });
        onMain(() -> assertEquals(domain, ((TextView) activity.findViewById(R.id.search_src_text)).getText().toString()));
        showApp(apps.get(1));
        onMain(() -> assertEquals("no-local-match", holder(apps.get(1)).domainSearch.getText().toString()));
    }

    // Test: Copy deduplicates off-screen domains from matching apps, respecting each app's local filter.
    // Why: The main-screen copy action must follow the displayed lists rather than the removed search screen.
    @Test public void copiesDistinctDisplayedDomains() {
        List<String> domains = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String domain = String.format(Locale.ROOT, "domain-%02d", i) + SUFFIX;
            domains.add(domain);
            insert(apps.get(0).uid, domain, 443, 100, 1);
            insert(apps.get(1).uid, domain.toUpperCase(Locale.ROOT), 443, 100, 0);
        }
        insert(apps.get(1).uid, "second-only" + SUFFIX, 443, 101, 0);
        openSearch();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData previous = clipboard.getPrimaryClip();
        try {
            query(SUFFIX);
            await(() -> displayedApps().size() == 2);
            showApp(apps.get(1));
            onMain(() -> holder(apps.get(1)).domainSearch.setText("domain-01"));
            await(() -> entries(apps.get(1)).size() == 1);
            copy();
            awaitClipboard(clipboard, android.text.TextUtils.join("\n", domains));
            query("domain-01" + SUFFIX);
            await(() -> displayedApps().size() == 2);
            copy();
            awaitClipboard(clipboard, domains.get(1));
            query("no-match" + SUFFIX);
            await(() -> displayedApps().isEmpty());
            findText(context.getString(R.string.list_no_domains_across_apps));
            onMain(() -> assertFalse(activity.findViewById(R.id.copyMatchingDomains).isEnabled()));
        } finally {
            clipboard.setPrimaryClip(previous == null ? ClipData.newPlainText("", "") : previous);
        }
    }

    // Test: SQL treats wildcard punctuation literally and matches uppercase recorded domains.
    // Why: A broad wildcard search could return unrelated apps or expose a false match.
    @Test public void databaseSearchUsesLiteralCharacters() {
        insert(99193, "plain" + SUFFIX, 443, 1, 0);
        insert(99193, "percent%" + SUFFIX, 443, 1, 0);
        insert(99193, "under_score" + SUFFIX, 443, 1, 0);
        insert(99193, "UPPER" + SUFFIX, 443, 1, 0);
        for (String query : new String[]{"%", "_", "upper" + SUFFIX}) {
            try (Cursor cursor = db.searchAccessDomains(new DomainSearchQuery(query))) {
                assertEquals(query, 1, cursor.getCount());
            }
        }
    }

    private void openSearch() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk")))
            device.findObject(By.res("android", "button2")).click();
        device.wait(Until.findObject(By.desc("More options")), 5000).click();
        findText(context.getString(R.string.domain_search_title)).click();
        assertTrue(device.wait(Until.hasObject(By.res(context.getPackageName(), "search_src_text")), 5000));
    }

    private void query(String query) {
        onMain(() -> ((TextView) activity.findViewById(R.id.search_src_text)).setText(query));
    }

    private void copy() {
        onMain(() -> activity.findViewById(R.id.copyMatchingDomains).performClick());
    }

    private static void put(SharedPreferences.Editor edit, String key, Object value) {
        if (value == null) edit.remove(key);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else edit.putInt(key, (Integer) value);
    }

    private void awaitClipboard(ClipboardManager clipboard, String expected) {
        await(() -> {
            ClipData clip = clipboard.getPrimaryClip();
            return clip != null && expected.contentEquals(clip.getItemAt(0).getText());
        });
    }

    private RecyclerView list() { return activity.findViewById(R.id.rvApplication); }

    private List<Rule> displayedApps() {
        List<Rule> result = new ArrayList<>();
        onMain(() -> {
            if (list() != null && list().getAdapter() != null)
                result.addAll(readField(list().getAdapter(), "listFiltered"));
        });
        return result;
    }

    private void showApp(Rule app) {
        onMain(() -> list().scrollToPosition(position(app)));
        await(() -> holder(app) != null && holder(app).lvAccess.getAdapter() != null);
    }

    private int position(Rule app) {
        List<Rule> displayed = readField(list().getAdapter(), "listFiltered");
        for (int i = 0; i < displayed.size(); i++)
            if (app.packageName.equals(displayed.get(i).packageName)) return i;
        return -1;
    }

    private AdapterRule.ViewHolder holder(Rule app) {
        return (AdapterRule.ViewHolder) list().findViewHolderForAdapterPosition(position(app));
    }

    private List<AccessEntry> entries(Rule app) {
        List<AccessEntry> result = new ArrayList<>();
        onMain(() -> {
            AdapterRule.ViewHolder row = holder(app);
            if (row != null && row.lvAccess.getAdapter() != null)
                result.addAll(readField(row.lvAccess.getAdapter(), "entries"));
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> readField(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (List<T>) field.get(object);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private long insert(int uid, String domain, int port, long time, Integer allowed) {
        ContentValues row = new ContentValues();
        row.put("uid", uid); row.put("daddr", domain); row.put("version", 4); row.put("protocol", 6);
        row.put("dport", port); row.put("time", time); row.put("allowed", allowed);
        row.put("block", 1); row.put("pending_allow", 0);
        return db.getWritableDatabase().insertOrThrow("access", null, row);
    }

    private UiObject2 findText(String text) {
        UiObject2 view = device.wait(Until.findObject(By.text(text)), 5000);
        assertNotNull(text, view);
        return view;
    }

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue(condition.getAsBoolean());
    }

    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
