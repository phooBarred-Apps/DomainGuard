package heartguard.heartguard.main;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DomainAttributionTest {
    // Test: A shared IP never picks an arbitrary hostname. Why: This can make unrelated sites appear under the wrong app.
    @Test public void sharedAddressRemainsAmbiguous() {
        DomainAttribution result = DomainAttribution.resolve(10001, 1000, Arrays.asList(
                new DomainAttribution.Dns(10002, "events.news.invalid", 900, 1100),
                new DomainAttribution.Dns(10003, "mail.example.invalid", 900, 1100)));
        assertNull(result.name); assertEquals(DomainAttribution.AMBIGUOUS, result.source);
        assertTrue(result.candidates.contains("mail.example.invalid"));
    }
    // Test: This app's valid DNS evidence wins over other apps. Why: Shared cache entries must not override app-specific observations.
    @Test public void appLookupIsPreferredButStillInferred() {
        DomainAttribution result = DomainAttribution.resolve(10001, 1000, Arrays.asList(
                new DomainAttribution.Dns(10002, "a.invalid", 990, 2000),
                new DomainAttribution.Dns(10001, "mail.invalid", 900, 1100)));
        assertEquals("mail.invalid", result.name); assertEquals(DomainAttribution.DNS_APP, result.source);
        assertFalse(result.observed());
    }
    // Test: Resolver/system ownership is not treated as app-specific proof. Why: Android can share its DNS resolver across apps.
    @Test public void systemLookupIsShared() {
        DomainAttribution result = DomainAttribution.resolve(0, 1000,
                Collections.singletonList(new DomainAttribution.Dns(0, "mail.invalid", 900, 1100)));
        assertEquals(DomainAttribution.DNS_SHARED, result.source);
    }
    // Test: Expired and future observations are ignored. Why: Firewall TTL compatibility settings must not make stale display evidence valid.
    @Test public void usesObservationTimeAndExpiry() {
        DomainAttribution result = DomainAttribution.resolve(10001, 1000, Arrays.asList(
                new DomainAttribution.Dns(10001, "old.invalid", 800, 1000),
                new DomainAttribution.Dns(10001, "future.invalid", 1001, 2000)));
        assertNull(result.name); assertEquals(DomainAttribution.UNKNOWN, result.source);
    }
    // Test: Hostnames normalize case/trailing dots and reject control/injection characters. Why: Untrusted wire data reaches UI and rule inputs.
    @Test public void validatesNames() {
        assertEquals("mail.invalid", DomainAttribution.hostname("MAIL.INVALID."));
        for (String bad : Arrays.asList("", "a..invalid", "-a.invalid", "a.invalid\nHost:evil", "<b>", "a/b", "a\u0000.invalid"))
            assertNull(bad, DomainAttribution.hostname(bad));
    }
}
