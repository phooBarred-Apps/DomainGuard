package heartguard.heartguard.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionAttributionTest {
    private static Packet packet(int uid, int sport, long time) {
        Packet p = new Packet(); p.uid=uid; p.sport=sport; p.time=time; p.saddr="10.0.0.1"; p.daddr="192.0.2.1";
        p.version=4; p.protocol=6; p.dport=443; p.flags="S"; p.allowed=true; return p;
    }
    // Test: Same IP in different apps/ports stays isolated. Why: DNS and handshake evidence must not cross connection identities.
    @Test public void isolatesFlowsAndExpires() {
        ConnectionAttribution names = new ConnectionAttribution(); Packet original=packet(10001, 4567, 1000); names.track(original);
        assertNull(names.inspect(packet(10002,4567,1100), new byte[]{22}));
        assertNull(names.inspect(packet(10001,4568,1100), new byte[]{22}));
        assertSame(original, names.inspect(packet(10001,4567,1100),new byte[]{22}).packet);
        assertNull(names.inspect(packet(10001,4567,17000),new byte[]{3}));
    }
    // Test: Blocked connections are never enrolled and memory is bounded. Why: Identification must not open blocked connections or retain unlimited buffers.
    @Test public void blockedAndBounded() {
        ConnectionAttribution names=new ConnectionAttribution(); Packet blocked=packet(10001,1,1000); blocked.allowed=false; names.track(blocked);
        assertFalse(names.contains(blocked));
        for(int i=0;i<130;i++) names.track(packet(10001,i+2,1000));
        assertFalse(names.contains(packet(10001,2,1000))); assertTrue(names.contains(packet(10001,131,1000)));
        names.clear(); assertFalse(names.contains(packet(10001,131,1000)));
    }
}
