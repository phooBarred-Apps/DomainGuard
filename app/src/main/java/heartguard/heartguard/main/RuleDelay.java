package heartguard.heartguard.main;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
        return exactSeconds(input, unit).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    static BigDecimal exactSeconds(String input, Unit unit) {
        String value = input.trim();
        if (!value.matches("(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})")) throw new IllegalArgumentException();
        try {
            BigDecimal seconds = new BigDecimal(value).multiply(BigDecimal.valueOf(unit.seconds));
            // Enforce the limit before rounding; never round an out-of-range duration into validity.
            if (seconds.compareTo(BigDecimal.valueOf(RulesManager.MAX_DELAY)) > 0) throw new IllegalArgumentException();
            return seconds;
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
        if (parts.size() == 1) return parts.get(0);
        String beginning = TextUtils.join(", ", parts.subList(0, parts.size() - 1));
        return context.getString(parts.size() == 2 ? R.string.duration_pair : R.string.duration_last,
                beginning, parts.get(parts.size() - 1));
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
