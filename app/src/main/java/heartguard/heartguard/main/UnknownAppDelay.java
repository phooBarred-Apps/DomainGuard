package heartguard.heartguard.main;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The setting is an opt-in rule, so every removal path uses the same protected wait. */
final class UnknownAppDelay {
    static final String KEY = FeatureRule.FEATURE_NAME_STRICT_UNKNOWN_DELAY;
    static final String RULE = "feature " + KEY;
    static final String REMOVE = "- " + RULE;

    static long pending(Context context) {
        try (Cursor row = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(REMOVE)) {
            return row.moveToFirst() ? row.getLong(row.getColumnIndexOrThrow("enact_time")) : 0;
        }
    }

    static void update(Context context, SwitchPreference preference) {
        boolean enabled = RulesManager.getInstance(context).useStrictUnknownDelay();
        long pending = pending(context);
        preference.setChecked(enabled);
        preference.setSummary(pending > 0 ? context.getString(R.string.unknown_delay_pending_summary,
                GlobalProtectionStatus.date(pending)) : context.getString(enabled
                ? R.string.unknown_delay_on_summary : R.string.unknown_delay_off_summary));
    }

    static final class Wait {
        final int seconds;
        final String sources;
        Wait(int seconds, String sources) { this.seconds = seconds; this.sources = sources; }
        boolean same(Wait other) { return seconds == other.seconds && sources.equals(other.sources); }
    }

    static Wait longestWait(Context context) {
        RulesManager rules = RulesManager.getInstance(context);
        int defaultDelay = rules.getDefaultDelay();
        Map<String, Integer> overrides = rules.getAppDelayOverrides();
        int longest = defaultDelay;
        for (int seconds : overrides.values()) longest = Math.max(longest, seconds);
        List<String> names = new ArrayList<>();
        if (defaultDelay == longest) names.add(context.getString(R.string.unknown_delay_default_source));
        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            if (entry.getValue() != longest) continue;
            String name = entry.getKey();
            try {
                name = context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(name, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException missing) { /* Keep the saved package name. */ }
            names.add(name);
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return new Wait(longest, TextUtils.join(", ", names));
    }

    static void show(Context context) {
        RulesManager rules = RulesManager.getInstance(context);
        boolean enabled = rules.useStrictUnknownDelay();
        long pending = pending(context);
        Wait wait = longestWait(context);
        String duration = wait.seconds == 0 ? context.getString(R.string.unknown_delay_no_wait)
                : RuleDelay.duration(context, wait.seconds);
        int title = pending > 0 ? R.string.unknown_delay_pending_title : enabled
                ? R.string.unknown_delay_disable_title : R.string.unknown_delay_enable_title;
        String message = pending > 0 ? context.getString(R.string.unknown_delay_pending_body, GlobalProtectionStatus.date(pending))
                : context.getString(enabled ? R.string.unknown_delay_disable_body : R.string.unknown_delay_enable_body,
                        duration, wait.sources);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.confirm_yes, null).create();
        dialog.show(); DialogSurface.apply(dialog);
        CompactUi.dismissButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        if (enabled) CompactUi.destructiveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        else CompactUi.actionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (enabled != rules.useStrictUnknownDelay() || pending != pending(context)
                    || (pending == 0 && !wait.same(longestWait(context)))) {
                dialog.dismiss();
                Toast.makeText(context, R.string.unknown_delay_changed, Toast.LENGTH_SHORT).show();
                show(context); return;
            }
            rules.queueRuleText(context, pending > 0 ? "abandon " + REMOVE : enabled ? REMOVE : RULE);
            dialog.dismiss();
        });
    }
}
