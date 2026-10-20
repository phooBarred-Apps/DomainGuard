package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class DomainInputTest {
    @Test public void urlBecomesLowercaseDomain() {
        assertEquals("example.com", DomainInput.normalize(" HTTPS://Example.COM:443/a?q=1#section "));
    }
    @Test public void unicodeDomainUsesAsciiForDnsMatching() {
        assertEquals("xn--bcher-kva.example", DomainInput.normalize("https://bücher.example/book"));
    }
    @Test public void wildcardAndTrailingDotUseExistingSubdomainSemantics() {
        assertEquals("example.com", DomainInput.normalize("*.Example.com."));
    }
    @Test public void normalizesIpv4() {
        assertEquals("192.168.1.1", DomainInput.normalize("http://192.168.001.001/path"));
    }
    @Test public void acceptsNumericIpv6WithoutDns() {
        String normalized = DomainInput.normalize("[2001:db8::1]");
        assertEquals("ip:" + normalized + "/128", DomainInput.destination(normalized));
        assertEquals(normalized, DomainInput.globalDomain("allow " + DomainInput.destination(normalized)));
        assertEquals(DomainInput.normalize("2001:db8::1"), DomainInput.normalize("https://[2001:db8::1]/"));
    }
    @Test public void rejectsMalformedOrUnsafeInput() {
        for (String input : new String[]{"", " ", "example.com other.com", "https://u:p@example.com/",
                "ftp://example.com", "example.com:99999", "999.1.1.1", "example..com",
                "https:///example.com", "https://example.com%2f.evil", "-bad.example", "https://[bad::z]/"}) {
            assertThrows(input, IllegalArgumentException.class, () -> DomainInput.normalize(input));
        }
    }
    @Test public void extractsOnlyGlobalAddressRules() {
        assertEquals("example.com", DomainInput.globalDomain("allow host:example.com"));
        assertEquals("example.com", DomainInput.globalDomain("allow host:example.com sticky"));
        assertEquals("1.2.3.4", DomainInput.globalDomain("allow ip:1.2.3.4"));
        assertNull(DomainInput.globalDomain("allow package:app.test host:example.com"));
        assertNull(DomainInput.globalDomain("block package:app.test host:example.com"));
        assertNull(DomainInput.globalDomain("allow directip"));
        assertNull(DomainInput.globalDomain("- allow host:example.com"));
    }
}
