package heartguard.heartguard.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
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

/** Searches recorded attempts without changing rules or the main screen's filters. */
public class ActivityDomainSearch extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::load;
    private boolean resumed;
    private final DatabaseHelper.AccessChangedListener listener = () -> main.post(() -> {
        if (resumed) schedule();
    });
    private SearchView search;
    private TextView status;
    private View copyDomains;
    private ArrayAdapter<Result> adapter;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setContentView(R.layout.domain_search);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        search = findViewById(R.id.domain_search);
        status = findViewById(R.id.domain_search_status);
        copyDomains = findViewById(R.id.domain_search_copy);
        copyDomains.setOnClickListener(view -> copyDomains());
        adapter = new ArrayAdapter<Result>(this, R.layout.domain_search_row, new ArrayList<>()) {
            @Override public boolean isEnabled(int position) { return false; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public View getView(int position, View view, ViewGroup parent) {
                if (view == null) view = getLayoutInflater().inflate(R.layout.domain_search_row, parent, false);
                Result result = getItem(position);
                ((TextView) view.findViewById(R.id.domain_result_apps)).setText(result.apps);
                ((TextView) view.findViewById(R.id.domain_result_host)).setText(result.domain);
                String outcome = getString(result.allowed > 0 ? R.string.domain_search_allowed
                        : result.allowed == 0 ? R.string.domain_search_blocked : R.string.domain_search_unknown);
                if (result.pending) outcome += " \u00b7 " + getString(R.string.domain_search_pending);
                ((TextView) view.findViewById(R.id.domain_result_outcome)).setText(outcome);
                ((TextView) view.findViewById(R.id.domain_result_time)).setText(DateUtils.formatDateTime(
                        ActivityDomainSearch.this, result.time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
                return view;
            }
        };
        adapter.setNotifyOnChange(false);
        ((ListView) findViewById(R.id.domain_search_results)).setAdapter(adapter);
        if (state != null) search.setQuery(state.getString("query", ""), false);
        search.clearFocus();
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            public boolean onQueryTextSubmit(String query) {
                search.clearFocus();
                schedule();
                return true;
            }
            public boolean onQueryTextChange(String query) {
                adapter.clear();
                adapter.notifyDataSetChanged();
                copyDomains.setEnabled(false);
                status.setText(new DomainSearchQuery(query).text.isEmpty()
                        ? R.string.domain_search_start : R.string.domain_search_loading);
                schedule();
                return true;
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        DatabaseHelper.getInstance(this).addAccessChangedListener(listener);
        schedule();
    }

    @Override protected void onPause() {
        resumed = false;
        generation++;
        main.removeCallbacks(reload);
        DatabaseHelper.getInstance(this).removeAccessChangedListener(listener);
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", search.getQuery().toString());
        super.onSaveInstanceState(state);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void schedule() {
        generation++;
        main.removeCallbacks(reload);
        if (resumed) main.postDelayed(reload, 200);
    }

    private void load() {
        int requested = generation;
        DomainSearchQuery query = new DomainSearchQuery(search.getQuery().toString());
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
                    status.setText(results.isEmpty() ? getString(R.string.domain_search_empty)
                            : getString(R.string.domain_search_count, results.size()));
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
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.domain_search_copy), TextUtils.join("\n", domains)));
        Toast.makeText(this, getResources().getQuantityString(R.plurals.domain_search_copied,
                domains.size(), domains.size()), Toast.LENGTH_SHORT).show();
    }

    private List<Result> read(DomainSearchQuery query) {
        List<Result> results = new ArrayList<>();
        Map<Integer, String> apps = new HashMap<>();
        Set<String> seen = new HashSet<>();
        try (Cursor cursor = DatabaseHelper.getInstance(this).searchAccessDomains(query)) {
            while (cursor.moveToNext() && !Thread.currentThread().isInterrupted()) {
                int uid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                String domain = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                // The query puts the latest attempt first, including ties across ports/protocols.
                if (!seen.add(uid + ":" + domain.toLowerCase(Locale.ROOT))) continue;
                if (!apps.containsKey(uid)) {
                    List<String> names = Util.getApplicationNames(uid, this);
                    String label = names.isEmpty() || (names.size() == 1 && names.get(0).equals(Integer.toString(uid)))
                            ? getString(R.string.domain_search_uid, uid)
                            : TextUtils.join(", ", names);
                    if (names.size() > 1) label += " (" + getString(R.string.domain_search_shared) + ")";
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
