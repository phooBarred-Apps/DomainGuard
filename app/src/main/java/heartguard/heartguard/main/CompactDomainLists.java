package heartguard.heartguard.main;

import android.content.SharedPreferences;

final class CompactDomainLists {
    static final String ENABLED = "compact_domain_lists";
    static final String ROWS = "compact_domain_rows";
    static final int MIN_ROWS = 2, MAX_ROWS = 10, DEFAULT_ROWS = 5;

    static int rows(SharedPreferences preferences) {
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, preferences.getInt(ROWS, DEFAULT_ROWS)));
    }

    static int maximumRows(SharedPreferences preferences) {
        return preferences.getBoolean(ENABLED, false) ? rows(preferences) : 0;
    }
}
