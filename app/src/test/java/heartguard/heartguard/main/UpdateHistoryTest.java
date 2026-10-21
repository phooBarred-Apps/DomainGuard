package heartguard.heartguard.main;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class UpdateHistoryTest {
    private JSONObject release(String tag, String notes) throws Exception {
        return new JSONObject().put("tag_name", tag).put("body", notes).put("draft", false);
    }

    // Test: List published MW versions numerically newest first, regardless of APK availability.
    // Why: History must include older releases without mixing in upstream releases or draft notes.
    @Test public void filtersAndSortsPublishedMwHistory() throws Exception {
        JSONArray page = new JSONArray()
                .put(release("v2.317-mw.9", "Nine"))
                .put(release("v2.317", "Upstream"))
                .put(release("v2.317-mw.11", "Private draft").put("draft", true))
                .put(release("v2.317-mw.10", "Ten").put("prerelease", true))
                .put(release("v2.317-mw.3", "Three"))
                .put(release("v2.317-mw.12-extra", "Other format"));
        List<UpdateHistory.Entry> history = UpdateHistory.load(number -> page.toString());
        assertEquals(3, history.size());
        assertEquals("2.317-mw.10", history.get(0).version);
        assertTrue(history.get(0).prerelease);
        assertEquals("2.317-mw.9", history.get(1).version);
        assertFalse(history.get(1).prerelease);
        assertEquals("Three", history.get(2).notes);
    }

    // Test: Read later pages even when a full API page contains mostly excluded versions; deduplicate overlaps.
    // Why: Filtering before deciding to paginate would silently omit older MW updates.
    @Test public void paginatesUsingRawEntryCountAndDeduplicates() throws Exception {
        JSONArray first = new JSONArray().put(release("v2.317-mw.7", "First copy"));
        for (int i = 1; i < UpdateHistory.PAGE_SIZE; i++) first.put(release("v2." + i, "Upstream"));
        JSONArray second = new JSONArray().put(release("v2.317-mw.7", "Duplicate"))
                .put(release("v2.317-mw.3", "Oldest"));
        AtomicInteger calls = new AtomicInteger();
        List<UpdateHistory.Entry> history = UpdateHistory.load(number -> {
            assertEquals(calls.incrementAndGet(), number);
            assertTrue("Must stop after the short page", number <= 2);
            return (number == 1 ? first : second).toString();
        });
        assertEquals(2, calls.get());
        assertEquals(2, history.size());
        assertEquals("First copy", history.get(0).notes);
        assertEquals("2.317-mw.3", history.get(1).version);
    }

    // Test: Preserve complete notes and represent missing/null notes as empty text.
    // Why: Update history must not truncate long entries or display the literal word "null".
    @Test public void preservesLongNotesAndHandlesNoNotes() throws Exception {
        String longNotes = new String(new char[12000]).replace('\0', 'x');
        JSONArray page = new JSONArray().put(release("v2.317-mw.7", longNotes))
                .put(release("v2.317-mw.6", null).put("body", JSONObject.NULL))
                .put(release("v2.317-mw.5", null));
        List<UpdateHistory.Entry> history = UpdateHistory.load(number -> page.toString());
        assertEquals(longNotes, history.get(0).notes);
        assertEquals("", history.get(1).notes);
        assertEquals("", history.get(2).notes);
        assertTrue(UpdateHistory.load(number -> "[]").isEmpty());
    }

    // Test: Report invalid data or a later-page request failure instead of presenting incomplete history.
    // Why: A partial list could look like a successful list of all MW updates.
    @Test public void rejectsMalformedOrIncompleteHistory() throws Exception {
        assertThrows(IOException.class, () -> UpdateHistory.load(number -> "not JSON"));
        JSONArray first = new JSONArray();
        for (int i = 0; i < UpdateHistory.PAGE_SIZE; i++) first.put(release("v2.317-mw." + i, "Notes"));
        assertThrows(IOException.class, () -> UpdateHistory.load(number -> {
            if (number == 1) return first.toString();
            throw new IOException("Second page failed");
        }));
    }
}
