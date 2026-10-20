package heartguard.heartguard.main;

import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FileBackupHelper;
import android.app.backup.SharedPreferencesBackupHelper;
import android.database.Cursor;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Uses Android's account backup transport, with no Drive login or files to choose. */
public class SettingsBackupAgent extends BackupAgentHelper {
    private static final String RULES_FILE = "backup/rules.json";

    @Override public void onCreate() {
        addHelper("preferences", new SharedPreferencesBackupHelper(this,
                getPackageName() + "_preferences", "apply", "roaming", "lockdown"));
        addHelper("rules", new FileBackupHelper(this, RULES_FILE));
        addHelper("hosts", new FileBackupHelper(this, "hosts.txt"));
    }

    @Override public void onRestoreFinished() {
        RulesManager.getInstance(this).startTrueTimeRequest(this);
    }

    @Override public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput output,
                                   ParcelFileDescriptor newState) throws IOException {
        List<RulesBackup.Row> rows = new ArrayList<>();
        try (Cursor cursor = DatabaseHelper.getInstance(this).getAllRules()) {
            while (cursor.moveToNext())
                rows.add(new RulesBackup.Row(
                        cursor.getString(cursor.getColumnIndexOrThrow("ruletext")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("create_time")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("enacted")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("major_category")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("minor_category"))));
        }
        byte[] bytes = RulesBackup.encode(rows);
        File file = new File(getFilesDir(), RULES_FILE);
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
            throw new IOException("Cannot prepare settings backup");
        AtomicFile snapshot = new AtomicFile(file);
        FileOutputStream stream = snapshot.startWrite();
        try {
            stream.write(bytes);
            snapshot.finishWrite(stream);
        } catch (IOException error) {
            snapshot.failWrite(stream);
            throw error;
        }
        super.onBackup(oldState, output, newState);
    }

    @Override public void onRestore(BackupDataInput input, int version, ParcelFileDescriptor newState) throws IOException {
        File file = new File(getFilesDir(), RULES_FILE);
        // Avoid applying a stale local snapshot if the incoming backup contains no rules.
        new AtomicFile(file).delete();
        super.onRestore(input, version, newState);
        if (file.isFile()) {
            if (file.length() > RulesBackup.MAX_BYTES) throw new IOException("Rule backup is too large");
            // Validate everything before replacing saved rules. Keep pending rules pending.
            DatabaseHelper.getInstance(this).restoreRules(RulesBackup.decode(new AtomicFile(file).readFully()));
        }
    }
}
