package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GlobalListsDeviceTest {
    private Context context;
    private UiDevice device;
    private Activity activity;
    private static final String DOMAIN = "preview.mw-global-lists-test.invalid";

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertEquals(0, RuleDelay.read(context).seconds);
        assertFalse(hasRule("allow host:" + DOMAIN)); assertFalse(hasRule("blockdomain " + DOMAIN));
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk")))
            device.findObject(By.res("android", "button2")).click();
    }
    @After public void cleanup() {
        if (activity != null) onMain(() -> activity.finish());
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRules()) {
            while (cursor.moveToNext()) {
                String rule = cursor.getString(cursor.getColumnIndexOrThrow("ruletext"));
                if (rule.endsWith(DOMAIN)) ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
        }
        DatabaseHelper.getInstance(context).removeRulesById(ids.toArray(new Long[0]));
        RulesManager.getInstance(context).startTrueTimeRequest(context);
    }

    // Test: A landscape global-list dialog scrolls its content while Done remains reachable.
    // Why: The domain entry form and list must fit on short screens without hiding the dialog's exit.
    @Test public void landscapeDialogKeepsControlsReachable() throws Exception {
        try {
            device.setOrientationLeft();
            device.waitForIdle();
            device.wait(Until.findObject(By.desc("More options")), 5000).click();
            findText(context.getString(R.string.global_lists)).click();
            findText(context.getString(R.string.global_whitelist)).click();
            assertTrue(findText(context.getString(R.string.list_done).toUpperCase()).isClickable());
            new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector()
                    .resourceId(context.getPackageName() + ":id/global_list_scroll"))
                    .scrollIntoView(new androidx.test.uiautomator.UiSelector()
                            .resourceId(context.getPackageName() + ":id/whitelist_add"));
            assertNotNull(find("whitelist_add"));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "global-list-landscape.png"));
            findText(context.getString(R.string.list_done).toUpperCase()).click();
            findText(context.getString(R.string.global_lists));
            device.pressBack();
        } finally {
            device.setOrientationNatural();
            device.unfreezeRotation();
            device.waitForIdle();
        }
    }

    // Test: The legend displays both global rule badges and their allowed/blocked labels.
    // Why: Users must be able to distinguish the new globe icons from app-specific rules.
    @Test public void legendExplainsBothGlobalBadges() throws Exception {
        device.wait(Until.findObject(By.desc("More options")), 5000).click();
        findText(context.getString(R.string.menu_legend)).click();
        new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector().scrollable(true))
                .scrollIntoView(new androidx.test.uiautomator.UiSelector()
                        .description(context.getString(R.string.domain_globally_blocked)));
        assertTrue(device.hasObject(By.desc(context.getString(R.string.domain_globally_allowed))));
        assertTrue(device.hasObject(By.desc(context.getString(R.string.domain_globally_blocked))));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "global-badges-legend.png"));
        device.pressBack();
    }

    // Test: Both lists add/remove global rules, and Done or Back returns to the Global lists menu.
    // Why: Leaving either list must keep its parent menu reachable without changing rule behavior.
    @Test public void menuDialogsAddAndRemoveBothKindsOfGlobalRule() throws Exception {
        for (boolean blocked : new boolean[]{true, false}) {
            device.wait(Until.findObject(By.desc("More options")), 5000).click();
            findText(context.getString(R.string.global_lists)).click();
            findText(context.getString(blocked ? R.string.global_blacklist : R.string.global_whitelist)).click();
            find("whitelist_input").click();
            find("whitelist_input").setText("https://" + DOMAIN + "/page");
            device.waitForIdle();
            device.pressBack();
            find("whitelist_add").click();
            String rule = blocked ? "blockdomain " + DOMAIN : "allow host:" + DOMAIN;
            await(() -> hasRule(rule));
            String activeEntry = DOMAIN + "\n" + context.getString(R.string.whitelist_active);
            assertTrue(device.wait(Until.hasObject(By.text(activeEntry)), 5000));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), blocked ? "global-blacklist.png" : "global-whitelist.png"));
            // Resolve the current row when clicking, after the pending-to-active transition.
            device.findObject(new androidx.test.uiautomator.UiSelector().text(activeEntry)).click();
            device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
            await(() -> !hasRule(rule));
            findText(context.getString(R.string.list_done).toUpperCase()).click();
            findText(context.getString(R.string.global_lists));
            findText(context.getString(R.string.global_blacklist));
            findText(context.getString(R.string.global_whitelist));
            findText(context.getString(blocked ? R.string.global_blacklist : R.string.global_whitelist)).click();
            find("whitelist_input");
            device.pressBack();
            findText(context.getString(R.string.global_lists));
            findText(context.getString(R.string.global_blacklist));
            findText(context.getString(R.string.global_whitelist));
            device.pressBack();
            assertTrue(device.wait(Until.gone(By.text(context.getString(R.string.global_lists))), 5000));
        }
    }

    // Test: An active app query, expansion and domain query survive a real rule update and list reload.
    // Why: Allowing a domain previously cleared the filter and forced the user back through the app list.
    @Test public void searchesSurviveRuleChanges() {
        device.wait(Until.findObject(By.res(context.getPackageName(), "menu_search")), 5000).click();
        device.wait(Until.findObject(By.res(context.getPackageName(), "search_src_text")), 5000).setText("com.android.chrome");
        device.pressBack();
        device.waitForIdle();
        onMain(() -> {
            androidx.recyclerview.widget.RecyclerView list = activity.findViewById(R.id.rvApplication);
            assertEquals("Chrome search must have one result", 1, list.getAdapter().getItemCount());
            AdapterRule.ViewHolder row = (AdapterRule.ViewHolder) list.findViewHolderForAdapterPosition(0);
            assertNotNull("Search result must be bound", row);
            assertEquals("Single search result must be expanded", android.view.View.VISIBLE, row.llConfiguration.getVisibility());
            assertTrue("Expanded controls must have measured height; item=" + row.itemView.getHeight()
                    + ", configuration=" + row.llConfiguration.getHeight(), row.llConfiguration.getHeight() > 0);
            activity.invalidateOptionsMenu();
        });
        device.waitForIdle();
        await(() -> "com.android.chrome".equals(text(R.id.search_src_text)));
        scrollTo("domainSearch");
        find("domainSearch").click();
        device.waitForIdle();
        find("domainSearch").setText("review-query");
        device.waitForIdle();
        device.pressBack();
        RulesManager.getInstance(context).queueRuleText(context, "allow host:" + DOMAIN);
        await(() -> hasRule("allow host:" + DOMAIN));
        device.waitForIdle();
        await(() -> "com.android.chrome".equals(text(R.id.search_src_text)));
        assertEquals("review-query", text(R.id.domainSearch));
        assertNotNull(find("domainSearch"));
        assertTrue(find("listSummary").getText().startsWith("1 of "));
    }

    // Test: Each global list finds a covering parent from a URL and removes the selected filtered rule.
    // Why: Filtering must retain the correct rule identity and must not remove a different, hidden entry.
    @Test public void globalSearchFindsParentsAndRemovesOnlySelectedRule() throws Exception {
        for (boolean blocked : new boolean[]{true, false}) {
            String prefix = blocked ? "blockdomain " : "allow host:";
            String other = "other." + DOMAIN;
            RulesManager.getInstance(context).queueRuleText(context, prefix + other);
            RulesManager.getInstance(context).queueRuleText(context, prefix + DOMAIN);
            await(() -> hasRule(prefix + other) && hasRule(prefix + DOMAIN));
            device.wait(Until.findObject(By.desc("More options")), 5000).click();
            findText(context.getString(R.string.global_lists)).click();
            findText(context.getString(blocked ? R.string.global_blacklist : R.string.global_whitelist)).click();
            searchGlobal("HTTPS://IMAGES." + DOMAIN.toUpperCase(java.util.Locale.ROOT) + "/path");
            scrollGlobalTo("whitelist_entries");
            String match = DOMAIN + "\n" + context.getString(R.string.whitelist_active)
                    + " \u00b7 " + context.getString(R.string.global_search_parent);
            UiObject2 row = findText(match);
            device.takeScreenshot(new File(context.getExternalFilesDir(null), blocked
                    ? "global-blacklist-search.png" : "global-whitelist-search.png"));
            row.click();
            findText(DOMAIN);
            device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
            await(() -> !hasRule(prefix + DOMAIN));
            assertTrue("Hidden sibling rule must remain", hasRule(prefix + other));
            scrollGlobalTo("whitelist_empty");
            findText(context.getString(R.string.global_search_empty));
            searchGlobal("");
            scrollGlobalTo("whitelist_entries");
            findText(other + "\n" + context.getString(R.string.whitelist_active));
            findText(context.getString(R.string.list_done).toUpperCase()).click();
            findText(context.getString(R.string.global_lists));
            device.pressBack();
        }
    }

    private void searchGlobal(String query) throws Exception {
        scrollGlobalTo("global_list_search");
        UiObject2 field = find("global_list_search").findObject(By.res(context.getPackageName(), "search_src_text"));
        field.click();
        field.setText(query);
        device.waitForIdle();
        device.pressBack();
        device.waitForIdle();
    }

    private void scrollGlobalTo(String id) throws Exception {
        new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector()
                .resourceId(context.getPackageName() + ":id/global_list_scroll"))
                .scrollIntoView(new androidx.test.uiautomator.UiSelector()
                        .resourceId(context.getPackageName() + ":id/" + id));
        device.waitForIdle();
    }

    private void scrollTo(String id) {
        if (device.hasObject(By.res(context.getPackageName(), id))) return;
        try {
            new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector()
                    .resourceId(context.getPackageName() + ":id/rvApplication"))
                    .scrollIntoView(new androidx.test.uiautomator.UiSelector().resourceId(context.getPackageName() + ":id/" + id));
        } catch (androidx.test.uiautomator.UiObjectNotFoundException failure) { throw new AssertionError(failure); }
    }
    private UiObject2 find(String id) {
        UiObject2 view = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
        if (view == null) {
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "missing-" + id + ".png"));
            try { device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "missing-" + id + ".xml")); }
            catch (java.io.IOException ignored) { }
        }
        assertNotNull(id, view); return view;
    }
    private UiObject2 findText(String text) {
        UiObject2 view = device.wait(Until.findObject(By.text(text)), 5000);
        assertNotNull(text, view); return view;
    }
    private boolean hasRule(String text) {
        try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(text)) { return cursor.getCount() > 0; }
    }
    private String text(int id) {
        String[] result = {null};
        onMain(() -> {
            android.widget.TextView field = activity.findViewById(id);
            if (field != null) result[0] = field.getText().toString();
        });
        return result[0];
    }
    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue(condition.getAsBoolean());
    }
    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
