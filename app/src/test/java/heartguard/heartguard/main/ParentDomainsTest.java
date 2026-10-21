package heartguard.heartguard.main;

import org.junit.Test;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ParentDomainsTest {
    // Test: Every parent is offered down to the registrable host, including wildcard and private suffixes.
    // Why: Removing subdomains must never suggest allowing an entire public suffix or shared hosting provider.
    @Test public void respectsSuffixBoundaries() throws Exception {
        ParentDomains domains = new ParentDomains(new StringReader("com\nco.uk\ngithub.io\n*.ck\n!www.ck\n"));
        assertEquals(Arrays.asList("bar.cash-f.squarecdn.com", "cash-f.squarecdn.com", "squarecdn.com"), domains.choices("foo.bar.cash-f.squarecdn.com"));
        assertEquals(Arrays.asList("example.co.uk"), domains.choices("a.example.co.uk"));
        assertEquals(Arrays.asList("project.github.io"), domains.choices("cdn.project.github.io"));
        assertEquals(Arrays.asList("www.ck"), domains.choices("a.www.ck"));
        assertTrue(domains.choices("a.test.ck").isEmpty());
        assertTrue(domains.choices("192.0.2.1").isEmpty());
        assertTrue(domains.choices("2001:db8::1").isEmpty());
        assertTrue(domains.choices("bad domain").isEmpty());
    }
    // Test: The full bundled list parses and handles international names.
    // Why: One unsupported entry must not silently disable the real offline dropdown.
    @Test public void bundledListParses() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream("src/main/res/raw/public_suffix_list.dat"), StandardCharsets.UTF_8)) {
            ParentDomains domains = new ParentDomains(reader);
            assertEquals(Arrays.asList("squarecdn.com"), domains.choices("cash-f.squarecdn.com"));
            assertEquals(Arrays.asList("xn--bcher-kva.de"), domains.choices("www.bücher.de"));
            assertTrue(domains.choices("co.uk").isEmpty());
        }
    }
}
