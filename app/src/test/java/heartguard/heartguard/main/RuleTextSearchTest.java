package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RuleTextSearchTest {
    // Test: Previous/next wrap at either end and match literally, ignoring case.
    // Why: A long editor must reach every occurrence without changing its rule text.
    @Test public void searchWrapsAtBothEnds() {
        String rules = "EXAMPLE.com\nexampleXcom\nexample.com";
        int last = rules.lastIndexOf("example.com");
        assertEquals(0, RuleTextSearch.find(rules, "example.com", 0, false));
        assertEquals(last, RuleTextSearch.find(rules, "example.com", -1, true));
        assertEquals(last, RuleTextSearch.find(rules, "example.com", 1, false));
        assertEquals(0, RuleTextSearch.find(rules, "example.com", last + 1, false));
        assertEquals(-1, RuleTextSearch.find(rules, "", 0, false));
        assertEquals(-1, RuleTextSearch.find(rules, "missing", 0, true));
    }
}
