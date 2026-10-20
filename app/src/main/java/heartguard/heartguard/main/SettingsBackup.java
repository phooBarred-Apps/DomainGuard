package heartguard.heartguard.main;

import android.app.backup.BackupManager;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

final class SettingsBackup {
    private final Context context;
    // Keep strong references: SharedPreferences retains listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged = (preferences, key) -> changed();
    private final DatabaseHelper.RuleChangedListener rulesChanged = this::changed;

    SettingsBackup(Context context) {
        this.context = context.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(preferencesChanged);
        for (String name : new String[]{"apply", "roaming", "lockdown"})
            context.getSharedPreferences(name, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesChanged);
        DatabaseHelper.getInstance(context).addRuleChangedListener(rulesChanged);
    }

    static SharedPreferences controls(Context context) {
        // Device-local backup controls and timestamps are not restored onto a new phone.
        return context.getSharedPreferences("backup_control", Context.MODE_PRIVATE);
    }

    static void request(Context context) {
        new BackupManager(context).dataChanged();
        controls(context).edit().putLong("last_requested", System.currentTimeMillis()).apply();
    }

    private void changed() {
        if (controls(context).getBoolean("automatic", false)) request(context);
    }
}
