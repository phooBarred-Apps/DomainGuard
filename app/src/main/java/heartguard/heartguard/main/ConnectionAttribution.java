package heartguard.heartguard.main;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short-lived, bounded flow ownership. A hostname is never reused for another connection. */
final class ConnectionAttribution {
    private static final int MAX_FLOWS = 128;
    private static final long MAX_AGE = 15000;
    private final LinkedHashMap<String, Flow> flows = new LinkedHashMap<>();
    static final class Flow {
        final Packet packet;
        final HostnameParser parser = new HostnameParser();
        Flow(Packet packet) { this.packet = packet; }
    }
    static String key(Packet packet) {
        return packet.uid + ":" + packet.version + ":" + packet.protocol + ":" + packet.saddr
                + ":" + packet.sport + ":" + packet.daddr + ":" + packet.dport;
    }
    static String identity(Packet packet) { return key(packet) + ":" + packet.time; }
    synchronized void track(Packet packet) {
        expire(packet.time);
        if (packet.protocol != 6 || !packet.allowed || packet.dport == 53
                || packet.flags == null || !packet.flags.contains("S")) return;
        String key = key(packet);
        flows.remove(key);
        while (flows.size() >= MAX_FLOWS) flows.remove(flows.keySet().iterator().next());
        flows.put(key, new Flow(packet));
    }
    synchronized Flow inspect(Packet packet, byte[] data) {
        expire(packet.time);
        Flow flow = flows.get(key(packet));
        if (flow == null) return null;
        if (flow.parser.feed(data)) flows.remove(key(packet));
        return flow;
    }
    synchronized boolean contains(Packet packet) { return flows.containsKey(key(packet)); }
    synchronized void clear() { flows.clear(); }
    private void expire(long now) {
        Iterator<Map.Entry<String, Flow>> iterator = flows.entrySet().iterator();
        while (iterator.hasNext()) {
            long age = now - iterator.next().getValue().packet.time;
            if (age < 0 || age > MAX_AGE) iterator.remove();
        }
    }
}
