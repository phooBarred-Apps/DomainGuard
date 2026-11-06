package heartguard.heartguard.main;

import java.io.*;
import java.net.*;
import java.util.Arrays;

/** Shell-UID client for disposable emulator tests; waits for actual replies before closing. */
public final class NativeTrafficClient {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException();
        int port = Integer.parseInt(args[0]);
        if ("route".equals(args[2])) {
            // UDP connect selects the shell UID's route without sending a payload.
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("10.0.2.2"), port);
                System.out.println("fixture-route:" + socket.getLocalAddress().getHostAddress());
            }
            return;
        }
        byte[] data;
        try (FileInputStream input = new FileInputStream(args[1]); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (bytes.size() + count > 4096) throw new IllegalArgumentException();
                bytes.write(chunk, 0, count);
            }
            data = bytes.toByteArray();
        }
        if ("dns-udp".equals(args[2])) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(4000);
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("10.0.2.2"), port));
                byte[] reply = new byte[512]; DatagramPacket packet = new DatagramPacket(reply, reply.length);
                socket.receive(packet); dnsResult(Arrays.copyOf(reply, packet.getLength()));
                System.out.println("fixture-received");
            } catch (IOException blocked) { System.out.println("fixture-blocked"); }
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("10.0.2.2", port), 4000);
            socket.setSoTimeout(4000);
            OutputStream output = socket.getOutputStream();
            output.write(data, 0, Math.min(8, data.length)); output.flush();
            if (data.length > 8) { output.write(data, 8, data.length - 8); output.flush(); }
            DataInputStream input = new DataInputStream(socket.getInputStream());
            if ("dns".equals(args[2])) {
                int size = input.readUnsignedShort();
                if (size < 12 || size > 512) throw new IOException("Invalid fixture response");
                byte[] reply = new byte[size]; input.readFully(reply); dnsResult(reply);
            } else {
                byte[] reply = new byte[data.length]; input.readFully(reply);
                if (!Arrays.equals(data, reply)) throw new IOException("Echo mismatch");
            }
            System.out.println("fixture-received");
        } catch (IOException blocked) { System.out.println("fixture-blocked"); }
    }
    private static void dnsResult(byte[] reply) throws IOException {
        if (reply.length < 12) throw new IOException("Short fixture response");
        System.out.println("fixture-dns-rcode-" + (reply[3] & 15) + "-answers-" +
                (((reply[6] & 255) << 8) | (reply[7] & 255)));
    }
}
