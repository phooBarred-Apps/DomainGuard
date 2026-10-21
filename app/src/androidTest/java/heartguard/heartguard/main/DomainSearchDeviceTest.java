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
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.widget.SearchView;
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
import java.io.File;
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
    private boolean hadHidden, oldHidden;
    private Integer oldActivityFilter;

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        db = DatabaseHelper.getInstance(context);
        try (Cursor cursor = db.searchAccessDomains(new DomainSearchQuery(SUFFIX))) {
            assertEquals("Test domains must not already have traffic", 0, cursor.getCount());
        }
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        hadHidden = prefs.contains(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS);
        oldHidden = prefs.getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false);
        oldActivityFilter = prefs.contains("app_filter_default_activity")
                ? prefs.getInt("app_filter_default_activity", 0) : null;
    }

    @After public void cleanup() {
        onMain(() -> {
            for (Activity open : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)))
                if (open instanceof ActivityMain || open instanceof ActivityDomainSearch) open.finish();
            if (activity != null) activity.finish();
            SharedPreferences.Editor edit = prefs.edit();
            if (hadHidden) edit.putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, oldHidden);
            else edit.remove(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS);
            if (oldActivityFilter == null) edit.remove("app_filter_default_activity");
            else edit.putInt("app_filter_default_activity", oldActivityFilter);
            edit.commit();
        });
        db.getWritableDatabase().delete("access", "daddr LIKE ?", new String[]{"%" + SUFFIX});
    }

    // Test: Search finds older attempts across apps despite hide/filter settings, groups ports, and shows live outcomes.
    // Why: Reusing the per-app limit or ignoring allowed/null values would give incomplete or misleading results.
    @Test public void searchesAllAppsAndKeepsLatestOutcomes() throws Exception {
        int firstUid = context.getApplicationInfo().uid;
        int secondUid = context.getPackageManager().getApplicationInfo("com.android.settings", 0).uid;
        String domain = "images" + SUFFIX;
        insert(firstUid, domain, 443, 100, 1);
        long latest = insert(secondUid, domain, 443, 300, 0);
        insert(secondUid, domain, 80, 200, 1);
        insert(secondUid, "unknown" + SUFFIX, 443, 100, null);
        for (int i = 0; i < 260; i++) insert(firstUid, "unrelated-" + i + SUFFIX, 443, 1000 + i, 1);
        onMain(() -> prefs.edit().putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, true)
                .putInt("app_filter_default_activity", 2).commit());
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk")))
            device.findObject(By.res("android", "button2")).click();
        device.wait(Until.findObject(By.desc("More options")), 5000).click();
        findText(context.getString(R.string.domain_search_title)).click();
        await(() -> {
            onMain(() -> {
                for (Activity open : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (open instanceof ActivityDomainSearch) activity = open;
            });
            return activity instanceof ActivityDomainSearch;
        });
        query("HTTPS://IMAGES.MW-SEARCH-TEST.INVALID:443/path");
        await(() -> rowTexts().size() == 2);
        String rows = rowTexts().toString();
        assertTrue(rows, rows.contains(context.getString(R.string.domain_search_allowed)));
        assertTrue(rows, rows.contains(context.getString(R.string.domain_search_blocked)));
        assertTrue(rows, rows.contains(context.getString(R.string.app_name)));
        assertTrue(rows, rows.contains("Settings"));
        db.setAccessPending(Long.toString(latest), 1);
        await(() -> rowTexts().toString().contains(context.getString(R.string.domain_search_pending)));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "domain-search-across-apps.png"));
        query("unknown" + SUFFIX);
        await(() -> rowTexts().size() == 1);
        assertTrue(rowTexts().toString().contains(context.getString(R.string.domain_search_unknown)));
        query("no-match" + SUFFIX);
        findText(context.getString(R.string.domain_search_empty));
        query("");
        assertEquals(0, rowTexts().size());
        findText(context.getString(R.string.domain_search_start));
        query(domain);
        await(() -> rowTexts().size() == 2);
        Activity beforeRecreation = activity;
        onMain(() -> activity.recreate());
        await(() -> {
            onMain(() -> {
                for (Activity open : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (open instanceof ActivityDomainSearch) activity = open;
            });
            return activity != beforeRecreation && rowTexts().size() == 2;
        });
        onMain(() -> assertEquals(domain, ((SearchView) activity.findViewById(R.id.domain_search)).getQuery().toString()));
    }

    // Test: Copy includes off-screen matches once per domain, ignores case, and follows the current search.
    // Why: Apps share domains; copying visible rows alone would omit results or repeat the same host.
    @Test public void copiesDistinctDomainsFromAllMatchingRows() {
        List<String> domains = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String domain = String.format(Locale.ROOT, "domain-%02d", i) + SUFFIX;
            domains.add(domain);
            insert(99193, domain, 443, 100, 1);
            insert(99194, domain.toUpperCase(Locale.ROOT), 443, 100, 0);
        }
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityDomainSearch.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData previous = clipboard.getPrimaryClip();
        try {
            assertFalse(device.wait(Until.findObject(By.res(context.getPackageName(), "domain_search_copy")), 5000).isEnabled());
            query(SUFFIX);
            await(() -> rowTexts().size() == 40);
            device.findObject(By.res(context.getPackageName(), "domain_search_copy")).click();
            awaitClipboard(clipboard, android.text.TextUtils.join("\n", domains));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "domain-search-copy.png"));
            query("domain-01" + SUFFIX);
            await(() -> rowTexts().size() == 2);
            device.findObject(By.res(context.getPackageName(), "domain_search_copy")).click();
            awaitClipboard(clipboard, domains.get(1));
            query("no-match" + SUFFIX);
            findText(context.getString(R.string.domain_search_empty));
            assertFalse(device.findObject(By.res(context.getPackageName(), "domain_search_copy")).isEnabled());
            query("");
            assertFalse(device.findObject(By.res(context.getPackageName(), "domain_search_copy")).isEnabled());
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

    private void query(String query) {
        onMain(() -> ((SearchView) activity.findViewById(R.id.domain_search)).setQuery(query, false));
    }

    private void awaitClipboard(ClipboardManager clipboard, String expected) {
        await(() -> {
            ClipData clip = clipboard.getPrimaryClip();
            return clip != null && expected.contentEquals(clip.getItemAt(0).getText());
        });
    }

    private List<String> rowTexts() {
        List<String> rows = new ArrayList<>();
        onMain(() -> {
            ListView list = activity.findViewById(R.id.domain_search_results);
            if (list == null || list.getAdapter() == null) return;
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                View row = list.getAdapter().getView(i, null, list);
                rows.add(((TextView) row.findViewById(R.id.domain_result_apps)).getText() + "\n"
                        + ((TextView) row.findViewById(R.id.domain_result_host)).getText() + "\n"
                        + ((TextView) row.findViewById(R.id.domain_result_outcome)).getText());
            }
        });
        return rows;
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
