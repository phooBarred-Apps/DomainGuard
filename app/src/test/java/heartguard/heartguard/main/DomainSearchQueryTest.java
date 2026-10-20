package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class DomainSearchQueryTest {
    // Test: Pasted URLs and international hostnames search using the normalized host.
    // Why: URL paths/case and Unicode spelling must not prevent finding a recorded domain.
    @Test public void normalizesUrlsAndInternationalNames() {
        assertEquals("images.example.com", new DomainSearchQuery(" HTTPS://IMAGES.Example.com:443/a?q=x ").text);
        assertEquals("xn--bcher-kva.example", new DomainSearchQuery("https://bücher.example/book").text);
        assertEquals("example.com", new DomainSearchQuery("*.Example.com.").text);
    }

    // Test: A subdomain finds its covering parent rule, excluding lookalike domains.
    // Why: Searching a global list must reveal applicable rules without inventing parent matches.
    @Test public void findsCoveringParentsAtLabelBoundaries() {
        DomainSearchQuery query = new DomainSearchQuery("images.example.com");
        assertTrue(query.matchesRule("example.com"));
        assertTrue(query.coversSearch("Example.COM."));
        assertTrue(query.matchesRule("images.example.com"));
        assertFalse(query.coversSearch("images.example.com"));
        assertFalse(query.matchesRule("ample.com"));
        assertFalse(query.matchesRule("other.example.com"));
        assertFalse(new DomainSearchQuery("notexample.com").matchesRule("example.com"));
        assertFalse(new DomainSearchQuery("1.192.168.1.1").coversSearch("192.168.1.1"));
    }

    // Test: Partial/empty searches work and SQL wildcard characters remain literal.
    // Why: Typing a fragment should narrow results; % and _ must not silently match every domain.
    @Test public void preservesPartialAndLiteralSearches() {
        assertTrue(new DomainSearchQuery("exam").matchesRule("api.example.com"));
        assertTrue(new DomainSearchQuery(" ").matchesRule("example.com"));
        assertEquals("%\\%%", new DomainSearchQuery("%").sqlContains());
        assertEquals("%\\_%", new DomainSearchQuery("_").sqlContains());
        assertEquals("%a\\\\b%", new DomainSearchQuery("a\\b").sqlContains());
    }
}
