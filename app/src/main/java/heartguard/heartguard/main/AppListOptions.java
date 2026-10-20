package heartguard.heartguard.main;

import android.os.Bundle;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Session-only view choices. Saved sort defaults are handled separately. */
final class AppListOptions {
    String query = "";
    String sort = ListSort.NAME;
    int access;   // All, allowed by default, blocked by default.
    int activity; // All, with attempts, without attempts.
    int type;     // All, user apps, system apps.
    boolean includeNoInternet = true;
    boolean includeDisabled = true;

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
