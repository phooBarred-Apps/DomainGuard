package heartguard.heartguard.main;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Evidence about a connection, separate from the IP-based firewall policy. */
final class DomainAttribution {
    static final int UNKNOWN = 0, LEGACY = 1, DNS_SHARED = 2, DNS_APP = 3,
            TLS = 4, HTTP = 5, ENCRYPTED = 6, AMBIGUOUS = 7;
    final String name, candidates;
    final int source;

    DomainAttribution(String name, int source, String candidates) {
        this.name = name;
        this.source = source;
        this.candidates = candidates == null ? "" : candidates;
    }

    boolean observed() { return source == TLS || source == HTTP; }
    String destination(String ip) { return name == null ? ip : name; }

    static final class Dns {
        final int uid;
        final String name;
        final long time, expires;
        Dns(int uid, String name, long time, long expires) {
            this.uid = uid; this.name = name; this.time = time; this.expires = expires;
        }
    }

    static DomainAttribution resolve(int uid, long time, List<Dns> records) {
        Set<String> own = new LinkedHashSet<>(), shared = new LinkedHashSet<>();
        for (Dns record : records) {
            String name = hostname(record.name);
            if (name == null || record.time > time || record.expires <= time) continue;
            shared.add(name);
            // System resolvers and unknown owners do not establish which app asked.
            if (uid >= 10000 && record.uid == uid) own.add(name);
        }
        Set<String> names = own.isEmpty() ? shared : own;
        StringBuilder candidateText = new StringBuilder();
        int count = 0;
        for (String name : names) {
            if (count++ == 32) break;
            if (candidateText.length() > 0) candidateText.append('\n');
            candidateText.append(name);
        }
        String candidates = candidateText.toString();
        if (names.size() == 1)
            return new DomainAttribution(names.iterator().next(), own.isEmpty() ? DNS_SHARED : DNS_APP, candidates);
        return new DomainAttribution(null, names.isEmpty() ? UNKNOWN : AMBIGUOUS, candidates);
    }

    static String hostname(String value) {
        if (value == null) return null;
        String name = value.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || name.length() > 253) return null;
        for (String label : name.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return null;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' && c != '_') return null;
            }
        }
        return name;
    }
}
