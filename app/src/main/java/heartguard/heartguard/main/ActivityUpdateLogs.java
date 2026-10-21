package heartguard.heartguard.main;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ActivityUpdateLogs extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final UpdateClient client = new UpdateClient();
    private Spinner versions;
    private TextView notes;
    private ScrollView scroll;
    private View selector, progress, retry;
    private String selectedVersion;
    private int restoredScroll;

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setContentView(R.layout.update_logs);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        versions = findViewById(R.id.update_logs_versions);
        notes = findViewById(R.id.update_logs_notes);
        scroll = findViewById(R.id.update_logs_scroll);
        selector = findViewById(R.id.update_logs_selector);
        progress = findViewById(R.id.update_logs_progress);
        retry = findViewById(R.id.update_logs_retry);
        CompactUi.frameless(retry);
        if (state != null) {
            selectedVersion = state.getString("version");
            restoredScroll = state.getInt("scroll");
        }
        retry.setOnClickListener(view -> load());
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        selector.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        notes.setText(R.string.update_logs_loading);
        worker.execute(() -> {
            List<UpdateHistory.Entry> bundled = new ArrayList<>();
            try (java.io.InputStream input = getResources().openRawResource(R.raw.fork_update_history);
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                String json = output.toString("UTF-8");
                bundled = UpdateHistory.load(page -> page == 1 ? json : "[]");
            } catch (IOException invalidBundle) { /* Online history remains available. */ }
            final List<UpdateHistory.Entry> saved = bundled;
            try {
                List<UpdateHistory.Entry> history = UpdateHistory.merge(saved, client.history());
                post(() -> showHistory(history));
            } catch (IOException | RuntimeException failure) {
                post(() -> {
                    if (!saved.isEmpty()) {
                        showHistory(saved);
                        retry.setVisibility(View.VISIBLE);
                        return;
                    }
                    progress.setVisibility(View.GONE);
                    notes.setText(getString(R.string.update_logs_error, failure instanceof IOException
                            ? failure.getMessage() : getString(R.string.update_retry)));
                    retry.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showHistory(List<UpdateHistory.Entry> history) {
        progress.setVisibility(View.GONE);
        if (history.isEmpty()) {
            notes.setText(R.string.update_logs_empty);
            return;
        }
        List<String> labels = new ArrayList<>();
        int selection = 0;
        for (int i = 0; i < history.size(); i++) {
            UpdateHistory.Entry entry = history.get(i);
            String label = entry.prerelease ? getString(R.string.update_logs_prerelease, entry.version) : entry.version;
            labels.add(label + (entry.date.length() >= 10 ? " · " + entry.date.substring(0, 10) : ""));
            if (entry.version.equals(selectedVersion)) selection = i;
        }
        if (!history.get(selection).version.equals(selectedVersion)) restoredScroll = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        versions.setOnItemSelectedListener(null);
        versions.setAdapter(adapter);
        versions.setSelection(selection);
        showEntry(history.get(selection));
        int offset = restoredScroll;
        scroll.post(() -> scroll.scrollTo(0, offset));
        selector.setVisibility(View.VISIBLE);
        versions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                UpdateHistory.Entry entry = history.get(position);
                if (!entry.version.equals(selectedVersion)) {
                    showEntry(entry);
                    scroll.scrollTo(0, 0);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void showEntry(UpdateHistory.Entry entry) {
        selectedVersion = entry.version;
        notes.setText(entry.notes.trim().isEmpty() ? getString(R.string.update_logs_no_notes) : entry.notes);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("version", selectedVersion);
        state.putInt("scroll", selector.getVisibility() == View.VISIBLE ? scroll.getScrollY() : restoredScroll);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onDestroy() {
        client.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void post(Runnable callback) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) callback.run(); });
    }
}
