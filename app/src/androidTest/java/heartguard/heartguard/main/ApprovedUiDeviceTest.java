package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ApprovedUiDeviceTest {
    private Context context;
    private UiDevice device;
    private Activity activity;

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
    }
    @After public void cleanup() throws Exception {
        if (activity != null) onMain(() -> activity.finish());
        device.setOrientationNatural(); device.unfreezeRotation();
    }
    private void launch(Class<?> type) {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk"))) device.findObject(By.res("android", "button2")).click();
    }
    private UiObject2 find(String id) {
        UiObject2 found = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000);
        assertNotNull(id, found); return found;
    }
    private UiObject2 text(String value) {
        UiObject2 found = device.wait(Until.findObject(By.text(value)), 5000);
        assertNotNull(value, found); return found;
    }
    private void shot(String name) { device.waitForIdle(); device.takeScreenshot(new File(context.getExternalFilesDir(null), "approved-" + name + ".png")); }
    private void onMain(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }

    // Test: Both icon menu rows open the correct list, subdialogs preserve it, and Done/Back return to the chooser.
    // Why: Compact controls must preserve the complete global-list navigation on portrait and short screens.
    @Test public void globalMenuIconsAndNavigation() throws Exception {
        launch(ActivityMain.class);
        onMain(() -> GlobalListsDialog.choose(activity));
        assertEquals(2, device.findObjects(By.res(context.getPackageName(), "global_list_choice_icon")).size());
        shot("global-menu");
        for (int label : new int[]{R.string.global_blacklist, R.string.global_whitelist}) {
            text(context.getString(label)).click();
            find("whitelist_add").click();
            find("whitelist_input").setText("example.com");
            shot(label == R.string.global_blacklist ? "blacklist-add" : "whitelist-add");
            device.wait(Until.findObject(By.res("android", "button2")), 5000).click();
            text(context.getString(label));
            device.wait(Until.findObject(By.res("android", "button1")), 5000).click();
            text(context.getString(R.string.global_lists));
        }
        device.pressBack();
        device.setOrientationLeft(); device.waitForIdle();
        // Rotation recreates the activity, so use its visible menu for the next dialog.
        device.wait(Until.findObject(By.desc("More options")),5000).click();
        text(context.getString(R.string.global_lists)).click();
        text(context.getString(R.string.global_whitelist)).click();
        assertTrue(device.wait(Until.findObject(By.res("android", "button1")),5000).isClickable());
        assertNotNull(find("whitelist_add"));
        shot("global-landscape");
        device.pressBack(); text(context.getString(R.string.global_lists)); device.pressBack();
    }

    // Test: The real offline parent dropdown contains every requested parent; scope remains off until explicitly tapped.
    // Why: Parent selection changes domain breadth, and must not silently change the affected apps.
    @Test public void parentDropdownKeepsScopeSeparate() {
        launch(ActivityMain.class);
        onMain(() -> DomainRuleDialog.show(activity, "com.android.chrome", "foo.bar.cash-f.squarecdn.com", false));
        assertEquals(context.getString(R.string.scope_off), find("allow_new_access_global").getContentDescription());
        shot("parent-closed");
        find("domain_parent_choices").click();
        text("bar.cash-f.squarecdn.com"); text("cash-f.squarecdn.com"); text("squarecdn.com");
        assertFalse(device.hasObject(By.text("com")));
        shot("parent-open"); text("squarecdn.com").click();
        assertEquals("squarecdn.com", find("allow_new_access_daddr").getText());
        assertEquals(context.getString(R.string.scope_off), find("allow_new_access_global").getContentDescription());
        find("allow_new_access_global").click();
        assertEquals(context.getString(R.string.scope_on), find("allow_new_access_global").getContentDescription());
        text(context.getString(R.string.scope_all_apps));
        device.wait(Until.findObject(By.res("android", "button2")),5000).click();
    }

    // Test: Dragging/searching a long raw document preserves every rule and never saves or rewrites it.
    // Why: Fast navigation must not move the edit caret, open the keyboard, or drop nonmatching rules.
    @Test public void rawSearchAndScrollbarPreserveText() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 600; i++) source.append("allow host:raw-").append(i).append(".example.com\n");
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, ActivityRulesEntry.class)
                .putExtra(ActivityRulesEntry.SPECIFY_MODE, ActivityRulesEntry.MODE_SUBMIT_CHANGES)
                .putExtra("edittext", source.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        Rect editor = find("etAddMultipleRules").getVisibleBounds();
        onMain(() -> assertFalse(activity.findViewById(R.id.etAddMultipleRules).hasFocus()));
        device.swipe(editor.right-2, editor.top+15, editor.right-2, editor.bottom-20, 50);
        onMain(() -> { FastScrollEditText input=activity.findViewById(R.id.etAddMultipleRules);
            assertTrue("Scrollbar reaches later rules",input.getScrollY()>input.getHeight());
            assertEquals(source.toString(),input.getText().toString()); assertFalse(input.hasFocus()); });
        shot("raw-scroll"); find("raw_search").click();
        find("search_src_text").setText("raw-450.example.com");
        text("Line 451"); device.pressBack(); shot("raw-search");
        find("rawNext").click(); text("Line 451");
        onMain(() -> assertEquals(source.toString(),((TextView)activity.findViewById(R.id.etAddMultipleRules)).getText().toString()));
    }

    // Test: Backup help overlays its screen; Display settings is reachable directly; filter defaults remain separate from Apply.
    // Why: Reorganized controls must remain reachable and legible rather than becoming hidden nested options.
    @Test public void backupHelpDisplayAndFilters() {
        launch(ActivityDeviceBackup.class); find("backup_information").click();
        text(context.getString(R.string.backup_help_title)); shot("backup-info"); device.pressBack();
        find("backup_automatic_toggle"); onMain(() -> activity.finish());
        activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,ActivitySettings.class)
                .putExtra("open_display_settings",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        text(context.getString(R.string.timestamp_format)); shot("display-settings"); device.pressBack();
        onMain(() -> activity.finish()); launch(ActivityMain.class);
        onMain(() -> AppFilterDialog.show(activity,new AppListOptions(),()->{},()->{}));
        Rect defaults=find("filter_default").getVisibleBounds();
        Rect reset=find("filter_reset").getVisibleBounds();
        Rect apply=device.wait(Until.findObject(By.res("android","button1")),5000).getVisibleBounds();
        assertEquals(defaults.centerY(),reset.centerY()); assertTrue(defaults.bottom<=apply.top);
        shot("filter"); device.pressBack();
        onMain(() -> SortDialog.show(activity,R.string.list_sort_apps,ListSort.APPS,ListSort.BLOCKED,ListSort.APP_DEFAULT,sort->{}));
        text(context.getString(R.string.list_sort_blocked)); text(context.getString(R.string.list_sort_allowed)); shot("sort"); device.pressBack();
        onMain(() -> new RuleDelayDialog().show(((AppCompatActivity)activity).getSupportFragmentManager(),"delay"));
        find("rule_delay_input"); shot("delay"); device.pressBack();
    }
}
