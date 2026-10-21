package heartguard.heartguard.main;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fork release notes, independent of APK availability and the installed version. */
final class UpdateHistory {
    static final int PAGE_SIZE = 100;
    interface PageSource { String read(int page) throws IOException; }

    static final class Entry {
        final String version;
        final String notes;
        final String date;
        final boolean prerelease;

        Entry(String version, String notes, boolean prerelease) {
            this(version, notes, prerelease, "");
        }

        Entry(String version, String notes, boolean prerelease, String date) {
            this.date = date;
            this.version = version;
            this.notes = notes;
            this.prerelease = prerelease;
        }
    }

    static List<Entry> load(PageSource source) throws IOException {
        Map<String, Entry> versions = new HashMap<>();
        for (int page = 1; ; page++) {
            try {
                JSONArray releases = new JSONArray(source.read(page));
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.getJSONObject(i);
                    String version = UpdateRelease.versionFromRelease(release);
                    if (version == null || release.optBoolean("draft", true)) continue;
                    // Pagination can overlap if a release is published while the list is loading.
                    if (!versions.containsKey(version))
                        versions.put(version, new Entry(version, release.isNull("body") ? "" : release.optString("body"),
                                release.optBoolean("prerelease", false), release.optString("published_at", "")));
                }
                // Count all API entries, including non-MW entries excluded from the dropdown.
                if (releases.length() < PAGE_SIZE) break;
            } catch (JSONException invalid) {
                throw new IOException("GitHub returned invalid update logs.", invalid);
            }
        }
        List<Entry> result = new ArrayList<>(versions.values());
        Collections.sort(result, (first, second) -> UpdateRelease.compare(second.version, first.version));
        return result;
    }

    static List<Entry> merge(List<Entry> bundled, List<Entry> published) {
        Map<String, Entry> versions = new HashMap<>();
        for (Entry entry : bundled) versions.put(entry.version, entry);
        for (Entry entry : published) versions.put(entry.version, entry);
        List<Entry> result = new ArrayList<>(versions.values());
        Collections.sort(result, (first, second) -> UpdateRelease.compare(second.version, first.version));
        return result;
    }
}
