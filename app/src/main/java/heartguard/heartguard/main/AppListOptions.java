package heartguard.heartguard.main;

import android.content.SharedPreferences;
import android.os.Bundle;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Session view choices, initialized from explicitly saved defaults. */
final class AppListOptions {
    final boolean trafficOnly;
    private final String defaultPrefix;

    AppListOptions() { this(false); }

    AppListOptions(boolean trafficOnly) {
        this.trafficOnly = trafficOnly;
        defaultPrefix = trafficOnly ? "recent_filter_default_" : "app_filter_default_";
    }
    String query = "";
    String sort = ListSort.NAME;
    int access;   // All, allowed by default, blocked by default.
    int activity; // All, with attempts, without attempts.
    int type;     // All, user apps, system apps.
    boolean includeNoInternet = true;
    boolean includeDisabled = true;

    void loadFilterDefaults(SharedPreferences prefs) {
        access = validFilter(prefs.getInt(defaultPrefix + "access", 0));
        activity = trafficOnly ? 0 : validFilter(prefs.getInt(defaultPrefix + "activity", 0));
        type = validFilter(prefs.getInt(defaultPrefix + "type", 0));
        includeNoInternet = trafficOnly || prefs.getBoolean(defaultPrefix + "no_internet", true);
        includeDisabled = prefs.getBoolean(defaultPrefix + "disabled", true);
    }

    void saveFilterDefaults(SharedPreferences prefs) {
        // Search text stays temporary; sort has its own Set as default button.
        SharedPreferences.Editor edit = prefs.edit().putInt(defaultPrefix + "access", access)
                .putInt(defaultPrefix + "type", type).putBoolean(defaultPrefix + "disabled", includeDisabled);
        if (!trafficOnly) edit.putInt(defaultPrefix + "activity", activity)
                .putBoolean(defaultPrefix + "no_internet", includeNoInternet);
        edit.apply();
    }

    private static int validFilter(int value) {
        return value >= 0 && value <= 2 ? value : 0;
    }

    boolean matches(Rule app, DatabaseHelper.AccessStats stats) {
        return matches(app, stats, false);
    }

    boolean matches(Rule app, DatabaseHelper.AccessStats stats, boolean domains) {
        String term = domains ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean searchMatch = term.isEmpty() || (app.name != null && app.name.toLowerCase(Locale.ROOT).contains(term))
                || app.packageName.toLowerCase(Locale.ROOT).contains(term) || Integer.toString(app.uid).equals(term);
        boolean hasAttempts = stats != null && stats.count > 0;
        return searchMatch && matchesApp(app)
                && (activity == 0 || (activity == 1) == hasAttempts)
                && (includeNoInternet || app.internet);
    }

    boolean matchesApp(Rule app) {
        boolean blocked = app.wifi_blocked && app.other_blocked && app.apply;
        return (access == 0 || (access == 2) == blocked)
                && (type == 0 || (type == 2) == app.system)
                && (includeDisabled || app.enabled);
    }

    boolean hasAppFilters() { return access != 0 || type != 0 || !includeDisabled; }

    // A UID may belong to several apps: include it when any one app matches all choices.
    java.util.Set<Integer> matchingUids(Iterable<Rule> apps) {
        java.util.Set<Integer> uids = new java.util.HashSet<>();
        for (Rule app : apps) if (matchesApp(app)) uids.add(app.uid);
        return uids;
    }

    Comparator<Rule> comparator(Map<Integer, DatabaseHelper.AccessStats> stats) {
        Collator names = Collator.getInstance(Locale.getDefault());
        names.setStrength(Collator.SECONDARY);
        return (left, right) -> {
            int order = 0;
            if (ListSort.RECENT.equals(sort) || ListSort.OLDEST.equals(sort)) {
                order = Long.compare(lastTime(stats.get(right.uid)), lastTime(stats.get(left.uid)));
                if (ListSort.OLDEST.equals(sort)) order = -order;
            } else if (ListSort.UID.equals(sort) || ListSort.UID_DESC.equals(sort)) {
                order = Integer.compare(left.uid, right.uid);
                if (ListSort.UID_DESC.equals(sort)) order = -order;
            } else if (ListSort.BLOCKED.equals(sort) || ListSort.ALLOWED.equals(sort)) {
                boolean leftBlocked = left.wifi_blocked && left.other_blocked && left.apply;
                boolean rightBlocked = right.wifi_blocked && right.other_blocked && right.apply;
                order = Boolean.compare(leftBlocked, rightBlocked);
                if (ListSort.BLOCKED.equals(sort)) order = -order;
            }
            if (order == 0) {
                order = names.compare(left.name, right.name);
                if (ListSort.NAME_DESC.equals(sort)) order = -order;
            }
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
        access = validFilter(state.getInt("access"));
        activity = trafficOnly ? 0 : validFilter(state.getInt("activity"));
        type = validFilter(state.getInt("type"));
        includeNoInternet = trafficOnly || state.getBoolean("noInternet", true);
        includeDisabled = state.getBoolean("disabled", true);
    }
}
