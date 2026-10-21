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
        ArrayAdapter<String> choices = new ArrayAdapter<String>(context, R.layout.global_list_choice,
                R.id.global_list_choice_label, new String[]{context.getString(R.string.global_blacklist),
                context.getString(R.string.global_whitelist)}) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                View row = super.getView(position, recycled, parent);
                android.widget.ImageView icon = row.findViewById(R.id.global_list_choice_icon);
                icon.setImageResource(position == 0 ? R.drawable.host_global_blocked : R.drawable.host_global_allowed);
                return row;
            }
        };
        AlertDialog chooser = new AlertDialog.Builder(context)
                .setCustomTitle(SortDialog.title(context, R.string.global_lists))
                .setAdapter(choices,
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
    private android.widget.TextView empty, count;
    private ListView list;
    private AlertDialog addDialog;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() { if (showing) { filter(); handler.postDelayed(this, 1000); } }
    };
    private final DatabaseHelper.RuleChangedListener listener = () -> handler.post(() -> {
        if (showing) refresh();
    });

    GlobalListsDialog(Context context, boolean blocked) {
        this.context = context;
        this.blocked = blocked;
        View content = LayoutInflater.from(context).inflate(R.layout.global_whitelist, null, false);
        empty = content.findViewById(R.id.whitelist_empty);
        count = content.findViewById(R.id.global_list_count);
        list = content.findViewById(R.id.whitelist_entries);
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new ArrayList<String>());
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        list.setEmptyView(content.findViewById(R.id.whitelist_empty));
        list.setOnItemClickListener((parent, view, position, id) -> remove(visibleEntries.get(position)));
        SearchView searchView = content.findViewById(R.id.global_list_search);
        searchView.setVisibility(View.GONE);
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
        android.widget.LinearLayout header = CompactUi.header(context,
                blocked ? R.string.global_blacklist : R.string.global_whitelist, 0, 0, null);
        header.addView(CompactUi.icon(context, R.drawable.ic_search_white_24dp, R.string.menu_search, view -> {
            boolean opening = searchView.getVisibility() != View.VISIBLE;
            searchView.setVisibility(opening ? View.VISIBLE : View.GONE);
            if (opening) { searchView.setIconified(false); searchView.requestFocus(); }
            else { searchView.setQuery("", false); searchView.clearFocus(); }
        }));
        android.widget.ImageButton add = CompactUi.icon(context, R.drawable.ic_add_compact,
                blocked ? R.string.blacklist_add : R.string.whitelist_add, view -> showAdd());
        add.setId(R.id.whitelist_add);
        add.setImageTintList(android.content.res.ColorStateList.valueOf(CompactUi.color(context, R.attr.colorActionPositive)));
        header.addView(add);
        header.addView(CompactUi.icon(context, R.drawable.ic_info_compact, R.string.help_title,
                view -> CompactUi.help(context, blocked ? R.string.global_blacklist : R.string.global_whitelist,
                        getString(blocked ? R.string.global_blacklist_description : R.string.global_whitelist_description))));
        dialog = new AlertDialog.Builder(context)
                .setCustomTitle(header)
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
            handler.removeCallbacks(tick);
            if (addDialog != null) addDialog.dismiss();
            DatabaseHelper.getInstance(context).removeRuleChangedListener(listener);
        });
    }

    void show() {
        showing = true;
        dialog.show();
        DialogSurface.apply(dialog);
        int available = context.getResources().getDisplayMetrics().heightPixels - CompactUi.dp(context, 230);
        View content = list.getParent() instanceof View ? (View) list.getParent() : list;
        android.view.ViewGroup.LayoutParams size = content.getLayoutParams();
        size.height = Math.max(CompactUi.dp(context, 96), Math.min(CompactUi.dp(context, 360), available));
        content.setLayoutParams(size);
        DatabaseHelper.getInstance(context).addRuleChangedListener(listener);
        RulesManager.getInstance(context).startTrueTimeRequest(context);
        refresh();
        handler.postDelayed(tick, 1000);
    }

    private String domain(String text) {
        try { text = TemporaryRuleText.base(text); }
        catch (IllegalArgumentException invalid) { return null; }
        if (!blocked) return DomainInput.globalDomain(text);
        if (!text.matches("blockdomain \\S+( try)?")) return null;
        return text.split(" ")[1];
    }

    private String getString(int resource) { return context.getString(resource); }

    private void refresh() {
        entries.clear();
        java.util.Map<String, Long> removing = new java.util.HashMap<>();
        try (Cursor cursor = DatabaseHelper.getInstance(context).getAllRulesSorted()) {
            int textCol = cursor.getColumnIndexOrThrow("ruletext");
            int enactedCol = cursor.getColumnIndexOrThrow("enacted");
            while (cursor.moveToNext()) {
                String text = cursor.getString(textCol);
                boolean active = cursor.getInt(enactedCol) > 0;
                if (!active && text.startsWith("- ") && domain(text.substring(2)) != null)
                    removing.put(text.substring(2), cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")));
                String domain = domain(text);
                if (domain != null) entries.add(new Entry(text, domain, active, cursor.getLong(cursor.getColumnIndexOrThrow("enact_time"))));
            }
        }
        for (Entry entry : entries) {
            entry.removing = removing.containsKey(entry.rule);
            if (entry.removing) entry.enactTime = removing.get(entry.rule);
        }
        filter();
    }

    private void filter() {
        visibleEntries.clear();
        List<String> labels = new ArrayList<>();
        for (Entry entry : entries) {
            if (!search.matchesRule(entry.domain)) continue;
            visibleEntries.add(entry);
            String label = entry.domain;
            if (entry.removing || !entry.active) {
                long seconds = Math.max(0, (entry.enactTime - System.currentTimeMillis() + 999) / 1000);
                String duration = seconds == 0 ? getString(R.string.pending_due) : RuleDelay.duration(context, (int) Math.min(Integer.MAX_VALUE, seconds));
                label += "\n◷ " + context.getString(entry.removing ? R.string.pending_removing : R.string.pending_adding, duration);
            }
            if (TemporaryRuleText.isTemporary(entry.rule)) {
                TemporaryRuleText temporary = TemporaryRuleText.parse(entry.rule);
                long remaining = entry.active ? Math.max(0, (temporary.expiresAt(entry.activatedAt) - System.currentTimeMillis() + 999) / 1000)
                        : temporary.seconds;
                label += "\n◷ " + context.getString(entry.active ? R.string.temporary_remaining : R.string.temporary_after_activation,
                        RuleDelay.duration(context, (int) remaining));
            }
            if (search.coversSearch(entry.domain)) label += " \u00b7 " + getString(R.string.global_search_parent);
            labels.add(label);
        }
        int pending = 0;
        for (Entry entry : visibleEntries) if (entry.removing || !entry.active) pending++;
        count.setText(context.getString(R.string.global_count, visibleEntries.size(), pending));
        empty.setText(search.text.isEmpty()
                ? (blocked ? R.string.blacklist_empty : R.string.whitelist_empty) : R.string.global_search_empty);
        boolean unchanged = adapter.getCount() == labels.size();
        for (int i = 0; unchanged && i < labels.size(); i++)
            unchanged = labels.get(i).equals(adapter.getItem(i));
        if (unchanged) return;
        int first = list.getFirstVisiblePosition();
        View anchor = list.getChildAt(0);
        int offset = anchor == null ? 0 : anchor.getTop();
        adapter.setNotifyOnChange(false);
        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(first, offset);
    }

    private void showAdd() {
        android.widget.LinearLayout content = new android.widget.LinearLayout(context);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setPadding(CompactUi.dp(context, 20), 0, CompactUi.dp(context, 20), 0);
        input = new EditText(context);
        input.setId(R.id.whitelist_input);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setHint(R.string.global_add_hint);
        input.setGravity(android.view.Gravity.TOP);
        content.addView(input, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.TextView scope = new android.widget.TextView(context);
        scope.setText(R.string.scope_subdomains);
        scope.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_globe_compact, 0, 0, 0);
        scope.setCompoundDrawablePadding(CompactUi.dp(context, 8));
        scope.setPadding(0, CompactUi.dp(context, 12), 0, CompactUi.dp(context, 12));
        content.addView(scope);
        android.widget.TextView delay = new android.widget.TextView(context);
        delay.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_history_compact, 0, 0, 0);
        delay.setCompoundDrawablePadding(CompactUi.dp(context, 8));
        content.addView(delay);
        Runnable preview = () -> {
            int seconds = 0;
            for (String line : input.getText().toString().split("\\r?\\n")) {
                try {
                    String host = DomainInput.normalize(line);
                    seconds = Math.max(seconds, RulesManager.getInstance(context).previewDelay(context,
                            blocked ? "blockdomain " + host : "allow " + DomainInput.destination(host)));
                } catch (IllegalArgumentException ignored) { }
            }
            delay.setText(context.getString(R.string.domain_delay, RuleDelay.duration(context, seconds)));
            delay.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { preview.run(); }
            public void afterTextChanged(android.text.Editable text) { }
        });
        addDialog = new AlertDialog.Builder(context)
                .setCustomTitle(SortDialog.title(context, blocked ? R.string.blacklist_add : R.string.whitelist_add))
                .setView(content).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.global_add_action, null).create();
        addDialog.show();
        DialogSurface.apply(addDialog);
        addDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> add());
        preview.run();
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
            for (Entry entry : entries) existing |= entry.domain.equals(domain) && !TemporaryRuleText.isTemporary(entry.rule);
            if (!existing)
                RulesManager.getInstance(context).queueRuleText(context, blocked ? "blockdomain " + domain : "allow " + DomainInput.destination(domain));
        }
        input.setText("");
        input.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        if (addDialog != null) addDialog.dismiss();
        Toast.makeText(context, R.string.rule_queued, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void remove(Entry entry) {
        String action = entry.removing || !entry.active ? getString(R.string.whitelist_cancel)
                : getString(blocked ? R.string.blacklist_remove : R.string.whitelist_remove);
        int delay = entry.active && !entry.removing ? RulesManager.getInstance(context).previewDelay(context, "- " + entry.rule) : 0;
        String message = entry.domain + (delay > 0 ? "\n\n◷ " + context.getString(R.string.domain_delay, RuleDelay.duration(context, delay)) : "");
        AlertDialog confirmation = new AlertDialog.Builder(context).setTitle(action).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String command = entry.removing ? "abandon - " : entry.active ? "- " : "abandon ";
                    RulesManager.getInstance(context).queueRuleText(context, command + entry.rule);
                }).create();
        confirmation.show();
        DialogSurface.apply(confirmation);
        CompactUi.destructiveButton(confirmation.getButton(AlertDialog.BUTTON_POSITIVE));
    }

    private static final class Entry {
        final String rule, domain;
        final boolean active;
        boolean removing;
        long enactTime;
        final long activatedAt;
        Entry(String rule, String domain, boolean active, long enactTime) {
            this.enactTime = enactTime;
            this.activatedAt = enactTime;
            this.rule = rule; this.domain = domain; this.active = active;
        }
    }
}
