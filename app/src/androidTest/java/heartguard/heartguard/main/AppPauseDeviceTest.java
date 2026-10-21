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
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppPauseDeviceTest {
    private static final String PKG = "com.android.chrome";
    private static final String HOST = "app-pause-test.invalid";
    private Context context;
    private RulesManager rules;
    private DatabaseHelper db;
    private UiDevice device;
    private Activity activity;
    private int uid;
    private final List<String> owned = new ArrayList<>();
    private final java.util.Map<String, Boolean> hints = new java.util.HashMap<>();
    private Boolean previousSearch;

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context); rules = RulesManager.getInstance(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uid = context.getPackageManager().getApplicationInfo(PKG, 0).uid;
        assertEquals(0, RuleDelay.read(context).seconds);
        assertTrue(AppPause.read(context, PKG).isEmpty());
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        previousSearch = prefs.contains(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH)
                ? prefs.getBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH, false) : null;
        for (String key : new String[]{"hint_usage", "hint_fairemail", "hint_system", "hint_whitelist", "hint_push"})
            hints.put(key, prefs.contains(key) ? prefs.getBoolean(key, true) : null);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (String key : hints.keySet()) editor.putBoolean(key, false);
        editor.remove(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH);
        editor.commit();
    }
    @After public void cleanup() throws Exception {
        if (activity != null) onMain(() -> activity.finish());
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getAllRules()) {
            while (cursor.moveToNext()) {
                String text = cursor.getString(cursor.getColumnIndexOrThrow("ruletext"));
                if (owned.contains(text) || (text.startsWith("- ") && owned.contains(text.substring(2))))
                    ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
        }
        db.removeRulesById(ids.toArray(new Long[0]));
        rules.startTrueTimeRequest(context); barrier();
        android.content.SharedPreferences.Editor editor = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (String key : hints.keySet()) { if (hints.get(key) == null) editor.remove(key); else editor.putBoolean(key, hints.get(key)); }
        if (previousSearch == null) editor.remove(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH);
        else editor.putBoolean(Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH, previousSearch);
        editor.commit();
    }

    // Test: Capture before, pending, active and expired UI while enforcing the configured delay and restoring blocks.
    // Why: A temporary bypass must never start early, erase rules or remain active after its chosen duration.
    @Test public void pauseWaitsThenExpiresAndPreservesRules() throws Exception {
        add("blockdomain " + HOST);
        add("block package:" + PKG + " host:" + HOST);
        add("delay 20");
        launch();
        find("appOptionsButton").click();
        find("appPauseButton"); shot("1-before");
        find("appPauseButton").click();
        setAmount("30");
        find("app_pause_unit").click();
        String secondsLabel = context.getResources().getQuantityString(R.plurals.rule_delay_unit_seconds, 30);
        unitChoice(secondsLabel).click();
        shot("2-duration");
        android.graphics.Rect anchor = find("app_pause_unit").getVisibleBounds();
        find("app_pause_unit").click();
        device.waitForIdle();
        android.graphics.Rect firstChoice = unitChoice(secondsLabel).getVisibleBounds();
        assertTrue("Unit choices must open below the selector", firstChoice.top >= anchor.bottom);
        shot("3-units"); unitChoice(secondsLabel).click();
        owned.add(AppPauseRule.text(PKG, 30));
        device.findObject(By.res("android", "button1")).click();
        await(() -> !AppPause.read(context, PKG).isEmpty(), 5000);
        assertFalse(rules.isUidPaused(uid));
        find("appPauseStatus"); shot("4-waiting");
        assertDns(true); assertPacket(false);
        await(() -> rules.isUidPaused(uid), 30000);
        device.wait(Until.findObject(By.textStartsWith("Paused until")), 5000);
        shot("5-during");
        assertDns(false); assertPacket(true);
        assertFalse(rules.isUidPaused(99199));
        // Rebuilding the rule snapshot must retain the same saved deadline.
        long deadline = AppPause.read(context, PKG).get(0).time;
        rules.startTrueTimeRequest(context); barrier();
        assertEquals(deadline, AppPause.read(context, PKG).get(0).time);
        find("appPauseStatus").click(); find("app_pause_icon");
        assertTrue(find("app_pause_state_action").getVisibleBounds().top >= find("app_pause_status").getVisibleBounds().bottom);
        assertFalse(device.hasObject(By.res("android", "button3")));
        shot("6-resume"); device.findObject(By.res("android", "button2")).click();
        await(() -> !rules.isUidPaused(uid), 40000);
        await(() -> AppPause.read(context, PKG).isEmpty(), 5000);
        device.wait(Until.gone(By.res(context.getPackageName(), "appPauseStatus")), 5000);
        shot("7-after");
        assertDns(true); assertPacket(false);
        assertTrue(exists("blockdomain " + HOST));
        assertTrue(exists("block package:" + PKG + " host:" + HOST));
    }

    // Test: The seven-day maximum and invalid inputs apply to raw rules as well as the duration selector.
    // Why: Editing rule text or overflowing an input must not create an unlimited bypass.
    @Test public void boundsAndEarlyResume() throws Exception {
        assertNotNull(MyRule.getRuleFromText(context, AppPauseRule.text(PKG, 604800)));
        for (String seconds : new String[]{"0", "604801", "999999999999", "-1"})
            assertNull(MyRule.getRuleFromText(context, "pause package:" + PKG + " seconds:" + seconds));
        add(AppPauseRule.text(PKG, 604800));
        assertTrue(rules.isUidPaused(uid));
        AppPause.resume(context, PKG); barrier();
        assertFalse(rules.isUidPaused(uid));
        assertTrue(AppPause.read(context, PKG).isEmpty());
    }

    // Test: A 35-minute delay keeps the app filtered and exposes the pending start time and cancellation.
    // Why: The requested preview must show the actual waiting UI, not an already-active bypass.
    @Test public void previewThirtyFiveMinuteDelay() throws Exception {
        add("delay 2100");
        launch(); find("appOptionsButton").click(); find("appPauseButton");
        assertFalse(device.hasObject(By.res(context.getPackageName(), "domainSearch")));
        find("domainSearchButton");
        shot("8-before-35"); find("appPauseButton").click();
        find("app_pause_icon"); shot("9-duration-35");
        owned.add(AppPauseRule.text(PKG, 300));
        device.findObject(By.res("android", "button1")).click();
        find("appPauseStatus"); shot("10-waiting-35");
        List<AppPause.Entry> entries = AppPause.read(context, PKG);
        assertEquals(1, entries.size()); assertFalse(entries.get(0).active);
        assertTrue(entries.get(0).time - System.currentTimeMillis() > 2080000);
        assertTrue(find("appPauseStatus").getText().startsWith("5 minute pause starts at "));
        assertFalse(rules.isUidPaused(uid));
        find("appPauseStatus").click(); find("app_pause_icon"); shot("11-pending-details-35");
        assertTrue(find("app_pause_state_action").getVisibleBounds().top >= find("app_pause_status").getVisibleBounds().bottom);
        assertFalse(device.hasObject(By.res("android", "button3")));
        find("app_pause_state_action").click(); barrier();
        await(() -> AppPause.read(context, PKG).isEmpty(), 5000);
        assertFalse(rules.isUidPaused(uid));
    }
    // Test: The Legend explains the pause icon with the approved wording and a visible scrollbar.
    // Why: The new per-app control must be identifiable alongside the existing app controls.
    @Test public void legendExplainsPause() throws Exception {
        launch();
        device.wait(Until.findObject(By.desc("More options")), 5000).click();
        text(context.getString(R.string.menu_legend)).click();
        new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector().scrollable(true))
                .scrollIntoView(new androidx.test.uiautomator.UiSelector()
                        .text(context.getString(R.string.app_pause_legend)));
        text("Temporarily pause DomainGuard for an app");
        shot("12-legend");
        device.pressBack();
    }

    // Test: Capture action, cancellation and dismissal colors in real dark-theme pause dialogs.
    // Why: A palette contrast calculation does not prove that the actual dialog uses those colors.
    @Test public void previewDarkDialogs() throws Exception {
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        Boolean previousDark = prefs.contains("dark_theme") ? prefs.getBoolean("dark_theme", false) : null;
        try {
            prefs.edit().putBoolean("dark_theme", true).commit();
            add("delay 2100");
            launch(); find("appOptionsButton").click(); find("appPauseButton").click();
            find("app_pause_icon"); shot("13-dark-duration");
            owned.add(AppPauseRule.text(PKG, 300));
            device.findObject(By.res("android", "button1")).click();
            find("appPauseStatus").click();
            find("app_pause_icon"); shot("14-dark-pending");
            find("app_pause_state_action").click();
            await(() -> AppPause.read(context, PKG).isEmpty(), 5000);
        } finally {
            android.content.SharedPreferences.Editor editor = prefs.edit();
            if (previousDark == null) editor.remove("dark_theme"); else editor.putBoolean("dark_theme", previousDark);
            editor.commit();
        }
    }

    // Test: Active-pause text stays inside the dialog body after its status and controls refresh.
    // Why: Long end timestamps and disabled fields must not push text past the dialog padding.
    @Test public void activeDialogKeepsTextWithinBounds() throws Exception {
        add(AppPauseRule.text(PKG, 300));
        launch(); find("appOptionsButton").click(); find("appPauseButton").click();
        find("app_pause_state_action"); device.waitForIdle(); SystemClock.sleep(900);
        shot("15-active-bounds");
        onMain(() -> {
            for (android.view.View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                android.view.View status = root.findViewById(R.id.app_pause_status);
                if (status != null) checkTextBounds((android.view.ViewGroup) status.getParent());
            }
        });
    }

    private void checkTextBounds(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);
            if (child.getVisibility() != android.view.View.VISIBLE) continue;
            assertTrue("Child exceeds body: " + child + " parent width=" + parent.getWidth(),
                    child.getRight() <= parent.getWidth() - parent.getPaddingRight());
            if (child instanceof android.widget.TextView && !(child instanceof android.widget.EditText)) {
                android.widget.TextView text = (android.widget.TextView) child;
                if (text.getEllipsize() != null) {
                    assertTrue("Unit label exceeds field: " + text.getText(),
                            text.getPaint().measureText(text.getText().toString()) <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight());
                } else if (text.getLayout() != null) {
                    assertTrue("Text layout exceeds field: " + text.getText(),
                            text.getLayout().getWidth() <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight());
                    for (int line = 0; line < text.getLayout().getLineCount(); line++)
                        assertTrue("Text exceeds its line: " + text.getText(),
                                text.getLayout().getLineRight(line) <= text.getLayout().getWidth());
                }
            }
            if (child instanceof android.view.ViewGroup) checkTextBounds((android.view.ViewGroup) child);
        }
    }

    private void launch() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context, ActivityMain.class)
                .putExtra(ActivityMain.EXTRA_SEARCH, "Chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdle();
        if (device.hasObject(By.res(context.getPackageName(), "cbDontAsk"))) device.findObject(By.res("android", "button2")).click();
        for (String id : new String[]{"btnUsage", "btnFairEmail", "btnSystem", "btnWhitelist", "btnPush"}) {
            UiObject2 hint = device.findObject(By.res(context.getPackageName(), id));
            if (hint != null) hint.click();
        }
    }
    private void add(String text) throws Exception { owned.add(text); rules.queueRuleText(context, text); barrier(); }
    private boolean exists(String text) { try (Cursor c = db.getRuleMatchingRuletext(text)) { return c.moveToFirst(); } }
    private void barrier() throws Exception { CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown); assertTrue(done.await(15, TimeUnit.SECONDS)); }
    private void await(BooleanSupplier check, int ms) { long end = SystemClock.elapsedRealtime()+ms; while (!check.getAsBoolean() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100); assertTrue(check.getAsBoolean()); }
    private UiObject2 find(String id) { UiObject2 view=device.wait(Until.findObject(By.res(context.getPackageName(), id)),5000); assertNotNull(id,view); return view; }
    private UiObject2 text(String value) { UiObject2 view=device.wait(Until.findObject(By.text(value)),5000); assertNotNull(value,view); return view; }
    private UiObject2 unitChoice(String value) {
        UiObject2 list = device.wait(Until.findObject(By.clazz("android.widget.ListView").hasDescendant(By.text(value))), 5000);
        assertNotNull("Unit choices", list);
        return list.findObject(By.text(value));
    }
    private void onMain(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private void setAmount(String value) {
        onMain(() -> {
            for (android.view.View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                android.widget.EditText input = root.findViewById(R.id.app_pause_amount);
                if (input != null) { input.setText(value); input.clearFocus(); }
            }
        });
    }
    private void shot(String name) { device.waitForIdle(); SystemClock.sleep(900); device.takeScreenshot(new File(context.getExternalFilesDir(null), "pause-"+name+".png")); }
    private HeartGuardDeviceTest.TestService service() throws Exception {
        HeartGuardDeviceTest.TestService[] value = new HeartGuardDeviceTest.TestService[1];
        onMain(() -> { value[0] = new HeartGuardDeviceTest.TestService(); value[0].attach(context); });
        Method prepare = ServiceSinkhole.class.getDeclaredMethod("prepareHostsBlocked"); prepare.setAccessible(true); prepare.invoke(value[0]);
        return value[0];
    }
    private void assertDns(boolean blocked) throws Exception {
        Method check=ServiceSinkhole.class.getDeclaredMethod("isDnsBlocked",String.class,int.class,boolean.class); check.setAccessible(true);
        assertEquals(blocked,check.invoke(service(), HOST, uid, false));
    }
    private void assertPacket(boolean allowed) throws Exception {
        Packet p=new Packet(); p.uid=uid; p.daddr=HOST; p.version=4; p.protocol=6; p.flags=""; p.dport=443;
        Method check=ServiceSinkhole.class.getDeclaredMethod("isAddressAllowed",Packet.class); check.setAccessible(true);
        assertEquals(allowed,check.invoke(service(),p)!=null);
    }
}
