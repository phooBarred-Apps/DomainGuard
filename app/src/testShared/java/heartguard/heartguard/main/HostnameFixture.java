package heartguard.heartguard.main;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Synthetic wire data shared by unit and native-bridge tests; excluded from production. */
final class HostnameFixture {
    static byte[] hello(String host, boolean encrypted) {
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        word(ext, 0); word(ext, name.length + 5); word(ext, name.length + 3);
        ext.write(0); word(ext, name.length); ext.write(name, 0, name.length);
        if (encrypted) { word(ext, 0xfe0d); word(ext, 1); ext.write(0); }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(3); body.write(3); body.write(new byte[32], 0, 32); body.write(0);
        word(body, 2); word(body, 0x1301); body.write(1); body.write(0);
        word(body, ext.size()); byte[] extensions = ext.toByteArray(); body.write(extensions, 0, extensions.length);
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(1); handshake.write(0); word(handshake, body.size());
        byte[] content = body.toByteArray(); handshake.write(content, 0, content.length);
        return record(handshake.toByteArray());
    }
    static byte[] record(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(22); out.write(3); out.write(1); word(out, bytes.length); out.write(bytes, 0, bytes.length); return out.toByteArray();
    }
    private static void word(ByteArrayOutputStream out, int value) { out.write(value >> 8); out.write(value); }
}
