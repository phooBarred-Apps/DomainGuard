package heartguard.heartguard.main;

import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class UpdateTest {
    private static final String HASH = new String(new char[64]).replace('\0', 'a');
    private JSONObject listing() throws Exception {
        JSONObject asset = new JSONObject().put("name", "HeartGuard-MW.apk").put("state", "uploaded")
                .put("size", 123).put("digest", "sha256:" + HASH)
                .put("browser_download_url", "https://github.com/" + UpdateRelease.REPOSITORY
                        + "/releases/download/v2.317-mw.2/HeartGuard-MW.apk");
        return new JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v2.317-mw.2")
                .put("body", "Release notes").put("assets", new JSONArray().put(asset));
    }
    private UpdateRelease parse(JSONObject listing) throws IOException {
        return UpdateRelease.parse(listing.toString(), "2.317-mw.1");
    }
    @Test public void findsNewerMwRelease() throws Exception { assertEquals("2.317-mw.2", parse(listing()).version); }
    // Test: The configured API and strict asset validation accept the canonical release repository.
    // Why: A working API check is insufficient if the installer rejects the returned download URL.
    @Test public void acceptsCanonicalDomainGuardRelease() throws Exception {
        assertEquals("https://api.github.com/repos/phooBarred/DomainGuard-downloads/releases/latest",
                BuildConfig.GITHUB_LATEST_API);
        JSONObject release = listing().put("tag_name", "v2.83").put("name", "DomainGuard 2.83");
        release.getJSONArray("assets").getJSONObject(0).put("name", "DomainGuard.apk")
                .put("browser_download_url", "https://github.com/phooBarred/DomainGuard-downloads/releases/download/v2.83/DomainGuard.apk");
        assertEquals("2.83", UpdateRelease.parse(release.toString(), "2.82").version);
    }
    @Test public void comparesNumericVersionComponents() {
        assertTrue(UpdateRelease.compare("2.317-mw.10", "2.317-mw.2") > 0);
        assertTrue(UpdateRelease.compare("2.318-mw.1", "2.317-mw.99") > 0);
    }
    @Test public void ignoresCurrentOlderDraftAndPrereleases() throws Exception {
        assertNull(UpdateRelease.parse(listing().toString(), "2.317-mw.2"));
        assertNull(UpdateRelease.parse(listing().toString(), "2.317-mw.3"));
        assertNull(parse(listing().put("draft", true)));
        assertNull(parse(listing().put("prerelease", true)));
    }
    @Test public void rejectsMissingChecksum() throws Exception {
        JSONObject listing = listing();
        listing.getJSONArray("assets").getJSONObject(0).remove("digest");
        assertThrows(IOException.class, () -> parse(listing));
    }
    @Test public void rejectsMalformedListing() {
        assertThrows(IOException.class, () -> UpdateRelease.parse("not JSON", "2.317-mw.1"));
    }
    @Test public void rejectsWrongRepoOrUntrustedAssetUrls() throws Exception {
        for (String url : new String[]{"http://github.com/" + UpdateRelease.REPOSITORY,
                "https://evil.example/update.apk", "https://github.com/other/repo/releases/download/v2.317-mw.2/HeartGuard-MW.apk",
                "https://github.com/" + UpdateRelease.REPOSITORY + "/releases/download/v2.317-mw.2/HeartGuard-MW.apk?extra=1"}) {
            JSONObject listing = listing();
            listing.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url);
            assertThrows(IOException.class, () -> parse(listing));
        }
    }
    @Test public void rejectsMissingAndOversizedApk() throws Exception {
        assertThrows(IOException.class, () -> parse(listing().put("assets", new JSONArray())));
        JSONObject listing = listing();
        listing.getJSONArray("assets").getJSONObject(0).put("size", UpdateRelease.MAX_APK_BYTES + 1);
        assertThrows(IOException.class, () -> parse(listing));
    }
    @Test public void redirectPolicyStaysOnHttpsGithubHosts() {
        assertTrue(UpdateClient.allowed(URI.create("https://release-assets.githubusercontent.com/path?sig=x"), true));
        assertFalse(UpdateClient.allowed(URI.create("http://github.com/path"), true));
        assertFalse(UpdateClient.allowed(URI.create("https://github.com.evil.test/path"), true));
        assertFalse(UpdateClient.allowed(URI.create("https://user@github.com/path"), true));
        assertFalse(UpdateClient.allowed(URI.create("https://github.com:444/path"), true));
        assertFalse(UpdateClient.allowed(URI.create("https://github.com/path"), false));
    }
    @Test public void verifiesDownloadedBytes() throws Exception {
        byte[] data = "APK fixture".getBytes(StandardCharsets.UTF_8);
        UpdateRelease release = new UpdateRelease("2.317-mw.2", "", URI.create("https://github.com"),
                data.length, UpdateClient.hex(UpdateClient.sha256().digest(data)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new UpdateClient().copyVerified(new ByteArrayInputStream(data), out, release, percent -> {});
        assertArrayEquals(data, out.toByteArray());
    }
    @Test public void rejectsTruncationTamperingAndOversize() {
        byte[] data = {1, 2, 3};
        for (long size : new long[]{2, 3, 4}) {
            UpdateRelease release = new UpdateRelease("", "", null, size, HASH);
            assertThrows(IOException.class, () -> new UpdateClient().copyVerified(new ByteArrayInputStream(data),
                    new ByteArrayOutputStream(), release, percent -> {}));
        }
    }
    @Test public void cancellationStopsDownload() {
        UpdateClient client = new UpdateClient();
        client.cancel();
        assertThrows(IOException.class, () -> client.copyVerified(new ByteArrayInputStream(new byte[]{1}),
                new ByteArrayOutputStream(), new UpdateRelease("", "", null, 1, HASH), percent -> {}));
    }
}
