package heartguard.heartguard.main;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** The settings UI reads and queues the same delay rules as the rule editor. */
final class RuleDelay {
    enum Unit {
        SECONDS(1, R.plurals.rule_delay_unit_seconds),
        MINUTES(60, R.plurals.rule_delay_unit_minutes),
        HOURS(3600, R.plurals.rule_delay_unit_hours),
        DAYS(86400, R.plurals.rule_delay_unit_days);

        final int seconds;
        final int label;

        Unit(int seconds, int label) { this.seconds = seconds; this.label = label; }
    }

    static Unit displayUnit(int seconds) {
        Unit[] units = Unit.values();
        for (int i = units.length - 1; seconds > 0 && i >= 0; i--)
            if (seconds % units[i].seconds == 0) return units[i];
        return Unit.SECONDS;
    }

    static int parseSeconds(String input) {
        return parseSeconds(input, Unit.SECONDS);
    }

    static int parseSeconds(String input, Unit unit) {
        String value = input.trim();
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException();
        try {
            int amount = Integer.parseInt(value);
            // Check before multiplying so large values cannot overflow into a shorter delay.
            if (amount > RulesManager.MAX_DELAY / unit.seconds) throw new IllegalArgumentException();
            return amount * unit.seconds;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException();
        }
    }

    static String duration(Context context, int seconds) {
        return duration(context, seconds, false);
    }

    static String duration(Context context, int seconds, boolean singularUnits) {
        if (seconds == 0) return context.getString(R.string.rule_delay_none);
        List<String> parts = new ArrayList<>();
        int[] amounts = {seconds / 86400, seconds / 3600 % 24, seconds / 60 % 60, seconds % 60};
        int[] plurals = {R.plurals.rule_delay_days, R.plurals.rule_delay_hours,
                R.plurals.rule_delay_minutes, R.plurals.rule_delay_seconds};
        for (int i = 0; i < amounts.length; i++)
            if (amounts[i] > 0)
                parts.add(context.getResources().getQuantityString(plurals[i], singularUnits ? 1 : amounts[i], amounts[i]));
        return TextUtils.join(" ", parts);
    }

    static State read(Context context) {
        return read(context, null);
    }

    static State read(Context context, String packageName) {
        State state = new State();
        state.packageName = packageName;
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRulesSorted()) {
            int textCol = cursor.getColumnIndexOrThrow("ruletext");
            int activeCol = cursor.getColumnIndexOrThrow("enacted");
            int timeCol = cursor.getColumnIndexOrThrow("enact_time");
            while (cursor.moveToNext()) {
                String text = cursor.getString(textCol);
                boolean removal = text.startsWith("- ");
                String positive = removal ? text.substring(2) : text;
                if (!positive.matches("delay (package:\\S+ )?[0-9]+")) continue;
                DelayRule delay = new DelayRule(positive);
                boolean active = cursor.getInt(activeCol) > 0;
                if (!removal && active && delay.getPackageName() == null)
                    state.generalSeconds = Math.max(state.generalSeconds, delay.getDelay());
                if (!java.util.Objects.equals(packageName, delay.getPackageName())) continue;
                if (!removal && active) {
                    state.overridden = true;
                    state.seconds = Math.max(state.seconds, delay.getDelay());
                    state.activeRules.add(text);
                } else if (!active)
                    state.pending.add(new Pending(text, delay.getDelay(), removal, cursor.getLong(timeCol)));
            }
        }
        if (!state.overridden) state.seconds = state.generalSeconds;
        return state;
    }

    static final class State {
        int seconds;
        int generalSeconds;
        String packageName;
        boolean overridden;
        final List<String> activeRules = new ArrayList<>();
        final List<Pending> pending = new ArrayList<>();

        String summary(Context context) {
            List<String> lines = new ArrayList<>();
            if (packageName == null || !pending.isEmpty())
                lines.add(context.getString(R.string.rule_delay_current, duration(context, seconds)));
            for (Pending change : pending)
                lines.add(context.getString(change.removal ? R.string.rule_delay_pending_removal : R.string.rule_delay_pending,
                        duration(context, change.seconds),
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(change.time))));
            return TextUtils.join("\n", lines);
        }
    }

    static final class Pending {
        final String rule;
        final int seconds;
        final boolean removal;
        final long time;

        Pending(String rule, int seconds, boolean removal, long time) {
            this.rule = rule;
            this.seconds = seconds;
            this.removal = removal;
            this.time = time;
        }
    }
}
