package heartguard.heartguard.main;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Turns a pasted URL into a host rule. Paths, ports and queries are not firewall boundaries. */
final class DomainInput {
    private DomainInput() {}

    static String normalize(String input) {
        String value = input.trim();
        if (value.isEmpty() || value.matches(".*\\s.*"))
            throw new IllegalArgumentException("Enter a domain or URL.");
        if (value.startsWith("*."))
            value = value.substring(2);
        // A colon-containing bare numeric IPv6 literal must not be parsed as a URL scheme.
        if (value.matches("[0-9a-fA-F:]+") && value.contains(":"))
            return ipv6(value);
        try {
            URI uri = new URI(value.contains("://") ? value : "https://" + value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Use an HTTP or HTTPS URL.");
            if (uri.getRawUserInfo() != null)
                throw new IllegalArgumentException("URLs containing credentials are not supported.");
            String authority = uri.getRawAuthority();
            if (authority == null || authority.contains("@"))
                throw new IllegalArgumentException("Enter a valid host.");
            String host;
            if (authority.startsWith("[")) {
                host = uri.getHost();
                if (host == null || uri.getPort() > 65535)
                    throw new IllegalArgumentException("Invalid IPv6 URL.");
                return ipv6(host.substring(1, host.length() - 1));
            }
            String[] parts = authority.split(":", -1);
            if (parts.length > 2 || (parts.length == 2 &&
                    (!parts[1].matches("[0-9]{1,5}") || Integer.parseInt(parts[1]) > 65535)))
                throw new IllegalArgumentException("Invalid port.");
            host = IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.endsWith("."))
                host = host.substring(0, host.length() - 1);
            if (host.isEmpty() || host.length() > 253)
                throw new IllegalArgumentException("Invalid domain.");
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4)
                    throw new IllegalArgumentException("Invalid IPv4 address.");
                StringBuilder canonical = new StringBuilder();
                for (String octet : octets) {
                    if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255)
                        throw new IllegalArgumentException("Invalid IPv4 address.");
                    if (canonical.length() > 0) canonical.append('.');
                    canonical.append(Integer.parseInt(octet));
                }
                return canonical.toString();
            }
            for (String label : host.split("\\.", -1))
                if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
                    throw new IllegalArgumentException("Invalid domain.");
            return host;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Enter a valid URL.", ex);
        }
    }

    private static String ipv6(String value) {
        try {
            // Only numeric colon-containing literals enter here; no DNS lookup.
            if (!value.matches("[0-9a-fA-F:]+") || !(InetAddress.getByName(value) instanceof Inet6Address))
                throw new IllegalArgumentException("Invalid IPv6 address.");
            return InetAddress.getByName(value).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (java.net.UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid IPv6 address.", ex);
        }
    }

    static String destination(String normalized) {
        // Legacy bare IPv6 rules cover a /64 subnet; a pasted address must allow only that address.
        if (normalized.contains(":")) return "ip:" + normalized + "/128";
        return (normalized.matches("[0-9.]+") ? "ip:" : "host:") + normalized;
    }

    static String globalDomain(String ruleText) {
        // Keep advanced rules (directip, package rules, etc.) in the existing Rules screen.
        if (!ruleText.matches("allow (host|ip):\\S+( sticky)?"))
            return null;
        String destination = ruleText.split(" ")[1].substring(ruleText.startsWith("allow host:") ? 5 : 3);
        return destination.contains(":") && destination.endsWith("/128")
                ? destination.substring(0, destination.length() - 4) : destination;
    }
}
