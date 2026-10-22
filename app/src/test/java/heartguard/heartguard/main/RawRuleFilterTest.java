package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class RawRuleFilterTest {
    @Test public void blocksIncludeTemporaryRulesAndPendingRemovalsWithoutIncludingAllows() {
        RawRuleFilter filter = new RawRuleFilter();
        filter.allTypes = RawRuleFilter.BLOCK;
        assertTrue(filter.matches("block package:com.example.app host:example.com"));
        assertTrue(filter.matches("- temporary seconds:300 blockdomain example.com"));
        assertTrue(filter.matches("temporary seconds:60 block package:com.example.app ip:192.0.2.1"));
        assertFalse(filter.matches("allow host:block.example.com"));
        assertFalse(filter.matches("# blockdomain example.com"));
    }

    @Test public void globalScopeExcludesPackagesUidsCommentsAndUnknownSyntax() {
        RawRuleFilter filter = new RawRuleFilter();
        filter.globalOnly = true;
        assertTrue(filter.matches("allow host:example.com"));
        assertTrue(filter.matches("- temporary seconds:60 allow ip:192.0.2.1"));
        assertTrue(filter.matches("delay 21600"));
        assertTrue(filter.matches("pause seconds:60"));
        assertTrue(filter.matches("ignore host:example.com"));
        assertTrue(filter.matches("feature enabled"));
        assertFalse(filter.matches("allow uid:10001"));
        assertFalse(filter.matches("delay package:com.example.app 60"));
        assertFalse(filter.matches("temporary seconds:60 allow package:com.example.app host:example.com"));
        assertFalse(filter.matches("pause package:com.example.app seconds:60"));
        assertFalse(filter.matches("# global comment"));
        assertFalse(filter.matches("unrecognized example"));
        assertFalse(filter.matches(null));
    }

    @Test public void otherContainsOnlyNonAllowBlockRulesAndNoMalformedTemporaryCrash() {
        RawRuleFilter filter = new RawRuleFilter();
        filter.globalOnly = true;
        filter.globalTypes = RawRuleFilter.OTHER;
        assertTrue(filter.matches("- delay 60"));
        assertTrue(filter.matches("partner name:example totp:FAKEONLY"));
        assertFalse(filter.matches("allow host:example.com"));
        assertFalse(filter.matches("temporary seconds:60 blockdomain example.com"));
        assertFalse(filter.matches("temporary seconds:bad blockdomain example.com"));
    }

    @Test public void scopesStartWithAllTypesAndKeepTheirOwnToggleChoices() {
        RawRuleFilter filter = new RawRuleFilter();
        assertEquals(RawRuleFilter.ALL, filter.types());
        filter.setEnabled(RawRuleFilter.OTHER, false);
        filter.globalOnly = true;
        assertEquals(RawRuleFilter.ALL, filter.types());
        filter.setEnabled(RawRuleFilter.ALLOW, false);
        filter.setEnabled(RawRuleFilter.OTHER, false);
        assertTrue(filter.matches("blockdomain example.com"));
        assertFalse(filter.matches("block package:example host:example.com"));
        filter.setEnabled(RawRuleFilter.BLOCK, false);
        assertFalse(filter.matches("blockdomain example.com"));
        filter.globalOnly = false;
        assertEquals(RawRuleFilter.BLOCK | RawRuleFilter.ALLOW, filter.types());
    }
}
