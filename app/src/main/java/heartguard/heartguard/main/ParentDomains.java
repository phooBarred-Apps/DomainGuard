package heartguard.heartguard.main;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.IDN;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parent choices stop at the registrable domain, including private public suffixes. */
final class ParentDomains {
    private final Set<String> exact = new HashSet<>(), wildcard = new HashSet<>(), exceptions = new HashSet<>();

    ParentDomains(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Set<String> target = line.startsWith("!") ? exceptions : line.startsWith("*.") ? wildcard : exact;
            if (target == exceptions) line = line.substring(1);
            if (target == wildcard) line = line.substring(2);
            // The PSL includes scripts newer than older Android Unicode tables.
            // Preserve their punycode boundaries even when typed Unicode is not supported.
            target.add(IDN.toASCII(line, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT));
        }
    }

    List<String> choices(String input) {
        List<String> result = new ArrayList<>();
        String host;
        try { host = DomainInput.normalize(input); } catch (IllegalArgumentException invalid) { return result; }
        if (host.contains(":") || host.matches("[0-9.]+")) return result;
        String[] labels = host.split("\\.");
        int suffix = 1; // PSL's implicit wildcard rule for unlisted TLDs.
        for (int start = 0; start < labels.length; start++) {
            String candidate = join(labels, start);
            int length = labels.length - start;
            if (exceptions.contains(candidate)) { suffix = length - 1; break; }
            if (exact.contains(candidate)) suffix = Math.max(suffix, length);
            if (start > 0 && wildcard.contains(candidate)) suffix = Math.max(suffix, length + 1);
        }
        for (int start = 1; labels.length - start > suffix; start++) result.add(join(labels, start));
        return result;
    }

    private static String join(String[] labels, int start) {
        StringBuilder joined = new StringBuilder();
        for (int i = start; i < labels.length; i++) {
            if (joined.length() > 0) joined.append('.');
            joined.append(labels[i]);
        }
        return joined.toString();
    }
}
