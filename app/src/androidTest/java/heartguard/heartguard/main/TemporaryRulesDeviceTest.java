package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TemporaryRulesDeviceTest {
    private static final String HOST = "temporary-device-test.invalid";
    private static final String PKG = "com.android.chrome";
    private Context context;
    private RulesManager rules;
    private DatabaseHelper db;
    private WhitelistManager whitelist;
    private UiDevice device;
    private Activity activity;
    private int uid;
    private final List<String> owned = new ArrayList<>();

    @Before public void setup() throws Exception {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        rules = RulesManager.getInstance(context);
        db = DatabaseHelper.getInstance(context);
        whitelist = WhitelistManager.getInstance(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        uid = context.getPackageManager().getApplicationInfo(PKG, 0).uid;
        assertEquals(0, RuleDelay.read(context).seconds);
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
        reload();
        // Only the synthetic fixture access entry is removed.
        db.getWritableDatabase().delete("access", "uid = ? AND daddr = ?", new String[]{Integer.toString(uid), HOST});
    }

    // Test: An allow waits for its delay, then receives its full duration and expires on schedule.
    // Why: Waiting must not consume the duration, and rebuilding the rule snapshot must not restart it.
    @Test public void delayedAllowStartsOnActivationAndKeepsDeadline() throws Exception {
        add("delay 2");
        String text = TemporaryRuleText.text("allow package:" + PKG + " host:" + HOST, 3);
        add(text);
        assertNull(whitelist.isAllowed(context, HOST, uid));
        await(() -> rules.isTemporaryRuleActive(text), 6000);
        assertEquals(1, whitelist.isAllowed(context, HOST, uid).allowed);
        long deadline = activeDeadline(text);
        assertTrue(deadline - System.currentTimeMillis() > 2000);
        reload();
        assertEquals(deadline, activeDeadline(text));
        await(() -> !rules.isTemporaryRuleActive(text), 6000);
        assertNull(whitelist.isAllowed(context, HOST, uid));
        await(() -> !exists(text), 5000);
    }

    // Test: A temporary app block overrides a permanent global allow and expiry restores it without deleting history.
    // Why: Expiry must remove only the temporary exception, preserving rules, row identity and traffic history.
    @Test public void appBlockExpiryPreservesPermanentAllowAndHistory() throws Exception {
        String permanent = "allow host:" + HOST;
        add(permanent);
        Packet packet = new Packet(); packet.uid = uid; packet.daddr = "192.0.2.61";
        packet.version = 4; packet.protocol = 6; packet.dport = 443; packet.time = System.currentTimeMillis(); packet.allowed = true;
        db.updateAccess(context, packet, HOST);
        long id = accessId();
        String block = TemporaryRuleText.text("block package:" + PKG + " host:" + HOST, 3);
        add(block);
        assertEquals(0, whitelist.isAllowed(context, HOST, uid).allowed);
        assertEquals(1, whitelist.isAllowed(context, HOST, 99182).allowed);
        await(() -> !rules.isTemporaryRuleActive(block), 6000);
        assertEquals(1, whitelist.isAllowed(context, HOST, uid).allowed);
        await(() -> !exists(block), 5000);
        assertTrue(exists(permanent));
        assertEquals(id, accessId());
    }

    // Test: Temporary global blocks expire, but an overlapping permanent block remains authoritative.
    // Why: Global DNS enforcement must check deadlines itself and must not remove permanent protection.
    @Test public void globalBlockExpiryAndPermanentOverlap() throws Exception {
        String temporary = TemporaryRuleText.text("blockdomain " + HOST, 2);
        add(temporary);
        assertTrue(rules.isGlobalDomainBlocked("cdn." + HOST));
        await(() -> !rules.isGlobalDomainBlocked(HOST), 5000);
        await(() -> !exists(temporary), 5000);
        add("blockdomain " + HOST);
        add(temporary);
        await(() -> !rules.isTemporaryRuleActive(temporary), 5000);
        assertTrue(rules.isGlobalDomainBlocked(HOST));
        assertTrue(exists("blockdomain " + HOST));
    }

    // Test: Early block removal waits for the normal delay; expiry also clears that pending removal.
    // Why: A leftover removal must not delete a later temporary block with the same duration and domain.
    @Test public void expiredBlockClearsItsPendingRemoval() throws Exception {
        add("delay 60");
        String text = TemporaryRuleText.text("block package:" + PKG + " host:" + HOST, 3);
        add(text);
        rules.queueRuleText(context, "- " + text); barrier();
        assertTrue(rules.isTemporaryRuleActive(text));
        try (Cursor cursor = db.getRuleMatchingRuletext("- " + text)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")) - System.currentTimeMillis() > 50000);
        }
        await(() -> !exists(text), 6000);
        assertFalse(exists("- " + text));
        // Re-add through the normal queue after expiry; there must be no stale deletion attached.
        rules.queueRuleText(context, text); barrier();
        assertTrue(rules.isTemporaryRuleActive(text));
        assertFalse(exists("- " + text));
    }

    // Test: Both dialog actions default to permanent and support local/global timed rules with validated duration.
    // Why: The same toggle and units must work for Allow and Block without accidentally changing scope.
    @Test public void dialogSupportsBothActionsAndScopes() throws Exception {
        Intent intent = new Intent(context, ActivityMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        for (boolean blocked : new boolean[]{false, true}) for (boolean global : new boolean[]{false, true}) {
            onMain(() -> DomainRuleDialog.show(activity, PKG, HOST, blocked));
            assertFalse(find("temporary_rule_enabled").isChecked());
            assertFalse(device.hasObject(By.res(context.getPackageName(), "temporary_rule_amount")));
            find("temporary_rule_enabled").click();
            if (global) find("allow_new_access_global").click();
            onMain(() -> {
                View root = dialogRoot();
                ((EditText) root.findViewById(R.id.temporary_rule_amount)).setText("0");
                ((Spinner) root.findViewById(R.id.temporary_rule_unit)).setSelection(RuleDelay.Unit.SECONDS.ordinal());
            });
            positive().click();
            find("temporary_rule_amount");
            onMain(() -> ((EditText) dialogRoot().findViewById(R.id.temporary_rule_amount)).setText("300"));
            await(() -> "300".equals(find("temporary_rule_amount").getText()), 5000);
            String base = blocked && global ? "blockdomain " + HOST
                    : (blocked ? "block " : "allow ") + (global ? "" : "package:" + PKG + " ") + "host:" + HOST;
            String text = TemporaryRuleText.text(base, 300);
            owned.add(text);
            device.waitForIdle();
            SystemClock.sleep(900);
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "temporary-" + (blocked ? "block" : "allow") + (global ? "-global" : "-app") + ".png"));
            positive().click();
            await(() -> rules.isTemporaryRuleActive(text), 5000);
        }
    }

    // Test: The raw parser rejects whole-app temporary allows and retains block-removal delay.
    // Why: The domain feature must not introduce a new whole-app bypass or a way around the timer.
    @Test public void parserRejectsNonDomainBypassesAndKeepsRemovalDelay() {
        assertNull(MyRule.getRuleFromText(context, "temporary seconds:60 allow package:" + PKG));
        assertNull(MyRule.getRuleFromText(context, "temporary seconds:60 allow directip"));
        assertNull(MyRule.getRuleFromText(context, "temporary seconds:60 delay 0"));
        assertNull(MyRule.getRuleFromText(context, "temporary seconds:604801 allow host:" + HOST));
        MyRule block = MyRule.getRuleFromText(context, TemporaryRuleText.text("block package:" + PKG + " host:" + HOST, 60));
        assertEquals(0, block.getDelayToAdd(context, 120));
        assertEquals(120, block.getDelayToRemove(context, 120));
    }

    private View dialogRoot() {
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews())
            if (root.findViewById(R.id.temporary_rule_amount) != null) return root;
        throw new AssertionError("Domain rule dialog missing");
    }
    private UiObject2 positive() throws Exception {
        UiObject2 button = device.wait(Until.findObject(By.res("android", "button1")), 5000);
        if (button == null) {
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "temporary-dialog-failure.png"));
            device.dumpWindowHierarchy(new File(context.getExternalFilesDir(null), "temporary-dialog-failure.xml"));
        }
        assertNotNull("Domain rule confirmation button", button);
        return button;
    }
    private void add(String text) throws Exception { assertFalse(exists(text)); owned.add(text); rules.queueRuleText(context, text); barrier(); }
    private boolean exists(String text) { try (Cursor cursor = db.getRuleMatchingRuletext(text)) { return cursor.moveToFirst(); } }
    private long activeDeadline(String text) {
        try (Cursor cursor = db.getRuleMatchingRuletext(text)) {
            assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("enacted")));
            return TemporaryRuleText.parse(text).expiresAt(cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")));
        }
    }
    private long accessId() {
        try (Cursor cursor = db.getAccess(uid)) {
            while (cursor.moveToNext()) if (HOST.equals(cursor.getString(cursor.getColumnIndexOrThrow("daddr"))))
                return cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
        }
        throw new AssertionError("Fixture history disappeared");
    }
    private void reload() throws Exception {
        Method method = RulesManager.class.getDeclaredMethod("getAllEnactedRulesFromDb", Context.class);
        method.setAccessible(true); method.invoke(rules, context);
        whitelist.updateRulesFromRulesManager(context);
        rules.startTrueTimeRequest(context); barrier();
    }
    private void barrier() throws Exception { CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown); assertTrue(done.await(15, TimeUnit.SECONDS)); }
    private void await(BooleanSupplier check, int ms) { long end = SystemClock.elapsedRealtime() + ms; while (!check.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50); assertTrue(check.getAsBoolean()); }
    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private UiObject2 find(String id) { UiObject2 view = device.wait(Until.findObject(By.res(context.getPackageName(), id)), 5000); assertNotNull(id, view); return view; }
}
