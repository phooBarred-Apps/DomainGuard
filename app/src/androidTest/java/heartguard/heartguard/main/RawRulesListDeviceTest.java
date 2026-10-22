package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RawRulesListDeviceTest {
    private Context context;
    private Activity activity;
    private UiDevice device;
    private ListView list;
    private final List<Long> inserted = new ArrayList<>();

    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (int i = 0; i < 120; i++) insert(String.format(java.util.Locale.ROOT, "allow host:rule-%03d.raw-list-test.invalid", i));
        insert("partner name:raw-list-test totp:PREVIEWONLYSECRET");
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, ActivityRulesList.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        list = activity.findViewById(R.id.lvRulesList);
        device.waitForIdle();
    }

    @After public void cleanup() {
        if (activity != null) onMain(activity::finish);
        for (long id : inserted) DatabaseHelper.getInstance(context).getWritableDatabase().delete("rules", "_id = ?", new String[]{Long.toString(id)});
    }

    private void insert(String rule) {
        ContentValues row = new ContentValues();
        row.put("ruletext", rule); row.put("create_time", 0); row.put("enact_time", 0);
        row.put("enacted", 1); row.put("major_category", 999); row.put("minor_category", 0);
        inserted.add(DatabaseHelper.getInstance(context).getWritableDatabase().insertOrThrow("rules", null, row));
    }

    // Test: Find, previous/next, no-match and a rule refresh preserve the list and highlight the correct rule.
    // Why: Searching must not hide rules, expose partner secrets, or retarget an action after rows move.
    @Test public void findNavigatesWithoutFilteringOrExposingSecrets() throws Exception {
        int total = list.getCount();
        device.wait(Until.findObject(By.desc(context.getString(R.string.raw_find))), 5000).click();
        search("RULE-11");
        int first = highlightedPosition();
        device.findObject(By.res(context.getPackageName(), "rawNext")).click();
        awaitPosition(first + 1);
        device.findObject(By.res(context.getPackageName(), "rawPrevious")).click();
        awaitPosition(first);
        search("rule-115");
        String expected = "allow host:rule-115.raw-list-test.invalid";
        assertEquals(expected, highlightedText());
        insert("allow host:aaa.raw-list-test.invalid");
        java.lang.reflect.Method refresh = ActivityRulesList.class.getDeclaredMethod("refreshRules");
        refresh.setAccessible(true);
        onMain(() -> { try { refresh.invoke(activity); } catch (Exception failure) { throw new AssertionError(failure); } });
        device.waitForIdle();
        assertEquals(expected, highlightedText());
        assertEquals(total + 1, list.getCount());
        onMain(() -> ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(list.getWindowToken(), 0));
        device.waitForIdle();
        File directory = new File(context.getExternalFilesDir(null), "raw-list-preview"); directory.mkdirs();
        device.takeScreenshot(new File(directory, "raw-search.png"));
        device.findObject(By.text(expected)).click();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.menu_clipboard))), 5000));
        device.pressBack();
        search("no-such-domain");
        assertEquals(context.getString(R.string.raw_no_match), status());
        assertEquals(total + 1, list.getCount());
        search("PREVIEWONLYSECRET");
        assertEquals(context.getString(R.string.raw_no_match), status());
        search("rule-115");
        assertEquals(expected, highlightedText());
    }

    // Test: Drag the extreme right edge to both ends of an overflowing list.
    // Why: A visible scrollbar is useful only if it moves the list and does not open a rule's action menu.
    @Test public void edgeScrollbarDragsToBothEnds() {
        Rect bounds = device.wait(Until.findObject(By.res(context.getPackageName(), "lvRulesList")), 5000).getVisibleBounds();
        device.swipe(bounds.right - 3, bounds.top + 12, bounds.right - 3, bounds.bottom - 12, 45);
        device.waitForIdle();
        onMain(() -> assertEquals(list.getCount() - 1, list.getLastVisiblePosition()));
        assertFalse(device.hasObject(By.text(context.getString(R.string.menu_clipboard))));
        device.swipe(bounds.right - 3, bounds.bottom - 12, bounds.right - 3, bounds.top + 12, 45);
        device.waitForIdle();
        onMain(() -> assertEquals(0, list.getFirstVisiblePosition()));
    }

    // Test: Select scope headers and toggle types while preserving rules, Find and per-scope choices.
    // Why: Display filtering must never change firewall rules or confuse global rules with app rules.
    @Test public void scopeAndTypeFiltersPreserveRulesAndFind() throws Exception {
        insert("blockdomain global.raw-list-test.invalid");
        insert("- temporary seconds:300 blockdomain pending.raw-list-test.invalid");
        insert("block package:com.example.app host:local.raw-list-test.invalid");
        insert("delay 21600");
        refresh();
        int total = list.getCount();
        device.findObject(By.res(context.getPackageName(), "rawFilterTitle")).click();
        device.wait(Until.findObject(By.res(context.getPackageName(), "rawFilterGlobalMenu")), 5000).click();
        assertTrue(device.wait(Until.findObject(By.res(context.getPackageName(), "rawFilterBlock")), 5000).isChecked());
        assertTrue(device.findObject(By.res(context.getPackageName(), "rawFilterAllow")).isChecked());
        assertTrue(device.findObject(By.res(context.getPackageName(), "rawFilterOther")).isChecked());
        device.findObject(By.res(context.getPackageName(), "rawFilterAllow")).click();
        device.findObject(By.res(context.getPackageName(), "rawFilterOther")).click();
        device.waitForIdle();
        assertTrue(ruleTexts().contains("blockdomain global.raw-list-test.invalid"));
        assertTrue(ruleTexts().contains("- temporary seconds:300 blockdomain pending.raw-list-test.invalid"));
        assertFalse(ruleTexts().contains("block package:com.example.app host:local.raw-list-test.invalid"));
        assertFalse(ruleTexts().contains("delay 21600"));
        for (String rule : ruleTexts()) assertTrue(rule, rule.contains("blockdomain "));
        File directory = new File(context.getExternalFilesDir(null), "raw-list-preview"); directory.mkdirs();
        device.takeScreenshot(new File(directory, "global-filter.png"));
        int globals = list.getCount();
        device.findObject(By.res(context.getPackageName(), "rawFilterBlock")).click();
        awaitCount(0);
        device.findObject(By.res(context.getPackageName(), "rawFilterBlock")).click();
        awaitCount(globals);
        device.findObject(By.res(context.getPackageName(), "rawFilterAll")).click();
        awaitCount(total);
        assertFalse(device.hasObject(By.res(context.getPackageName(), "rawFilterBlock")));
        device.findObject(By.res(context.getPackageName(), "rawFilterGlobal")).click();
        awaitCount(globals);
        device.pressBack();
        assertEquals(context.getString(R.string.raw_filter_global_blocks), device.findObject(By.res(context.getPackageName(), "rawFilterTitle")).getText());
        device.findObject(By.desc(context.getString(R.string.raw_find))).click();
        search("global.raw-list-test.invalid");
        assertEquals("blockdomain global.raw-list-test.invalid", highlightedText());
        search("local.raw-list-test.invalid");
        assertEquals(context.getString(R.string.raw_no_match), status());
        assertEquals(globals, list.getCount());
        try (Cursor rules = DatabaseHelper.getInstance(context).getAllRulesSorted()) { assertEquals(total, rules.getCount()); }
    }

    private void refresh() throws Exception {
        java.lang.reflect.Method method = ActivityRulesList.class.getDeclaredMethod("refreshRules");
        method.setAccessible(true);
        onMain(() -> { try { method.invoke(activity); } catch (Exception error) { throw new AssertionError(error); } });
        device.waitForIdle();
    }

    private void awaitCount(int expected) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        int[] count = {-1};
        do {
            onMain(() -> count[0] = list.getCount());
            if (count[0] == expected) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("Displayed rule count after one tap", expected, count[0]);
    }

    private List<String> ruleTexts() {
        List<String> rules = new ArrayList<>();
        onMain(() -> {
            for (int i = 0; i < list.getCount(); i++) {
                Cursor cursor = (Cursor) list.getItemAtPosition(i);
                rules.add(cursor.getString(cursor.getColumnIndexOrThrow("ruletext")));
            }
        });
        return rules;
    }

    private void search(String query) {
        device.findObject(By.res(context.getPackageName(), "search_src_text")).setText(query);
        device.waitForIdle(); SystemClock.sleep(120);
    }
    private String status() { return device.findObject(By.res(context.getPackageName(), "rawSearchStatus")).getText(); }
    private int visibleMatch() {
        int[] result = {-1};
        onMain(() -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                TextView text = list.getChildAt(i).findViewById(R.id.tvRuleText);
                if (text.getText() instanceof Spanned && ((Spanned) text.getText()).getSpans(0, text.length(), BackgroundColorSpan.class).length > 0)
                    result[0] = list.getFirstVisiblePosition() + i;
            }
        });
        return result[0];
    }
    private int highlightedPosition() {
        int position = visibleMatch();
        assertTrue("A matched rule is visible and highlighted; " + status()
                + "; visible " + list.getFirstVisiblePosition() + "-" + list.getLastVisiblePosition(), position >= 0);
        return position;
    }
    private void awaitPosition(int expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (visibleMatch() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        if (visibleMatch() != expected) {
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "raw-search-failure.png"));
            java.lang.reflect.Field field = ActivityRulesList.class.getDeclaredField("matchRow"); field.setAccessible(true);
            assertEquals("Status: " + status() + "; target row: " + field.get(activity), expected, visibleMatch());
        }
    }
    private String highlightedText() {
        int position = highlightedPosition();
        String[] result = {null};
        onMain(() -> result[0] = ((TextView) list.getChildAt(position - list.getFirstVisiblePosition()).findViewById(R.id.tvRuleText)).getText().toString());
        return result[0];
    }
    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
