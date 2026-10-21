package heartguard.heartguard.main;

import android.database.Cursor;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ActivityGlobalWhitelist extends AppCompatActivity {
    private final List<Entry> entries = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private EditText input;
    private final DatabaseHelper.RuleChangedListener listener = () -> runOnUiThread(() -> {
        if (!isFinishing() && !isDestroyed()) refresh();
    });

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setContentView(R.layout.global_whitelist_legacy);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        input = findViewById(R.id.whitelist_input);
        ListView list = findViewById(R.id.whitelist_entries);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.whitelist_empty));
        list.setOnItemClickListener((parent, view, position, id) -> remove(entries.get(position)));
        findViewById(R.id.whitelist_add).setOnClickListener(view -> add());
        CompactUi.actionButton(findViewById(R.id.whitelist_add));
    }

    @Override protected void onResume() {
        super.onResume();
        DatabaseHelper.getInstance(this).addRuleChangedListener(listener);
        RulesManager.getInstance(this).startTrueTimeRequest(this);
        refresh();
    }

    @Override protected void onPause() {
        DatabaseHelper.getInstance(this).removeRuleChangedListener(listener);
        super.onPause();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        entries.clear();
        Set<String> removing = new HashSet<>();
        try (Cursor cursor = DatabaseHelper.getInstance(this).getAllRulesSorted()) {
            int textCol = cursor.getColumnIndexOrThrow("ruletext");
            int enactedCol = cursor.getColumnIndexOrThrow("enacted");
            while (cursor.moveToNext()) {
                String text = cursor.getString(textCol);
                boolean active = cursor.getInt(enactedCol) > 0;
                if (!active && text.startsWith("- ") && DomainInput.globalDomain(text.substring(2)) != null)
                    removing.add(text.substring(2));
                String domain = DomainInput.globalDomain(text);
                if (domain != null) entries.add(new Entry(text, domain, active));
            }
        }
        adapter.clear();
        for (Entry entry : entries) {
            entry.removing = removing.contains(entry.rule);
            adapter.add(entry.domain + "\n" + getString(entry.removing ? R.string.whitelist_removing
                    : entry.active ? R.string.whitelist_active : R.string.whitelist_pending));
        }
        adapter.notifyDataSetChanged();
    }

    private void add() {
        Set<String> domains = new LinkedHashSet<>();
        try {
            for (String line : input.getText().toString().split("\\r?\\n")) {
                if (!line.trim().isEmpty()) domains.add(DomainInput.normalize(line));
            }
            if (domains.isEmpty()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            input.setError(getString(R.string.domain_error));
            return;
        }
        // Validate the entire paste before queuing any changes.
        for (String domain : domains) {
            boolean existing = false;
            for (Entry entry : entries) existing |= entry.domain.equals(domain);
            if (!existing)
                RulesManager.getInstance(this).queueRuleText(this, "allow " + DomainInput.destination(domain));
        }
        input.setText("");
        input.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        Toast.makeText(this, R.string.whitelist_added, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void remove(Entry entry) {
        String action = entry.removing || !entry.active ? getString(R.string.whitelist_cancel)
                : getString(R.string.whitelist_remove);
        AlertDialog confirmation = new AlertDialog.Builder(this).setTitle(action).setMessage(entry.domain)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String command = entry.removing ? "abandon - " : entry.active ? "- " : "abandon ";
                    RulesManager.getInstance(this).queueRuleText(this, command + entry.rule);
                }).show();
        DialogSurface.apply(confirmation);
        CompactUi.destructiveButton(confirmation.getButton(AlertDialog.BUTTON_POSITIVE));
    }

    private static final class Entry {
        final String rule, domain;
        final boolean active;
        boolean removing;
        Entry(String rule, String domain, boolean active) {
            this.rule = rule; this.domain = domain; this.active = active;
        }
    }
}
