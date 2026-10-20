package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class HeartGuardDeviceTest {
    private Context context;
    private RulesManager rules;
    private WhitelistManager whitelist;
    private final List<String> ownedRules = new ArrayList<>();
    private Activity activity;
    private static final String HOST = "mw-device-tests.invalid";

    @Before public void setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        rules = RulesManager.getInstance(context);
        whitelist = WhitelistManager.getInstance(context);
    }

    @After public void cleanup() {
        if (activity != null)
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        // Remove only this test's synthetic rules, without changing other saved settings.
        DatabaseHelper db = DatabaseHelper.getInstance(context);
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
    }

    @Test public void globalRuleReachesUnknownAndFutureAppUid() throws Exception {
        add("allow host:" + HOST);
        assertEquals(1, whitelist.isAllowed(context, HOST, 98765).allowed);
        assertEquals(1, whitelist.isAllowed(context, "cdn." + HOST, 98766).allowed);
    }

    @Test public void appBlockOverridesGlobalAppAllowAndPacketShortcut() throws Exception {
        String pkg = context.getPackageName();
        add("allow host:" + HOST);
        add("allow package:" + pkg + " host:" + HOST);
        add("block package:" + pkg + " host:" + HOST);
        assertEquals(0, whitelist.isAllowed(context, HOST, android.os.Process.myUid()).allowed);
        assertEquals(1, whitelist.isAllowed(context, HOST, 98765).allowed);
        assertTrue(rules.getPreferenceCaptureAllTraffic(context));
        Packet packet = packet();
        TestService[] service = new TestService[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            service[0] = new TestService();
            service[0].attach(context);
        });
        Method check = ServiceSinkhole.class.getDeclaredMethod("isAddressAllowed", Packet.class);
        check.setAccessible(true);
        assertNull(check.invoke(service[0], packet));
        assertFalse(packet.allowed);
    }

    @Test public void globalAdditionDoesNotOverwriteCachedAppBlock() throws Exception {
        add("block package:" + context.getPackageName() + " host:" + HOST);
        Packet packet = packet();
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        db.updateAccess(context, packet, HOST);
        add("allow host:" + HOST);
        try (Cursor cursor = db.getAccess(packet.uid)) {
            boolean found = false;
            while (cursor.moveToNext()) {
                if (HOST.equals(cursor.getString(cursor.getColumnIndexOrThrow("daddr")))) {
                    found = true;
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("block")));
                }
            }
            assertTrue(found);
        }
    }

    @Test public void removingBlockRestoresGlobalAllow() throws Exception {
        String block = "block package:" + context.getPackageName() + " host:" + HOST;
        add("allow host:" + HOST);
        add(block);
        rules.queueRuleText(context, "- " + block);
        waitFor(() -> {
            RuleAllowData result = whitelist.isAllowed(context, HOST, android.os.Process.myUid());
            return result != null && result.allowed == 1;
        });
    }

    @Test public void pastedIpv6AllowsOnlyThatAddress() {
        RuleAndPackage rule = RulesManager.parseTextToWhitelistRule(context,
                "allow " + DomainInput.destination(DomainInput.normalize("https://[2001:db8::1]/")));
        assertNotNull(rule);
        assertTrue(rule.rule.matchesAddr("2001:db8::1"));
        assertFalse(rule.rule.matchesAddr("2001:db8::2"));
    }

    @Test public void blockParserRequiresAppAndPreservesDelayPolicy() {
        assertNull(MyRule.getRuleFromText(context, "block host:" + HOST));
        assertNull(MyRule.getRuleFromText(context, "block package:" + context.getPackageName() + " directip"));
        RuleAndPackage block = (RuleAndPackage) MyRule.getRuleFromText(context,
                "block package:" + context.getPackageName() + " host:" + HOST);
        assertNotNull(block);
        assertEquals(0, block.getDelayToAdd(context, 60));
        assertEquals(60, block.getDelayToRemove(context, 60));
        RuleAndPackage allow = (RuleAndPackage) MyRule.getRuleFromText(context, "allow host:" + HOST);
        assertEquals(60, allow.getDelayToAdd(context, 60));
    }

    @Test public void whitelistScreenAcceptsPastedUrlsAndSurvivesReopen() throws Exception {
        String rule = "allow host:" + HOST;
        ownedRules.add(rule);
        launch(ActivityGlobalWhitelist.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ((EditText) activity.findViewById(R.id.whitelist_input)).setText("https://" + HOST + "/path?q=1");
            activity.findViewById(R.id.whitelist_add).performClick();
        });
        waitFor(() -> active(rule));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        androidx.test.uiautomator.BySelector entry = By.text(java.util.regex.Pattern.compile("(?s).*" + java.util.regex.Pattern.quote(HOST) + ".*"));
        boolean shown = device.wait(Until.hasObject(entry), 5000);
        if (!shown) {
            device.dumpWindowHierarchy(new java.io.File(context.getExternalFilesDir(null), "whitelist-failure.xml"));
            device.takeScreenshot(new java.io.File(context.getExternalFilesDir(null), "whitelist-failure.png"));
        }
        assertTrue(shown);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        launch(ActivityGlobalWhitelist.class);
        assertTrue(device.wait(Until.hasObject(entry), 5000));
    }

    @Test public void invalidBatchDoesNotPartiallyAddRules() throws Exception {
        launch(ActivityGlobalWhitelist.class);
        String rule = "allow host:" + HOST;
        ownedRules.add(rule);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ((EditText) activity.findViewById(R.id.whitelist_input)).setText(HOST + "\nhttps://user:pass@example.com/");
            activity.findViewById(R.id.whitelist_add).performClick();
            assertNotNull(((EditText) activity.findViewById(R.id.whitelist_input)).getError());
        });
        assertFalse(active(rule));
    }

    private void launch(Class<?> type) {
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private Packet packet() {
        Packet packet = new Packet();
        packet.uid = android.os.Process.myUid(); packet.daddr = HOST; packet.dport = 443;
        packet.version = 4; packet.protocol = 6; packet.flags = "";
        packet.time = System.currentTimeMillis();
        return packet;
    }

    private void add(String text) throws Exception {
        ownedRules.add(text);
        rules.queueRuleText(context, text);
        waitFor(() -> active(text));
        // The DB row can be visible just before the in-memory rules are refreshed.
        waitFor(() -> {
            for (RuleAndPackage rule : rules.getCurrentRules(context))
                if (rule.getRuleText().equals(text)) return true;
            return false;
        });
        whitelist.updateRulesFromRulesManager(context);
    }

    private boolean active(String text) {
        try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(text)) {
            return cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow("enacted")) > 0;
        }
    }

    private interface Condition { boolean satisfied(); }
    private void waitFor(Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.satisfied()) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for rule processing");
    }

    public static class TestService extends ServiceSinkhole {
        void attach(Context context) { attachBaseContext(context); }
    }
}
