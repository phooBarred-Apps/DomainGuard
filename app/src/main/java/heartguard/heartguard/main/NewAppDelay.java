package heartguard.heartguard.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import androidx.preference.PreferenceManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Assigns ordinary, protected app overrides; the template is itself a protected rule. */
final class NewAppDelay {
    static final String KEY = "new_app_delay";
    static final String KNOWN_PACKAGES = "new_app_delay_known_packages";
    static final String PREFIX = "newappdelay ";
    static final int INITIAL_SECONDS = 6 * 3600;
    private static final AtomicBoolean dirty = new AtomicBoolean(true);

    static void packagesChanged(Context context) {
        dirty.set(true);
        RulesManager.getInstance(context).startTrueTimeRequest(context);
    }

    static RuleDelay.State read(Context context) {
        RuleDelay.State state = new RuleDelay.State();
        try (Cursor rows = DatabaseHelper.getInstance(context).getAllRulesSorted()) {
            while (rows.moveToNext()) {
                String text = rows.getString(rows.getColumnIndexOrThrow("ruletext"));
                boolean removal = text.startsWith("- ");
                String positive = removal ? text.substring(2) : text;
                boolean active = rows.getInt(rows.getColumnIndexOrThrow("enacted")) > 0;
                if (active && text.matches("delay [0-9]+"))
                    state.generalSeconds = Math.max(state.generalSeconds, new DelayRule(text).getDelay());
                if (!positive.startsWith(PREFIX)) continue;
                MyRule parsed = MyRule.getRuleFromText(context, positive);
                if (!(parsed instanceof NewAppDelayRule)) continue;
                int seconds = ((NewAppDelayRule) parsed).seconds;
                if (active && !removal) {
                    state.overridden = true;
                    state.seconds = Math.max(state.seconds, seconds);
                    state.activeRules.add(text);
                } else if (!active)
                    state.pending.add(new RuleDelay.Pending(text, seconds, removal,
                            rows.getLong(rows.getColumnIndexOrThrow("enact_time"))));
            }
        }
        if (!state.overridden) state.seconds = state.generalSeconds;
        return state;
    }

    static String summary(Context context, RuleDelay.State state) {
        String text = context.getString(state.overridden ? R.string.new_app_delay_on : R.string.new_app_delay_off,
                RuleDelay.duration(context, state.seconds));
        for (RuleDelay.Pending pending : state.pending)
            text += "\n" + context.getString(pending.removal ? R.string.new_app_delay_pending_off : R.string.rule_delay_pending,
                    pending.removal ? GlobalProtectionStatus.date(pending.time) : RuleDelay.duration(context, pending.seconds),
                    GlobalProtectionStatus.date(pending.time));
        return text;
    }

    /** Called on the rule worker before committing changes and before overdue changes activate. */
    static boolean reconcileIfNeeded(Context context, long now, boolean force) {
        boolean changed = dirty.getAndSet(false);
        if (!force && !changed) return false;
        try {
            return reconcile(context, context.getPackageManager().getInstalledPackages(0), now);
        } catch (RuntimeException failed) {
            dirty.set(true); // Retry before a later rule change; never record a failed assignment as seen.
            throw failed;
        }
    }

    static boolean reconcile(Context context, List<PackageInfo> installed, long now) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean initialized = prefs.contains(KNOWN_PACKAGES);
        Set<String> known = new HashSet<>(prefs.getStringSet(KNOWN_PACKAGES, Collections.emptySet()));
        Set<String> previous = new HashSet<>(known);
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        Set<String> configured = new HashSet<>();
        Map<String, Long> active = new HashMap<>();
        List<RuleDelay.Pending> pending = new ArrayList<>();
        try (Cursor rows = db.getAllRules()) {
            while (rows.moveToNext()) {
                String text = rows.getString(rows.getColumnIndexOrThrow("ruletext"));
                boolean removal = text.startsWith("- ");
                String positive = removal ? text.substring(2) : text;
                if (positive.matches("delay package:\\S+ [0-9]+")
                        && rows.getInt(rows.getColumnIndexOrThrow("enacted")) > 0)
                    configured.add(new DelayRule(positive).getPackageName());
                if (!positive.startsWith(PREFIX)) continue;
                MyRule parsed = MyRule.getRuleFromText(context, positive);
                if (!(parsed instanceof NewAppDelayRule)) continue;
                long at = rows.getLong(rows.getColumnIndexOrThrow("enact_time"));
                if (!removal && rows.getInt(rows.getColumnIndexOrThrow("enacted")) > 0)
                    active.put(text, at);
                else
                    pending.add(new RuleDelay.Pending(text, ((NewAppDelayRule) parsed).seconds, removal, at));
            }
        }
        Collections.sort(pending, (first, second) -> Long.compare(first.time, second.time));
        boolean added = false;
        for (PackageInfo app : installed) {
            if (!known.add(app.packageName) || !initialized || configured.contains(app.packageName)) continue;
            // Use install time, not discovery time, if an off/reduction deadline passed while the process was stopped.
            int seconds = delayAt(active, pending, Math.min(app.firstInstallTime, now));
            if (seconds < 0) continue;
            DelayRule rule = new DelayRule("delay package:" + app.packageName + " " + seconds);
            db.addNewRule(rule.getRuleText(), now, now, 1, rule.getMajorCategory(), rule.getMinorCategory());
            configured.add(app.packageName);
            added = true;
        }
        // Rules are durable first. Retrying after a crash finds the override and cannot duplicate or replace it.
        if (!initialized || !known.equals(previous))
            if (!prefs.edit().putStringSet(KNOWN_PACKAGES, known).commit())
                throw new IllegalStateException("Cannot save new-app delay inventory");
        return added;
    }

    private static int delayAt(Map<String, Long> active, List<RuleDelay.Pending> pending, long installedAt) {
        Map<String, Long> effective = new HashMap<>();
        for (Map.Entry<String, Long> entry : active.entrySet())
            if (entry.getValue() <= installedAt) effective.put(entry.getKey(), entry.getValue());
        for (RuleDelay.Pending change : pending) {
            if (change.time > installedAt) break;
            if (change.removal) effective.remove(change.rule.substring(2));
            else { effective.clear(); effective.put(change.rule, change.time); }
        }
        int seconds = -1;
        for (String text : effective.keySet()) seconds = Math.max(seconds, new NewAppDelayRule(text).seconds);
        return seconds;
    }
}

class NewAppDelayRule extends MyRule {
    final int seconds;

    NewAppDelayRule(String text) {
        super(text, MAJOR_CATEGORY_DELAY, 150);
        if (!text.matches("newappdelay [0-9]+")) throw new RuleParseNoMatchException();
        seconds = Integer.parseInt(text.substring(NewAppDelay.PREFIX.length()));
        if (seconds > RulesManager.MAX_DELAY) throw new RuleParseNoMatchException();
    }

    @Override public int getDelayToAdd(Context context, int mainDelay) {
        return Math.max(0, NewAppDelay.read(context).seconds - seconds);
    }

    @Override public int getDelayToRemove(Context context, int mainDelay) {
        return NewAppDelay.read(context).seconds;
    }

    @Override public Map<Long, String> getRulesToRemoveAfterAdd(Context context) {
        Map<Long, String> removals = new HashMap<>();
        for (Map.Entry<Long, String> entry : DatabaseHelper.getInstance(context).getEnactedRulesMap().entrySet())
            if (entry.getValue().startsWith(NewAppDelay.PREFIX) && !entry.getValue().equals(getRuleText()))
                removals.put(entry.getKey(), entry.getValue());
        return removals;
    }
}
