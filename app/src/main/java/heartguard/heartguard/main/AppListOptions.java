package heartguard.heartguard.main;

import android.content.SharedPreferences;
import android.os.Bundle;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Session view choices, initialized from explicitly saved defaults. */
final class AppListOptions {
    private static final String DEFAULT_ACCESS = "app_filter_default_access";
    private static final String DEFAULT_ACTIVITY = "app_filter_default_activity";
    private static final String DEFAULT_TYPE = "app_filter_default_type";
    private static final String DEFAULT_NO_INTERNET = "app_filter_default_no_internet";
    private static final String DEFAULT_DISABLED = "app_filter_default_disabled";
    String query = "";
    String sort = ListSort.NAME;
    int access;   // All, allowed by default, blocked by default.
    int activity; // All, with attempts, without attempts.
    int type;     // All, user apps, system apps.
    boolean includeNoInternet = true;
    boolean includeDisabled = true;

    void loadFilterDefaults(SharedPreferences prefs) {
        access = validFilter(prefs.getInt(DEFAULT_ACCESS, 0));
        activity = validFilter(prefs.getInt(DEFAULT_ACTIVITY, 0));
        type = validFilter(prefs.getInt(DEFAULT_TYPE, 0));
        includeNoInternet = prefs.getBoolean(DEFAULT_NO_INTERNET, true);
        includeDisabled = prefs.getBoolean(DEFAULT_DISABLED, true);
    }

    void saveFilterDefaults(SharedPreferences prefs) {
        // Search text stays temporary; sort has its own Set as default button.
        prefs.edit().putInt(DEFAULT_ACCESS, access)
                .putInt(DEFAULT_ACTIVITY, activity)
                .putInt(DEFAULT_TYPE, type)
                .putBoolean(DEFAULT_NO_INTERNET, includeNoInternet)
                .putBoolean(DEFAULT_DISABLED, includeDisabled).apply();
    }

    private static int validFilter(int value) {
        return value >= 0 && value <= 2 ? value : 0;
    }

    boolean matches(Rule app, DatabaseHelper.AccessStats stats) {
        String term = query.trim().toLowerCase(Locale.ROOT);
        boolean searchMatch = term.isEmpty() || (app.name != null && app.name.toLowerCase(Locale.ROOT).contains(term))
                || app.packageName.toLowerCase(Locale.ROOT).contains(term) || Integer.toString(app.uid).equals(term);
        boolean blocked = app.wifi_blocked && app.other_blocked && app.apply;
        boolean hasAttempts = stats != null && stats.count > 0;
        return searchMatch
                && (access == 0 || (access == 2) == blocked)
                && (activity == 0 || (activity == 1) == hasAttempts)
                && (type == 0 || (type == 2) == app.system)
                && (includeNoInternet || app.internet)
                && (includeDisabled || app.enabled);
    }

    Comparator<Rule> comparator(Map<Integer, DatabaseHelper.AccessStats> stats) {
        Collator names = Collator.getInstance(Locale.getDefault());
        names.setStrength(Collator.SECONDARY);
        return (left, right) -> {
            int order = 0;
            if (ListSort.RECENT.equals(sort))
                order = Long.compare(lastTime(stats.get(right.uid)), lastTime(stats.get(left.uid)));
            else if (ListSort.UID.equals(sort))
                order = Integer.compare(left.uid, right.uid);
            else if (ListSort.BLOCKED.equals(sort) || ListSort.ALLOWED.equals(sort)) {
                boolean leftBlocked = left.wifi_blocked && left.other_blocked && left.apply;
                boolean rightBlocked = right.wifi_blocked && right.other_blocked && right.apply;
                order = Boolean.compare(leftBlocked, rightBlocked);
                if (ListSort.BLOCKED.equals(sort)) order = -order;
            }
            if (order == 0) order = names.compare(left.name, right.name);
            return order == 0 ? left.packageName.compareTo(right.packageName) : order;
        };
    }

    private static long lastTime(DatabaseHelper.AccessStats stats) {
        return stats == null ? 0 : stats.lastTime;
    }

    void reset() {
        query = "";
        access = activity = type = 0;
        includeNoInternet = includeDisabled = true;
    }

    Bundle save() {
        Bundle state = new Bundle();
        state.putString("query", query);
        state.putString("sort", sort);
        state.putInt("access", access);
        state.putInt("activity", activity);
        state.putInt("type", type);
        state.putBoolean("noInternet", includeNoInternet);
        state.putBoolean("disabled", includeDisabled);
        return state;
    }

    void restore(Bundle state) {
        query = state.getString("query", "");
        sort = ListSort.valid(state.getString("sort"), ListSort.APPS);
        access = state.getInt("access");
        activity = state.getInt("activity");
        type = state.getInt("type");
        includeNoInternet = state.getBoolean("noInternet", true);
        includeDisabled = state.getBoolean("disabled", true);
    }
}
