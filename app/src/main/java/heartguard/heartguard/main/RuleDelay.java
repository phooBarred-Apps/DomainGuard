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
        if (seconds == 0) return context.getString(R.string.rule_delay_none);
        List<String> parts = new ArrayList<>();
        int[] amounts = {seconds / 86400, seconds / 3600 % 24, seconds / 60 % 60, seconds % 60};
        int[] plurals = {R.plurals.rule_delay_days, R.plurals.rule_delay_hours,
                R.plurals.rule_delay_minutes, R.plurals.rule_delay_seconds};
        for (int i = 0; i < amounts.length; i++)
            if (amounts[i] > 0)
                parts.add(context.getResources().getQuantityString(plurals[i], amounts[i], amounts[i]));
        return TextUtils.join(" ", parts);
    }

    static State read(Context context) {
        State state = new State();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRulesSorted()) {
            int textCol = cursor.getColumnIndexOrThrow("ruletext");
            int activeCol = cursor.getColumnIndexOrThrow("enacted");
            int timeCol = cursor.getColumnIndexOrThrow("enact_time");
            while (cursor.moveToNext()) {
                String text = cursor.getString(textCol);
                // Package-specific delays remain independent of this global setting.
                if (text.matches("delay [0-9]+")) {
                    int seconds = parseSeconds(text.substring(6));
                    if (cursor.getInt(activeCol) > 0)
                        state.seconds = Math.max(state.seconds, seconds);
                    else
                        state.pending.add(new Pending(text, seconds, false, cursor.getLong(timeCol)));
                } else if (cursor.getInt(activeCol) == 0 && text.matches("- delay [0-9]+")) {
                    state.pending.add(new Pending(text, parseSeconds(text.substring(8)), true, cursor.getLong(timeCol)));
                }
            }
        }
        return state;
    }

    static final class State {
        int seconds;
        final List<Pending> pending = new ArrayList<>();

        String summary(Context context) {
            List<String> lines = new ArrayList<>();
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
