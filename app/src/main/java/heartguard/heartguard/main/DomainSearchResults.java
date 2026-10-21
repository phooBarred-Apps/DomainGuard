package heartguard.heartguard.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared live domain results for the main screen and the standalone search screen. */
final class DomainSearchResults {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::load;
    private boolean resumed;
    private final DatabaseHelper.AccessChangedListener listener = () -> main.post(() -> {
        if (resumed) schedule();
    });
    private final Context context;
    private String query = "";
    private final TextView status;
    private final View copyDomains;
    private final ArrayAdapter<Result> adapter;
    private int generation;

    DomainSearchResults(Context context, View root) {
        this.context = context;
        status = root.findViewById(R.id.domain_search_status);
        copyDomains = root.findViewById(R.id.domain_search_copy);
        copyDomains.setOnClickListener(view -> copyDomains());
        adapter = new ArrayAdapter<Result>(context, R.layout.domain_search_row, new ArrayList<>()) {
            @Override public boolean isEnabled(int position) { return false; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public View getView(int position, View view, ViewGroup parent) {
                if (view == null) view = LayoutInflater.from(context).inflate(R.layout.domain_search_row, parent, false);
                Result result = getItem(position);
                ((TextView) view.findViewById(R.id.domain_result_apps)).setText(result.apps);
                ((TextView) view.findViewById(R.id.domain_result_host)).setText(result.domain);
                String outcome = context.getString(result.allowed > 0 ? R.string.domain_search_allowed
                        : result.allowed == 0 ? R.string.domain_search_blocked : R.string.domain_search_unknown);
                if (result.pending) outcome += " \u00b7 " + context.getString(R.string.domain_search_pending);
                ((TextView) view.findViewById(R.id.domain_result_outcome)).setText(outcome);
                ((TextView) view.findViewById(R.id.domain_result_time)).setText(DateUtils.formatDateTime(
                        context, result.time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
                return view;
            }
        };
        adapter.setNotifyOnChange(false);
        ((ListView) root.findViewById(R.id.domain_search_results)).setAdapter(adapter);
    }

    void start() {
        if (resumed) return;
        resumed = true;
        DatabaseHelper.getInstance(context).addAccessChangedListener(listener);
        schedule();
    }

    void stop() {
        resumed = false;
        generation++;
        main.removeCallbacks(reload);
        DatabaseHelper.getInstance(context).removeAccessChangedListener(listener);
    }

    void close() {
        stop();
        worker.shutdownNow();
    }

    String getQuery() { return query; }

    void setQuery(String query) {
        if (this.query.equals(query)) return;
        this.query = query;
        adapter.clear();
        adapter.notifyDataSetChanged();
        copyDomains.setEnabled(false);
        status.setText(new DomainSearchQuery(query).text.isEmpty()
                ? R.string.domain_search_start : R.string.domain_search_loading);
        schedule();
    }

    private void schedule() {
        generation++;
        main.removeCallbacks(reload);
        if (resumed) main.postDelayed(reload, 200);
    }

    private void load() {
        int requested = generation;
        DomainSearchQuery query = new DomainSearchQuery(this.query);
        if (query.text.isEmpty()) {
            status.setText(R.string.domain_search_start);
            return;
        }
        worker.execute(() -> {
            try {
                List<Result> results = read(query);
                main.post(() -> {
                    if (!resumed || generation != requested) return;
                    adapter.clear();
                    adapter.addAll(results);
                    adapter.notifyDataSetChanged();
                    copyDomains.setEnabled(!results.isEmpty());
                    status.setText(results.isEmpty() ? context.getString(R.string.domain_search_empty)
                            : context.getString(R.string.domain_search_count, results.size()));
                });
            } catch (RuntimeException failure) {
                Log.e("HeartGuard.DomainSearch", "Could not read domain attempts", failure);
                main.post(() -> {
                    if (resumed && generation == requested) status.setText(R.string.domain_search_error);
                });
            }
        });
    }

    private void copyDomains() {
        Set<String> domains = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < adapter.getCount(); i++) domains.add(adapter.getItem(i).domain);
        if (domains.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.domain_search_copy), TextUtils.join("\n", domains)));
        Toast.makeText(context, context.getResources().getQuantityString(R.plurals.domain_search_copied,
                domains.size(), domains.size()), Toast.LENGTH_SHORT).show();
    }

    private List<Result> read(DomainSearchQuery query) {
        List<Result> results = new ArrayList<>();
        Map<Integer, String> apps = new HashMap<>();
        Set<String> seen = new HashSet<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).searchAccessDomains(query)) {
            while (cursor.moveToNext() && !Thread.currentThread().isInterrupted()) {
                int uid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                String domain = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                // The query puts the latest attempt first, including ties across ports/protocols.
                if (!seen.add(uid + ":" + domain.toLowerCase(Locale.ROOT))) continue;
                if (!apps.containsKey(uid)) {
                    List<String> names = Util.getApplicationNames(uid, context);
                    String label = names.isEmpty() || (names.size() == 1 && names.get(0).equals(Integer.toString(uid)))
                            ? context.getString(R.string.domain_search_uid, uid)
                            : TextUtils.join(", ", names);
                    if (names.size() > 1) label += " (" + context.getString(R.string.domain_search_shared) + ")";
                    apps.put(uid, label);
                }
                int allowedColumn = cursor.getColumnIndexOrThrow("allowed");
                results.add(new Result(uid, apps.get(uid), domain,
                        cursor.isNull(allowedColumn) ? -1 : cursor.getInt(allowedColumn),
                        cursor.getInt(cursor.getColumnIndexOrThrow("pending_allow")) > 0,
                        cursor.getLong(cursor.getColumnIndexOrThrow("time"))));
            }
        }
        // New traffic updates outcomes without moving existing results to the top.
        Collections.sort(results, (left, right) -> {
            int app = left.apps.compareToIgnoreCase(right.apps);
            if (app != 0) return app;
            int domain = left.domain.compareToIgnoreCase(right.domain);
            return domain != 0 ? domain : Integer.compare(left.uid, right.uid);
        });
        return results;
    }

    private static final class Result {
        final int uid, allowed;
        final String apps, domain;
        final boolean pending;
        final long time;
        Result(int uid, String apps, String domain, int allowed, boolean pending, long time) {
            this.uid = uid; this.apps = apps; this.domain = domain; this.allowed = allowed;
            this.pending = pending; this.time = time;
        }
    }
}
