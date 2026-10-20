package heartguard.heartguard.main;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned rules-only snapshot; traffic, DNS caches and APK downloads are never included. */
final class RulesBackup {
    static final int MAX_BYTES = 4 * 1024 * 1024;

    static final class Row {
        final String text;
        final long created, enactAt;
        final int enacted, major, minor;

        Row(String text, long created, long enactAt, int enacted, int major, int minor) {
            this.text = text;
            this.created = created;
            this.enactAt = enactAt;
            this.enacted = enacted;
            this.major = major;
            this.minor = minor;
        }
    }

    static byte[] encode(List<Row> rows) throws IOException {
        try {
            JSONArray values = new JSONArray();
            for (Row row : rows)
                values.put(new JSONObject().put("text", row.text).put("created", row.created)
                        .put("enactAt", row.enactAt).put("enacted", row.enacted)
                        .put("major", row.major).put("minor", row.minor));
            byte[] bytes = new JSONObject().put("version", 1).put("rules", values)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Rules exceed the backup size limit");
            return bytes;
        } catch (JSONException invalid) {
            throw new IOException("Cannot encode rule backup", invalid);
        }
    }

    static List<Row> decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Rule backup is too large");
        try {
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (data.getInt("version") != 1) throw new IOException("Unsupported rule backup version");
            JSONArray values = data.getJSONArray("rules");
            List<Row> rows = new ArrayList<>();
            Set<String> texts = new HashSet<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                Row row = new Row(value.getString("text"), value.getLong("created"), value.getLong("enactAt"),
                        value.getInt("enacted"), value.getInt("major"), value.getInt("minor"));
                if (row.text.trim().isEmpty() || row.created < 0 || row.enactAt < 0 ||
                        (row.enacted != 0 && row.enacted != 1) || !texts.add(row.text))
                    throw new IOException("Invalid rule backup");
                rows.add(row);
            }
            return rows;
        } catch (JSONException invalid) {
            throw new IOException("Invalid rule backup", invalid);
        }
    }
}
