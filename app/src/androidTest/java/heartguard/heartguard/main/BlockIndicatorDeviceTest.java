package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BlockIndicatorDeviceTest {
    private static final String HOST = "block-indicator-test.invalid";
    private Context context;
    private DatabaseHelper db;
    private Activity activity;
    private int uid;
    private final List<Long> ruleIds = new ArrayList<>();

    @Before public void setup() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = DatabaseHelper.getInstance(context);
        uid = context.getApplicationInfo().uid;
    }

    @After public void cleanup() {
        if (activity != null) onMain(() -> activity.finish());
        db.removeRulesById(ruleIds.toArray(new Long[0]));
        db.getWritableDatabase().delete("access", "uid = ? AND daddr = ?", new String[]{Integer.toString(uid), "alias." + HOST});
    }

    // Test: Only enacted, unexpired, matching app blocks get the explicit marker; global blocks retain their globe.
    // Why: Default blocks, pending/removed rules, similar suffixes and another app must not be mislabeled.
    @Test public void explicitBlocksFollowActiveRulesAndAppIdentity() {
        String block = block("host:explicit." + HOST);
        long id = add(block, true, System.currentTimeMillis());
        add(block("host:pending." + HOST), false, System.currentTimeMillis());
        add("temporary seconds:60 " + block("host:temporary." + HOST), true, System.currentTimeMillis());
        add("temporary seconds:60 " + block("host:expired." + HOST), true, System.currentTimeMillis() - 120000);
        add("blockdomain global." + HOST, true, System.currentTimeMillis());
        add(block("ip:192.0.2.0/24"), true, System.currentTimeMillis());
        GlobalRuleIndicators policy = db.getGlobalRuleIndicators();
        AccessEntry explicit = row(policy, uid, "sub.explicit." + HOST, 1, 1, null);
        assertTrue(explicit.explicitBlock); // Existing rows need no new traffic or stored rule text.
        assertFalse(row(policy, uid + 1, "sub.explicit." + HOST, 0, 1, null).explicitBlock);
        assertFalse(row(policy, uid, "notexplicit." + HOST, 0, 1, null).explicitBlock);
        assertFalse(row(policy, uid, "pending." + HOST, 0, 1, null).explicitBlock);
        assertFalse(row(policy, uid, "expired." + HOST, 0, 1, null).explicitBlock);
        assertTrue(row(policy, uid, "temporary." + HOST, 0, 1, null).explicitBlock);
        assertTrue(row(policy, uid, "192.0.2.17", 0, 1, null).explicitBlock);
        assertFalse(row(policy, uid, "192.0.3.17", 0, 1, null).explicitBlock);
        assertFalse(row(policy, uid, "explicit." + HOST, 0, 0, null).explicitBlock);
        AccessEntry global = row(policy, uid, "global." + HOST, 0, 1, null);
        assertEquals(GlobalRuleIndicators.BLOCKED, global.globalScope);
        assertFalse(global.explicitBlock);
        db.removeRulesById(new Long[]{id});
        AccessEntry removed = row(db.getGlobalRuleIndicators(), uid, "sub.explicit." + HOST, 1, 1, block);
        assertFalse(removed.explicitBlock);
        assertFalse("A rule-only change must rebind the icon", explicit.sameContents(removed));
    }

    // Test: A matched block reached through an alias is retained as metadata and cleared when no rule matches.
    // Why: The displayed hostname need not be the hostname or IP which matched the explicit rule.
    @Test public void matchedBlockMetadataPreservesRecordedResult() {
        String block = block("host:explicit." + HOST);
        add(block, true, System.currentTimeMillis());
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("uid", uid); values.put("daddr", "alias." + HOST); values.put("dport", 443);
        values.put("version", 4); values.put("protocol", 6); values.put("time", 1234); values.put("allowed", 1); values.put("block", 1);
        values.put("pending_allow", 0);
        long id = db.getWritableDatabase().insertOrThrow("access", null, values);
        RuleAllowData decision = new RuleAllowData(); decision.allowed = 0; decision.ruletext = block;
        decision.input_daddr = "192.0.2.17"; decision.relevant_daddr = "explicit." + HOST;
        db.setAccess(id, decision, "Test block reason");
        try (Cursor rows = db.getAccess(uid, false, "alias." + HOST, ListSort.RECENT)) {
            assertTrue(rows.moveToFirst());
            AccessEntry entry = new AccessEntry(rows, db.getGlobalRuleIndicators());
            assertTrue(entry.explicitBlock); assertEquals(1, entry.allowed); assertEquals(1234, entry.time);
        }
        db.setAccess(id, null, "Test default block");
        try (Cursor rows = db.getAccess(uid, false, "alias." + HOST, ListSort.RECENT)) {
            assertTrue(rows.moveToFirst());
            assertNull(rows.getString(rows.getColumnIndexOrThrow("ruletext")));
            assertFalse(new AccessEntry(rows, db.getGlobalRuleIndicators()).explicitBlock);
        }
    }

    // Test: Shared row styling distinguishes both causes in all themes and restores the plain X when recycled.
    // Why: Per-app and Recent traffic use this renderer; reusing a view must not leave a stale circle or explanation.
    @Test public void blockSymbolsAndLegendWorkAcrossThemes() {
        add(block("host:explicit." + HOST), true, System.currentTimeMillis());
        GlobalRuleIndicators policy = db.getGlobalRuleIndicators();
        AccessEntry explicit = row(policy, uid, "explicit." + HOST, 0, 1, null);
        AccessEntry passive = row(policy, uid, "default." + HOST, 0, 1, null);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ComponentTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> {
            for (int theme : new int[]{R.style.AppThemeTeal, R.style.AppThemeTealDark, R.style.AppThemeBlue,
                    R.style.AppThemeBlueDark, R.style.AppThemePurple, R.style.AppThemePurpleDark,
                    R.style.AppThemeAmber, R.style.AppThemeAmberDark, R.style.AppThemeOrange,
                    R.style.AppThemeOrangeDark, R.style.AppThemeGreen, R.style.AppThemeGreenDark}) {
                Context themed = new ContextThemeWrapper(activity, theme);
                ImageView icon = new ImageView(themed); TextView domain = new TextView(themed);
                AccessRowStyle style = new AccessRowStyle(themed);
                style.bind(explicit, icon, domain);
                assertEquals(context.getString(R.string.domain_explicitly_blocked), icon.getContentDescription());
                Bitmap circled = pixels(icon);
                style.bind(passive, icon, domain);
                assertEquals(context.getString(R.string.domain_blocked_default), icon.getContentDescription());
                assertFalse(circled.sameAs(pixels(icon)));
                LinearLayout content = new LinearLayout(themed); content.setOrientation(LinearLayout.VERTICAL);
                content.setBackgroundColor(CompactUi.color(themed, android.R.attr.colorBackground));
                LayoutInflater.from(themed).inflate(R.layout.legend, content, true);
                activity.setContentView(content);
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 scroll = device.wait(Until.findObject(By.res(context.getPackageName(), "legendScroll")), 10000);
        assertNotNull(scroll);
        for (int attempt = 0; attempt < 8 && !device.hasObject(By.text(context.getString(R.string.domain_explicitly_blocked))); attempt++)
            if (!scroll.scroll(Direction.DOWN, 0.6f)) break;
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.domain_explicitly_blocked))), 5000));
        device.takeScreenshot(
                new java.io.File(context.getExternalFilesDir(null), "circled-block-legend.png"));
    }

    private Bitmap pixels(ImageView icon) {
        Bitmap image = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888);
        icon.getDrawable().setBounds(0, 0, 72, 72); icon.getDrawable().draw(new Canvas(image)); return image;
    }
    private String block(String target) { return "block package:" + context.getPackageName() + " " + target; }
    private long add(String text, boolean enacted, long time) {
        try (Cursor existing = db.getRuleMatchingRuletext(text)) { assertEquals("Fixture already exists", 0, existing.getCount()); }
        db.addNewRule(text, time, time, enacted ? 1 : 0, 0, 0);
        try (Cursor row = db.getRuleMatchingRuletext(text)) {
            assertTrue(row.moveToFirst()); long id = row.getLong(row.getColumnIndexOrThrow("_id")); ruleIds.add(id); return id;
        }
    }
    private AccessEntry row(GlobalRuleIndicators policy, int appUid, String host, int recorded, int block, String rule) {
        try (MatrixCursor row = new MatrixCursor(new String[]{"ID", "uid", "time", "version", "protocol", "dport", "allowed", "block", "count", "connections", "pending_allow", "sent", "received", "daddr", "ruletext"})) {
            row.addRow(new Object[]{1, appUid, 1234, 4, 6, 443, recorded, block, 1, 0, 0, 0, 0, host, rule});
            row.moveToFirst(); return new AccessEntry(row, policy);
        }
    }
    private void onMain(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
}
