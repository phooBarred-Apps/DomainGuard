package heartguard.heartguard.main;

/** Literal, case-insensitive navigation without filtering or rewriting the editable document. */
final class RuleTextSearch {
    static int find(String text, String query, int from, boolean backwards) {
        if (query.isEmpty() || query.length() > text.length()) return -1;
        int count = text.length() - query.length() + 1;
        int start = ((from % count) + count) % count;
        for (int step = 0; step < count; step++) {
            int index = (start + (backwards ? -step : step) + count) % count;
            if (text.regionMatches(true, index, query, 0, query.length())) return index;
        }
        return -1;
    }
}
