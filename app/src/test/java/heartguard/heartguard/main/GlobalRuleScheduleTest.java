package heartguard.heartguard.main;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import static org.junit.Assert.*;

public class GlobalRuleScheduleTest {
    // Test: Default and shorter overrides use the global start; longer/shared app delays retain the latest deadline.
    // Why: A global rule must not grant access early to a slow app or to another package sharing its UID.
    @Test public void independentDeadlinesUseTheOriginalRequest() {
        Map<String, Integer> delays = new HashMap<>();
        delays.put("chrome", 21600); delays.put("peer", 25200); delays.put("shorter", 0);
        GlobalRuleSchedule schedule = GlobalRuleSchedule.capture(1000, 1, delays);
        assertEquals(2000, schedule.readyAt(new String[]{"normal"}));
        assertEquals(2000, schedule.readyAt(new String[]{"shorter"}));
        assertEquals(21601000, schedule.readyAt(new String[]{"chrome"}));
        assertEquals(25201000, schedule.readyAt(new String[]{"chrome", "peer"}));
        assertEquals(2000, schedule.readyAt(null));
        assertEquals(2000, schedule.readyAt(new String[0]));
        assertEquals(25201000, schedule.readyAt(null, true));
        assertEquals(25201000, schedule.readyAt(new String[0], true));
        assertEquals(2000, schedule.readyAt(new String[]{"normal"}, true));
    }

    // Test: Four-hour global access never reaches a six-hour app; eight-hour access leaves only two hours.
    // Why: The user explicitly rejected restarting a global temporary duration for each app.
    @Test public void temporaryGlobalRulesHaveOneExpiry() {
        GlobalRuleSchedule schedule = GlobalRuleSchedule.capture(0, 1, Collections.singletonMap("chrome", 21600));
        long fourHours = 1000 + 14400000, eightHours = 1000 + 28800000;
        assertEquals(0, schedule.next(1000, fourHours));
        assertEquals(1, schedule.excludedCount(1000, fourHours));
        assertEquals(21600000, schedule.next(1000, eightHours));
        assertEquals(0, schedule.excludedCount(1000, eightHours));
        assertEquals(7201000, eightHours - schedule.readyAt(new String[]{"chrome"}));
        assertFalse(schedule.retire(21600000).hasWaits());
    }

    // Test: Stored deadlines round-trip by package name, malformed numbers fail and legacy null keeps existing behavior.
    // Why: Backups and reinstalls must neither restart waits nor assign another app's UID to a saved override.
    @Test public void storagePreservesDeadlinesAndRejectsCorruption() {
        GlobalRuleSchedule schedule = GlobalRuleSchedule.capture(1000, 1, Collections.singletonMap("missing.app", 21600));
        assertEquals(schedule.readyAt(new String[]{"missing.app"}), GlobalRuleSchedule.parse(schedule.encode()).readyAt(new String[]{"missing.app"}));
        assertFalse(GlobalRuleSchedule.parse(null).hasWaits());
        assertEquals(Long.MAX_VALUE, GlobalRuleSchedule.INVALID.readyAt(new String[]{"anything"}));
        assertEquals(Long.MAX_VALUE, GlobalRuleSchedule.INVALID.readyAt(null, false));
        for (String corrupt : new String[]{"no", "{\"app\":-1}", "{\"app\":1.5}", "{\"app\":1e99}"}) {
            try { GlobalRuleSchedule.parse(corrupt); fail("Accepted " + corrupt); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
