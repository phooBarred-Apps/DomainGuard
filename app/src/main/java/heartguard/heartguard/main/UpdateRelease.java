package heartguard.heartguard.main;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UpdateRelease {
    static final String REPOSITORY = "phooBarred/DomainGuard-downloads";
    static final long MAX_APK_BYTES = 64 * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)-mw\\.(\\d+)");
    private static final Pattern DOMAIN_GUARD_VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    final String version, notes, sha256;
    String publishedAt = "";
    final URI url;
    final long size;

    UpdateRelease(String version, String notes, URI url, long size, String sha256) {
        this.version = version; this.notes = notes; this.url = url;
        this.size = size; this.sha256 = sha256;
    }

    static UpdateRelease parse(String json, String installedVersion) throws IOException {
        try {
            JSONObject release = new JSONObject(json);
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true))
                return null;
            String tag = release.getString("tag_name");
            String version = versionFromRelease(release);
            if (version == null)
                throw new IOException("The published version is not a DomainGuard update.");
            if (compare(version, installedVersion) <= 0)
                return null;
            String assetName = VERSION.matcher(version).matches() ? "HeartGuard-MW.apk" : "DomainGuard.apk";
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!assetName.equals(asset.optString("name")) ||
                        !"uploaded".equals(asset.optString("state"))) continue;
                String digest = asset.optString("digest");
                long size = asset.optLong("size");
                if (!digest.matches("sha256:[0-9a-fA-F]{64}") || size <= 0 || size > MAX_APK_BYTES)
                    throw new IOException("The release is missing a valid APK size or checksum.");
                URI uri = URI.create(asset.getString("browser_download_url"));
                String path = "/" + REPOSITORY + "/releases/download/" + tag + "/" + assetName;
                if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                        || uri.getFragment() != null || !path.equals(uri.getRawPath()))
                    throw new IOException("The update is outside the DomainGuard release repository.");
                String notes = release.optString("body");
                if (notes.length() > 8000) notes = notes.substring(0, 8000);
                UpdateRelease result = new UpdateRelease(version, notes, uri, size, digest.substring(7).toLowerCase(Locale.ROOT));
                result.publishedAt = release.optString("published_at", "");
                return result;
            }
            throw new IOException("The latest release does not contain " + assetName + ".");
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("GitHub returned an invalid update listing.", invalid);
        }
    }

    static String versionFromRelease(JSONObject release) {
        String version = versionFromTag(release.optString("tag_name"));
        if (version == null) return null;
        // Legacy MW tags identify our fork. New plain version tags require our release name,
        // so unrelated upstream releases never enter this app's update history.
        return VERSION.matcher(version).matches() || release.optString("name").startsWith("DomainGuard ") ? version : null;
    }

    static String versionFromTag(String tag) {
        if (!tag.startsWith("v")) return null;
        String version = tag.substring(1);
        return VERSION.matcher(version).matches() || DOMAIN_GUARD_VERSION.matcher(version).matches() ? version : null;
    }

    static int compare(String first, String second) {
        boolean legacyFirst = VERSION.matcher(first).matches(), legacySecond = VERSION.matcher(second).matches();
        Matcher a = (legacyFirst ? VERSION : DOMAIN_GUARD_VERSION).matcher(first);
        Matcher b = (legacySecond ? VERSION : DOMAIN_GUARD_VERSION).matcher(second);
        if (!a.matches() || !b.matches()) throw new IllegalArgumentException("Unsupported version.");
        // DomainGuard 2.4 follows every legacy release despite the old upstream 2.317 base.
        if (legacyFirst != legacySecond) return legacyFirst ? -1 : 1;
        for (int group = 1; group <= 3; group++) {
            int compared = new java.math.BigInteger(a.group(group) == null ? "0" : a.group(group))
                    .compareTo(new java.math.BigInteger(b.group(group) == null ? "0" : b.group(group)));
            if (compared != 0) return compared;
        }
        return 0;
    }
}
