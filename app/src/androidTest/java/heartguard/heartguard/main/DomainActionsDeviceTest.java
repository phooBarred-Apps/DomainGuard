package heartguard.heartguard.main;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.widget.Button;
import android.widget.FrameLayout;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DomainActionsDeviceTest {
    private static final int FIRST_UID = 99197, SECOND_UID = 99198;
    private static final String HOST = "mw-domain-actions.invalid";
    private Context context;
    private DatabaseHelper db;
    private RulesManager rules;
    private Activity activity;
    private AdapterRule adapter;
    private UiDevice device;
    private final List<String> ownedRules = new ArrayList<>();

    @Before public void setup() throws Exception {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        rules = RulesManager.getInstance(context);
        // Recover only this test fixture after an interrupted emulator run.
        db.getWritableDatabase().delete("access", "uid IN (?, ?) AND daddr LIKE ?", new String[]{Integer.toString(FIRST_UID), Integer.toString(SECOND_UID), "%" + HOST});
        awaitRules();
        Field delay = RulesManager.class.getDeclaredField("m_delay");
        delay.setAccessible(true);
        assertEquals("Fixture must have no rule delay", 0, delay.getInt(rules));
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (int uid : new int[]{FIRST_UID, SECOND_UID})
            try (Cursor cursor = db.getAccess(uid)) { assertEquals(0, cursor.getCount()); }
    }

    @After public void cleanup() throws Exception {
        if (activity != null) onMain(() -> {
            if (adapter != null) adapter.dispose();
            activity.finish();
        });
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.getAllRules()) {
            while (cursor.moveToNext()) {
                String text = cursor.getString(cursor.getColumnIndexOrThrow("ruletext"));
                if (ownedRules.contains(text) || ownedRules.contains(text.startsWith("- ") ? text.substring(2) : text))
                    ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
        }
        if (!ids.isEmpty()) db.removeRulesById(ids.toArray(new Long[0]));
        rules.startTrueTimeRequest(context);
        awaitRules();
        db.getWritableDatabase().delete("access", "uid IN (?, ?) AND daddr LIKE ?",
                new String[]{Integer.toString(FIRST_UID), Integer.toString(SECOND_UID), "%" + HOST});
    }

    // Test: Add/remove a global block without altering traffic rows; DNS enforcement follows both changes.
    // Why: Global block post-actions previously cleared every app's access history, including unrelated traffic.
    @Test public void globalBlockPreservesAllHistoryAndStillBlocksDomains() throws Exception {
        String blockedHost = "blocked." + HOST;
        insert(FIRST_UID, blockedHost, 100);
        insert(FIRST_UID, "cdn." + blockedHost, 200);
        insert(SECOND_UID, "other." + HOST, 300);
        List<String> before = snapshot();
        String rule = "blockdomain " + blockedHost;
        ownedRules.add(rule);
        rules.queueRuleText(context, rule);
        awaitRules();
        assertTrue(active(rule));
        assertEquals(before, snapshot());
        assertDomainBlocked(blockedHost, true);
        assertDomainBlocked("cdn." + blockedHost, true);
        assertDomainBlocked("lookalike-" + blockedHost, false);
        assertDomainBlocked("other." + HOST, false);

        rules.queueRuleText(context, "- " + rule);
        awaitRules();
        assertFalse(active(rule));
        assertEquals(before, snapshot());
        assertDomainBlocked(blockedHost, false);
        assertDomainBlocked("cdn." + blockedHost, false);
    }

    // Test: Both menu actions remain available for allowed rows; global scope is opt-in on every new dialog.
    // Why: Combining local/global actions must never accidentally apply a previous global choice to every app.
    @Test public void allowAndBlockMenusDefaultToAppAndSaveSelectedScope() throws Exception {
        launchMenuFixture();
        // HeartGuard deliberately excludes itself from its app list.
        Rule app = Rule.getRules(true, context).get(0);
        for (boolean block : new boolean[]{false, true}) {
            // Global first proves the next app-only dialog does not retain the previous checked value.
            for (boolean global : new boolean[]{true, false}) {
                String host = (block ? "block-" : "allow-") + (global ? "global." : "app.") + HOST;
                insert(FIRST_UID, host, System.currentTimeMillis());
                AccessEntry entry = null;
                try (Cursor cursor = db.getAccess(FIRST_UID)) {
                    while (cursor.moveToNext())
                        if (host.equals(cursor.getString(cursor.getColumnIndexOrThrow("daddr")))) entry = new AccessEntry(cursor);
                }
                assertNotNull(entry);
                Method menu = AdapterRule.class.getDeclaredMethod("showAccessMenu", Context.class, Rule.class, AccessEntry.class);
                menu.setAccessible(true);
                Rule selectedApp = app;
                AccessEntry selectedEntry = entry;
                onMain(() -> {
                    try { menu.invoke(adapter, activity, selectedApp, selectedEntry); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
                boolean menuShown = device.wait(Until.hasObject(By.text("Allow")), 5000);
                if (!menuShown) {
                    device.takeScreenshot(new File(context.getExternalFilesDir(null), "domain-menu-failure.png"));
                    device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "domain-menu-failure.xml"));
                }
                assertTrue("Domain menu should show Allow", menuShown);
                assertTrue(device.hasObject(By.text("Block")));
                assertFalse(device.hasObject(By.text("Allow globally")));
                assertFalse(device.hasObject(By.text("Block for this app")));
                device.findObject(By.text(block ? "Block" : "Allow")).click();
                UiObject2 toggle = device.wait(Until.findObject(By.res(context.getPackageName(), "allow_new_access_global")), 5000);
                assertNotNull(toggle);
                assertFalse(toggle.isSelected());
                assertEquals(context.getString(R.string.scope_off), toggle.getContentDescription());
                if (global) toggle.click();
                String expected = block && global ? "blockdomain " + host
                        : (block ? "block " : "allow ") + (global ? "" : "package:" + app.packageName + " ") + "host:" + host;
                ownedRules.add(expected);
                if (block && global) device.takeScreenshot(new File(context.getExternalFilesDir(null), "domain-block-global.png"));
                device.findObject(By.res("android", "button1")).click();
                assertTrue(device.wait(Until.gone(By.res(context.getPackageName(), "allow_new_access_global")), 5000));
                awaitRules();
                assertTrue(expected, active(expected));
            }
        }
    }

    private void launchMenuFixture() {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityGlobalWhitelist.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            Button anchor = new Button(activity);
            anchor.setAllCaps(false);
            anchor.setText("Domain menu test");
            FrameLayout container = new FrameLayout(activity);
            container.addView(anchor, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    (int) (48 * context.getResources().getDisplayMetrics().density)));
            activity.setContentView(container);
            adapter = new AdapterRule(activity, anchor);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(device.wait(Until.hasObject(By.text("Domain menu test")), 5000));
    }

    private void insert(int uid, String host, long time) {
        ContentValues row = new ContentValues();
        row.put("uid", uid); row.put("version", 4); row.put("protocol", 6);
        row.put("daddr", host); row.put("dport", 443); row.put("time", time);
        row.put("allowed", 1); row.put("block", 0); row.put("pending_allow", 0);
        row.put("sent", 123); row.put("received", 456); row.put("connections", 7);
        db.getWritableDatabase().insertOrThrow("access", null, row);
    }

    private List<String> snapshot() {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = db.getReadableDatabase().rawQuery(
                "SELECT ID, uid, daddr, time, sent, received, connections, allowed, block FROM access WHERE uid IN (?, ?) ORDER BY ID",
                new String[]{Integer.toString(FIRST_UID), Integer.toString(SECOND_UID)})) {
            while (cursor.moveToNext()) {
                StringBuilder row = new StringBuilder();
                for (int column = 0; column < cursor.getColumnCount(); column++) row.append(cursor.getString(column)).append('|');
                result.add(row.toString());
            }
        }
        return result;
    }

    private void assertDomainBlocked(String host, boolean expected) throws Exception {
        HeartGuardDeviceTest.TestService[] service = new HeartGuardDeviceTest.TestService[1];
        onMain(() -> {
            service[0] = new HeartGuardDeviceTest.TestService();
            service[0].attach(context);
        });
        Method prepare = ServiceSinkhole.class.getDeclaredMethod("prepareHostsBlocked");
        prepare.setAccessible(true);
        prepare.invoke(service[0]);
        Method check = ServiceSinkhole.class.getDeclaredMethod("isDomainBlocked", String.class);
        check.setAccessible(true);
        assertEquals(host, expected, check.invoke(service[0], host));
    }

    private boolean active(String text) {
        try (Cursor cursor = db.getRuleMatchingRuletext(text)) {
            return cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow("enacted")) > 0;
        }
    }

    private void awaitRules() throws Exception {
        // Queue a barrier after rule processing so assertions run after all destructive post-actions too.
        CountDownLatch done = new CountDownLatch(1);
        rules.handler.post(done::countDown);
        assertTrue("Rule processing did not finish", done.await(15, TimeUnit.SECONDS));
    }

    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
