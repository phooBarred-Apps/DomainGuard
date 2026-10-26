package heartguard.heartguard.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.TooltipCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** A combined view of the same access records shown beneath individual apps. */
public class ActivityRecentTraffic extends AppCompatActivity {
    static final int DEFAULT_WINDOW_SECONDS = 180;
    private static final int MAX_WINDOW_SECONDS = 3 * 24 * 60 * 60;
    private static final int PAGE_SIZE = 250;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final LatestTask<Snapshot> reads = new LatestTask<>(worker, main::post);
    // This cache is only read and written by the single database worker.
    private final Map<Integer, RecentTrafficAdapter.App> appCache = new HashMap<>();
    private final Map<Integer, String> hiddenApps = new LinkedHashMap<>();
    private DatabaseHelper database;
    private RecentTrafficAdapter adapter;
    private RecyclerView list;
    private LinearLayoutManager layout;
    private Button window, more;
    private TextView empty, count, protectionStatus;
    private ImageButton hidden, hideAllowedButton;
    private int seconds = DEFAULT_WINDOW_SECONDS, limit = PAGE_SIZE;
    private String sort = ListSort.RECENT;
    private boolean resumed, failed, scrollToTop, hideAllowed;
    private long loadedRevision = -1, oldestVisible = Long.MAX_VALUE;
    private final DatabaseHelper.AccessChangedListener changed = () -> main.post(this::refresh);
    private final DatabaseHelper.RuleChangedListener rulesChanged = () -> main.post(this::refresh);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateProtectionStatus();
            long cutoff = System.currentTimeMillis() - seconds * 1000L;
            if (!failed && (database.getAccessRevision() != loadedRevision || oldestVisible < cutoff)) refresh();
            if ("elapsed".equals(PreferenceManager.getDefaultSharedPreferences(ActivityRecentTraffic.this)
                    .getString("domain_timestamp_format", "exact")))
                for (int i = 0; i < list.getChildCount(); i++)
                    ((RecentTrafficAdapter.Holder) list.getChildViewHolder(list.getChildAt(i))).refreshTime();
            main.postDelayed(this, tickInterval());
        }
    };

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setContentView(R.layout.recent_traffic);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.recent_traffic_title);
        worker.setRemoveOnCancelPolicy(true);
        database = DatabaseHelper.getInstance(this);
        hideAllowed = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false);
        if (state != null) {
            hideAllowed = state.getBoolean("hide_allowed", hideAllowed);
            seconds = Math.max(1, Math.min(MAX_WINDOW_SECONDS, state.getInt("seconds", DEFAULT_WINDOW_SECONDS)));
            sort = ListSort.valid(state.getString("sort", ListSort.RECENT), ListSort.DOMAINS);
            limit = Math.max(PAGE_SIZE, state.getInt("limit", PAGE_SIZE));
            int[] uids = state.getIntArray("hidden_uids");
            String[] names = state.getStringArray("hidden_names");
            if (uids != null && names != null && uids.length == names.length)
                for (int i = 0; i < uids.length; i++) hiddenApps.put(uids[i], names[i]);
        }
        list = findViewById(R.id.recent_list);
        layout = new LinearLayoutManager(this);
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        adapter = new RecentTrafficAdapter(new RecentTrafficAdapter.Actions() {
            public void open(RecentTrafficAdapter.Entry entry) { showActions(entry); }
            public void hide(RecentTrafficAdapter.Entry entry) {
                hiddenApps.put(entry.uid, entry.app.name);
                updateControls();
                refresh();
            }
        });
        adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        list.setAdapter(adapter);
        window = findViewById(R.id.recent_window);
        more = findViewById(R.id.recent_more);
        protectionStatus = findViewById(R.id.recent_protection_status);
        empty = findViewById(R.id.recent_empty);
        count = findViewById(R.id.recent_count);
        hidden = findViewById(R.id.recent_hidden);
        hideAllowedButton = findViewById(R.id.recent_hide_allowed);
        for (Button button : new Button[]{window, more}) CompactUi.frameless(button);
        window.setOnClickListener(view -> showWindow());
        hideAllowedButton.setOnClickListener(view -> {
            hideAllowed = !hideAllowed;
            limit = PAGE_SIZE;
            failed = false;
            updateControls();
            refresh();
        });
        findViewById(R.id.recent_sort).setOnClickListener(view -> showSort());
        hidden.setOnClickListener(view -> showHidden());
        findViewById(R.id.recent_help).setOnClickListener(view -> CompactUi.help(this,
                R.string.recent_traffic_title, getString(R.string.recent_traffic_help)));
        empty.setOnClickListener(view -> { if (failed) { failed = false; refresh(); } });
        more.setOnClickListener(view -> { limit += PAGE_SIZE; refresh(); });
        updateControls();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        database.addAccessChangedListener(changed);
        database.addRuleChangedListener(rulesChanged);
        failed = false;
        refresh();
        main.postDelayed(tick, tickInterval());
    }

    private long tickInterval() {
        // Very short windows need an update before a newly recorded attempt expires.
        return Math.min(1000L, seconds * 250L);
    }

    @Override protected void onPause() {
        resumed = false;
        database.removeAccessChangedListener(changed);
        database.removeRuleChangedListener(rulesChanged);
        reads.cancel();
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        reads.cancel();
        worker.shutdown();
        super.onDestroy();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("seconds", seconds);
        state.putInt("limit", limit);
        state.putString("sort", sort);
        state.putBoolean("hide_allowed", hideAllowed);
        int[] uids = new int[hiddenApps.size()];
        int i = 0;
        for (int uid : hiddenApps.keySet()) uids[i++] = uid;
        state.putIntArray("hidden_uids", uids);
        state.putStringArray("hidden_names", hiddenApps.values().toArray(new String[0]));
        super.onSaveInstanceState(state);
    }

    private void updateControls() {
        window.setText(getString(R.string.recent_traffic_window, RuleDelay.duration(this, seconds)));
        hideAllowedButton.setSelected(hideAllowed);
        hideAllowedButton.setAlpha(hideAllowed ? 1f : 0.5f);
        hideAllowedButton.setContentDescription(getString(R.string.hide_allowed_domains) + ": "
                + getString(hideAllowed ? R.string.control_on : R.string.control_off));
        TooltipCompat.setTooltipText(hideAllowedButton, hideAllowedButton.getContentDescription());
        hidden.setSelected(!hiddenApps.isEmpty());
        hidden.setContentDescription(getString(R.string.recent_traffic_hidden, hiddenApps.size()));
        TooltipCompat.setTooltipText(hidden, hidden.getContentDescription());
        View sortButton = findViewById(R.id.recent_sort);
        sortButton.setContentDescription(getString(ListSort.label(sort)));
        TooltipCompat.setTooltipText(sortButton, sortButton.getContentDescription());
    }

    private void refresh() {
        if (!resumed) return;
        Set<Integer> hiddenSnapshot = new LinkedHashSet<>(hiddenApps.keySet());
        String requestedSort = sort;
        int requestedSeconds = seconds, requestedLimit = limit;
        boolean requestedHideAllowed = hideAllowed;
        reads.submit(() -> read(hiddenSnapshot, requestedHideAllowed, requestedSort, requestedSeconds, requestedLimit), this::display);
    }

    private Snapshot read(Set<Integer> hiddenUids, boolean requestedHideAllowed, String requestedSort, int requestedSeconds, int requestedLimit) {
        long revision = database.getAccessRevision();
        List<RecentTrafficAdapter.Entry> entries = new ArrayList<>();
        long oldest = Long.MAX_VALUE;
        GlobalRuleIndicators indicators = database.getGlobalRuleIndicators();
        try (Cursor cursor = database.getRecentAccess(System.currentTimeMillis() - requestedSeconds * 1000L,
                hiddenUids, requestedHideAllowed, requestedSort, requestedLimit + 1)) {
            boolean hasMore = false;
            while (cursor.moveToNext()) {
                if (entries.size() == requestedLimit) { hasMore = true; break; }
                int uid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                RecentTrafficAdapter.App app = appCache.get(uid);
                if (app == null) { app = resolveApp(uid); appCache.put(uid, app); }
                AccessEntry access = new AccessEntry(cursor, indicators);
                oldest = Math.min(oldest, access.time);
                entries.add(new RecentTrafficAdapter.Entry(uid, access, app));
            }
            return new Snapshot(entries, revision, oldest, hasMore, false);
        } catch (RuntimeException failure) {
            Log.e("DomainGuard.Recent", "Unable to read recent traffic", failure);
            return new Snapshot(Collections.emptyList(), revision, oldest, false, true);
        }
    }

    private RecentTrafficAdapter.App resolveApp(int uid) {
        PackageManager manager = getPackageManager();
        String[] packages = uid < 0 ? null : manager.getPackagesForUid(uid);
        if (packages == null || packages.length == 0)
            return new RecentTrafficAdapter.App(uid < 0 ? getString(R.string.recent_traffic_unknown_app)
                    : getString(R.string.recent_traffic_uid, uid), manager.getDefaultActivityIcon(), new String[0]);
        List<String> names = new ArrayList<>();
        android.graphics.drawable.Drawable icon = manager.getDefaultActivityIcon();
        for (String name : packages) {
            try {
                ApplicationInfo info = manager.getApplicationInfo(name, 0);
                if (names.isEmpty()) icon = manager.getApplicationIcon(info);
                names.add(manager.getApplicationLabel(info).toString());
            } catch (PackageManager.NameNotFoundException missing) { names.add(name); }
        }
        return new RecentTrafficAdapter.App(android.text.TextUtils.join(" / ", names), icon, packages);
    }

    private void display(Snapshot result) {
        if (!resumed) return;
        updateProtectionStatus();
        failed = result.failed;
        empty.setText(failed ? R.string.recent_traffic_error
                : hideAllowed ? R.string.recent_traffic_empty_blocked
                : hiddenApps.isEmpty() ? R.string.recent_traffic_empty : R.string.recent_traffic_empty_hidden);
        if (failed) {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            more.setVisibility(View.GONE);
            count.setVisibility(View.GONE);
            return;
        }
        loadedRevision = result.revision;
        oldestVisible = result.oldest;
        int first = layout.findFirstVisibleItemPosition();
        View anchor = first < 0 ? null : layout.findViewByPosition(first);
        int top = anchor == null ? 0 : anchor.getTop();
        long anchorId = first >= 0 && first < adapter.getItemCount() ? adapter.getItemId(first) : -1;
        boolean follow = ListSort.RECENT.equals(sort) && first <= 0 && top >= 0;
        adapter.submit(result.entries);
        int nextAnchor = adapter.positionOf(anchorId);
        if (scrollToTop || follow) layout.scrollToPositionWithOffset(0, 0);
        else if (nextAnchor >= 0) layout.scrollToPositionWithOffset(nextAnchor, top);
        scrollToTop = false;
        list.setVisibility(View.VISIBLE);
        empty.setVisibility(result.entries.isEmpty() ? View.VISIBLE : View.GONE);
        more.setVisibility(result.more ? View.VISIBLE : View.GONE);
        count.setVisibility(View.VISIBLE);
        count.setText(getResources().getQuantityString(R.plurals.recent_traffic_count,
                result.entries.size(), result.entries.size()));
    }

    private void updateProtectionStatus() {
        protectionStatus.setVisibility(RulesManager.getInstance(this).getPreferenceEnabled(this) ? View.GONE : View.VISIBLE);
    }

    private void showWindow() {
        View content = getLayoutInflater().inflate(R.layout.recent_traffic_window, null);
        TextView preview = content.findViewById(R.id.recent_window_preview);
        DurationInput[] input = new DurationInput[1];
        Runnable update = () -> {
            if (input[0] == null) return;
            try { preview.setText(getString(R.string.recent_traffic_window, RuleDelay.duration(this, input[0].seconds()))); }
            catch (IllegalArgumentException invalid) { preview.setText(""); }
        };
        input[0] = new DurationInput(content.findViewById(R.id.recent_window_amount),
                content.findViewById(R.id.recent_window_unit), seconds, update);
        update.run();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.recent_traffic_window_title)
                .setView(content).setPositiveButton(R.string.list_apply, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int value = input[0].seconds();
                if (value < 1 || value > MAX_WINDOW_SECONDS) throw new IllegalArgumentException();
                seconds = value;
                limit = PAGE_SIZE;
                main.removeCallbacks(tick);
                main.postDelayed(tick, tickInterval());
                updateControls();
                failed = false;
                refresh();
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) { input[0].amount.setError(getString(R.string.recent_traffic_window_error)); }
        }));
        dialog.show();
        DialogSurface.apply(dialog);
    }

    private void showSort() {
        String[] labels = new String[ListSort.DOMAINS.length];
        int selected = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(ListSort.label(ListSort.DOMAINS[i]));
            if (sort.equals(ListSort.DOMAINS[i])) selected = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.list_sort_domains)
                .setSingleChoiceItems(labels, selected, (choice, position) -> {
                    sort = ListSort.DOMAINS[position];
                    limit = PAGE_SIZE;
                    scrollToTop = true;
                    updateControls();
                    refresh();
                    choice.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).create();
        dialog.show();
        DialogSurface.apply(dialog);
    }

    private void showHidden() {
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(CompactUi.dp(this, 16), CompactUi.dp(this, 12), CompactUi.dp(this, 8), 0);
        scroll.addView(body);
        TextView message = new TextView(this);
        message.setText(hiddenApps.isEmpty() ? R.string.recent_traffic_none_hidden : R.string.recent_traffic_hidden_help);
        body.addView(message);
        for (Map.Entry<Integer, String> app : new LinkedHashMap<>(hiddenApps).entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(app.getValue());
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton show = CompactUi.icon(this, R.drawable.ic_visibility_off_compact,
                    R.string.recent_traffic_hidden_title, view -> {
                        hiddenApps.remove(app.getKey());
                        body.removeView(row);
                        if (hiddenApps.isEmpty()) message.setText(R.string.recent_traffic_none_hidden);
                        updateControls();
                        refresh();
                    });
            show.setSelected(true);
            show.setContentDescription(getString(R.string.recent_traffic_show, app.getValue()));
            TooltipCompat.setTooltipText(show, show.getContentDescription());
            row.addView(show);
            body.addView(row);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.recent_traffic_hidden_title).setView(scroll)
                .setNeutralButton(R.string.recent_traffic_show_all, (which, button) -> {
                    hiddenApps.clear(); updateControls(); refresh();
                }).setPositiveButton(R.string.list_done, null).create();
        dialog.show();
        DialogSurface.apply(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!hiddenApps.isEmpty());
    }

    private void showActions(RecentTrafficAdapter.Entry entry) {
        List<String> availablePackages = new ArrayList<>();
        for (String name : entry.app.packages) {
            try {
                if (getPackageManager().getApplicationInfo(name, 0).uid == entry.uid) availablePackages.add(name);
            } catch (PackageManager.NameNotFoundException removed) { /* Keep copy available for removed apps. */ }
        }
        String[] choices = availablePackages.isEmpty() ? new String[]{getString(R.string.title_log_copy)}
                : new String[]{getString(R.string.title_allow), getString(R.string.title_block), getString(R.string.title_log_copy)};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(entry.destination)
                .setItems(choices, (which, position) -> {
                    if (availablePackages.isEmpty() || position == 2) {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                                ClipData.newPlainText(getString(R.string.recent_traffic_title), entry.destination));
                    } else if (availablePackages.size() == 1)
                        DomainRuleDialog.show(this, availablePackages.get(0), entry.destination, position == 1);
                    else choosePackage(entry, availablePackages, position == 1);
                }).setNegativeButton(android.R.string.cancel, null).create();
        dialog.show();
        DialogSurface.apply(dialog);
    }

    private void choosePackage(RecentTrafficAdapter.Entry entry, List<String> packages, boolean block) {
        String[] labels = new String[packages.size()];
        for (int i = 0; i < labels.length; i++) {
            String name = packages.get(i);
            try { labels[i] = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(name, 0)) + "\n" + name; }
            catch (PackageManager.NameNotFoundException removed) { labels[i] = name; }
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(entry.app.name)
                .setItems(labels, (which, position) -> DomainRuleDialog.show(this, packages.get(position), entry.destination, block))
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.show();
        DialogSurface.apply(dialog);
    }

    private static final class Snapshot {
        final List<RecentTrafficAdapter.Entry> entries;
        final long revision, oldest;
        final boolean more, failed;
        Snapshot(List<RecentTrafficAdapter.Entry> entries, long revision, long oldest, boolean more, boolean failed) {
            this.entries = entries; this.revision = revision; this.oldest = oldest; this.more = more; this.failed = failed;
        }
    }
}
