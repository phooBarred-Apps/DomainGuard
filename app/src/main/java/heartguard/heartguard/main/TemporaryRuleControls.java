package heartguard.heartguard.main;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;

/** Optional duration shared by allow and block, with the existing anchored unit picker. */
final class TemporaryRuleControls {
    private final Context context;
    private final SwitchCompat enabled;
    private final EditText amount;
    private final Spinner unit;
    private final DurationInput duration;

    TemporaryRuleControls(View root) {
        context = root.getContext();
        enabled = root.findViewById(R.id.temporary_rule_enabled);
        amount = root.findViewById(R.id.temporary_rule_amount);
        unit = root.findViewById(R.id.temporary_rule_unit);
        View fields = root.findViewById(R.id.temporary_rule_fields);
        TextView summary = root.findViewById(R.id.temporary_rule_summary);
        Runnable refresh = () -> {
            try { summary.setText(context.getString(R.string.temporary_summary, RuleDelay.duration(context, seconds()))); }
            catch (IllegalArgumentException invalid) { summary.setText(R.string.temporary_invalid); }
        };
        enabled.setOnCheckedChangeListener((button, checked) -> {
            fields.setVisibility(checked ? View.VISIBLE : View.GONE);
            amount.setError(null);
            refresh.run();
        });
        duration = new DurationInput(amount, unit, 300, refresh);
        refresh.run();
    }

    private int seconds() {
        int seconds = duration.seconds();
        if (seconds < 1 || seconds > TemporaryRuleText.MAX_SECONDS) throw new IllegalArgumentException();
        return seconds;
    }

    /** Null means the visible duration needs correction; no rule should be queued. */
    String apply(String rule) {
        if (!enabled.isChecked()) return rule;
        try { return TemporaryRuleText.text(rule, seconds()); }
        catch (IllegalArgumentException invalid) {
            amount.setError(context.getString(R.string.temporary_invalid));
            return null;
        }
    }
}
