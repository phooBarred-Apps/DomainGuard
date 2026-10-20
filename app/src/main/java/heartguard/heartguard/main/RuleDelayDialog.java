package heartguard.heartguard.main;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

public class RuleDelayDialog extends DialogFragment {
    private EditText input;
    private TextView preview;
    private TextView current;
    private View cancelPending;
    private RuleDelay.State state;
    private final DatabaseHelper.RuleChangedListener listener = () -> {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> {
                if (isResumed()) refresh();
            });
    };

    @Override public Dialog onCreateDialog(Bundle savedState) {
        View content = requireActivity().getLayoutInflater().inflate(R.layout.rule_delay, null);
        input = content.findViewById(R.id.rule_delay_input);
        preview = content.findViewById(R.id.rule_delay_preview);
        current = content.findViewById(R.id.rule_delay_current);
        cancelPending = content.findViewById(R.id.rule_delay_cancel_pending);
        state = RuleDelay.read(requireContext());
        input.setText(savedState == null ? Integer.toString(state.seconds) : savedState.getString("seconds"));
        input.setSelection(input.length());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        cancelPending.setOnClickListener(view -> {
            // Abandon only pending global-delay changes; the active delay still applies.
            for (RuleDelay.Pending pending : RuleDelay.read(requireContext()).pending)
                RulesManager.getInstance(requireContext()).queueRuleText(requireContext(), "abandon " + pending.rule);
            cancelPending.setEnabled(false);
        });
        TextView title = new TextView(requireContext());
        title.setText(R.string.rule_delay_title);
        title.setTextAppearance(requireContext(), R.style.TextLarge);
        title.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        title.setPadding(padding, padding, padding, padding / 2);
        return new AlertDialog.Builder(requireContext())
                .setCustomTitle(title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rule_delay_save, null).create();
    }

    @Override public void onResume() {
        super.onResume();
        DatabaseHelper.getInstance(requireContext()).addRuleChangedListener(listener);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save());
        RulesManager.getInstance(requireContext()).startTrueTimeRequest(requireContext());
        refresh();
    }

    @Override public void onPause() {
        DatabaseHelper.getInstance(requireContext()).removeRuleChangedListener(listener);
        super.onPause();
    }

    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("seconds", input.getText().toString());
    }

    private void refresh() {
        state = RuleDelay.read(requireContext());
        current.setText(state.summary(requireContext()));
        input.setEnabled(state.pending.isEmpty());
        cancelPending.setVisibility(state.pending.isEmpty() ? View.GONE : View.VISIBLE);
        cancelPending.setEnabled(true);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(state.pending.isEmpty());
        updatePreview();
    }

    private void updatePreview() {
        try {
            int seconds = RuleDelay.parseSeconds(input.getText().toString());
            String text = RuleDelay.duration(requireContext(), seconds);
            if (seconds < state.seconds)
                text += "\n" + getString(R.string.rule_delay_reduction,
                        RuleDelay.duration(requireContext(), state.seconds - seconds));
            preview.setText(text);
        } catch (IllegalArgumentException invalid) {
            preview.setText(R.string.rule_delay_invalid);
        }
    }

    private void save() {
        int seconds;
        try {
            seconds = RuleDelay.parseSeconds(input.getText().toString());
        } catch (IllegalArgumentException invalid) {
            input.setError(getString(R.string.rule_delay_invalid));
            return;
        }
        // Re-read before saving so an existing pending change cannot be silently replaced.
        refresh();
        if (!state.pending.isEmpty()) return;
        if (seconds != state.seconds)
            RulesManager.getInstance(requireContext()).queueRuleText(requireContext(), "delay " + seconds);
        dismiss();
    }
}
