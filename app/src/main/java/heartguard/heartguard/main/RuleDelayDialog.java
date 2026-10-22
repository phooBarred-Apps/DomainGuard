package heartguard.heartguard.main;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;

public class RuleDelayDialog extends DialogFragment {
    private EditText input;
    private Spinner unit;
    private TextView preview;
    private TextView current;
    private View cancelPending;
    private View durationFields;
    private SwitchCompat inherit;
    private RuleDelay.State state;
    private DurationInput duration;
    private String packageName;
    private final DatabaseHelper.RuleChangedListener listener = () -> {
        if (getActivity() != null)
            getActivity().runOnUiThread(() -> { if (isResumed()) refresh(); });
    };

    static RuleDelayDialog forApp(String packageName, String name) {
        RuleDelayDialog dialog = new RuleDelayDialog();
        Bundle args = new Bundle();
        args.putString("package", packageName);
        args.putString("name", name);
        dialog.setArguments(args);
        return dialog;
    }

    @Override public Dialog onCreateDialog(Bundle savedState) {
        packageName = getArguments() == null ? null : getArguments().getString("package");
        View content = requireActivity().getLayoutInflater().inflate(R.layout.rule_delay, null);
        input = content.findViewById(R.id.rule_delay_input);
        unit = content.findViewById(R.id.rule_delay_unit);
        preview = content.findViewById(R.id.rule_delay_preview);
        current = content.findViewById(R.id.rule_delay_current);
        cancelPending = content.findViewById(R.id.rule_delay_cancel_pending);
        durationFields = content.findViewById(R.id.rule_delay_fields);
        inherit = content.findViewById(R.id.rule_delay_inherit);
        CompactUi.destructiveButton((android.widget.Button) cancelPending);
        state = RuleDelay.read(requireContext(), packageName);
        inherit.setVisibility(packageName == null ? View.GONE : View.VISIBLE);
        inherit.setChecked(savedState == null ? !state.overridden : savedState.getBoolean("inherit"));
        duration = new DurationInput(input, unit, state.seconds, this::updatePreview);
        if (savedState != null) {
            input.setText(savedState.getString("amount"));
            unit.setSelection(savedState.getInt("unit"));
        }
        inherit.setOnCheckedChangeListener((button, checked) -> updatePreview());
        cancelPending.setOnClickListener(view -> {
            for (RuleDelay.Pending pending : RuleDelay.read(requireContext(), packageName).pending)
                RulesManager.getInstance(requireContext()).queueRuleText(requireContext(), "abandon " + pending.rule);
            cancelPending.setEnabled(false);
        });
        View title = CompactUi.header(requireContext(), R.string.rule_delay_title, R.drawable.ic_info_compact,
                R.string.help_title, view -> CompactUi.help(requireContext(), R.string.rule_delay_title,
                        getString(packageName == null ? R.string.rule_delay_explanation : R.string.app_delay_help)));
        if (packageName != null) {
            TextView appName = content.findViewById(R.id.rule_delay_app);
            appName.setText(getArguments().getString("name"));
            try {
                android.graphics.drawable.Drawable icon = requireContext().getPackageManager().getApplicationIcon(packageName);
                icon.setBounds(0, 0, CompactUi.dp(requireContext(), 32), CompactUi.dp(requireContext(), 32));
                appName.setCompoundDrawablesRelative(icon, null, null, null);
                appName.setCompoundDrawablePadding(CompactUi.dp(requireContext(), 12));
                appName.setGravity(android.view.Gravity.CENTER_VERTICAL);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            appName.setVisibility(View.VISIBLE);
        }
        return new AlertDialog.Builder(requireContext()).setCustomTitle(title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rule_delay_save, null).create();
    }

    @Override public void onResume() {
        super.onResume();
        DialogSurface.apply((AlertDialog) requireDialog());
        requireDialog().getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
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
        out.putString("amount", input.getText().toString());
        out.putInt("unit", unit.getSelectedItemPosition());
        out.putBoolean("inherit", inherit.isChecked());
    }

    private void refresh() {
        state = RuleDelay.read(requireContext(), packageName);
        current.setText(state.summary(requireContext()));
        current.setVisibility(current.length() == 0 ? View.GONE : View.VISIBLE);
        inherit.setText(getString(R.string.app_delay_default, RuleDelay.duration(requireContext(), state.generalSeconds)));
        inherit.setEnabled(state.pending.isEmpty());
        input.setEnabled(state.pending.isEmpty());
        unit.setEnabled(state.pending.isEmpty());
        cancelPending.setVisibility(state.pending.isEmpty() ? View.GONE : View.VISIBLE);
        cancelPending.setEnabled(true);
        ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(state.pending.isEmpty());
        updatePreview();
    }

    private boolean useGeneral() { return packageName != null && inherit.isChecked(); }

    private void updatePreview() {
        if (duration == null) return;
        durationFields.setVisibility(useGeneral() ? View.GONE : View.VISIBLE);
        try {
            int seconds = useGeneral() ? state.generalSeconds : duration.seconds();
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
        refresh();
        if (!state.pending.isEmpty()) return;
        RulesManager rules = RulesManager.getInstance(requireContext());
        if (useGeneral()) {
            for (String active : state.activeRules) rules.queueRuleText(requireContext(), "- " + active);
        } else {
            int seconds;
            try { seconds = duration.seconds(); }
            catch (IllegalArgumentException invalid) {
                input.setError(getString(R.string.rule_delay_invalid)); return;
            }
            if (seconds != state.seconds || (packageName != null && !state.overridden))
                rules.queueRuleText(requireContext(), "delay " + (packageName == null ? "" : "package:" + packageName + " ") + seconds);
        }
        dismiss();
    }
}
