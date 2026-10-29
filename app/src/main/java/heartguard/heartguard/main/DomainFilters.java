package heartguard.heartguard.main;

import android.content.SharedPreferences;

/** Immutable display choices shared by domain lists, counts, copies and recent traffic. */
final class DomainFilters {
    static final int SHOW = 0, HIDE_GLOBAL = 1, HIDE_ALL = 2;
    static final String ALLOWED_KEY = "domain_filter_allowed", BLOCKED_KEY = "domain_filter_blocked";
    final int allowed, blocked;

    DomainFilters(int allowed, int blocked) {
        this.allowed = valid(allowed);
        this.blocked = valid(blocked);
    }

    private static int valid(int value) { return value >= SHOW && value <= HIDE_ALL ? value : SHOW; }
    static DomainFilters defaults(SharedPreferences prefs) {
        // Preserve the existing Hide allowed preference when upgrading.
        return new DomainFilters(prefs.getInt(ALLOWED_KEY,
                prefs.getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false) ? HIDE_ALL : SHOW),
                prefs.getInt(BLOCKED_KEY, SHOW));
    }
    void saveDefaults(SharedPreferences prefs) {
        prefs.edit().putInt(ALLOWED_KEY, allowed).putInt(BLOCKED_KEY, blocked).apply();
    }
    int encode() { return allowed + 3 * blocked; }
    static DomainFilters decode(int value) { return new DomainFilters(value % 3, value / 3); }
    boolean active() { return allowed != SHOW || blocked != SHOW; }
    boolean needsGlobalScope() { return allowed == HIDE_GLOBAL || blocked == HIDE_GLOBAL; }

    // A domain may have several ports/outcomes. Its search index keeps all represented categories.
    static int category(int allowed, int block, int scope) {
        // Traffic result is historical; the current rule icon must not change its category.
        if (allowed > 0) return scope == GlobalRuleIndicators.ALLOWED ? 2 : 1;
        if (allowed == 0) return scope == GlobalRuleIndicators.BLOCKED ? 8 : 4;
        return 16; // Unknown results stay visible.
    }
    boolean includes(int categories) {
        int mask = 16 | (allowed == SHOW ? 3 : allowed == HIDE_GLOBAL ? 1 : 0)
                | (blocked == SHOW ? 12 : blocked == HIDE_GLOBAL ? 4 : 0);
        return (categories & mask) != 0;
    }
    boolean includes(AccessEntry entry) { return includes(category(entry.allowed, entry.block, entry.globalScope)); }

    String sqlCondition() {
        String condition = "";
        if (allowed == HIDE_ALL)
            condition += " AND COALESCE(a.allowed, -1) <= 0";
        if (blocked == HIDE_ALL)
            condition += " AND COALESCE(a.allowed, -1) != 0";
        return condition;
    }
}
