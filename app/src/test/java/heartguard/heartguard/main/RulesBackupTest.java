package heartguard.heartguard.main;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class RulesBackupTest {
    // Test: Restore rule text, active/pending state, activation times and partner configuration.
    // Why: Backups must preserve enforcement and must not prematurely activate pending changes.
    @Test public void roundTripPreservesPendingStateAndPartnerConfiguration() throws Exception {
        List<RulesBackup.Row> source = Arrays.asList(
                new RulesBackup.Row("delay 60", 1000, 1000, 1, 100, 100),
                new RulesBackup.Row("allow host:example.org", 2000, 62000, 0, 200, 10),
                new RulesBackup.Row("partner name:test totp:ABCDEFGH", 3000, 63000, 0, 300, 1));
        List<RulesBackup.Row> restored = RulesBackup.decode(RulesBackup.encode(source));
        assertEquals(3, restored.size());
        assertEquals("delay 60", restored.get(0).text);
        assertEquals(1, restored.get(0).enacted);
        assertEquals(0, restored.get(1).enacted);
        assertEquals(62000, restored.get(1).enactAt);
        assertEquals(source.get(2).text, restored.get(2).text);
    }

    // Test: An intentionally empty rule set round-trips successfully.
    // Why: No saved rules is a valid configuration, not a corrupt backup.
    @Test public void emptyConfigurationCanBeRestored() throws Exception {
        assertTrue(RulesBackup.decode(RulesBackup.encode(Collections.emptyList())).isEmpty());
    }

    // Test: Reject malformed data, unknown versions and invalid activation states.
    // Why: Validate the entire snapshot before replacing saved rules.
    @Test public void rejectsUnknownAndCorruptBackupsBeforeRestore() {
        for (String value : new String[]{"", "{", "{\"version\":2,\"rules\":[]}",
                "{\"version\":1,\"rules\":[{}]}",
                "{\"version\":1,\"rules\":[{\"text\":\"delay 0\",\"created\":0,\"enactAt\":0,\"enacted\":2,\"major\":0,\"minor\":0}]}"}) {
            try {
                RulesBackup.decode(value.getBytes(StandardCharsets.UTF_8));
                fail("Invalid snapshot accepted");
            } catch (IOException expected) {}
        }
    }

    // Test: Reject a snapshot larger than the rules backup budget.
    // Why: Restore input must be bounded before parsing.
    @Test public void rejectsOversizedBackup() {
        try {
            RulesBackup.decode(new byte[RulesBackup.MAX_BYTES + 1]);
            fail("Oversized snapshot accepted");
        } catch (IOException expected) {}
    }
}
