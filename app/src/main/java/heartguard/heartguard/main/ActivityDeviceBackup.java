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

public class ActivityDeviceBackup extends AppCompatActivity {
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
