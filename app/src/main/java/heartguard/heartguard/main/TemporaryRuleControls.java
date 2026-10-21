package heartguard.heartguard.main;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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

    TemporaryRuleControls(View root) {
        context = root.getContext();
        enabled = root.findViewById(R.id.temporary_rule_enabled);
        amount = root.findViewById(R.id.temporary_rule_amount);
        unit = root.findViewById(R.id.temporary_rule_unit);
        View fields = root.findViewById(R.id.temporary_rule_fields);
        TextView summary = root.findViewById(R.id.temporary_rule_summary);
        amount.setText("5");
        ArrayAdapter<RuleDelay.Unit> units = new ArrayAdapter<RuleDelay.Unit>(context,
                android.R.layout.simple_spinner_item, RuleDelay.Unit.values()) {
            private View label(View view, int position) {
                int quantity = amount.getText().toString().trim().matches("0*1") ? 1 : 2;
                ((TextView) view).setText(context.getResources().getQuantityString(getItem(position).label, quantity));
                ((TextView) view).setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                view.setMinimumHeight(CompactUi.dp(context, 48));
                view.setPadding(CompactUi.dp(context, 8), 0, CompactUi.dp(context, 8), 0);
                return view;
            }
            @Override public View getView(int position, View view, ViewGroup parent) { return label(super.getView(position, view, parent), position); }
            @Override public View getDropDownView(int position, View view, ViewGroup parent) { return label(super.getDropDownView(position, view, parent), position); }
        };
        units.setDropDownViewResource(android.R.layout.simple_spinner_item);
        unit.setAdapter(units);
        unit.setSelection(RuleDelay.Unit.MINUTES.ordinal());
        Runnable refresh = () -> {
            try { summary.setText(context.getString(R.string.temporary_summary, RuleDelay.duration(context, seconds()))); }
            catch (IllegalArgumentException invalid) { summary.setText(R.string.temporary_invalid); }
        };
        enabled.setOnCheckedChangeListener((button, checked) -> {
            fields.setVisibility(checked ? View.VISIBLE : View.GONE);
            amount.setError(null);
            refresh.run();
        });
        amount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                amount.setError(null); units.notifyDataSetChanged(); refresh.run();
            }
            public void afterTextChanged(Editable text) { }
        });
        unit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) { refresh.run(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        refresh.run();
    }

    private int seconds() {
        int seconds = RuleDelay.parseSeconds(amount.getText().toString(), (RuleDelay.Unit) unit.getSelectedItem());
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
