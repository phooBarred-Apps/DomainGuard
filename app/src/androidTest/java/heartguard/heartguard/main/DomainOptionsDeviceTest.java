package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.content.ClipData;
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
    private Boolean oldDetails;
    private Boolean oldHideAllowed;

    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        oldCompact = preferences.contains(CompactDomainLists.ENABLED) ? preferences.getBoolean(CompactDomainLists.ENABLED, false) : null;
        oldRows = preferences.contains(CompactDomainLists.ROWS) ? preferences.getInt(CompactDomainLists.ROWS, 5) : null;
        oldDetails = preferences.contains(Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS)
                ? preferences.getBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS, false) : null;
        oldHideAllowed = preferences.contains(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS)
                ? preferences.getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false) : null;
        preferences.edit().remove(CompactDomainLists.ENABLED).remove(CompactDomainLists.ROWS)
                .remove(Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS)
                .remove(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS).commit();
        try (android.database.Cursor rows = DatabaseHelper.getInstance(context).getAccess(UID)) {
            assertEquals("Synthetic UID must be unused", 0, rows.getCount());
        }
        try (android.database.Cursor rows = DatabaseHelper.getInstance(context).getAccess(UID + 1)) {
            assertEquals("Second synthetic UID must be unused", 0, rows.getCount());
        }
    }

    @After public void cleanup() {
        if (activity != null) onMain(() -> {
            if (adapter != null) adapter.dispose();
            activity.finish();
        });
        DatabaseHelper.getInstance(context).getWritableDatabase().delete("access", "uid IN (?, ?) AND daddr LIKE ?",
                new String[]{Integer.toString(UID), Integer.toString(UID + 1), "%.options-test.invalid"});
        android.content.SharedPreferences.Editor edit = preferences.edit();
        if (oldCompact == null) edit.remove(CompactDomainLists.ENABLED); else edit.putBoolean(CompactDomainLists.ENABLED, oldCompact);
        if (oldRows == null) edit.remove(CompactDomainLists.ROWS); else edit.putInt(CompactDomainLists.ROWS, oldRows);
        if (oldDetails == null) edit.remove(Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS);
        else edit.putBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS, oldDetails);
        if (oldHideAllowed == null) edit.remove(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS);
        else edit.putBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, oldHideAllowed);
        edit.commit();
    }

    // Test: Main Copy intersects both searches, while the app's Copy button copies only that app, without duplicates.
    // Why: A tap immediately after editing must not copy domains hidden by the current local query.
    @Test public void copyUsesCurrentFiltersAndPerAppScope() {
        launch(3);
        insertDomain(UID, "domain-1.options-test.invalid", 80);
        insertDomain(UID + 1, "domain-9.options-test.invalid", 443);
        insertDomain(UID + 1, "domain-1.options-test.invalid", 443);
        onMain(() -> adapter.setDomainSearch(true, "options-test.invalid"));
        await(() -> adapter.getItemCount() == 2 && holder() != null && adapter.matchingDomainCount() == 4);
        onMain(() -> {
            holder().domainSearch.setText("domain-1");
            // Deliberately copy before the asynchronous search refresh completes.
            adapter.copyMatchingDomains();
        });
        await(() -> clipboardText().equals("domain-1.options-test.invalid\ndomain-9.options-test.invalid"));
        onMain(() -> holder().domainCopyButton.performClick());
        await(() -> clipboardText().equals("domain-1.options-test.invalid"));
        onMain(() -> holder().domainSearch.setText("not-present"));
        await(() -> holder() != null && holder().lvAccess.getAdapter().getItemCount() == 0);
        onMain(() -> adapter.copyMatchingDomains());
        await(() -> clipboardText().equals("domain-1.options-test.invalid\ndomain-9.options-test.invalid"));
        onMain(() -> {
            holder().domainSearch.setText("domain-0");
            holder().domainCopyButton.performClick();
        });
        await(() -> clipboardText().equals("domain-0.options-test.invalid"));
        ContentValues allowed = new ContentValues(); allowed.put("allowed", 1); allowed.put("block", 0);
        DatabaseHelper.getInstance(context).getWritableDatabase().update("access", allowed,
                "uid = ? AND daddr = ?", new String[]{Integer.toString(UID), "domain-0.options-test.invalid"});
        onMain(() -> {
            holder().cbHideAllowedDomains.performClick();
            adapter.copyMatchingDomains();
        });
        await(() -> clipboardText().equals("domain-1.options-test.invalid\ndomain-9.options-test.invalid"));
    }

    // Test: Copy includes the current 250-row result set, not older rows outside the displayed limit.
    // Why: The main search index can contain more domains than an app's list displays.
    @Test public void copyRespectsDisplayedRowLimit() {
        launch(250);
        for (int i = 250; i < 255; i++) insertDomain(UID, "domain-" + i + ".options-test.invalid", 443);
        onMain(() -> adapter.setDomainSearch(true, "options-test.invalid"));
        await(() -> adapter.matchingDomainCount() == 255);
        onMain(() -> adapter.copyMatchingDomains());
        await(() -> clipboardText().split("\n").length == 250);
        onMain(() -> {
            assertTrue(clipboardText().contains("domain-0.options-test.invalid"));
            assertFalse(clipboardText().contains("domain-254.options-test.invalid"));
        });
    }

    private void insertDomain(int uid, String address, int port) {
        ContentValues row = new ContentValues();
        row.put("uid", uid); row.put("version", 4); row.put("protocol", 6);
        row.put("daddr", address); row.put("dport", port); row.put("time", 1L);
        row.put("allowed", 0); row.put("block", 1); row.put("pending_allow", 0);
        DatabaseHelper.getInstance(context).getWritableDatabase().insertOrThrow("access", null, row);
    }

    private String clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        return clip == null ? "" : clip.getItemAt(0).coerceToText(activity).toString();
    }

    // Test: Details follow the default; an app can override it through refresh/rotation, while a new session resets.
    // Why: Always showing details must not prevent users hiding one app's panel or let recycled rows leak state.
    @Test public void detailsDefaultAllowsSessionOverrides() {
        preferences.edit().putBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS, true)
                .putBoolean(CompactDomainLists.ENABLED, true).commit();
        launch(50);
        onMain(() -> {
            assertEquals(View.VISIBLE, holder().itemView.findViewById(R.id.appOptions).getVisibility());
            holder().itemView.findViewById(R.id.appDomainsButton).performClick();
            adapter.set(new ArrayList<>(apps));
        });
        await(() -> holder() != null && holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.GONE);
        onMain(() -> {
            Bundle saved = adapter.saveViewState();
            adapter.dispose();
            adapter = new AdapterRule(activity, list);
            list.setAdapter(adapter);
            adapter.restoreViewState(saved);
            adapter.set(new ArrayList<>(apps));
        });
        await(() -> holder() != null && holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.GONE);
        onMain(() -> {
            Bundle saved = adapter.saveViewState();
            saved.remove("optionsExpanded");
            saved.remove("optionsCollapsed");
            adapter.dispose();
            adapter = new AdapterRule(activity, list);
            list.setAdapter(adapter);
            adapter.restoreViewState(saved);
            adapter.set(new ArrayList<>(apps));
        });
        await(() -> holder() != null && holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.VISIBLE);
        onMain(() -> adapter.setDomainSearch(true, "domain-1.options-test.invalid"));
        await(() -> adapter.getItemCount() == 1 && adapter.matchingDomainCount() == 1 && holder() != null
                && holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.GONE
                && holder().lvAccess.getAdapter().getItemCount() == 1);
        onMain(() -> assertTrue(holder().itemView.findViewById(R.id.appDomainsButton).isSelected()));
    }

    // Test: A local search or Hide allowed can empty one app's domains without removing its main-search result.
    // Why: Removing that app also removes its search field, leaving no way to correct the local query.
    @Test public void localSearchKeepsMainSearchResultWhenNoDomainsMatch() {
        launch(2);
        onMain(() -> adapter.setDomainSearch(true, "options-test.invalid"));
        await(() -> adapter.getItemCount() == 1 && adapter.matchingDomainCount() == 2 && holder() != null);
        onMain(() -> {
            if (holder().domainSearch.getVisibility() != View.VISIBLE) holder().domainSearchButton.performClick();
            holder().domainSearch.setText("domain-1");
        });
        await(() -> holder() != null && holder().lvAccess.getAdapter().getItemCount() == 1 && adapter.matchingDomainCount() == 1);
        onMain(() -> holder().domainSearch.setText("not-present"));
        await(() -> adapter.getItemCount() == 1 && holder() != null
                && holder().lvAccess.getAdapter().getItemCount() == 0
                && adapter.matchingDomainCount() == 0
                && holder().domainStatus.getText().toString().contains("not-present"));
        onMain(() -> {
            assertEquals(View.VISIBLE, holder().domainSearch.getVisibility());
            assertTrue(holder().domainSearchButton.isSelected());
            adapter.refreshTraffic();
            adapter.set(new ArrayList<>(apps));
        });
        await(() -> holder() != null && "not-present".equals(holder().domainSearch.getText().toString())
                && holder().lvAccess.getAdapter().getItemCount() == 0);
        onMain(() -> holder().domainSearch.setText(""));
        await(() -> adapter.getItemCount() == 1 && adapter.matchingDomainCount() == 2
                && holder().lvAccess.getAdapter().getItemCount() == 2);
        ContentValues allowed = new ContentValues(); allowed.put("allowed", 1); allowed.put("block", 0);
        DatabaseHelper.getInstance(context).getWritableDatabase().update("access", allowed, "uid = ?", new String[]{Integer.toString(UID)});
        Object beforeTrafficChange = field("domainRows");
        // Raw fixture writes bypass DatabaseHelper's access-change notification.
        onMain(() -> adapter.refreshTraffic());
        await(() -> field("domainRows") != beforeTrafficChange);
        onMain(() -> {
            if (!holder().cbHideAllowedDomains.isSelected()) holder().cbHideAllowedDomains.performClick();
            else adapter.refreshTraffic();
        });
        await(() -> adapter.getItemCount() == 1 && holder() != null && adapter.matchingDomainCount() == 0
                && holder().lvAccess.getAdapter().getItemCount() == 0);
        onMain(() -> holder().cbHideAllowedDomains.performClick());
        await(() -> adapter.matchingDomainCount() == 2 && holder().lvAccess.getAdapter().getItemCount() == 2);
        onMain(() -> adapter.setDomainSearch(true, "missing-main-query"));
        await(() -> adapter.getItemCount() == 0);
    }

    // Test: Local filters reuse main-search results; main-query edits reuse stats until traffic changes.
    // Why: A per-app keystroke must not trigger whole-database scans, but new traffic must still appear.
    @Test public void searchesReuseSnapshotsUntilTrafficChanges() {
        launch(3);
        onMain(() -> adapter.setDomainSearch(true, "options-test.invalid"));
        await(() -> adapter.matchingDomainCount() == 3 && holder() != null);
        Object stats = field("stats");
        onMain(() -> adapter.setDomainSearch(true, "domain-1"));
        await(() -> adapter.matchingDomainCount() == 1);
        assertSame("Changing the main query reuses app statistics", stats, field("stats"));
        Object domains = field("domainRows");
        onMain(() -> holder().domainSearch.setText("absent"));
        await(() -> holder().lvAccess.getAdapter().getItemCount() == 0 && adapter.matchingDomainCount() == 0);
        assertSame("Local search reuses all-app matches", domains, field("domainRows"));
        assertSame(stats, field("stats"));
        onMain(() -> {
            holder().domainSearch.setText("");
            holder().cbHideAllowedDomains.performClick();
        });
        await(() -> holder() != null && holder().lvAccess.getAdapter().getItemCount() == 1);
        assertSame("Hide allowed also stays local", domains, field("domainRows"));
        ContentValues row = new ContentValues();
        row.put("uid", UID); row.put("version", 4); row.put("protocol", 6); row.put("dport", 443);
        row.put("daddr", "domain-1-new.options-test.invalid"); row.put("time", System.currentTimeMillis());
        row.put("allowed", 0); row.put("block", 1); row.put("pending_allow", 0);
        DatabaseHelper.getInstance(context).getWritableDatabase().insertOrThrow("access", null, row);
        onMain(() -> adapter.refreshTraffic());
        await(() -> adapter.matchingDomainCount() == 2 && holder().lvAccess.getAdapter().getItemCount() == 2);
        assertNotSame("Traffic change reloads stats", stats, field("stats"));
        assertNotSame("Traffic change reloads main-search matches", domains, field("domainRows"));
    }

    private Object field(String name) {
        try {
            java.lang.reflect.Field field = AdapterRule.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(adapter);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    // Test: Dragging either outside gutter moves the app screen, without changing the inner domain offset.
    // Why: Several expanded apps must not trap every vertical gesture inside their compact domain lists.
    @Test public void bothSideGuttersScrollTheOuterAppList() {
        preferences.edit().putBoolean(CompactDomainLists.ENABLED, true).putInt(CompactDomainLists.ROWS, 5).commit();
        launch(250);
        for (boolean left : new boolean[]{true, false}) {
            onMain(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0));
            device.waitForIdle();
            int[] top = new int[1];
            Rect[] bounds = new Rect[1];
            onMain(() -> {
                top[0] = holder().itemView.getTop();
                Rect app = new Rect(), domains = new Rect();
                holder().itemView.getGlobalVisibleRect(app);
                holder().lvAccess.getGlobalVisibleRect(domains);
                assertTrue("Left gutter", domains.left - app.left >= CompactUi.dp(context, 16));
                assertTrue("Right gutter", app.right - domains.right >= CompactUi.dp(context, 16));
                bounds[0] = domains;
            });
            int x = left ? bounds[0].left - CompactUi.dp(context, 8) : bounds[0].right + CompactUi.dp(context, 8);
            device.swipe(x, bounds[0].bottom - 20, x, bounds[0].top + 20, 35);
            await(() -> holder() == null || holder().itemView.getTop() < top[0]);
            onMain(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0));
            device.waitForIdle();
            onMain(() -> assertEquals("Outer scrolling must not advance domains", 0,
                    ((LinearLayoutManager) holder().lvAccess.getLayoutManager()).findFirstVisibleItemPosition()));
        }
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "domain-side-gutters.png"));
    }

    // Test: Switch tabs with a real tap, retain the selected tab after refresh, and switch back to all domains.
    // Why: A long domain list must not push the controls offscreen or lose content when panels switch.
    @Test public void optionsRemainReachableWithLongDomainList() {
        launch(250);
        assertNotNull(device.wait(Until.findObject(By.res(context.getPackageName(), "appOptionsButton")), 5000));
        device.findObject(By.res(context.getPackageName(), "appOptionsButton")).click();
        await(() -> holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.VISIBLE);
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "long-options-top.png"));
        onMain(() -> {
            View options = holder().itemView.findViewById(R.id.appOptions);
            Rect bounds = new Rect();
            assertTrue("Selected panel must be immediately visible", options.getGlobalVisibleRect(bounds));
            assertTrue("Options must have measurable height", bounds.height() > 0);
            assertEquals(View.GONE, holder().llFilter.getVisibility());
            assertEquals(View.INVISIBLE, holder().itemView.findViewById(R.id.domainControls).getVisibility());
            assertTrue(holder().itemView.findViewById(R.id.appOptionsButton).isSelected());
            holder().itemView.findViewById(R.id.appOptionsButton).performClick();
            assertEquals("Selecting the active tab keeps it open", View.VISIBLE, options.getVisibility());
        });
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "long-options-bottom.png"));
        onMain(() -> adapter.set(new ArrayList<>(apps)));
        await(() -> holder() != null && holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.VISIBLE);
        onMain(() -> ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0));
        device.waitForIdle();
        device.findObject(By.res(context.getPackageName(), "appDomainsButton")).click();
        await(() -> holder().itemView.findViewById(R.id.appOptions).getVisibility() == View.GONE);
        onMain(() -> {
            assertEquals(View.VISIBLE, holder().llFilter.getVisibility());
            assertEquals(250, holder().lvAccess.getAdapter().getItemCount());
        });
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
        await(() -> holder().lvAccess.getScrollState() == RecyclerView.SCROLL_STATE_IDLE);
        onMain(() -> assertEquals("Scrolling domains must not move the app header", originalTop[0], holder().itemView.getTop()));
        int[] domainOffset = new int[1];
        onMain(() -> domainOffset[0] = holder().lvAccess.computeVerticalScrollOffset());
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
        device.findObject(By.res(context.getPackageName(), "appDomainsButton")).click();
        device.waitForIdle();
        onMain(() -> assertEquals("Switching tabs preserves domain position", domainOffset[0],
                holder().lvAccess.computeVerticalScrollOffset()));
        onMain(() -> holder().domainSearch.setText("domain-249.options-test.invalid"));
        await(() -> holder().lvAccess.getAdapter().getItemCount() == 1);
        device.waitForIdle();
        onMain(() -> {
            assertTrue(holder().lvAccess.getHeight() < ((DomainListView) holder().lvAccess).maximumHeight());
            assertFalse(holder().lvAccess.canScrollVertically(1));
        });
    }

    // Test: Appearance toggles compact mode directly; its title opens height settings and a full-screen preview.
    // Why: Changing or previewing height must not toggle compact mode or save a canceled draft.
    @Test public void fullScreenPreviewReturnsToUnsavedHeightChoice() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, ActivitySettings.class)
                .putExtra("open_display_settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.wait(Until.findObject(By.res(context.getPackageName(), "compact_domains_toggle")), 5000).click();
        await(() -> preferences.getBoolean(CompactDomainLists.ENABLED, false));
        assertFalse(device.hasObject(By.res(context.getPackageName(), "compact_domains_rows")));
        device.findObject(By.res(context.getPackageName(), "compact_domains_toggle")).click();
        await(() -> !preferences.getBoolean(CompactDomainLists.ENABLED, false));
        device.findObject(By.text(context.getString(R.string.compact_domains_title))).click();
        onMain(() -> {
            CompactDomainListDialog dialog = (CompactDomainListDialog) ((androidx.appcompat.app.AppCompatActivity) activity)
                    .getSupportFragmentManager().findFragmentByTag(CompactDomainLists.ENABLED);
            assertNull("The toggle belongs in Appearance, not this dialog",
                    dialog.requireDialog().findViewById(R.id.compact_domains_toggle));
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
        assertTrue(device.wait(Until.gone(By.res(context.getPackageName(), "compact_domains_rows")), 5000));
        device.waitForIdle();
        assertFalse(preferences.getBoolean(CompactDomainLists.ENABLED, false));
        device.findObject(By.text(context.getString(R.string.compact_domains_title))).click();
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.compact_domains_height, 5))), 5000));
        onMain(() -> {
            CompactDomainListDialog dialog = (CompactDomainListDialog) ((androidx.appcompat.app.AppCompatActivity) activity)
                    .getSupportFragmentManager().findFragmentByTag(CompactDomainLists.ENABLED);
            ((android.widget.SeekBar) dialog.requireDialog().findViewById(R.id.compact_domains_rows)).setProgress(4);
        });
        device.findObject(By.res("android", "button1")).click();
        await(() -> CompactDomainLists.rows(preferences) == 6);
        assertFalse("Saving height must not change the switch", preferences.getBoolean(CompactDomainLists.ENABLED, false));
        device.findObject(By.res(context.getPackageName(), "compact_domains_toggle")).click();
        await(() -> preferences.getBoolean(CompactDomainLists.ENABLED, false));
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
        List<Rule> available = Rule.getRules(true, context);
        apps = new ArrayList<>(available.subList(0, Math.min(30, available.size())));
        for (int i = 0; i < apps.size(); i++) apps.get(i).uid = UID + i;
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ComponentTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("Test", "clipboard-before-copy"));
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
