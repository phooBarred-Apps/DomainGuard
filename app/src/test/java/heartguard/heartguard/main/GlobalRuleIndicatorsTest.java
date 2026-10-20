package heartguard.heartguard.main;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class GlobalRuleIndicatorsTest {
    // Test: Only an active global allow receives the global indicator, including IP and advanced global rules.
    // Why: Pending or removed allow rules must not look active; package rules must retain their single icon.
    @Test public void activeGlobalAllowsAreDistinguishedFromLocalAndRemovedRules() {
        GlobalRuleIndicators policy = new GlobalRuleIndicators(Arrays.asList("allow host:example.com",
                "allow package:test.app host:example.com", "allow ip:192.0.2.1", "allow directip"));
        assertEquals(GlobalRuleIndicators.ALLOWED, policy.scope(0, "allow host:example.com", "example.com"));
        assertEquals(GlobalRuleIndicators.ALLOWED, policy.scope(0, "allow ip:192.0.2.1", "192.0.2.1"));
        assertEquals(GlobalRuleIndicators.ALLOWED, policy.scope(0, "allow directip", "192.0.2.1"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(0, "allow package:test.app host:example.com", "example.com"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(0, "allow host:removed.com", "removed.com"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(1, "allow host:example.com", "example.com"));
    }

    // Test: A global blacklist marks blocked domains and subdomains, with case/trailing-dot normalization.
    // Why: Similar suffixes must not inherit the global marker and a local/default block must stay single.
    @Test public void globalBlacklistUsesDomainBoundaries() {
        GlobalRuleIndicators policy = new GlobalRuleIndicators(Arrays.asList("blockdomain example.com", "blockdomain trial.org try"));
        assertEquals(GlobalRuleIndicators.BLOCKED, policy.scope(1, null, "SUB.EXAMPLE.COM."));
        assertEquals(GlobalRuleIndicators.BLOCKED, policy.scope(1, null, "trial.org"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(1, null, "notexample.com"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(1, null, "example.com.other"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(-1, null, "example.com"));
        assertEquals(GlobalRuleIndicators.LOCAL, policy.scope(1, null, "unrelated.org"));
    }
}
