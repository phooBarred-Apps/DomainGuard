package heartguard.heartguard.main;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Preserves the pre-update snapshot independently of the next Android backup. */
final class RulesRecovery {
    private static final String[] NAMES = {"rules.json", "rules.json.bak", "rules.json.new"};
    private final File source, preserved;

    RulesRecovery(File files, File noBackupFiles) {
        source = new File(files, "backup");
        preserved = new File(noBackupFiles, "rules-recovery");
    }

    static RulesRecovery of(Context context) {
        return new RulesRecovery(context.getFilesDir(), context.getNoBackupFilesDir());
    }

    static void preserveOnStartup(Context context) {
        try { of(context).preserve(); }
        catch (IOException error) { Log.e("DomainGuard.Recovery", "Cannot preserve rule snapshot", error); }
    }

    void preserve() throws IOException {
        synchronized (RulesRecovery.class) {
            for (String name : NAMES) {
                File original = new File(source, name);
                File saved = new File(preserved, name);
                if (!original.isFile() || saved.exists()) continue;
                byte[] bytes = read(original);
                if (!preserved.isDirectory() && !preserved.mkdirs())
                    throw new IOException("Cannot create recovery folder");
                File pending = new File(preserved, name + ".pending");
                try (FileOutputStream stream = new FileOutputStream(pending)) {
                    stream.write(bytes);
                    stream.getFD().sync();
                }
                if (!pending.renameTo(saved)) throw new IOException("Cannot preserve rule snapshot");
                saved.setLastModified(original.lastModified());
            }
        }
    }

    List<Snapshot> available() throws IOException {
        preserve();
        List<Snapshot> result = new ArrayList<>();
        synchronized (RulesRecovery.class) {
            for (String name : NAMES) {
                File saved = new File(preserved, name);
                if (!saved.isFile()) continue;
                try {
                    byte[] bytes = read(saved);
                    List<RulesBackup.Row> rows = RulesBackup.decode(bytes);
                    if (hasRules(rows)) result.add(new Snapshot(saved.lastModified(), bytes, rows.size()));
                } catch (IOException invalid) {
                    // Retain invalid evidence too; never delete it or apply a partial snapshot.
                }
            }
        }
        return result;
    }

    static boolean hasRules(List<RulesBackup.Row> rows) {
        for (RulesBackup.Row row : rows)
            if (!"feature enabled".equals(row.text)) return true;
        return false;
    }

    private static byte[] read(File file) throws IOException {
        if (file.length() > RulesBackup.MAX_BYTES) throw new IOException("Rule snapshot is too large");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > RulesBackup.MAX_BYTES)
                    throw new IOException("Rule snapshot is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static final class Snapshot {
        final long modified;
        final byte[] bytes;
        final int count;
        Snapshot(long modified, byte[] bytes, int count) {
            this.modified = modified;
            this.bytes = bytes;
            this.count = count;
        }
    }
}
