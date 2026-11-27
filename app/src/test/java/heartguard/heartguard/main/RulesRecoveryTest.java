package heartguard.heartguard.main;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class RulesRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private byte[] savedRules() throws Exception {
        return RulesBackup.encode(Arrays.asList(
                new RulesBackup.Row("delay package:missing.app 21600", 123, 123, 1, 0, 0),
                new RulesBackup.Row("allow host:recovery.invalid", 123, 999999, 0, 0, 0)));
    }

    // Test: Preserve the exact existing snapshot when the next backup becomes empty.
    // Why: This is the reported recovery window; replacing the only copy would destroy it.
    @Test public void preservationSurvivesEmptyReplacement() throws Exception {
        File files = temp.newFolder("files"), safe = temp.newFolder("safe");
        File backup = new File(files, "backup"); assertTrue(backup.mkdir());
        File source = new File(backup, "rules.json"); byte[] bytes = savedRules();
        Files.write(source.toPath(), bytes); assertTrue(source.setLastModified(1700000000000L));
        RulesRecovery recovery = new RulesRecovery(files, safe); recovery.preserve();
        Files.write(source.toPath(), RulesBackup.encode(Collections.emptyList()));
        recovery.preserve();
        assertEquals(1, recovery.available().size());
        RulesRecovery.Snapshot saved = recovery.available().get(0);
        assertArrayEquals(bytes, saved.bytes); assertEquals(2, saved.count);
        assertEquals(1700000000000L, saved.modified);
        assertEquals(999999, RulesBackup.decode(saved.bytes).get(1).enactAt);
    }

    // Test: Recover a valid AtomicFile fallback while retaining a damaged main file.
    // Why: Interrupted writes must not hide the older recoverable copy.
    @Test public void damagedMainDoesNotHideFallback() throws Exception {
        File files = temp.newFolder("files"), safe = temp.newFolder("safe");
        File backup = new File(files, "backup"); assertTrue(backup.mkdir());
        Files.write(new File(backup, "rules.json").toPath(), new byte[]{1, 2});
        Files.write(new File(backup, "rules.json.bak").toPath(), savedRules());
        RulesRecovery recovery = new RulesRecovery(files, safe);
        assertEquals(1, recovery.available().size());
        assertTrue(new File(safe, "rules-recovery/rules.json").isFile());
        assertTrue(new File(backup, "rules.json.bak").isFile());
    }

    // Test: Empty or freshly re-enabled configurations cannot replace cloud rules.
    // Why: Turning protection back on after data loss must not make an empty backup look useful.
    @Test public void emptyAndEnabledOnlyAreNotRecoverableRules() throws Exception {
        assertFalse(RulesRecovery.hasRules(Collections.emptyList()));
        assertFalse(RulesRecovery.hasRules(Collections.singletonList(
                new RulesBackup.Row("feature enabled", 1, 1, 1, 0, 0))));
        assertTrue(RulesRecovery.hasRules(RulesBackup.decode(savedRules())));
    }

    // Test: A missing file reports no recovery copy without creating an empty replacement.
    // Why: The recovery screen must be observational until the user confirms restoration.
    @Test public void missingBackupDoesNotCreateOrReplaceAnything() throws Exception {
        File files = temp.newFolder("files"), safe = temp.newFolder("safe");
        assertTrue(new RulesRecovery(files, safe).available().isEmpty());
        assertFalse(new File(files, "backup/rules.json").exists());
        assertFalse(new File(safe, "rules-recovery").exists());
    }
}
