package heartguard.heartguard.main;

import android.database.MatrixCursor;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Uses the production domain rows and scrolling view, without writing any sample data. */
public class ActivityDomainListPreview extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setTitle(R.string.compact_domains_preview);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        View app = getLayoutInflater().inflate(R.layout.rule, content, false);
        content.addView(app);
        ((TextView) app.findViewById(R.id.tvName)).setText(R.string.compact_domains_example);
        ((ImageView) app.findViewById(R.id.ivIcon)).setImageResource(android.R.drawable.sym_def_app_icon);
        ((ImageView) app.findViewById(R.id.ivExpander)).setImageLevel(1);
        ((CheckBox) app.findViewById(R.id.cbWifi)).setChecked(true);
        app.findViewById(R.id.cbWifi).setEnabled(false);
        app.findViewById(R.id.llConfiguration).setVisibility(View.VISIBLE);
        for (int id : new int[]{R.id.rlLockdown, R.id.cbOther, R.id.ivScreenWifi, R.id.ivScreenOther,
                R.id.tvRoaming, R.id.cbApply, R.id.tvTitleConditions, R.id.llScreenWifi, R.id.llScreenOther,
                R.id.llBlockWhenRoaming, R.id.llAllowInLockdown, R.id.btnClear, R.id.btnRelated,
                R.id.tvInternet, R.id.tvDisabled}) app.findViewById(id).setVisibility(View.GONE);
        ((TextView) app.findViewById(R.id.tvUid)).setText("12345");
        ((TextView) app.findViewById(R.id.tvPackage)).setText("com.example.preview");
        ((TextView) app.findViewById(R.id.tvVersion)).setText("1.0");
        // This screen previews domain height, so start on Domains regardless of the app-list default.
        AppPanelTabs.select(app, false);
        app.findViewById(R.id.appOptionsButton).setOnClickListener(view -> AppPanelTabs.select(app, true));
        app.findViewById(R.id.appDomainsButton).setOnClickListener(view -> AppPanelTabs.select(app, false));
        View.OnClickListener sampleOnly = view -> Toast.makeText(this, R.string.compact_domains_preview_note, Toast.LENGTH_SHORT).show();
        for (int id : new int[]{R.id.appPauseButton, R.id.ibSettings, R.id.ibLaunch, R.id.btnLogging,
                R.id.ivLive, R.id.btnClearAccess, R.id.cbNotify, R.id.domainSortButton, R.id.domainCopyButton})
            app.findViewById(id).setOnClickListener(sampleOnly);
        DomainListView domains = app.findViewById(R.id.lvAccess);
        domains.setLayoutManager(new LinearLayoutManager(this));
        domains.setItemAnimator(null);
        int rows = Math.max(CompactDomainLists.MIN_ROWS, Math.min(CompactDomainLists.MAX_ROWS,
                getIntent().getIntExtra(CompactDomainLists.ROWS, CompactDomainLists.DEFAULT_ROWS)));
        domains.setMaximumRows(getIntent().getBooleanExtra(CompactDomainLists.ENABLED, false) ? rows : 0);
        AdapterAccess adapter = new AdapterAccess(this, entry -> sampleOnly.onClick(domains));
        domains.setAdapter(adapter);
        List<AccessEntry> examples = examples();
        EditText search = app.findViewById(R.id.domainSearch);
        search.setVisibility(View.GONE);
        View searchButton = app.findViewById(R.id.domainSearchButton);
        searchButton.setVisibility(View.VISIBLE);
        searchButton.setOnClickListener(view -> {
            boolean open = search.getVisibility() != View.VISIBLE;
            search.setVisibility(open ? View.VISIBLE : View.GONE);
            searchButton.setSelected(open);
            ((android.widget.ImageButton) searchButton).setImageTintList(android.content.res.ColorStateList.valueOf(
                    CompactUi.color(this, open ? R.attr.colorActionPositive : android.R.attr.textColorSecondary)));
            if (!open) search.setText("");
        });
        android.widget.ImageButton filterButton = app.findViewById(R.id.domainFilterButton);
        DomainFilters[] filters = {DomainFilters.defaults(androidx.preference.PreferenceManager.getDefaultSharedPreferences(this))};
        Runnable filter = () -> {
            List<AccessEntry> shown = new ArrayList<>();
            String query = search.getText().toString().toLowerCase(Locale.ROOT);
            for (AccessEntry entry : examples)
                if (filters[0].includes(entry) && entry.address.contains(query)) shown.add(entry);
            adapter.submit(shown);
            DomainFilterDialog.bind(filterButton, filters[0]);
        };
        filterButton.setOnClickListener(view -> DomainFilterDialog.show(this, filters[0], selected -> {
            filters[0] = selected;
            filter.run();
        }, false));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable text) { }
        });
        filter.run();
        TextView note = new TextView(this);
        note.setText(R.string.compact_domains_preview_note);
        note.setPadding(CompactUi.dp(this, 16), CompactUi.dp(this, 16), CompactUi.dp(this, 16), CompactUi.dp(this, 16));
        content.addView(note);
        setContentView(scroll);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private List<AccessEntry> examples() {
        List<AccessEntry> result = new ArrayList<>();
        String[] names = {"www", "images", "api", "cdn", "login", "video", "news", "static", "assets", "media",
                "ads", "mail", "shop", "maps", "music", "chat", "files", "sync", "account", "support"};
        try (MatrixCursor cursor = new MatrixCursor(new String[]{"ID", "time", "version", "protocol", "dport",
                "allowed", "block", "count", "connections", "pending_allow", "sent", "received", "daddr"})) {
            for (int i = 0; i < names.length; i++) cursor.addRow(new Object[]{i, System.currentTimeMillis() - i * 10000L,
                    4, 6, 443, i % 3 == 0 ? 1 : 0, i % 3 == 0 ? 0 : 1, 1, 0, 0, 0, 0, names[i] + ".example.com"});
            while (cursor.moveToNext()) result.add(new AccessEntry(cursor));
        }
        return result;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
