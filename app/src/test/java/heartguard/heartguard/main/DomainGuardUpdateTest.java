package heartguard.heartguard.main;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import static org.junit.Assert.*;

public class DomainGuardUpdateTest {
    // Test: DomainGuard follows MW numerically and both brands retain their published history.
    // Why: Version 2.4 is numerically lower than the old upstream base 2.317 but is the next fork release.
    @Test public void modernAndLegacyHistoryStayTogether() throws Exception {
        JSONObject modern = new JSONObject().put("tag_name", "v2.4").put("name", "DomainGuard 2.4")
                .put("draft", false).put("prerelease", false).put("published_at", "2026-10-21T12:00:00Z").put("body", "New notes");
        JSONObject legacy = new JSONObject().put("tag_name", "v2.317-mw.1").put("draft", false).put("body", "Original notes");
        JSONObject upstream = new JSONObject().put("tag_name", "v2.400").put("name", "NetGuard 2.400").put("draft", false);
        List<UpdateHistory.Entry> history = UpdateHistory.load(page -> new JSONArray().put(legacy).put(upstream).put(modern).toString());
        assertEquals(2, history.size());
        assertEquals("2.4", history.get(0).version);
        assertEquals("2026-10-21T12:00:00Z", history.get(0).date);
        assertEquals("Original notes", history.get(1).notes);
        assertTrue(UpdateRelease.compare("2.4", "2.317-mw.999") > 0);
        assertTrue(UpdateRelease.compare("2.10", "2.4") > 0);
        String hash = new String(new char[64]).replace('\0', 'a');
        modern.put("assets", new JSONArray().put(new JSONObject().put("name", "DomainGuard.apk").put("state", "uploaded")
                .put("size", 123).put("digest", "sha256:" + hash).put("browser_download_url", "https://github.com/"
                        + UpdateRelease.REPOSITORY + "/releases/download/v2.4/DomainGuard.apk")));
        assertEquals("2.4", UpdateRelease.parse(modern.toString(), "2.317-mw.7").version);
        assertNull(UpdateRelease.parse(modern.toString(), "2.4"));
    }
}
