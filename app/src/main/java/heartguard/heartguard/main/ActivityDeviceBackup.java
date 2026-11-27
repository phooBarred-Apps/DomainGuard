package heartguard.heartguard.main;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.app.AlertDialog;
import java.util.List;

public class ActivityDeviceBackup extends AppCompatActivity {
    private byte[] exportBytes;
    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle(R.string.settings_backup_title);
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(CompactUi.dp(this, 20), CompactUi.dp(this, 12), CompactUi.dp(this, 20), CompactUi.dp(this, 20));
        scroll.addView(content);
        SwitchCompat automatic = new SwitchCompat(this);
        automatic.setId(R.id.backup_automatic_toggle);
        automatic.setText(R.string.backup_automatic);
        automatic.setMinHeight(CompactUi.dp(this, 48));
        automatic.setChecked(SettingsBackup.controls(this).getBoolean("automatic", false));
        automatic.setOnCheckedChangeListener((button, checked) -> {
            SettingsBackup.controls(this).edit().putBoolean("automatic", checked).apply();
            if (checked) SettingsBackup.request(this);
        });
        content.addView(automatic);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.backup_automatic_subtitle);
        content.addView(subtitle);
        TextView timing = new TextView(this);
        timing.setText(R.string.backup_timing_note);
        timing.setPadding(0, CompactUi.dp(this, 24), 0, CompactUi.dp(this, 16));
        content.addView(timing);
        android.widget.Button recover = CompactUi.button(this, R.string.backup_recover, view -> recover());
        CompactUi.actionButton(recover);
        content.addView(recover, new LinearLayout.LayoutParams(-2, -2));
        TextView recoveryNote = new TextView(this);
        recoveryNote.setText(R.string.backup_recover_note);
        recoveryNote.setPadding(0, 0, 0, CompactUi.dp(this, 16));
        content.addView(recoveryNote);
        android.widget.Button phone = CompactUi.button(this, R.string.settings_backup_open, view -> {
            try {
                startActivity(new Intent("com.google.android.gms.backup.ACTION_BACKUP_SETTINGS")
                        .setPackage("com.google.android.gms"));
            } catch (ActivityNotFoundException unavailable) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        phone.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_external_compact, 0);
        phone.setCompoundDrawablePadding(CompactUi.dp(this, 8));
        content.addView(phone, new LinearLayout.LayoutParams(-2, -2));
        setContentView(scroll);
    }

    private void recover() {
        try {
            List<RulesRecovery.Snapshot> snapshots = RulesRecovery.of(this).available();
            if (snapshots.isEmpty()) {
                CompactUi.help(this, R.string.backup_recover, getString(R.string.backup_recover_missing));
            } else if (snapshots.size() == 1) {
                showSnapshot(snapshots.get(0));
            } else {
                String[] labels = new String[snapshots.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = snapshotLabel(snapshots.get(i));
                AlertDialog choices = new AlertDialog.Builder(this).setTitle(R.string.backup_recover)
                        .setItems(labels, (dialog, which) -> showSnapshot(snapshots.get(which)))
                        .setNegativeButton(R.string.cancel_button, null).show();
                DialogSurface.apply(choices);
            }
        } catch (Exception error) {
            CompactUi.help(this, R.string.backup_recover, getString(R.string.backup_recover_read_error));
        }
    }

    private String snapshotLabel(RulesRecovery.Snapshot snapshot) {
        String date = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(snapshot.modified));
        return getString(R.string.backup_recover_summary, snapshot.count, date);
    }

    private void showSnapshot(RulesRecovery.Snapshot snapshot) {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_OVERLAY);
        TextView body = new TextView(this);
        body.setText(snapshotLabel(snapshot) + "\n\n" + getString(R.string.backup_recover_confirm));
        body.setTextAppearance(this, R.style.TextMedium);
        body.setPadding(CompactUi.dp(this, 24), CompactUi.dp(this, 12), CompactUi.dp(this, 24), CompactUi.dp(this, 12));
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(CompactUi.header(this, R.string.backup_recover, 0, 0, null)).setView(scroll)
                .setNegativeButton(R.string.cancel_button, null)
                .setNeutralButton(R.string.backup_recover_export, (d, which) -> {
                    exportBytes = snapshot.bytes;
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                                .putExtra(Intent.EXTRA_TITLE, "DomainGuard-recovered-rules.json"), 401);
                    } catch (ActivityNotFoundException unavailable) {
                        exportBytes = null;
                        CompactUi.help(this, R.string.backup_recover, getString(R.string.backup_recover_export_error));
                    }
                })
                .setPositiveButton(R.string.backup_recover_restore, null).create();
        dialog.setOnShowListener(ignored -> {
            CompactUi.dismissButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            CompactUi.actionButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
            CompactUi.actionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                RulesManager.getInstance(this).recoverSavedRules(getApplicationContext(), snapshot.bytes, error -> {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    CompactUi.help(this, R.string.backup_recover,
                            getString(error == null ? R.string.backup_recover_success : R.string.backup_recover_failed));
                });
            });
        });
        dialog.show();
        DialogSurface.apply(dialog);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 401) return;
        byte[] bytes = exportBytes;
        exportBytes = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (bytes == null) throw new java.io.IOException("Recovery selection was lost");
            try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (output == null) throw new java.io.IOException("Cannot open export");
                output.write(bytes);
            }
            CompactUi.help(this, R.string.backup_recover, getString(R.string.backup_recover_exported));
        } catch (Exception error) {
            CompactUi.help(this, R.string.backup_recover, getString(R.string.backup_recover_export_error));
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, R.id.backup_information, 0, R.string.backup_help_title)
                .setIcon(R.drawable.ic_info_compact).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        if (item.getItemId() == R.id.backup_information) {
            CompactUi.help(this, R.string.backup_help_title, getString(R.string.backup_help_body));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
