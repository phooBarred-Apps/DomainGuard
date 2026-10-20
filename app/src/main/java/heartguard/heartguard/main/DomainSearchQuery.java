package heartguard.heartguard.main;

import java.util.Locale;

/** Shared search normalization; this does not change firewall matching. */
final class DomainSearchQuery {
    final String text;

    DomainSearchQuery(String input) {
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            try {
                normalized = DomainInput.normalize(normalized);
            } catch (IllegalArgumentException ignored) {
                // Partial names and literal punctuation are useful while typing.
            }
        }
        text = normalized;
    }

    String sqlContains() {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    boolean matchesRule(String domain) {
        String candidate = domain.toLowerCase(Locale.ROOT);
        if (candidate.endsWith(".")) candidate = candidate.substring(0, candidate.length() - 1);
        return text.isEmpty() || candidate.contains(text) || coversSearch(candidate);
    }

    boolean coversSearch(String domain) {
        String candidate = domain.toLowerCase(Locale.ROOT);
        if (candidate.endsWith(".")) candidate = candidate.substring(0, candidate.length() - 1);
        return !candidate.isEmpty() && !candidate.contains(":") && !candidate.matches("[0-9./]+")
                && text.endsWith("." + candidate);
    }
}
