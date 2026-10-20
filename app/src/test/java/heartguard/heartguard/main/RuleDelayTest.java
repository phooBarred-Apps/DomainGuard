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

    // Test: Increasing the delay is immediate; decreasing or disabling it still waits.
    // Why: Exposing a shortcut in Settings must preserve the original protection semantics.
    @Test public void increasingDelayIsImmediateButReducingAndDisablingMustWait() {
        assertEquals(0, new DelayRule("delay 120").getDelayToAdd(null, 60));
        assertEquals(60, new DelayRule("delay 60").getDelayToAdd(null, 120));
        assertEquals(120, new DelayRule("delay 0").getDelayToAdd(null, 120));
    }
}
