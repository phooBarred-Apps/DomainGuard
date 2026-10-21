package heartguard.heartguard.main;

import android.content.Context;
import android.database.Cursor;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class AppPause {
    static final class Entry {
        final AppPauseRule rule;
        final boolean active;
        final long time;
        Entry(AppPauseRule rule, boolean active, long time) {
            this.rule = rule; this.active = active; this.time = time;
        }
        String label(Context context) {
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(time));
            return active ? context.getString(R.string.app_pause_until, date)
                    : context.getString(R.string.app_pause_pending, RuleDelay.duration(context, rule.seconds, true), date);
        }
    }

    static List<Entry> read(Context context, String packageName) {
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRules()) {
            while (cursor.moveToNext()) {
                String text = cursor.getString(cursor.getColumnIndexOrThrow("ruletext"));
                if (!text.startsWith("pause ")) continue;
                MyRule parsed = MyRule.getRuleFromText(context, text);
                if (!(parsed instanceof AppPauseRule)) continue;
                AppPauseRule rule = (AppPauseRule) parsed;
                if (!rule.packageName.equals(packageName)) continue;
                boolean active = cursor.getInt(cursor.getColumnIndexOrThrow("enacted")) != 0;
                long time = cursor.getLong(cursor.getColumnIndexOrThrow("enact_time"));
                if (active) time += rule.seconds * 1000L;
                if (!active || time > System.currentTimeMillis()) entries.add(new Entry(rule, active, time));
            }
        }
        return entries;
    }

    static void resume(Context context, String packageName) {
        for (Entry entry : read(context, packageName))
            RulesManager.getInstance(context).queueRuleText(context, "- " + entry.rule.getRuleText());
    }
}
