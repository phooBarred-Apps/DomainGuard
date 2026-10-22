package heartguard.heartguard.main;

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

/** Shared integer/unit binding; the rule engine owns the duration limit. */
final class DurationInput {
    final EditText amount;
    final Spinner unit;

    DurationInput(EditText amount, Spinner unit, int seconds, Runnable changed) {
        this.amount = amount;
        this.unit = unit;
        RuleDelay.Unit initial = RuleDelay.displayUnit(seconds);
        amount.setText(Integer.toString(seconds / initial.seconds));
        ArrayAdapter<RuleDelay.Unit> adapter = new ArrayAdapter<RuleDelay.Unit>(amount.getContext(),
                android.R.layout.simple_spinner_item, RuleDelay.Unit.values()) {
            private View label(View view, int position) {
                int quantity = amount.getText().toString().trim().matches("0*1") ? 1 : 2;
                TextView text = (TextView) view;
                text.setText(amount.getResources().getQuantityString(getItem(position).label, quantity));
                text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                text.setMinimumHeight(CompactUi.dp(getContext(), 48));
                text.setPadding(CompactUi.dp(getContext(), 8), 0, CompactUi.dp(getContext(), 8), 0);
                return view;
            }
            @Override public View getView(int position, View view, ViewGroup parent) { return label(super.getView(position, view, parent), position); }
            @Override public View getDropDownView(int position, View view, ViewGroup parent) { return label(super.getDropDownView(position, view, parent), position); }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_item);
        unit.setAdapter(adapter);
        unit.setSelection(initial.ordinal());
        unit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { amount.setError(null); changed.run(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        amount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { adapter.notifyDataSetChanged(); amount.setError(null); changed.run(); }
            public void afterTextChanged(Editable text) { }
        });
    }

    int seconds() { return RuleDelay.parseSeconds(amount.getText().toString(), (RuleDelay.Unit) unit.getSelectedItem()); }
}
