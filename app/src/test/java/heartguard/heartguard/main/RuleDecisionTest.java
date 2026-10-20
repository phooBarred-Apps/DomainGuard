package heartguard.heartguard.main;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class RuleDecisionTest {
    private final RuleForApp global = new DomainRule("allow host:example.com", "example.com", 1);
    private final RuleForApp block = new DomainRule("block package:app host:example.com", "example.com", 0);
    private final List<String> names = Collections.singletonList("cdn.example.com");
    private RuleAllowData decide(List<RuleForApp> app) {
        return RuleDecision.resolve(app, Collections.singletonList(global), "192.0.2.1", names);
    }
    @Test public void globalWhitelistCoversAppsWithoutAnyRules() { assertEquals(1, decide(null).allowed); }
    @Test public void explicitBlockOverridesGlobal() { assertEquals(0, decide(Collections.singletonList(block)).allowed); }
    @Test public void explicitBlockOverridesAppAllowRegardlessOfOrder() {
        assertEquals(0, decide(Arrays.asList(global, block)).allowed);
        assertEquals(0, decide(Arrays.asList(block, global)).allowed);
    }
    @Test public void removingExceptionRestoresGlobalAllow() { assertEquals(1, decide(Collections.emptyList()).allowed); }
    @Test public void blocksOnlyMatchingDomains() {
        RuleForApp unrelated = new DomainRule("block", "other.example", 0);
        assertEquals(1, decide(Collections.singletonList(unrelated)).allowed);
    }
    @Test public void respectsDnsLabelBoundaries() {
        assertFalse(global.matchesAddr("notexample.com"));
        assertFalse(global.matchesAddr("example.com.evil"));
        assertTrue(global.matchesAddr("a.example.com"));
    }
    @Test public void matchesCaseAndRootDot() { assertTrue(global.matchesAddr("CDN.EXAMPLE.COM.")); }
    @Test public void anyBlockedDnsAliasWins() {
        RuleAllowData result = RuleDecision.resolve(Collections.singletonList(block), Collections.singletonList(global),
                "192.0.2.1", Arrays.asList("permitted.test", "example.com"));
        assertEquals(0, result.allowed);
    }
    @Test public void ipBlockOverridesHostWhitelist() {
        assertEquals(0, decide(Collections.singletonList(new IPRule("block ip", "192.0.2.1", 0))).allowed);
    }
    @Test public void unrelatedTrafficKeepsDefaultDecision() {
        assertNull(RuleDecision.resolve(null, Collections.singletonList(global), "192.0.2.2",
                Collections.singletonList("other.example")));
    }
}
