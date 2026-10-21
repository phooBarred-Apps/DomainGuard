package heartguard.heartguard.main;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

public class CompactDomainListDialog extends DialogFragment {
    private SwitchCompat enabled;
    private SeekBar rows;

    @Override public Dialog onCreateDialog(Bundle state) {
        View content = requireActivity().getLayoutInflater().inflate(R.layout.compact_domain_lists, null);
        enabled = content.findViewById(R.id.compact_domains_enabled);
        rows = content.findViewById(R.id.compact_domains_rows);
        TextView height = content.findViewById(R.id.compact_domains_height);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        enabled.setChecked(state == null ? preferences.getBoolean(CompactDomainLists.ENABLED, false) : state.getBoolean("enabled"));
        rows.setMax(CompactDomainLists.MAX_ROWS - CompactDomainLists.MIN_ROWS);
        rows.setProgress((state == null ? CompactDomainLists.rows(preferences) : state.getInt("rows")) - CompactDomainLists.MIN_ROWS);
        Runnable refresh = () -> {
            rows.setEnabled(enabled.isChecked());
            height.setEnabled(enabled.isChecked());
            height.setText(getString(R.string.compact_domains_height, selectedRows()));
        };
        enabled.setOnCheckedChangeListener((button, checked) -> refresh.run());
        rows.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { refresh.run(); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        content.findViewById(R.id.compact_domains_preview).setOnClickListener(view -> startActivity(
                new Intent(requireContext(), ActivityDomainListPreview.class)
                        .putExtra(CompactDomainLists.ENABLED, enabled.isChecked())
                        .putExtra(CompactDomainLists.ROWS, selectedRows())));
        refresh.run();
        return new AlertDialog.Builder(requireContext()).setTitle(R.string.compact_domains_title).setView(content)
                .setPositiveButton(R.string.compact_domains_save, (dialog, which) -> preferences.edit()
                        .putBoolean(CompactDomainLists.ENABLED, enabled.isChecked())
                        .putInt(CompactDomainLists.ROWS, selectedRows()).apply())
                .setNegativeButton(android.R.string.cancel, null).create();
    }

    private int selectedRows() { return rows.getProgress() + CompactDomainLists.MIN_ROWS; }

    @Override public void onStart() {
        super.onStart();
        DialogSurface.apply((AlertDialog) requireDialog());
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("enabled", enabled.isChecked());
        state.putInt("rows", selectedRows());
    }
}
