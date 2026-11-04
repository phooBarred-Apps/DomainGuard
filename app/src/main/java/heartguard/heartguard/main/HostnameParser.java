package heartguard.heartguard.main;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded inspection of only the first plaintext HTTP header or TLS ClientHello.
 * Input is the ordered stream actually forwarded, never raw/retransmitted TCP segments.
 * No response bodies, certificates or encrypted application content are decrypted. */
final class HostnameParser {
    static final int LIMIT = 32768;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean finished;
    private DomainAttribution result;

    boolean feed(byte[] data) {
        if (finished) return true;
        if (data.length > LIMIT - bytes.size()) return finish(null);
        bytes.write(data, 0, data.length);
        byte[] input = bytes.toByteArray();
        if (input.length == 0) return false;
        try {
            if ((input[0] & 255) == 22) return tls(input);
            return http(input);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            return finish(null);
        }
    }

    DomainAttribution result() { return result; }
    private boolean finish(DomainAttribution value) {
        result = value; finished = true; bytes.reset(); return true;
    }
    private static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static int u24(byte[] b, int p) { return ((b[p] & 255) << 16) | u16(b, p + 1); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException(); }

    private boolean tls(byte[] input) {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        int p = 0;
        while (p + 5 <= input.length) {
            require(input[p] == 22 && input[p + 1] == 3);
            int size = u16(input, p + 3);
            require(size > 0 && size <= 18432);
            if (p + 5 + size > input.length) return false;
            handshake.write(input, p + 5, size);
            byte[] hello = handshake.toByteArray();
            if (hello.length >= 4) {
                require(hello[0] == 1);
                int length = u24(hello, 1);
                require(length <= LIMIT - 4);
                if (hello.length >= length + 4) return clientHello(Arrays.copyOf(hello, length + 4));
            }
            p += 5 + size;
        }
        return false;
    }

    private boolean clientHello(byte[] hello) {
        require(hello.length >= 39);
        int p = 38;
        int session = hello[p++] & 255;
        require(session <= 32 && p + session + 2 <= hello.length);
        p += session;
        int ciphers = u16(hello, p); p += 2;
        require(ciphers >= 2 && ciphers % 2 == 0 && p + ciphers + 1 <= hello.length);
        p += ciphers;
        int compression = hello[p++] & 255;
        require(compression > 0 && p + compression <= hello.length);
        p += compression;
        if (p == hello.length) return finish(null);
        require(p + 2 <= hello.length);
        int extensions = u16(hello, p); p += 2;
        require(p + extensions == hello.length);
        String host = null;
        boolean encrypted = false, seenName = false;
        while (p < hello.length) {
            require(p + 4 <= hello.length);
            int type = u16(hello, p), size = u16(hello, p + 2); p += 4;
            int end = p + size;
            require(end <= hello.length);
            if (type == 0xfe0d) encrypted = true; // Never present an ECH cover name as the destination.
            if (type == 0) {
                require(!seenName && size >= 5 && u16(hello, p) == size - 2);
                seenName = true;
                int n = p + 2;
                while (n < end) {
                    require(n + 3 <= end);
                    int nameType = hello[n++] & 255, length = u16(hello, n); n += 2;
                    require(length > 0 && n + length <= end);
                    if (nameType == 0) {
                        require(host == null);
                        host = DomainAttribution.hostname(new String(hello, n, length, StandardCharsets.US_ASCII));
                        require(host != null);
                    }
                    n += length;
                }
            }
            p = end;
        }
        if (encrypted) return finish(new DomainAttribution(null, DomainAttribution.ENCRYPTED, ""));
        return finish(host == null ? null : new DomainAttribution(host, DomainAttribution.TLS, ""));
    }

    private boolean http(byte[] input) {
        String text = new String(input, StandardCharsets.US_ASCII);
        int line = text.indexOf("\r\n");
        if (line < 0) {
            if (input.length > 8192 || !Character.isUpperCase((char) input[0])) return finish(null);
            return false;
        }
        if (!text.substring(0, line).matches("[A-Z]+ [^\r\n ]+ HTTP/1\\.[01]")) return finish(null);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) return false;
        String host = null;
        for (String header : text.substring(line + 2, end).split("\r\n")) {
            if (header.regionMatches(true, 0, "Host:", 0, 5)) {
                require(host == null);
                String value = header.substring(5).trim();
                int colon = value.lastIndexOf(':');
                if (colon >= 0) {
                    require(value.substring(colon + 1).matches("[0-9]{1,5}"));
                    value = value.substring(0, colon);
                }
                host = DomainAttribution.hostname(value);
                require(host != null);
            }
        }
        return finish(host == null ? null : new DomainAttribution(host, DomainAttribution.HTTP, ""));
    }
}
