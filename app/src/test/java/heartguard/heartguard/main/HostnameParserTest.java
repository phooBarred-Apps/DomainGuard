package heartguard.heartguard.main;

import static heartguard.heartguard.main.HostnameFixture.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostnameParserTest {
    // Test: Every TCP split preserves the same SNI. Why: A handshake rarely maps exactly to a packet or socket write.
    @Test public void tlsAcrossEverySplit() {
        byte[] bytes = hello("mail.example.invalid", false);
        for (int i = 1; i < bytes.length; i++) {
            HostnameParser parser = new HostnameParser();
            assertFalse(parser.feed(Arrays.copyOf(bytes, i)));
            assertTrue(parser.feed(Arrays.copyOfRange(bytes, i, bytes.length)));
            assertEquals("mail.example.invalid", parser.result().name);
            assertEquals(DomainAttribution.TLS, parser.result().source);
        }
    }
    // Test: TLS record fragmentation also works. Why: Reassembled TCP is not necessarily a single TLS record.
    @Test public void tlsAcrossRecords() {
        byte[] bytes = hello("mail.invalid", false), handshake = Arrays.copyOfRange(bytes, 5, bytes.length);
        HostnameParser parser = new HostnameParser();
        assertFalse(parser.feed(record(Arrays.copyOf(handshake, 20))));
        assertTrue(parser.feed(record(Arrays.copyOfRange(handshake, 20, handshake.length))));
        assertEquals("mail.invalid", parser.result().name);
    }
    // Test: ECH never exposes its cover name as the actual target. Why: A visible SNI is misleading when the inner handshake is encrypted.
    @Test public void encryptedHelloHidesCoverName() {
        HostnameParser parser = new HostnameParser(); assertTrue(parser.feed(hello("cover.invalid", true)));
        assertNull(parser.result().name); assertEquals(DomainAttribution.ENCRYPTED, parser.result().source);
    }
    // Test: First HTTP Host is recognized across chunks and later requests ignored. Why: Connection evidence must not become an unbounded request recorder.
    @Test public void httpHostOnlyOnce() {
        HostnameParser parser = new HostnameParser();
        assertFalse(parser.feed("GET / HTTP/1.1\r\nHo".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(parser.feed("st: MAIL.INVALID:8080\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("mail.invalid", parser.result().name);
        parser.feed("GET / HTTP/1.1\r\nHost: second.invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals("mail.invalid", parser.result().name);
    }
    // Test: Conflicting Host fields are rejected. Why: Never choose one of multiple ambiguous names from malformed input.
    @Test public void duplicateHostRejected() {
        HostnameParser parser = new HostnameParser();
        assertTrue(parser.feed("GET / HTTP/1.1\r\nHost: a.invalid\r\nHost: b.invalid\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertNull(parser.result());
    }
    // Test: Oversize, truncated and arbitrary bytes cannot escape parsing or allocate without bounds. Why: Apps control this untrusted input.
    @Test public void malformedInputsAndLimit() {
        HostnameParser parser = new HostnameParser(); assertTrue(parser.feed(new byte[HostnameParser.LIMIT + 1])); assertNull(parser.result());
        Random random = new Random(291);
        for (int i = 0; i < 1000; i++) { byte[] bytes = new byte[random.nextInt(256)]; random.nextBytes(bytes); new HostnameParser().feed(bytes); }
        byte[] truncated = hello("mail.invalid", false); truncated[3] = 127;
        parser = new HostnameParser(); assertTrue(parser.feed(truncated)); assertNull(parser.result());
    }
}
