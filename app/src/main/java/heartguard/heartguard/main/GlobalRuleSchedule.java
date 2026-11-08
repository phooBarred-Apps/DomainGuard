package heartguard.heartguard.main;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Saved package identities and absolute deadlines, independent of a device's assigned UIDs. */
final class GlobalRuleSchedule {
    private static final String DEFAULT = "@default";
    static final GlobalRuleSchedule NONE = new GlobalRuleSchedule(Collections.emptyMap(), false);
    static final GlobalRuleSchedule INVALID = new GlobalRuleSchedule(Collections.emptyMap(), true);
    private final Map<String, Long> deadlines;
    private final boolean invalid;

    private GlobalRuleSchedule(Map<String, Long> deadlines, boolean invalid) {
        this.deadlines = Collections.unmodifiableMap(new HashMap<>(deadlines));
        this.invalid = invalid;
    }

    static GlobalRuleSchedule capture(long requestedAt, int defaultDelay, Map<String, Integer> overrides) {
        if (defaultDelay < 0 || requestedAt < 0 || requestedAt > Long.MAX_VALUE - defaultDelay * 1000L)
            throw new IllegalArgumentException();
        Map<String, Long> deadlines = new HashMap<>();
        deadlines.put(DEFAULT, requestedAt + defaultDelay * 1000L);
        for (Map.Entry<String, Integer> entry : overrides.entrySet())
            if (entry.getValue() > defaultDelay) {
                long wait = entry.getValue() * 1000L;
                if (requestedAt < 0 || requestedAt > Long.MAX_VALUE - wait) throw new IllegalArgumentException();
                deadlines.put(entry.getKey(), requestedAt + wait);
            }
        return new GlobalRuleSchedule(deadlines, false);
    }

    static GlobalRuleSchedule parse(String text) {
        if (text == null) return NONE; // Existing rules retain their existing activation time.
        try {
            JSONObject values = new JSONObject(text);
            Map<String, Long> deadlines = new HashMap<>();
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long deadline = Long.parseLong(values.get(key).toString());
                if (key.isEmpty() || !key.matches("\\S+") || deadline < 0) throw new IllegalArgumentException();
                deadlines.put(key, deadline);
            }
            return new GlobalRuleSchedule(deadlines, false);
        } catch (JSONException | NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid global rule deadlines", invalid);
        }
    }

    String encode() {
        if (invalid) throw new IllegalStateException("Cannot save invalid deadlines");
        return deadlines.isEmpty() ? null : new JSONObject(deadlines).toString();
    }

    boolean hasWaits() { return invalid || !deadlines.isEmpty(); }
    boolean hasDefault() { return deadlines.containsKey(DEFAULT); }
    long defaultAt() { return deadlines.containsKey(DEFAULT) ? deadlines.get(DEFAULT) : 0; }
    Map<String, Long> appDeadlines() {
        Map<String, Long> apps = new HashMap<>(deadlines); apps.remove(DEFAULT); return apps;
    }
    int phase(long now) {
        int ready = 0;
        for (long time : deadlines.values()) if (time <= now) ready++;
        return ready;
    }

    long readyAt(String[] packages) {
        if (invalid) return Long.MAX_VALUE; // Corrupt timing must never grant early access.
        if (packages == null || packages.length == 0) return latest();
        long ready = 0;
        for (String name : packages) {
            Long deadline = deadlines.get(name);
            ready = Math.max(ready, deadline == null ? defaultAt() : deadline);
        }
        return ready;
    }

    long latest() {
        if (invalid) return Long.MAX_VALUE;
        long latest = 0;
        for (long deadline : deadlines.values()) latest = Math.max(latest, deadline);
        return latest;
    }

    long next(long now, long expiresAt) {
        long next = 0;
        for (long deadline : deadlines.values())
            if (deadline > now && deadline < expiresAt && (next == 0 || deadline < next)) next = deadline;
        return next;
    }

    int waitingCount(long now) {
        int count = 0;
        for (long deadline : appDeadlines().values()) if (deadline > now) count++;
        return count;
    }

    int excludedCount(long now, long expiresAt) {
        int count = 0;
        for (long deadline : appDeadlines().values()) if (deadline > now && deadline >= expiresAt) count++;
        return count;
    }

    GlobalRuleSchedule retire(long now) {
        if (invalid) return this;
        if (hasDefault()) return latest() <= now ? NONE : this;
        Map<String, Long> remaining = new HashMap<>(deadlines);
        Iterator<Long> values = remaining.values().iterator();
        while (values.hasNext()) if (values.next() <= now) values.remove();
        return remaining.size() == deadlines.size() ? this : new GlobalRuleSchedule(remaining, false);
    }
}
