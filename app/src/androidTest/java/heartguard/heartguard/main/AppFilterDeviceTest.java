package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.FrameLayout;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppFilterDeviceTest {
    private Context context;
    private UiDevice device;
    private SharedPreferences prefs;
    private String savedPlacement;
    private final Map<String, Object> savedDefaults = new HashMap<>();

    @Before public void setup() throws Exception {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        savedPlacement = prefs.getString(Rule.PREFERENCE_STRING_LIST_CONTROLS, null);
        onMain(() -> {
            SharedPreferences.Editor edit = prefs.edit().remove(Rule.PREFERENCE_STRING_LIST_CONTROLS);
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
                if (entry.getKey().startsWith("app_filter_default_")) {
                    savedDefaults.put(entry.getKey(), entry.getValue());
                    edit.remove(entry.getKey());
                }
            edit.commit();
        });
        device.setOrientationNatural();
        launch();
    }

    @After public void cleanup() throws Exception {
        device.setOrientationNatural();
        device.unfreezeRotation();
        device.waitForIdle();
        onMain(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (activity instanceof ActivityMain || activity instanceof ActivitySettings) activity.finish();
            SharedPreferences.Editor edit = prefs.edit();
            if (savedPlacement == null) edit.remove(Rule.PREFERENCE_STRING_LIST_CONTROLS);
            else edit.putString(Rule.PREFERENCE_STRING_LIST_CONTROLS, savedPlacement);
            for (String key : prefs.getAll().keySet())
                if (key.startsWith("app_filter_default_")) edit.remove(key);
            for (Map.Entry<String, Object> entry : savedDefaults.entrySet())
                if (entry.getValue() instanceof Integer) edit.putInt(entry.getKey(), (Integer) entry.getValue());
                else edit.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            edit.commit();
        });
    }

    // Test: Settings switches between fixed controls, conditional toolbar icons and menu-only controls.
    // Why: Users must control placement without losing their search or needing to restart the app.
    @Test public void placementSettingControlsMainScreenAndPreservesSearch() {
        find("menu_search").click();
        find("search_src_text").setText("android");
        device.pressBack();
        for (int choice : new int[]{2, 1, 0}) {
            openOverflow();
            device.wait(Until.findObject(By.text(context.getString(R.string.menu_settings))), 5000).click();
            device.wait(Until.findObject(By.text(context.getString(R.string.setting_options))), 5000).click();
            device.wait(Until.findObject(By.text(context.getString(R.string.list_controls_title))), 5000).click();
            String label = context.getResources().getStringArray(R.array.list_controls_choices)[choice];
            device.wait(Until.findObject(By.text(label)), 5000).click();
            device.waitForIdle();
            assertEquals(context.getResources().getStringArray(R.array.list_controls_values)[choice],
                    prefs.getString(Rule.PREFERENCE_STRING_LIST_CONTROLS, null));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "list-controls-choice-" + choice + ".png"));
            device.findObject(By.res("android", "button1")).click();
            assertTrue(device.wait(Until.gone(By.res("android", "button1")), 5000));
            device.waitForIdle();
            device.pressBack(); // Options
            assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.settings_backup_title))), 5000));
            device.waitForIdle();
            device.pressBack(); // Settings
            assertEquals("android", find("search_src_text").getText());
            if (choice == 0) {
                find("filterApps"); find("sortApps");
            } else {
                assertFalse(device.hasObject(By.res(context.getPackageName(), "filterApps")));
                if (choice == 2) {
                    assertFalse(device.hasObject(By.res(context.getPackageName(), "menu_filter")));
                    assertFalse(device.hasObject(By.res(context.getPackageName(), "menu_sort")));
                }
                openListAction("menu_filter", R.string.list_filters);
                find("filter_access");
                closeFilter("button2");
                openListAction("menu_sort", R.string.list_sort_apps);
                assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.list_sort_recent))), 5000));
                device.findObject(By.res("android", "button1")).click();
                assertEquals("android", find("search_src_text").getText());
            }
        }
    }

    private void openOverflow() {
        device.wait(Until.findObject(By.desc("More options")), 5000).click();
    }

    private void openListAction(String id, int label) {
        UiObject2 icon = device.findObject(By.res(context.getPackageName(), id));
        if (icon != null) icon.click();
        else {
            openOverflow();
            device.wait(Until.findObject(By.text(context.getString(label))), 5000).click();
        }
    }

    // Test: Sort and Filter remain visible and usable during search, rotation and menu refreshes.
    // Why: Conditional toolbar placement previously moved these controls into overflow without warning.
    @Test public void controlsStayOnMainScreenWhileSearching() throws Exception {
        find("filterApps"); find("sortApps");
        find("menu_search").click();
        find("search_src_text").setText("android");
        device.pressBack(); // Hide the keyboard, keeping the search open.
        onMain(() -> currentActivity().invalidateOptionsMenu());
        device.waitForIdle();
        assertEquals("android", find("search_src_text").getText());
        find("filterApps").click();
        find("filter_access");
        device.findObject(By.res("android", "button2")).click();
        find("sortApps").click();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.list_sort_recent))), 5000));
        device.findObject(By.res("android", "button1")).click();
        assertEquals("android", find("search_src_text").getText());
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "main-fixed-controls.png"));
        device.setOrientationLeft();
        device.waitForIdle();
        find("filterApps"); find("sortApps");
        assertEquals("android", find("search_src_text").getText());
        find("filterApps").click();
        assertTrue(device.wait(Until.findObject(By.res("android", "button3")), 5000).isClickable());
        scrollTo("filter_reset");
        find("filter_reset");
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "filter-default-landscape.png"));
        device.findObject(By.res("android", "button2")).click();
    }

    // Test: Cancel discards edits, Apply is temporary, and only Set as default affects future launches.
    // Why: Filter defaults must be explicit and must not accidentally save search text or temporary sort.
    @Test public void filterDefaultsRequireExplicitSaveAndSurviveFreshLaunch() throws Exception {
        find("menu_search").click();
        find("search_src_text").setText("android");
        device.pressBack();
        onMain(() -> adapter().options.sort = ListSort.UID);
        find("filterApps").click();
        chooseFilters();
        closeFilter("button2");
        onMain(() -> assertFilters(adapter().options, false));

        find("filterApps").click();
        chooseFilters();
        closeFilter("button1");
        onMain(() -> {
            assertFilters(adapter().options, true);
            assertEquals("android", adapter().options.query);
            assertFilters(new AdapterRule(currentActivity(), new FrameLayout(currentActivity())).options, false);
        });

        find("filterApps").click();
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "filter-set-default.png"));
        closeFilter("button3");
        onMain(() -> {
            AppListOptions defaults = new AdapterRule(currentActivity(), new FrameLayout(currentActivity())).options;
            assertFilters(defaults, true);
            assertEquals("", defaults.query);
            assertEquals(ListSort.valid(prefs.getString(ListSort.APP_DEFAULT, ListSort.NAME), ListSort.APPS), defaults.sort);
            currentActivity().finish();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        launch();
        onMain(() -> {
            assertFilters(adapter().options, true);
            assertEquals("", adapter().options.query);
        });
        find("resetListFilters").click();
        device.setOrientationLeft();
        device.waitForIdle();
        find("filterApps");
        onMain(() -> {
            assertFilters(adapter().options, false);
            assertFilters(new AdapterRule(currentActivity(), new FrameLayout(currentActivity())).options, true);
        });
        device.setOrientationNatural();
        device.waitForIdle();
        find("filterApps").click();
        closeFilter("button3");
        onMain(() -> assertFilters(new AdapterRule(currentActivity(), new FrameLayout(currentActivity())).options, false));
    }

    private void closeFilter(String button) {
        device.findObject(By.res("android", button)).click();
        assertTrue(device.wait(Until.gone(By.res("android", "button1")), 5000));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void chooseFilters() throws Exception {
        choose("filter_access", R.array.list_access_choices, 2);
        choose("filter_activity", R.array.list_activity_choices, 1);
        choose("filter_type", R.array.list_type_choices, 1);
        scrollTo("filter_no_internet");
        if (find("filter_no_internet").isChecked()) find("filter_no_internet").click();
        scrollTo("filter_disabled");
        if (find("filter_disabled").isChecked()) find("filter_disabled").click();
    }

    private void choose(String id, int labels, int index) throws Exception {
        scrollTo(id);
        find(id).click();
        device.wait(Until.findObject(By.text(context.getResources().getStringArray(labels)[index])), 5000).click();
    }

    private void scrollTo(String id) throws Exception {
        new UiScrollable(new UiSelector().resourceId(context.getPackageName() + ":id/filter_scroll"))
                .scrollIntoView(new UiSelector().resourceId(context.getPackageName() + ":id/" + id));
    }

    private void assertFilters(AppListOptions options, boolean filtered) {
        assertEquals(filtered ? 2 : 0, options.access);
        assertEquals(filtered ? 1 : 0, options.activity);
        assertEquals(filtered ? 1 : 0, options.type);
        assertEquals(!filtered, options.includeNoInternet);
        assertEquals(!filtered, options.includeDisabled);
    }

    private void launch() {
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk")))
            device.findObject(By.res("android", "button2")).click();
        find("filterApps");
    }

    private Activity currentActivity() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof ActivityMain) return activity;
        throw new AssertionError("Main screen is not resumed");
    }

    private AdapterRule adapter() {
        return (AdapterRule) ((RecyclerView) currentActivity().findViewById(R.id.rvApplication)).getAdapter();
    }

    private UiObject2 find(String id) {
        UiObject2 view = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
        if (view == null) {
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "missing-filter-control.png"));
            try { device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "missing-filter-control.xml")); }
            catch (java.io.IOException error) { throw new AssertionError(error); }
        }
        assertNotNull("Missing visible control: " + id, view);
        return view;
    }

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
