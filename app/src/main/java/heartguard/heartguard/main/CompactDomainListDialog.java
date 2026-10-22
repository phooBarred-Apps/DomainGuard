package heartguard.heartguard.main;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

public class CompactDomainListDialog extends DialogFragment {
    private SeekBar rows;

    @Override public Dialog onCreateDialog(Bundle state) {
        View content = requireActivity().getLayoutInflater().inflate(R.layout.compact_domain_lists, null);
        rows = content.findViewById(R.id.compact_domains_rows);
        TextView height = content.findViewById(R.id.compact_domains_height);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        rows.setMax(CompactDomainLists.MAX_ROWS - CompactDomainLists.MIN_ROWS);
        rows.setProgress((state == null ? CompactDomainLists.rows(preferences) : state.getInt("rows")) - CompactDomainLists.MIN_ROWS);
        Runnable refresh = () -> {
            height.setText(getString(R.string.compact_domains_height, selectedRows()));
        };
        rows.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { refresh.run(); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        content.findViewById(R.id.compact_domains_preview).setOnClickListener(view -> startActivity(
                new Intent(requireContext(), ActivityDomainListPreview.class)
                        .putExtra(CompactDomainLists.ENABLED, true)
                        .putExtra(CompactDomainLists.ROWS, selectedRows())));
        refresh.run();
        return new AlertDialog.Builder(requireContext()).setTitle(R.string.compact_domains_title).setView(content)
                .setPositiveButton(R.string.compact_domains_save, (dialog, which) -> preferences.edit()
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
        state.putInt("rows", selectedRows());
    }
}
