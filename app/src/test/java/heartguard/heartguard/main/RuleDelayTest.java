package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuleDelayTest {
    // Test: Accept whole seconds, including zero and the supported maximum.
    // Why: The settings field must pass valid durations unchanged to the existing rule engine.
    @Test public void acceptsWholeSecondsIncludingOffAndMaximum() {
        assertEquals(0, RuleDelay.parseSeconds("0"));
        assertEquals(60, RuleDelay.parseSeconds(" 60 "));
        assertEquals(3600, RuleDelay.parseSeconds("03600"));
        assertEquals(604800, RuleDelay.parseSeconds("604800"));
    }

    // Test: Reject negative, fractional, nonnumeric and overflowing durations.
    // Why: Invalid input must never queue a rule or silently become a shorter delay.
    @Test public void rejectsInvalidOrUnsupportedDurations() {
        for (String value : new String[]{"", " ", "-1", "+1", "1.5", "1 minute", "604801", "2147483648"}) {
            try {
                RuleDelay.parseSeconds(value);
                fail("Accepted: " + value);
            } catch (IllegalArgumentException expected) {
                // No rule should be queued for invalid input.
            }
        }
    }

    // Test: All four input units convert exactly to seconds and enforce the same seven-day maximum.
    // Why: Unit multiplication must not overflow or let the UI bypass the rule engine's delay limit.
    @Test public void unitsConvertWithoutOverflowOrChangingTheLimit() {
        assertEquals(60, RuleDelay.parseSeconds("1", RuleDelay.Unit.MINUTES));
        assertEquals(7200, RuleDelay.parseSeconds("2", RuleDelay.Unit.HOURS));
        assertEquals(86400, RuleDelay.parseSeconds("1", RuleDelay.Unit.DAYS));
        for (RuleDelay.Unit unit : RuleDelay.Unit.values()) {
            int maximum = 604800 / unit.seconds;
            assertEquals(0, RuleDelay.parseSeconds("0", unit));
            assertEquals(604800, RuleDelay.parseSeconds(Integer.toString(maximum), unit));
            for (String invalid : new String[]{Integer.toString(maximum + 1), "2147483647", "1.5", "-1", ""}) {
                try {
                    RuleDelay.parseSeconds(invalid, unit);
                    fail("Accepted " + invalid + " " + unit);
                } catch (IllegalArgumentException expected) {
                    // Invalid values must not queue a delay rule.
                }
            }
        }
    }

    // Test: Existing delays select a convenient unit only when it preserves every second.
    // Why: Opening and saving a manually entered delay must never round or shorten it.
    @Test public void initialUnitNeverRoundsAnExistingDelay() {
        assertEquals(RuleDelay.Unit.SECONDS, RuleDelay.displayUnit(0));
        assertEquals(RuleDelay.Unit.SECONDS, RuleDelay.displayUnit(90));
        assertEquals(RuleDelay.Unit.SECONDS, RuleDelay.displayUnit(86401));
        assertEquals(RuleDelay.Unit.MINUTES, RuleDelay.displayUnit(60));
        assertEquals(RuleDelay.Unit.MINUTES, RuleDelay.displayUnit(5400));
        assertEquals(RuleDelay.Unit.HOURS, RuleDelay.displayUnit(7200));
        assertEquals(RuleDelay.Unit.DAYS, RuleDelay.displayUnit(604800));
    }

    // Test: Increasing the delay is immediate; decreasing or disabling it still waits.
    // Why: Exposing a shortcut in Settings must preserve the original protection semantics.
    @Test public void increasingDelayIsImmediateButReducingAndDisablingMustWait() {
        assertEquals(0, new DelayRule("delay 120").getDelayToAdd(null, 60));
        assertEquals(60, new DelayRule("delay 60").getDelayToAdd(null, 120));
        assertEquals(120, new DelayRule("delay 0").getDelayToAdd(null, 120));
    }
}
