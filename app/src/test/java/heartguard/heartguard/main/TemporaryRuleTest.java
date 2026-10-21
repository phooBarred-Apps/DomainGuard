package heartguard.heartguard.main;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class TemporaryRuleTest {
    // Test: Validate durations and preserve the underlying domain, IP and scope syntax.
    // Why: Raw rules and the UI must enforce the same seven-day limit without changing scope.
    @Test public void boundedDurationPreservesRule() {
        for (String rule : Arrays.asList("allow host:example.com", "blockdomain example.com",
                "block package:app host:example.com", "allow package:app ip:2001:db8::1/128")) {
            assertEquals(rule, TemporaryRuleText.base(TemporaryRuleText.text(rule, 604800)));
            assertEquals(2000, TemporaryRuleText.parse(TemporaryRuleText.text(rule, 1)).expiresAt(1000));
        }
        for (String value : Arrays.asList("0", "604801", "999999999999", "-1", "1.5")) {
            try { TemporaryRuleText.parse("temporary seconds:" + value + " allow host:example.com"); fail(value); }
            catch (IllegalArgumentException expected) { }
        }
    }

    // Test: Expired allows stop matching; expired blocks reveal existing permanent allows.
    // Why: An alarm may be delayed, so the policy itself must enforce the saved deadline.
    @Test public void expiryRestoresPolicyWithoutDependingOnCleanup() {
        String text = "temporary seconds:60 block package:app host:example.com";
        MyRule owner = new MyRule(text, MyRule.MAJOR_CATEGORY_BLOCK);
        RuleForApp temporary = new TemporaryRuleForApp(owner, new DomainRule(text, "example.com", 0));
        RuleForApp permanent = new DomainRule("allow host:example.com", "example.com", 1);
        assertEquals(0, RuleDecision.resolve(Collections.singletonList(temporary), Collections.singletonList(permanent),
                "192.0.2.1", Collections.singletonList("cdn.example.com")).allowed);
        owner.temporaryExpiresAt = System.currentTimeMillis() - 1;
        assertEquals(1, RuleDecision.resolve(Collections.singletonList(temporary), Collections.singletonList(permanent),
                "192.0.2.1", Collections.singletonList("cdn.example.com")).allowed);
        RuleForApp allow = new TemporaryRuleForApp(owner, permanent);
        assertNull(allow.isAllowed("192.0.2.1", Collections.singletonList("example.com")));
        assertTrue(allow.matchesAddr("example.com"));
    }

    // Test: Backup/restore preserves activation timestamps and therefore the original expiry.
    // Why: Restoring an active temporary rule must not grant it a fresh full duration.
    @Test public void restoreKeepsOriginalExpiry() throws Exception {
        String text = TemporaryRuleText.text("allow host:example.com", 300);
        RulesBackup.Row original = new RulesBackup.Row(text, 1000, 6000, 1, 400, 0);
        RulesBackup.Row restored = RulesBackup.decode(RulesBackup.encode(Collections.singletonList(original))).get(0);
        assertEquals(306000, TemporaryRuleText.parse(restored.text).expiresAt(restored.enactAt));
        assertEquals(1, restored.enacted);
    }

    // Test: Temporary global rules retain the globe badge, while per-app rules do not gain it.
    // Why: Duration must not obscure scope or mislabel a local exception as global.
    @Test public void scopeBadgesUseUnderlyingRule() {
        String allow = TemporaryRuleText.text("allow host:example.com", 60);
        String block = TemporaryRuleText.text("blockdomain blocked.test", 60);
        String local = TemporaryRuleText.text("allow package:app host:example.com", 60);
        GlobalRuleIndicators icons = new GlobalRuleIndicators(Arrays.asList(allow, block, local));
        assertEquals(GlobalRuleIndicators.ALLOWED, icons.scope(0, allow, "example.com"));
        assertEquals(GlobalRuleIndicators.BLOCKED, icons.scope(1, null, "sub.blocked.test"));
        assertEquals(GlobalRuleIndicators.LOCAL, icons.scope(0, local, "example.com"));
    }
}
