package heartguard.heartguard.main;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GlobalRuleCacheDeviceTest {
    // Test: Shared rule indicators are reused and immediately invalidated on insert, activation, edit and removal.
    // Why: Reducing repeated rule reads must never leave an obsolete global badge after a rule changes.
    @Test public void ruleMutationsInvalidateSharedIndicators() {
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        String host = "rule-cache-test.invalid", block = "blockdomain " + host, allow = "allow host:" + host;
        long id;
        try (Cursor cursor = db.getRuleMatchingRuletext(block)) { assertEquals(0, cursor.getCount()); }
        GlobalRuleIndicators initial = db.getGlobalRuleIndicators();
        assertSame(initial, db.getGlobalRuleIndicators());
        db.addNewRule(block, System.currentTimeMillis(), Long.MAX_VALUE, 0, MyRule.MAJOR_CATEGORY_BLOCK, 0);
        try (Cursor cursor = db.getRuleMatchingRuletext(block)) {
            assertTrue(cursor.moveToFirst());
            id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
        }
        try {
            GlobalRuleIndicators pending = db.getGlobalRuleIndicators();
            assertNotSame(initial, pending);
            assertEquals(GlobalRuleIndicators.LOCAL, pending.scope(1, block, host));
            db.setRuleEnacted(Long.toString(id), 1);
            GlobalRuleIndicators active = db.getGlobalRuleIndicators();
            assertNotSame(pending, active);
            assertSame(active, db.getGlobalRuleIndicators());
            assertEquals(GlobalRuleIndicators.BLOCKED, active.scope(1, block, host));
            ContentValues changed = new ContentValues(); changed.put("ruletext", allow);
            db.updateRuleWithCV(Long.toString(id), changed);
            GlobalRuleIndicators edited = db.getGlobalRuleIndicators();
            assertNotSame(active, edited);
            assertEquals(GlobalRuleIndicators.LOCAL, edited.scope(1, block, host));
            assertEquals(GlobalRuleIndicators.ALLOWED, edited.scope(0, allow, host));
            db.removeRulesById(new Long[]{id});
            assertNotSame(edited, db.getGlobalRuleIndicators());
            assertEquals(GlobalRuleIndicators.LOCAL, db.getGlobalRuleIndicators().scope(0, allow, host));
        } finally { db.removeRulesById(new Long[]{id}); }
    }
}
