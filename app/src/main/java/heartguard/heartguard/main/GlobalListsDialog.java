package heartguard.heartguard.main;

import android.database.Cursor;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class GlobalListsDialog {
    private final Context context;
    private final boolean blocked;
    private final AlertDialog dialog;
    private boolean showing;

    static void choose(Context context) {
        AlertDialog chooser = new AlertDialog.Builder(context)
                .setCustomTitle(SortDialog.title(context, R.string.global_lists))
                .setItems(new String[]{context.getString(R.string.global_blacklist), context.getString(R.string.global_whitelist)},
                        (window, index) -> new GlobalListsDialog(context, index == 0).show())
                .setNegativeButton(android.R.string.cancel, null).create();
        chooser.show();
        DialogSurface.apply(chooser);
    }
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> visibleEntries = new ArrayList<>();
    private DomainSearchQuery search = new DomainSearchQuery("");
    private ArrayAdapter<String> adapter;
    private EditText input;
    private android.widget.TextView empty;
    private final DatabaseHelper.RuleChangedListener listener = () -> input.post(() -> {
        if (showing) refresh();
    });

    GlobalListsDialog(Context context, boolean blocked) {
        this.context = context;
        this.blocked = blocked;
        View content = LayoutInflater.from(context).inflate(R.layout.global_whitelist, null, false);
        input = content.findViewById(R.id.whitelist_input);
        ((android.widget.TextView) content.findViewById(R.id.global_list_description)).setText(blocked
                ? R.string.global_blacklist_description : R.string.global_whitelist_description);
        empty = content.findViewById(R.id.whitelist_empty);
        ((android.widget.Button) content.findViewById(R.id.whitelist_add)).setText(blocked
                ? R.string.blacklist_add : R.string.whitelist_add);
        ListView list = content.findViewById(R.id.whitelist_entries);
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new ArrayList<String>());
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        list.setEmptyView(content.findViewById(R.id.whitelist_empty));
        list.setOnItemClickListener((parent, view, position, id) -> remove(visibleEntries.get(position)));
        SearchView searchView = content.findViewById(R.id.global_list_search);
        searchView.setVisibility(View.VISIBLE);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus();
                return true;
            }
            public boolean onQueryTextChange(String query) {
                search = new DomainSearchQuery(query);
                filter();
                return true;
            }
        });
        content.findViewById(R.id.whitelist_add).setOnClickListener(view -> add());
        dialog = new AlertDialog.Builder(context)
                .setCustomTitle(SortDialog.title(context, blocked ? R.string.global_blacklist : R.string.global_whitelist))
                .setView(content)
                .setPositiveButton(R.string.list_done, (window, which) -> choose(context))
                .setOnCancelListener(window -> choose(context)).create();
        androidx.lifecycle.LifecycleEventObserver lifecycleObserver = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss();
        };
        if (context instanceof androidx.lifecycle.LifecycleOwner)
            ((androidx.lifecycle.LifecycleOwner) context).getLifecycle().addObserver(lifecycleObserver);
        dialog.setOnDismissListener(window -> {
            if (context instanceof androidx.lifecycle.LifecycleOwner)
                ((androidx.lifecycle.LifecycleOwner) context).getLifecycle().removeObserver(lifecycleObserver);
            showing = false;
            DatabaseHelper.getInstance(context).removeRuleChangedListener(listener);
        });
    }

    void show() {
        showing = true;
        dialog.show();
        DialogSurface.apply(dialog);
        DatabaseHelper.getInstance(context).addRuleChangedListener(listener);
        RulesManager.getInstance(context).startTrueTimeRequest(context);
        refresh();
    }

    private String domain(String text) {
        if (!blocked) return DomainInput.globalDomain(text);
        if (!text.matches("blockdomain \\S+( try)?")) return null;
        return text.split(" ")[1];
    }

    private String getString(int resource) { return context.getString(resource); }

    private void refresh() {
        entries.clear();
        Set<String> removing = new HashSet<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRulesSorted()) {
            int textCol = cursor.getColumnIndexOrThrow("ruletext");
            int enactedCol = cursor.getColumnIndexOrThrow("enacted");
            while (cursor.moveToNext()) {
                String text = cursor.getString(textCol);
                boolean active = cursor.getInt(enactedCol) > 0;
                if (!active && text.startsWith("- ") && domain(text.substring(2)) != null)
                    removing.add(text.substring(2));
                String domain = domain(text);
                if (domain != null) entries.add(new Entry(text, domain, active));
            }
        }
        for (Entry entry : entries) entry.removing = removing.contains(entry.rule);
        filter();
    }

    private void filter() {
        visibleEntries.clear();
        List<String> labels = new ArrayList<>();
        for (Entry entry : entries) {
            if (!search.matchesRule(entry.domain)) continue;
            visibleEntries.add(entry);
            String label = entry.domain + "\n" + getString(entry.removing ? R.string.whitelist_removing
                    : entry.active ? R.string.whitelist_active : R.string.whitelist_pending);
            if (search.coversSearch(entry.domain)) label += " \u00b7 " + getString(R.string.global_search_parent);
            labels.add(label);
        }
        empty.setText(search.text.isEmpty()
                ? (blocked ? R.string.blacklist_empty : R.string.whitelist_empty) : R.string.global_search_empty);
        boolean unchanged = adapter.getCount() == labels.size();
        for (int i = 0; unchanged && i < labels.size(); i++)
            unchanged = labels.get(i).equals(adapter.getItem(i));
        if (unchanged) return;
        adapter.setNotifyOnChange(false);
        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();
    }

    private void add() {
        Set<String> domains = new LinkedHashSet<>();
        try {
            for (String line : input.getText().toString().split("\\r?\\n")) {
                if (!line.trim().isEmpty()) {
                    String normalized = DomainInput.normalize(line);
                    if (blocked && (normalized.contains(":") || normalized.matches("[0-9.]+")))
                        throw new IllegalArgumentException();
                    domains.add(normalized);
                }
            }
            if (domains.isEmpty()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            input.setError(getString(blocked ? R.string.blacklist_domain_error : R.string.domain_error));
            return;
        }
        // Validate the entire paste before queuing any changes.
        for (String domain : domains) {
            boolean existing = false;
            for (Entry entry : entries) existing |= entry.domain.equals(domain);
            if (!existing)
                RulesManager.getInstance(context).queueRuleText(context, blocked ? "blockdomain " + domain : "allow " + DomainInput.destination(domain));
        }
        input.setText("");
        input.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        Toast.makeText(context, R.string.rule_queued, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void remove(Entry entry) {
        String action = entry.removing || !entry.active ? getString(R.string.whitelist_cancel)
                : getString(blocked ? R.string.blacklist_remove : R.string.whitelist_remove);
        new AlertDialog.Builder(context).setTitle(action).setMessage(entry.domain)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String command = entry.removing ? "abandon - " : entry.active ? "- " : "abandon ";
                    RulesManager.getInstance(context).queueRuleText(context, command + entry.rule);
                }).show();
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
