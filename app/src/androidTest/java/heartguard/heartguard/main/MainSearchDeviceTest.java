package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import android.widget.ListView;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainSearchDeviceTest {
    private Context context;
    private UiDevice device;
    private Activity activity;
    private int firstUid, secondUid;
    private static final String HOST = "main-search-test.invalid";

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        java.util.List<Rule> apps = Rule.getRules(true, context);
        firstUid = context.getPackageManager().getLaunchIntentForPackage("com.android.chrome") != null ? apps.stream().filter(r -> r.packageName.equals("com.android.chrome")).findFirst().get().uid : apps.get(0).uid;
        secondUid = apps.stream().filter(r -> r.uid != firstUid).findFirst().get().uid;
    }

    @After public void cleanup() {
        if (activity != null) onMain(() -> activity.finish());
        DatabaseHelper.getInstance(context).getWritableDatabase().delete("access", "uid IN (?, ?) AND daddr = ?",
                new String[]{Integer.toString(firstUid), Integer.toString(secondUid), HOST});
    }

    // Test: Opening a prefiltered app list and rebuilding its toolbar keep the query without focusing the editor or IME.
    // Why: Programmatic SearchView expansion used to behave like a tap and repeatedly summon the keyboard.
    @Test public void restoredAppSearchDoesNotOpenKeyboard() {
        launch("com.android.chrome");
        await(() -> "com.android.chrome".equals(query()));
        device.waitForIdle();
        assertPassiveSearch();
        onMain(() -> activity.invalidateOptionsMenu());
        device.waitForIdle();
        await(() -> "com.android.chrome".equals(query()));
        assertPassiveSearch();
        device.findObject(By.res(context.getPackageName(), "search_src_text")).click();
        await(this::keyboardVisible);
        device.pressBack();
        await(() -> !keyboardVisible());
        onMain(() -> activity.invalidateOptionsMenu());
        device.waitForIdle();
        assertPassiveSearch();
        device.findObject(By.res(context.getPackageName(), "search_src_text")).click();
        await(this::keyboardVisible);
        onMain(() -> DomainRuleDialog.show(activity, "com.android.chrome", HOST, false));
        device.wait(Until.findObject(By.res("android", "button2")), 5000).click();
        device.waitForIdle();
        assertPassiveSearch();
    }

    // Test: An incoming app filter on an already-open main screen does not start text editing.
    // Why: Notification/search navigation used a separate expansion path that did not clear IME focus.
    @Test public void incomingAppFilterDoesNotOpenKeyboard() {
        launch(null);
        assertNotNull(device.wait(Until.findObject(By.res(context.getPackageName(), "menu_search")), 5000));
        onMain(() -> ((ActivityMain) activity).onNewIntent(new Intent(context, ActivityMain.class)
                .putExtra(ActivityMain.EXTRA_SEARCH, "com.android.chrome").putExtra(ActivityMain.EXTRA_RELATED, true)));
        await(() -> "com.android.chrome".equals(query()));
        device.waitForIdle();
        assertPassiveSearch();
    }

    // Test: Hold the toolbar icon to switch modes in place, copy distinct results, and retain independent queries.
    // Why: Domain search must include apps outside the app filter and must not overwrite the user's app search.
    @Test public void modeMenuSearchesAllAppsInPlaceAndPreservesQueries() throws Exception {
        for (int uid : new int[]{firstUid, secondUid}) {
            ContentValues row = new ContentValues();
            row.put("uid", uid); row.put("version", 4); row.put("protocol", 6);
            row.put("daddr", HOST); row.put("dport", 443); row.put("time", System.currentTimeMillis());
            row.put("allowed", 0); row.put("block", -1); row.put("pending_allow", 0);
            DatabaseHelper.getInstance(context).getWritableDatabase().insertOrThrow("access", null, row);
        }
        launch(null);
        holdSearchIcon("menu_search");
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.search_mode_apps))), 5000));
        device.waitForIdle();
        device.dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null), "search-mode-menu.xml"));
        device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "search-mode-menu.png"));
        device.findObject(By.text(context.getString(R.string.search_mode_apps))).click();
        device.wait(Until.findObject(By.res(context.getPackageName(), "search_src_text")), 5000).setText("com.android.chrome");
        device.waitForIdle();
        chooseMode(true);
        assertPassiveSearch();
        onMain(() -> {
            assertEquals(View.VISIBLE, activity.findViewById(R.id.mainAppContent).getVisibility());
        });
        device.findObject(By.res(context.getPackageName(), "search_src_text")).setText(HOST);
        await(() -> resultCount() >= 2);
        onMain(() -> {
            androidx.recyclerview.widget.RecyclerView list = activity.findViewById(R.id.rvApplication);
            for (int i = 0; i < list.getAdapter().getItemCount(); i++) {
                AdapterRule.ViewHolder row = (AdapterRule.ViewHolder) list.findViewHolderForAdapterPosition(i);
                if (row != null) assertEquals(View.VISIBLE, row.llConfiguration.getVisibility());
            }
        });
        device.findObject(By.res(context.getPackageName(), "copyMatchingDomains")).click();
        device.waitForIdle();
        onMain(() -> assertEquals(HOST, ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE))
                .getPrimaryClip().getItemAt(0).getText().toString()));
        chooseMode(false);
        await(() -> "com.android.chrome".equals(query()));
        chooseMode(true);
        await(() -> HOST.equals(query()) && resultCount() >= 2);
        onMain(() -> activity.invalidateOptionsMenu());
        device.waitForIdle();
        assertEquals(HOST, query());
        assertPassiveSearch();
        device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "main-domain-search.png"));
        Activity previous = activity;
        onMain(previous::recreate);
        await(() -> {
            onMain(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof ActivityMain && candidate != previous) activity = candidate;
            });
            return activity != previous;
        });
        await(() -> HOST.equals(query()) && resultCount() >= 2);
        assertPassiveSearch();
        onMain(() -> activity.finish());
        launch(null);
        onMain(() -> {
            assertEquals(View.VISIBLE, activity.findViewById(R.id.mainAppContent).getVisibility());

        });
    }

    private void chooseMode(boolean domains) {
        holdSearchIcon("search_mag_icon");
        device.wait(Until.findObject(By.text(context.getString(domains
                ? R.string.domain_search_title : R.string.search_mode_apps))), 5000).click();
        device.waitForIdle();
    }

    private void holdSearchIcon(String id) {
        // UiAutomator 2.2 longClick releases at the exact system threshold; leave time for dispatch.
        device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000)
                .click(ViewConfiguration.getLongPressTimeout() + 500L);
    }

    private int resultCount() {
        int[] count = {-1};
        onMain(() -> count[0] = ((androidx.recyclerview.widget.RecyclerView) activity.findViewById(R.id.rvApplication)).getAdapter().getItemCount());
        return count[0];
    }

    private void launch(String query) {
        Intent intent = new Intent(context, ActivityMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ActivityMain.EXTRA_RELATED, true);
        if (query != null) intent.putExtra(ActivityMain.EXTRA_SEARCH, query);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk")))
            device.findObject(By.res("android", "button2")).click();
    }

    private void assertPassiveSearch() {
        onMain(() -> {
            View field = activity.findViewById(R.id.search_src_text);
            assertNotNull(field);
            assertFalse("Restored search must not take focus", field.hasFocus());
        });
        assertFalse("Restored search must not open the keyboard", keyboardVisible());
    }

    private String query() {
        String[] value = {null};
        onMain(() -> {
            TextView field = activity.findViewById(R.id.search_src_text);
            if (field != null) value[0] = field.getText().toString();
        });
        return value[0];
    }

    private boolean keyboardVisible() {
        boolean[] visible = {false};
        onMain(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
            visible[0] = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
        });
        return visible[0];
    }

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        fail("Expected search state did not appear");
    }

    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
