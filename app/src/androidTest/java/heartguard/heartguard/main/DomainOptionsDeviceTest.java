package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
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
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DomainOptionsDeviceTest {
    private static final int UID = 99181;
    private Context context;
    private Activity activity;
    private UiDevice device;
    private RecyclerView list;
    private AdapterRule adapter;
    private List<Rule> apps;
    private android.content.SharedPreferences preferences;
    private Boolean oldCompact;
    private Integer oldRows;

    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        oldCompact = preferences.contains(CompactDomainLists.ENABLED) ? preferences.getBoolean(CompactDomainLists.ENABLED, false) : null;
        oldRows = preferences.contains(CompactDomainLists.ROWS) ? preferences.getInt(CompactDomainLists.ROWS, 5) : null;
        preferences.edit().remove(CompactDomainLists.ENABLED).remove(CompactDomainLists.ROWS).commit();
        try (android.database.Cursor rows = DatabaseHelper.getInstance(context).getAccess(UID)) {
            assertEquals("Synthetic UID must be unused", 0, rows.getCount());
        }
    }

    @After public void cleanup() {
        if (activity != null) onMain(() -> {
            if (adapter != null) adapter.dispose();
            activity.finish();
        });
        DatabaseHelper.getInstance(context).getWritableDatabase().delete("access", "uid = ? AND daddr LIKE ?",
                new String[]{Integer.toString(UID), "%.options-test.invalid"});
        android.content.SharedPreferences.Editor edit = preferences.edit();
        if (oldCompact == null) edit.remove(CompactDomainLists.ENABLED); else edit.putBoolean(CompactDomainLists.ENABLED, oldCompact);
        if (oldRows == null) edit.remove(CompactDomainLists.ROWS); else edit.putInt(CompactDomainLists.ROWS, oldRows);
        edit.commit();
    }

    // Test: Open the app options with a real tap, scroll through long domain lists, and retain the panel after refresh.
    // Why: A long nested domain list must not clip or discard controls for the enclosing app.
    @Test public void optionsRemainReachableWithLongDomainList() {
        launch(250);
        assertNotNull(device.wait(Until.findObject(By.res(context.getPackageName(), "appOptionsButton")), 5000));
        device.findObject(By.res(context.getPackageName(), "appOptionsButton")).click();
        await(() -> holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.VISIBLE);
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "long-options-top.png"));
        onMain(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(1, list.getHeight() * 3 / 4));
        device.waitForIdle();
        await(() -> holder() != null);
        onMain(() -> {
            View options = holder().itemView.findViewById(R.id.appOptions);
            Rect bounds = new Rect();
            assertTrue("Expanded options must occupy visible space before the next app", options.getGlobalVisibleRect(bounds));
            assertTrue("Options must have measurable height", bounds.height() > 0);
        });
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "long-options-bottom.png"));
        onMain(() -> adapter.set(new ArrayList<>(apps)));
        await(() -> holder() != null && holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.VISIBLE);
        onMain(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0));
        device.waitForIdle();
        device.findObject(By.res(context.getPackageName(), "appOptionsButton")).click();
        await(() -> holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.GONE);
    }

    // Test: Capped domain rows scroll independently, expose the app options and shrink for short filtered results.
    // Why: Compact mode must bound height without losing domains, stealing taps or leaving empty space.
    @Test public void compactListScrollsAndShortResultsShrink() {
        preferences.edit().putBoolean(CompactDomainLists.ENABLED, true).putInt(CompactDomainLists.ROWS, 3).commit();
        launch(250);
        device.waitForIdle();
        int[] originalTop = new int[1];
        onMain(() -> {
            DomainListView domains = (DomainListView) holder().lvAccess;
            assertTrue(domains.getHeight() <= domains.maximumHeight());
            assertTrue(domains.canScrollVertically(1));
            assertTrue(domains.isVerticalScrollBarEnabled());
            assertFalse(domains.isScrollbarFadingEnabled());
            originalTop[0] = holder().itemView.getTop();
        });
        Rect bounds = device.findObject(By.res(context.getPackageName(), "lvAccess")).getVisibleBounds();
        device.swipe(bounds.centerX(), bounds.bottom - 12, bounds.centerX(), bounds.top + 12, 35);
        await(() -> ((LinearLayoutManager) holder().lvAccess.getLayoutManager()).findFirstVisibleItemPosition() > 0);
        onMain(() -> assertEquals("Scrolling domains must not move the app header", originalTop[0], holder().itemView.getTop()));
        device.findObject(By.res(context.getPackageName(), "appOptionsButton")).click();
        assertNotNull(device.wait(Until.findObject(By.res(context.getPackageName(), "appPauseButton")), 5000));
        onMain(() -> {
            AdapterRule.ViewHolder app = holder();
            assertEquals(app.itemView.findViewById(R.id.appPauseButton).getParent(), app.btnClearAccess.getParent());
            assertEquals(app.ibSettings.getParent(), app.btnClearAccess.getParent());
            app.ivLive.performClick();
            assertFalse(adapter.isLive());
            assertEquals(context.getString(R.string.live_updates_paused_logging), app.tvLogging.getText().toString());
            assertTrue(RulesManager.getInstance(context).getPreferenceLogApp(context));
            assertTrue(RulesManager.getInstance(context).getPreferenceFilter(context));
            app.ivLive.performClick();
            assertTrue(adapter.isLive());
        });
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "compact-options.png"));
        onMain(() -> holder().domainSearch.setText("domain-249.options-test.invalid"));
        await(() -> holder().lvAccess.getAdapter().getItemCount() == 1);
        device.waitForIdle();
        onMain(() -> {
            assertTrue(holder().lvAccess.getHeight() < ((DomainListView) holder().lvAccess).maximumHeight());
            assertFalse(holder().lvAccess.canScrollVertically(1));
        });
    }

    // Test: Preview opens full screen with its title and Back arrow, returns to the draft and saves only on Save.
    // Why: Trying a fake app must not mutate real preferences or trap users away from the height control.
    @Test public void fullScreenPreviewReturnsToUnsavedHeightChoice() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, ActivitySettings.class)
                .putExtra("open_display_settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.wait(Until.findObject(By.text(context.getString(R.string.compact_domains_title))), 5000).click();
        device.wait(Until.findObject(By.res(context.getPackageName(), "compact_domains_enabled")), 5000).click();
        onMain(() -> {
            CompactDomainListDialog dialog = (CompactDomainListDialog) ((androidx.appcompat.app.AppCompatActivity) activity)
                    .getSupportFragmentManager().findFragmentByTag(CompactDomainLists.ENABLED);
            ((android.widget.SeekBar) dialog.requireDialog().findViewById(R.id.compact_domains_rows)).setProgress(4);
        });
        device.findObject(By.res(context.getPackageName(), "compact_domains_preview")).click();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.compact_domains_preview))), 5000));
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.compact_domains_example))), 5000));
        assertFalse(preferences.getBoolean(CompactDomainLists.ENABLED, false));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "compact-full-screen-preview.png"));
        device.findObject(By.desc("Navigate up")).click();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.compact_domains_height, 6))), 5000));
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "compact-height-settings.png"));
        device.findObject(By.res("android", "button2")).click();
        assertTrue(device.wait(Until.gone(By.res(context.getPackageName(), "compact_domains_enabled")), 5000));
        device.waitForIdle();
        assertFalse(preferences.getBoolean(CompactDomainLists.ENABLED, false));
        device.findObject(By.text(context.getString(R.string.compact_domains_title))).click();
        device.wait(Until.findObject(By.res(context.getPackageName(), "compact_domains_enabled")), 5000).click();
        device.findObject(By.res("android", "button1")).click();
        await(() -> preferences.getBoolean(CompactDomainLists.ENABLED, false));
        assertEquals(5, CompactDomainLists.rows(preferences));
    }

    private void launch(int count) {
        for (int i = 0; i < count; i++) {
            ContentValues row = new ContentValues();
            row.put("uid", UID); row.put("version", 4); row.put("protocol", 6);
            row.put("daddr", "domain-" + i + ".options-test.invalid"); row.put("dport", 443);
            row.put("time", System.currentTimeMillis() - i * 1000L);
            row.put("allowed", 0); row.put("block", 1); row.put("pending_allow", 0);
            DatabaseHelper.getInstance(context).getWritableDatabase().insertOrThrow("access", null, row);
        }
        apps = new ArrayList<>(Rule.getRules(true, context).subList(0, 3));
        apps.get(0).uid = UID;
        apps.get(1).uid = UID + 1;
        apps.get(2).uid = UID + 2;
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityGlobalWhitelist.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            list = new RecyclerView(activity);
            list.setLayoutManager(new LinearLayoutManager(activity));
            activity.setContentView(list);
            adapter = new AdapterRule(activity, list);
            list.setAdapter(adapter);
            Bundle state = new Bundle();
            ArrayList<String> expanded = new ArrayList<>();
            expanded.add(apps.get(0).packageName);
            state.putStringArrayList("expanded", expanded);
            adapter.restoreViewState(state);
            adapter.options.sort = ListSort.UID;
            adapter.set(new ArrayList<>(apps));
        });
        await(() -> holder() != null && holder().lvAccess.getAdapter() != null
                && holder().lvAccess.getAdapter().getItemCount() == count);
    }

    private AdapterRule.ViewHolder holder() {
        return (AdapterRule.ViewHolder) list.findViewHolderForAdapterPosition(0);
    }

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        boolean[] result = {false};
        do {
            onMain(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out waiting for app options");
    }

    private void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
