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
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared decimal/unit binding; previews and saved rules use the same rounded seconds. */
final class DurationInput {
    final EditText amount;
    final Spinner unit;
    private boolean binding;

    DurationInput(EditText amount, Spinner unit, int seconds, Runnable changed) {
        this.amount = amount;
        this.unit = unit;
        ArrayAdapter<RuleDelay.Unit> adapter = new ArrayAdapter<RuleDelay.Unit>(amount.getContext(),
                android.R.layout.simple_spinner_item, RuleDelay.Unit.values()) {
            private View label(View view, int position) {
                int quantity = 2;
                try { if (new BigDecimal(amount.getText().toString()).compareTo(BigDecimal.ONE) == 0) quantity = 1; }
                catch (NumberFormatException ignored) { }
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
        setSeconds(seconds);
        unit.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!binding) { amount.setError(null); changed.run(); }
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        amount.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.notifyDataSetChanged();
                if (!binding) { amount.setError(null); changed.run(); }
            }
            public void afterTextChanged(Editable text) { }
        });
    }

    int seconds() { return RuleDelay.parseSeconds(amount.getText().toString(), (RuleDelay.Unit) unit.getSelectedItem()); }

    void setSeconds(int seconds) {
        RuleDelay.Unit display = RuleDelay.displayUnit(seconds);
        String value = Integer.toString(seconds / display.seconds);
        if (value.length() > 3) {
            value = "";
            for (RuleDelay.Unit candidate : RuleDelay.Unit.values()) {
                String decimal = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(candidate.seconds), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                try {
                    if (decimal.matches("[0-9]{1,3}(?:\\.[0-9]{1,2})?") && RuleDelay.parseSeconds(decimal, candidate) == seconds) {
                        display = candidate; value = decimal; break;
                    }
                } catch (IllegalArgumentException outsideLimit) { /* Try another unit without changing the stored value. */ }
            }
            // An old raw-rule value may not fit without loss. Keep its exact Current delay label;
            // a blank new-value field cannot silently replace it with a shorter duration.
        }
        binding = true;
        if (!value.equals(amount.getText().toString())) amount.setText(value);
        if (unit.getSelectedItemPosition() != display.ordinal()) unit.setSelection(display.ordinal());
        binding = false;
    }
}
