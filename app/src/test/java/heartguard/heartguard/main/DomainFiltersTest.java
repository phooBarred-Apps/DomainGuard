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
                    filters.includes(DomainFilters.category(1, 1, GlobalRuleIndicators.BLOCKED)));
            assertEquals(blocked != DomainFilters.HIDE_ALL,
                    filters.includes(DomainFilters.category(0, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(allowed == DomainFilters.SHOW,
                    filters.includes(DomainFilters.category(1, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(blocked == DomainFilters.SHOW,
                    filters.includes(DomainFilters.category(0, 1, GlobalRuleIndicators.BLOCKED)));
            assertTrue(filters.includes(DomainFilters.category(-1, 0, GlobalRuleIndicators.ALLOWED)));
            assertEquals(filters.encode(), DomainFilters.decode(filters.encode()).encode());
        }
    }
}
