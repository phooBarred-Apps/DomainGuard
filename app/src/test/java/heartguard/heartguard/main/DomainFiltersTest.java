package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class DomainFiltersTest {
    // Test: Every filter combination respects the recorded result and only narrows global scope within that result.
    // Why: A current block cannot reclassify an earlier allowed request, or vice versa.
    @Test public void recordedResultsRemainIndependentFromCurrentRules() {
        for (int allowed = 0; allowed < 3; allowed++) for (int blocked = 0; blocked < 3; blocked++) {
            DomainFilters filters = new DomainFilters(allowed, blocked);
            assertEquals(allowed != DomainFilters.HIDE_ALL,
                    filters.includes(filters.category(1, 1, GlobalRuleIndicators.BLOCKED)));
            assertEquals(blocked != DomainFilters.HIDE_ALL,
                    filters.includes(filters.category(0, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(allowed == DomainFilters.SHOW,
                    filters.includes(filters.category(1, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(blocked == DomainFilters.SHOW,
                    filters.includes(filters.category(0, 1, GlobalRuleIndicators.BLOCKED)));
            assertTrue(filters.includes(filters.category(-1, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(filters.encode(), DomainFilters.decode(filters.encode()).encode());
        }
    }

    // Test: Current-status filters follow present rules, including when the recorded result is unknown or different.
    // Why: Changing the basis must reverse mismatched rows without guessing unknown current status.
    @Test public void currentRulesCanReplaceRecordedResults() {
        for (int allowed = 0; allowed < 3; allowed++) for (int blocked = 0; blocked < 3; blocked++) {
            DomainFilters filters = new DomainFilters(allowed, blocked).withBasis(true);
            assertEquals(blocked == DomainFilters.SHOW,
                    filters.includes(filters.category(1, 1, GlobalRuleIndicators.BLOCKED)));
            assertEquals(allowed == DomainFilters.SHOW,
                    filters.includes(filters.category(0, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(allowed != DomainFilters.HIDE_ALL,
                    filters.includes(filters.category(-1, 0, GlobalRuleIndicators.LOCAL)));
            assertEquals(blocked != DomainFilters.HIDE_ALL,
                    filters.includes(filters.category(-1, 1, GlobalRuleIndicators.LOCAL)));
            assertTrue(filters.includes(filters.category(1, -1, GlobalRuleIndicators.LOCAL)));
            assertEquals(new DomainFilters(allowed, blocked).encode(), filters.encode());
        }
    }
}
