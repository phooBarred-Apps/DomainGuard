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
    static final String REPOSITORY = "phooBarred/heartguard-mw-downloads";
    static final long MAX_APK_BYTES = 64 * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)-mw\\.(\\d+)");
    final String version, notes, sha256;
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
            if (!tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches())
                throw new IOException("The published version is not a HeartGuard-MW update.");
            String version = tag.substring(1);
            if (compare(version, installedVersion) <= 0)
                return null;
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!"HeartGuard-MW.apk".equals(asset.optString("name")) ||
                        !"uploaded".equals(asset.optString("state"))) continue;
                String digest = asset.optString("digest");
                long size = asset.optLong("size");
                if (!digest.matches("sha256:[0-9a-fA-F]{64}") || size <= 0 || size > MAX_APK_BYTES)
                    throw new IOException("The release is missing a valid APK size or checksum.");
                URI uri = URI.create(asset.getString("browser_download_url"));
                String path = "/" + REPOSITORY + "/releases/download/" + tag + "/HeartGuard-MW.apk";
                if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                        || uri.getFragment() != null || !path.equals(uri.getRawPath()))
                    throw new IOException("The update is outside the HeartGuard-MW release repository.");
                String notes = release.optString("body");
                if (notes.length() > 8000) notes = notes.substring(0, 8000);
                return new UpdateRelease(version, notes, uri, size, digest.substring(7).toLowerCase(Locale.ROOT));
            }
            throw new IOException("The latest release does not contain HeartGuard-MW.apk.");
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("GitHub returned an invalid update listing.", invalid);
        }
    }

    static int compare(String first, String second) {
        Matcher a = VERSION.matcher(first), b = VERSION.matcher(second);
        if (!a.matches() || !b.matches()) throw new IllegalArgumentException("Unsupported version.");
        for (int group = 1; group <= 3; group++) {
            int compared = new java.math.BigInteger(a.group(group)).compareTo(new java.math.BigInteger(b.group(group)));
            if (compared != 0) return compared;
        }
        return 0;
    }
}
