package heartguard.heartguard.main;

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.SimpleItemAnimator;
import java.util.HashSet;
import java.util.Set;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.widget.CompoundButtonCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import heartguard.heartguard.main.GlideApp;

public class AdapterRule extends RecyclerView.Adapter<AdapterRule.ViewHolder> {
    private static final String TAG = "NetGuard.Adapter";

    private View anchor;
    private LayoutInflater inflater;
    private RecyclerView rv;
    private int colorText;
    private int colorChanged;
    private int colorOn;
    private int colorOff;
    private int colorGrayed;
    private int iconSize;
    private boolean wifiActive = true;
    private boolean otherActive = true;
    private boolean live = true;
    private List<Rule> listAll = new ArrayList<>();
    private List<Rule> listFiltered = new ArrayList<>();
    // Display overrides last for this screen session, including list refreshes.
    private final Map<String, Boolean> hideAllowedDomains = new HashMap<>();
    private final Map<String, String> domainQueries = new HashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private final Set<ViewHolder> boundHolders = new HashSet<>();
    final AppListOptions options = new AppListOptions();
    private Map<Integer, DatabaseHelper.AccessStats> stats = new HashMap<>();
    private String domainSort;
    private Runnable viewChanged;
    private int trafficGeneration;


    private List<String> messaging = Arrays.asList(
            "com.discord",
            "com.facebook.mlite",
            "com.facebook.orca",
            "com.instagram.android",
            "com.Slack",
            "com.skype.raider",
            "com.snapchat.android",
            "com.whatsapp",
            "com.whatsapp.w4b"
    );

    private List<String> download = Arrays.asList(
            "com.google.android.youtube"
    );

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public View view;

        public LinearLayout llApplication;
        public ImageView ivIcon;
        public ImageView ivExpander;
        public TextView tvName;

        public TextView tvHosts;

        public RelativeLayout rlLockdown;
        public ImageView ivLockdown;

        public CheckBox cbWifi;
        public ImageView ivScreenWifi;

        public CheckBox cbOther;
        public ImageView ivScreenOther;
        public TextView tvRoaming;

        public TextView tvRemarkMessaging;
        public TextView tvRemarkDownload;

        public LinearLayout llConfiguration;
        public TextView tvUid;
        public TextView tvPackage;
        public TextView tvVersion;
        public TextView tvInternet;
        public TextView tvDisabled;

        public Button btnRelated;
        public ImageButton ibSettings;
        public ImageButton ibLaunch;

        public CheckBox cbApply;

        public LinearLayout llScreenWifi;
        public ImageView ivWifiLegend;
        public CheckBox cbScreenWifi;

        public LinearLayout llScreenOther;
        public ImageView ivOtherLegend;
        public CheckBox cbScreenOther;

        public CheckBox cbRoaming;

        public CheckBox cbLockdown;
        public ImageView ivLockdownLegend;

        public ImageButton btnClear;

        public LinearLayout llFilter;
        public ImageView ivLive;
        public TextView tvLogging;
        public Button btnLogging;
        public CheckBox cbHideAllowedDomains;
        public RecyclerView lvAccess;
        public EditText domainSearch;
        public Button domainSortButton;
        public TextView domainStatus;
        private TextWatcher domainWatcher;
        private String boundPackage;
        private Rule boundRule;
        private int accessGeneration;

        public ImageButton btnClearAccess;
        public CheckBox cbNotify;

        public ViewHolder(View itemView) {
            super(itemView);
            view = itemView;

            llApplication = itemView.findViewById(R.id.llApplication);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            ivExpander = itemView.findViewById(R.id.ivExpander);
            tvName = itemView.findViewById(R.id.tvName);

            tvHosts = itemView.findViewById(R.id.tvHosts);

            rlLockdown = itemView.findViewById(R.id.rlLockdown);
            ivLockdown = itemView.findViewById(R.id.ivLockdown);

            cbWifi = itemView.findViewById(R.id.cbWifi);
            ivScreenWifi = itemView.findViewById(R.id.ivScreenWifi);

            cbOther = itemView.findViewById(R.id.cbOther);
            ivScreenOther = itemView.findViewById(R.id.ivScreenOther);
            tvRoaming = itemView.findViewById(R.id.tvRoaming);

            tvRemarkMessaging = itemView.findViewById(R.id.tvRemarkMessaging);
            tvRemarkDownload = itemView.findViewById(R.id.tvRemarkDownload);

            llConfiguration = itemView.findViewById(R.id.llConfiguration);
            tvUid = itemView.findViewById(R.id.tvUid);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvVersion = itemView.findViewById(R.id.tvVersion);
            tvInternet = itemView.findViewById(R.id.tvInternet);
            tvDisabled = itemView.findViewById(R.id.tvDisabled);

            btnRelated = itemView.findViewById(R.id.btnRelated);
            ibSettings = itemView.findViewById(R.id.ibSettings);
            ibLaunch = itemView.findViewById(R.id.ibLaunch);

            cbApply = itemView.findViewById(R.id.cbApply);

            llScreenWifi = itemView.findViewById(R.id.llScreenWifi);
            ivWifiLegend = itemView.findViewById(R.id.ivWifiLegend);
            cbScreenWifi = itemView.findViewById(R.id.cbScreenWifi);

            llScreenOther = itemView.findViewById(R.id.llScreenOther);
            ivOtherLegend = itemView.findViewById(R.id.ivOtherLegend);
            cbScreenOther = itemView.findViewById(R.id.cbScreenOther);

            cbRoaming = itemView.findViewById(R.id.cbRoaming);

            cbLockdown = itemView.findViewById(R.id.cbLockdown);
            ivLockdownLegend = itemView.findViewById(R.id.ivLockdownLegend);

            btnClear = itemView.findViewById(R.id.btnClear);

            llFilter = itemView.findViewById(R.id.llFilter);
            ivLive = itemView.findViewById(R.id.ivLive);
            tvLogging = itemView.findViewById(R.id.tvLogging);
            btnLogging = itemView.findViewById(R.id.btnLogging);
            cbHideAllowedDomains = itemView.findViewById(R.id.cbHideAllowedDomains);
            lvAccess = itemView.findViewById(R.id.lvAccess);
            lvAccess.setLayoutManager(new LinearLayoutManager(itemView.getContext()));
            lvAccess.setNestedScrollingEnabled(false);
            ((SimpleItemAnimator) lvAccess.getItemAnimator()).setSupportsChangeAnimations(false);
            domainSearch = itemView.findViewById(R.id.domainSearch);
            domainSortButton = itemView.findViewById(R.id.domainSortButton);
            domainStatus = itemView.findViewById(R.id.domainStatus);
            btnClearAccess = itemView.findViewById(R.id.btnClearAccess);
            cbNotify = itemView.findViewById(R.id.cbNotify);

            final View wifiParent = (View) cbWifi.getParent();
            wifiParent.post(new Runnable() {
                public void run() {
                    Rect rect = new Rect();
                    cbWifi.getHitRect(rect);
                    rect.bottom += rect.top;
                    rect.right += rect.left;
                    rect.top = 0;
                    rect.left = 0;
                    wifiParent.setTouchDelegate(new TouchDelegate(rect, cbWifi));
                }
            });

            final View otherParent = (View) cbOther.getParent();
            otherParent.post(new Runnable() {
                public void run() {
                    Rect rect = new Rect();
                    cbOther.getHitRect(rect);
                    rect.bottom += rect.top;
                    rect.right += rect.left;
                    rect.top = 0;
                    rect.left = 0;
                    otherParent.setTouchDelegate(new TouchDelegate(rect, cbOther));
                }
            });
        }
    }

    public AdapterRule(Context context, View anchor) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        options.loadFilterDefaults(prefs);
        options.sort = ListSort.valid(prefs.getString(ListSort.APP_DEFAULT, ListSort.NAME), ListSort.APPS);
        domainSort = ListSort.valid(prefs.getString(ListSort.DOMAIN_DEFAULT, ListSort.RECENT), ListSort.DOMAINS);
        this.anchor = anchor;
        this.inflater = LayoutInflater.from(context);

        if (prefs.getBoolean("dark_theme", false))
            colorChanged = Color.argb(128, Color.red(Color.DKGRAY), Color.green(Color.DKGRAY), Color.blue(Color.DKGRAY));
        else
            colorChanged = Color.argb(128, Color.red(Color.LTGRAY), Color.green(Color.LTGRAY), Color.blue(Color.LTGRAY));

        TypedArray ta = context.getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            colorText = ta.getColor(0, 0);
        } finally {
            ta.recycle();
        }

        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.colorOn, tv, true);
        colorOn = tv.data;
        context.getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        colorOff = tv.data;

        colorGrayed = ContextCompat.getColor(context, R.color.colorGrayed);

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.listPreferredItemHeight, typedValue, true);
        int height = TypedValue.complexToDimensionPixelSize(typedValue.data, context.getResources().getDisplayMetrics());
        this.iconSize = Math.round(height * context.getResources().getDisplayMetrics().density + 0.5f);

        setHasStableIds(true);
    }

    public void set(List<Rule> listRule) {
        listAll = new ArrayList<>(listRule);
        for (Rule rule : listAll) rule.expanded = expanded.contains(rule.packageName);
        applyOptions(true);
        refreshTraffic();
    }

    void dispose() {
        trafficGeneration++;
        for (ViewHolder holder : boundHolders) holder.accessGeneration++;
        boundHolders.clear();
        viewChanged = null;
    }

    void setViewChangedListener(Runnable listener) { viewChanged = listener; }
    int totalCount() { return listAll.size(); }
    void setQuery(String query) {
        String next = query == null ? "" : query;
        if (options.query.equals(next)) return;
        options.query = next;
        applyOptions(false);
    }
    void applyOptions(boolean preserveScroll) {
        List<Rule> previous = listFiltered;
        List<Rule> next = new ArrayList<>();
        for (Rule app : listAll) if (options.matches(app, stats.get(app.uid))) next.add(app);
        java.util.Collections.sort(next, options.comparator(stats));
        boolean autoExpand = next.size() == 1 && !options.query.trim().isEmpty() && !next.get(0).expanded;
        if (autoExpand) {
            next.get(0).expanded = true;
            expanded.add(next.get(0).packageName);
        }
        LinearLayoutManager layout = rv == null ? null : (LinearLayoutManager) rv.getLayoutManager();
        int first = layout == null ? -1 : layout.findFirstVisibleItemPosition();
        String anchorPackage = first < 0 || first >= previous.size() ? null : previous.get(first).packageName;
        View firstView = layout == null ? null : layout.findViewByPosition(first);
        int offset = firstView == null ? 0 : firstView.getTop() - rv.getPaddingTop();
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            public int getOldListSize() { return previous.size(); }
            public int getNewListSize() { return next.size(); }
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).packageName.equals(next.get(newPosition).packageName);
            }
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition) == next.get(newPosition);
            }
        });
        boolean orderChanged = previous.size() != next.size();
        for (int i = 0; !orderChanged && i < next.size(); i++)
            orderChanged = !previous.get(i).packageName.equals(next.get(i).packageName);
        listFiltered = next;
        diff.dispatchUpdatesTo(this);
        if (autoExpand) notifyItemChanged(0);
        if (layout != null) {
            if (!preserveScroll) layout.scrollToPositionWithOffset(0, 0);
            else if (orderChanged && anchorPackage != null)
                for (int i = 0; i < next.size(); i++)
                    if (anchorPackage.equals(next.get(i).packageName)) {
                        layout.scrollToPositionWithOffset(i, offset);
                        break;
                    }
        }
        if (viewChanged != null) viewChanged.run();
    }

    void refreshTraffic() {
        Context context = inflater.getContext();
        final int generation = ++trafficGeneration;
        new AsyncTask<Void, Void, Map<Integer, DatabaseHelper.AccessStats>>() {
            protected Map<Integer, DatabaseHelper.AccessStats> doInBackground(Void... ignored) {
                return DatabaseHelper.getInstance(context).getAccessStats();
            }
            protected void onPostExecute(Map<Integer, DatabaseHelper.AccessStats> result) {
                if (generation != trafficGeneration) return;
                stats = result;
                applyOptions(true);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        refreshDomains();
    }

    private void refreshDomains() {
        for (ViewHolder holder : new ArrayList<>(boundHolders))
            if (holder.boundRule != null && holder.boundRule.expanded) loadDomains(holder, holder.boundRule);
    }

    Bundle saveViewState() {
        Bundle state = options.save();
        state.putString("domainSort", domainSort);
        state.putStringArrayList("expanded", new ArrayList<>(expanded));
        Bundle hidden = new Bundle(), queries = new Bundle();
        for (String name : hideAllowedDomains.keySet()) hidden.putBoolean(name, hideAllowedDomains.get(name));
        for (String name : domainQueries.keySet()) queries.putString(name, domainQueries.get(name));
        state.putBundle("hidden", hidden);
        state.putBundle("domainQueries", queries);
        return state;
    }

    void restoreViewState(Bundle state) {
        if (state == null) return;
        options.restore(state);
        domainSort = ListSort.valid(state.getString("domainSort"), ListSort.DOMAINS);
        ArrayList<String> packages = state.getStringArrayList("expanded");
        if (packages != null) expanded.addAll(packages);
        Bundle hidden = state.getBundle("hidden"), queries = state.getBundle("domainQueries");
        if (hidden != null) for (String name : hidden.keySet()) hideAllowedDomains.put(name, hidden.getBoolean(name));
        if (queries != null) for (String name : queries.keySet()) domainQueries.put(name, queries.getString(name, ""));
    }

    private void loadDomains(ViewHolder holder, Rule rule) {
        Context context = holder.itemView.getContext();
        int generation = ++holder.accessGeneration;
        boolean hide = hideAllowedDomains.containsKey(rule.packageName) ? hideAllowedDomains.get(rule.packageName)
                : PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false);
        String query = domainQueries.containsKey(rule.packageName) ? domainQueries.get(rule.packageName) : "";
        String sort = domainSort;
        new AsyncTask<Void, Void, List<AccessEntry>>() {
            protected List<AccessEntry> doInBackground(Void... ignored) {
                List<AccessEntry> entries = new ArrayList<>();
                DatabaseHelper database = DatabaseHelper.getInstance(context);
                List<String> activeRules = new ArrayList<>();
                try (Cursor cursor = database.getEnactedRules()) {
                    while (cursor.moveToNext()) activeRules.add(cursor.getString(cursor.getColumnIndexOrThrow("ruletext")));
                }
                GlobalRuleIndicators indicators = new GlobalRuleIndicators(activeRules);
                try (Cursor cursor = database.getAccess(rule.uid, hide, query, sort)) {
                    while (cursor.moveToNext()) entries.add(new AccessEntry(cursor, indicators));
                }
                return entries;
            }
            protected void onPostExecute(List<AccessEntry> entries) {
                if (generation != holder.accessGeneration || !rule.packageName.equals(holder.boundPackage)) return;
                AdapterAccess access = (AdapterAccess) holder.lvAccess.getAdapter();
                if (access == null) return;
                access.submit(entries);
                holder.domainStatus.setText(entries.isEmpty() ? R.string.list_no_domains : R.string.list_domain_limit);
                holder.domainStatus.setVisibility(entries.isEmpty() || entries.size() == 250 ? View.VISIBLE : View.GONE);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void setWifiActive() {
        wifiActive = true;
        otherActive = false;
        notifyDataSetChanged();
    }

    public void setMobileActive() {
        wifiActive = false;
        otherActive = true;
        notifyDataSetChanged();
    }

    public void setDisconnected() {
        wifiActive = false;
        otherActive = false;
        notifyDataSetChanged();
    }

    public boolean isLive() {
        return this.live;
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        rv = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        rv = null;
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        final Context context = holder.itemView.getContext();

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean log_app = RulesManager.getInstance(context).getPreferenceLogApp(context);
        final boolean filter = RulesManager.getInstance(context).getPreferenceFilter(context);
        final boolean notify_access = prefs.getBoolean("notify_access", false);

        // Get rule
        final Rule rule = listFiltered.get(position);
        boundHolders.add(holder);
        holder.boundRule = rule;

        // Handle expanding/collapsing
        holder.llApplication.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                rule.expanded = !rule.expanded;
                if (rule.expanded) expanded.add(rule.packageName); else expanded.remove(rule.packageName);
                notifyItemChanged(holder.getAdapterPosition());
            }
        });

        // Show if non default rules
        holder.itemView.setBackgroundColor(rule.changed ? colorChanged : Color.TRANSPARENT);

        // Show expand/collapse indicator
        holder.ivExpander.setImageLevel(rule.expanded ? 1 : 0);

        // Show application icon
        if (rule.icon <= 0)
            holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        else {
            Uri uri = Uri.parse("android.resource://" + rule.packageName + "/" + rule.icon);
            GlideApp.with(holder.itemView.getContext())
                    .applyDefaultRequestOptions(new RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .load(uri)
                    //.diskCacheStrategy(DiskCacheStrategy.NONE)
                    //.skipMemoryCache(true)
                    .override(iconSize, iconSize)
                    .into(holder.ivIcon);
        }

        // Show application label
        holder.tvName.setText(rule.name);

        // Show application state
        int color = rule.system ? colorOff : colorText;
        if (!rule.internet || !rule.enabled)
            color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color));
        holder.tvName.setTextColor(color);

        holder.tvHosts.setVisibility(rule.hosts > 0 ? View.VISIBLE : View.GONE);
        holder.tvHosts.setText(Long.toString(rule.hosts));

        // Lockdown settings
        boolean lockdown = RulesManager.getInstance(context).getPreferenceLockdown(context);
        boolean lockdown_wifi = RulesManager.getInstance(context).getPreferenceLockdownWifi(context);
        boolean lockdown_other = RulesManager.getInstance(context).getPreferenceLockdownOther(context);
        if ((otherActive && !lockdown_other) || (wifiActive && !lockdown_wifi))
            lockdown = false;

        holder.rlLockdown.setVisibility(lockdown && !rule.lockdown ? View.VISIBLE : View.GONE);
        holder.ivLockdown.setEnabled(rule.apply);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(holder.ivLockdown.getDrawable());
            DrawableCompat.setTint(wrap, rule.apply ? colorOff : colorGrayed);
        }

        boolean screen_on = RulesManager.getInstance(context).getPreferenceScreenOn(context);

        // Wi-Fi settings
        holder.cbWifi.setEnabled(rule.apply);
        holder.cbWifi.setAlpha(wifiActive ? 1 : 0.5f);
        holder.cbWifi.setOnCheckedChangeListener(null);
        holder.cbWifi.setChecked(rule.wifi_blocked);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(CompoundButtonCompat.getButtonDrawable(holder.cbWifi));
            DrawableCompat.setTint(wrap, rule.apply ? (rule.wifi_blocked ? colorOff : colorOn) : colorGrayed);
        }
        holder.cbWifi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton compoundButton, boolean isChecked) {
                //rule.wifi_blocked = isChecked;
                //updateRule(context, rule, true, listAll);
                compoundButton.setChecked(rule.wifi_blocked);

                String enable_disable;
                if (isChecked) {
                    enable_disable = "unallow";
                } else {
                    enable_disable = "allow";
                }

                String message = context.getString(R.string.change_allow_package, enable_disable, rule.name);
                Util.areYouSure(context, message, new Util.DoubtListener() {
                    @Override
                    public void onSure() {
                        RulesManager.getInstance(compoundButton.getContext()).setPackageAllowed(compoundButton.getContext(), rule.packageName);
                    }
                });
            }
        });

        holder.ivScreenWifi.setEnabled(rule.apply);
        holder.ivScreenWifi.setAlpha(wifiActive ? 1 : 0.5f);
        holder.ivScreenWifi.setVisibility(rule.screen_wifi && rule.wifi_blocked ? View.VISIBLE : View.INVISIBLE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(holder.ivScreenWifi.getDrawable());
            DrawableCompat.setTint(wrap, rule.apply ? colorOn : colorGrayed);
        }

        // Mobile settings
        holder.cbOther.setEnabled(rule.apply);
        holder.cbOther.setAlpha(otherActive ? 1 : 0.5f);
        holder.cbOther.setOnCheckedChangeListener(null);
        holder.cbOther.setChecked(rule.other_blocked);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(CompoundButtonCompat.getButtonDrawable(holder.cbOther));
            DrawableCompat.setTint(wrap, rule.apply ? (rule.other_blocked ? colorOff : colorOn) : colorGrayed);
        }
        holder.cbOther.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                rule.other_blocked = isChecked;
                updateRule(context, rule, true, listAll);
            }
        });

        holder.ivScreenOther.setEnabled(rule.apply);
        holder.ivScreenOther.setAlpha(otherActive ? 1 : 0.5f);
        holder.ivScreenOther.setVisibility(rule.screen_other && rule.other_blocked ? View.VISIBLE : View.INVISIBLE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(holder.ivScreenOther.getDrawable());
            DrawableCompat.setTint(wrap, rule.apply ? colorOn : colorGrayed);
        }

        holder.tvRoaming.setTextColor(rule.apply ? colorOff : colorGrayed);
        holder.tvRoaming.setAlpha(otherActive ? 1 : 0.5f);
        holder.tvRoaming.setVisibility(rule.roaming && (!rule.other_blocked || rule.screen_other) ? View.VISIBLE : View.INVISIBLE);

        holder.tvRemarkMessaging.setVisibility(messaging.contains(rule.packageName) ? View.VISIBLE : View.GONE);
        holder.tvRemarkDownload.setVisibility(download.contains(rule.packageName) ? View.VISIBLE : View.GONE);

        // Expanded configuration section
        holder.llConfiguration.setVisibility(rule.expanded ? View.VISIBLE : View.GONE);

        // Show application details
        holder.tvUid.setText(Integer.toString(rule.uid));
        holder.tvPackage.setText(rule.packageName);
        holder.tvVersion.setText(rule.version);

        // Show application state
        holder.tvInternet.setVisibility(rule.internet ? View.GONE : View.VISIBLE);
        holder.tvDisabled.setVisibility(rule.enabled ? View.GONE : View.VISIBLE);

        // Show related
        holder.btnRelated.setVisibility(rule.relateduids ? View.VISIBLE : View.GONE);
        holder.btnRelated.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent main = new Intent(context, ActivityMain.class);
                main.putExtra(ActivityMain.EXTRA_SEARCH, Integer.toString(rule.uid));
                main.putExtra(ActivityMain.EXTRA_RELATED, true);
                context.startActivity(main);
            }
        });

        // Launch application settings
        if (rule.expanded) {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + rule.packageName));
            final Intent settings = (intent.resolveActivity(context.getPackageManager()) == null ? null : intent);

            holder.ibSettings.setVisibility(settings == null ? View.GONE : View.VISIBLE);
            holder.ibSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    context.startActivity(settings);
                }
            });
        } else
            holder.ibSettings.setVisibility(View.GONE);

        // Launch application
        if (rule.expanded) {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(rule.packageName);
            final Intent launch = (intent == null ||
                    intent.resolveActivity(context.getPackageManager()) == null ? null : intent);

            holder.ibLaunch.setVisibility(launch == null ? View.GONE : View.VISIBLE);
            holder.ibLaunch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    context.startActivity(launch);
                }
            });
        } else
            holder.ibLaunch.setVisibility(View.GONE);

        // Apply
        holder.cbApply.setEnabled(rule.pkg && filter);
        holder.cbApply.setOnCheckedChangeListener(null);
        holder.cbApply.setChecked(rule.apply);
        holder.cbApply.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                rule.apply = isChecked;
                updateRule(context, rule, true, listAll);
            }
        });

        // Show Wi-Fi screen on condition
        holder.llScreenWifi.setVisibility(screen_on ? View.VISIBLE : View.GONE);
        holder.cbScreenWifi.setEnabled(rule.wifi_blocked && rule.apply);
        holder.cbScreenWifi.setOnCheckedChangeListener(null);
        holder.cbScreenWifi.setChecked(rule.screen_wifi);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(holder.ivWifiLegend.getDrawable());
            DrawableCompat.setTint(wrap, colorOn);
        }

        holder.cbScreenWifi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                rule.screen_wifi = isChecked;
                updateRule(context, rule, true, listAll);
            }
        });

        // Show mobile screen on condition
        holder.llScreenOther.setVisibility(screen_on ? View.VISIBLE : View.GONE);
        holder.cbScreenOther.setEnabled(rule.other_blocked && rule.apply);
        holder.cbScreenOther.setOnCheckedChangeListener(null);
        holder.cbScreenOther.setChecked(rule.screen_other);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(holder.ivOtherLegend.getDrawable());
            DrawableCompat.setTint(wrap, colorOn);
        }

        holder.cbScreenOther.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                rule.screen_other = isChecked;
                updateRule(context, rule, true, listAll);
            }
        });

        // Show roaming condition
        holder.cbRoaming.setEnabled((!rule.other_blocked || rule.screen_other) && rule.apply);
        holder.cbRoaming.setOnCheckedChangeListener(null);
        holder.cbRoaming.setChecked(rule.roaming);
        holder.cbRoaming.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            @TargetApi(Build.VERSION_CODES.M)
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                rule.roaming = isChecked;
                updateRule(context, rule, true, listAll);
            }
        });

        // Show lockdown
        holder.cbLockdown.setEnabled(rule.apply);
        holder.cbLockdown.setOnCheckedChangeListener(null);
        holder.cbLockdown.setChecked(rule.lockdown);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrap = DrawableCompat.wrap(holder.ivLockdownLegend.getDrawable());
            DrawableCompat.setTint(wrap, colorOn);
        }

        holder.cbLockdown.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            @TargetApi(Build.VERSION_CODES.M)
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                rule.lockdown = isChecked;
                updateRule(context, rule, true, listAll);
            }
        });

        // Reset rule
        holder.btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Util.areYouSure(view.getContext(), R.string.msg_clear_rules, new Util.DoubtListener() {
                    @Override
                    public void onSure() {
                        holder.cbApply.setChecked(true);
                        holder.cbWifi.setChecked(rule.wifi_default);
                        holder.cbOther.setChecked(rule.other_default);
                        holder.cbScreenWifi.setChecked(rule.screen_wifi_default);
                        holder.cbScreenOther.setChecked(rule.screen_other_default);
                        holder.cbRoaming.setChecked(rule.roaming_default);
                        holder.cbLockdown.setChecked(false);
                    }
                });
            }
        });

        holder.llFilter.setVisibility(Util.canFilter(context) ? View.VISIBLE : View.GONE);

        // Live
        holder.ivLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                live = !live;
                TypedValue tv = new TypedValue();
                view.getContext().getTheme().resolveAttribute(live ? R.attr.iconPause : R.attr.iconPlay, tv, true);
                holder.ivLive.setImageResource(tv.resourceId);
                if (live)
                    AdapterRule.this.notifyDataSetChanged();
            }
        });

        // Show logging/filtering is disabled
        holder.tvLogging.setText(log_app && filter ? R.string.title_logging_enabled : R.string.title_logging_disabled);
        holder.btnLogging.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LayoutInflater inflater = LayoutInflater.from(context);
                View view = inflater.inflate(R.layout.enable, null, false);

                final CheckBox cbLogging = view.findViewById(R.id.cbLogging);
                final CheckBox cbFiltering = view.findViewById(R.id.cbFiltering);
                final CheckBox cbNotify = view.findViewById(R.id.cbNotify);
                TextView tvFilter4 = view.findViewById(R.id.tvFilter4);

                cbLogging.setChecked(log_app);
                cbLogging.setEnabled(false);
                cbFiltering.setChecked(filter);
                cbFiltering.setEnabled(false);
                tvFilter4.setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? View.GONE : View.VISIBLE);
                cbNotify.setChecked(notify_access);
                cbNotify.setEnabled(log_app);

                cbNotify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                        prefs.edit().putBoolean("notify_access", checked).apply();
                        ServiceSinkhole.reload("changed notify", context, false);
                        AdapterRule.this.notifyDataSetChanged();
                    }
                });

                AlertDialog dialog = new AlertDialog.Builder(context)
                        .setView(view)
                        .setCancelable(true)
                        .create();
                dialog.show();
            }
        });

        Boolean appHideAllowed = hideAllowedDomains.get(rule.packageName);
        final boolean hideAllowed = appHideAllowed == null
                ? prefs.getBoolean(Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS, false) : appHideAllowed;
        holder.cbHideAllowedDomains.setOnCheckedChangeListener(null);
        holder.cbHideAllowedDomains.setChecked(hideAllowed);
        holder.cbHideAllowedDomains.setOnCheckedChangeListener((button, checked) -> {
            hideAllowedDomains.put(rule.packageName, checked);
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition != RecyclerView.NO_POSITION)
                notifyItemChanged(currentPosition);
        });

        if (holder.domainWatcher != null) holder.domainSearch.removeTextChangedListener(holder.domainWatcher);
        String query = domainQueries.containsKey(rule.packageName) ? domainQueries.get(rule.packageName) : "";
        if (!query.equals(holder.domainSearch.getText().toString())) holder.domainSearch.setText(query);
        holder.domainWatcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                domainQueries.put(rule.packageName, text.toString());
                loadDomains(holder, rule);
            }
            public void afterTextChanged(Editable text) { }
        };
        holder.domainSearch.addTextChangedListener(holder.domainWatcher);
        holder.domainSortButton.setText(context.getString(R.string.list_sort_domains) + ": " + context.getString(ListSort.label(domainSort)));
        holder.domainSortButton.setOnClickListener(view -> SortDialog.show(context, R.string.list_sort_domains,
                ListSort.DOMAINS, domainSort, ListSort.DOMAIN_DEFAULT, selected -> {
                    domainSort = selected;
                    for (ViewHolder bound : boundHolders)
                        bound.domainSortButton.setText(context.getString(R.string.list_sort_domains) + ": " + context.getString(ListSort.label(selected)));
                    refreshDomains();
                }));
        if (!rule.packageName.equals(holder.boundPackage)) {
            holder.accessGeneration++;
            holder.boundPackage = rule.packageName;
            holder.lvAccess.setAdapter(new AdapterAccess(context, entry -> showAccessMenu(context, holder.boundRule, entry)));
        }
        if (rule.expanded) loadDomains(holder, rule);
        else holder.accessGeneration++;

        // Clear access log
        holder.btnClearAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Util.areYouSure(view.getContext(), R.string.msg_reset_access, new Util.DoubtListener() {
                    @Override
                    public void onSure() {
                        //DatabaseHelper.getInstance(context).clearAccess(rule.uid, true);
                        DatabaseHelper.getInstance(context).clearAccess(rule.uid, false);
                        if (!live)
                            notifyDataSetChanged();
                        if (rv != null)
                            rv.scrollToPosition(holder.getAdapterPosition());
                    }
                });
            }
        });

        // Notify on access
        holder.cbNotify.setEnabled(prefs.getBoolean("notify_access", false) && rule.apply);
        holder.cbNotify.setOnCheckedChangeListener(null);
        holder.cbNotify.setChecked(rule.notify);
        holder.cbNotify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                boolean notify = isChecked;
                RulesManager rm = RulesManager.getInstance(context);
                boolean rm_notify = rm.getPreferenceNotifyApp(context, rule.packageName);
                if (notify != rm_notify) {
                    String ruletext;

                    if (!notify) {
                        ruletext = "ignore package:" + rule.packageName;
                    } else {
                        ruletext = "- ignore package:" + rule.packageName;
                    }
                    rm.queueRuleText(context, ruletext);
                }
            }
        });

        // HeartGuard change - no need to show these controls
        holder.cbOther.setVisibility(View.GONE);
        holder.cbApply.setVisibility(View.GONE);
        holder.itemView.findViewById(R.id.llBlockWhenRoaming).setVisibility(View.GONE);
        holder.itemView.findViewById(R.id.llAllowInLockdown).setVisibility(View.GONE);
        holder.itemView.findViewById(R.id.tvTitleConditions).setVisibility(View.GONE);
        holder.btnClear.setVisibility(View.GONE);
        holder.ivScreenWifi.setVisibility(View.GONE);
        holder.ivScreenOther.setVisibility(View.GONE);
    }

    private void showAccessMenu(Context context, Rule rule, AccessEntry entry) {
        final long id = entry.id;
        final int version = entry.version, protocol = entry.protocol, dport = entry.port, pending = entry.pending;
        final String daddr = entry.address;
        long time = entry.time;
        int block = entry.block;
        PopupMenu popup = new PopupMenu(context, anchor);
        popup.inflate(R.menu.access);

        popup.getMenu().findItem(R.id.menu_host).setTitle(
                Util.getProtocolName(protocol, version, false) + " " +
                        daddr + (dport > 0 ? "/" + dport : ""));

        SubMenu sub = popup.getMenu().findItem(R.id.menu_host).getSubMenu();
        boolean multiple = false;
        Cursor alt = null;
        try {
            alt = DatabaseHelper.getInstance(context).getAlternateQNames(daddr);
            while (alt.moveToNext()) {
                multiple = true;
                sub.add(Menu.NONE, Menu.NONE, 0, alt.getString(0)).setEnabled(false);
            }
        } finally {
            if (alt != null)
                alt.close();
        }
        popup.getMenu().findItem(R.id.menu_host).setEnabled(multiple);

        popup.getMenu().findItem(R.id.menu_time).setTitle(
                SimpleDateFormat.getDateTimeInstance().format(time));

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                int menu = menuItem.getItemId();
                boolean result = false;
                switch (menu) {
                    case R.id.menu_block_app:
                        DomainRuleDialog.show(context, rule.packageName, daddr, true);
                        return true;
                    case R.id.menu_allow:
                    case R.id.menu_allow_globally:
                        // HeartGuard change - launch a window to queue a rule instead of directly allowing access
                        if (menu == R.id.menu_allow_globally)
                            DomainRuleDialog.show(context, "", daddr, false);
                        else
                            launchAllowUrlPage(context, rule.packageName, daddr);
                        result = true;
                        break;

                    // HeartGuard change - resetting access means deleting all relevant rules
                    case R.id.menu_reset:
                    //    DatabaseHelper.getInstance(context).setAccess(id, -1);
                    //    ServiceSinkhole.reload("reset host", context, false);
                    //    result = true;
                    //    break;
                        RulesManager rm = RulesManager.getInstance(context);
                        rm.resetRulesForAccess(context, rule.uid, daddr, pending);
                        result = true;
                        break;

                    case R.id.menu_copy:
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("netguard", daddr);
                        clipboard.setPrimaryClip(clip);
                        return true;

                    case R.id.menu_ignore:
                        launchIgnoreUrlPage(context, rule.packageName, daddr);
                        result = true;
                        break;
                }

                // HeartGuard change - R.id.menu_reset removed
                if (menu == R.id.menu_allow || menu == R.id.menu_allow_globally/* || menu == R.id.menu_reset*/)
                    new AsyncTask<Object, Object, Long>() {
                        @Override
                        protected Long doInBackground(Object... objects) {
                            return DatabaseHelper.getInstance(context).getHostCount(rule.uid, false);
                        }

                        @Override
                        protected void onPostExecute(Long hosts) {
                            rule.hosts = hosts;
                            refreshTraffic();
                        }
                    }.execute();

                return result;
            }
        });

        if (block == 0)
            popup.getMenu().removeItem(R.id.menu_allow);

        popup.show();
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        super.onViewRecycled(holder);
        boundHolders.remove(holder);
        holder.accessGeneration++;
        holder.boundRule = null;
        holder.boundPackage = null;
        holder.lvAccess.setAdapter(null);
    }

    private void updateRule(Context context, Rule rule, boolean root, List<Rule> listAll) {
        //SharedPreferences wifi = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_WIFI, Context.MODE_PRIVATE);
        //SharedPreferences other = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_OTHER, Context.MODE_PRIVATE);
        SharedPreferences apply = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_APPLY, Context.MODE_PRIVATE);
        //SharedPreferences screen_wifi = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_SCREEN_WIFI, Context.MODE_PRIVATE);
        //SharedPreferences screen_other = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_SCREEN_OTHER, Context.MODE_PRIVATE);
        SharedPreferences roaming = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_ROAMING, Context.MODE_PRIVATE);
        SharedPreferences lockdown = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_LOCKDOWN, Context.MODE_PRIVATE);
        //SharedPreferences notify = context.getSharedPreferences(Rule.PREFERENCE_STRING_PERAPP_NOTIFY, Context.MODE_PRIVATE);

        //if (rule.wifi_blocked == rule.wifi_default)
        //    wifi.edit().remove(rule.packageName).apply();
        //else
        //    wifi.edit().putBoolean(rule.packageName, rule.wifi_blocked).apply();

        //if (rule.other_blocked == rule.other_default)
        //    other.edit().remove(rule.packageName).apply();
        //else
        //    other.edit().putBoolean(rule.packageName, rule.other_blocked).apply();

        if (rule.apply)
            apply.edit().remove(rule.packageName).apply();
        else
            apply.edit().putBoolean(rule.packageName, rule.apply).apply();

        //if (rule.screen_wifi == rule.screen_wifi_default)
        //    screen_wifi.edit().remove(rule.packageName).apply();
        //else
        //    screen_wifi.edit().putBoolean(rule.packageName, rule.screen_wifi).apply();

        //if (rule.screen_other == rule.screen_other_default)
        //    screen_other.edit().remove(rule.packageName).apply();
        //else
        //    screen_other.edit().putBoolean(rule.packageName, rule.screen_other).apply();

        if (rule.roaming == rule.roaming_default)
            roaming.edit().remove(rule.packageName).apply();
        else
            roaming.edit().putBoolean(rule.packageName, rule.roaming).apply();

        if (rule.lockdown)
            lockdown.edit().putBoolean(rule.packageName, rule.lockdown).apply();
        else
            lockdown.edit().remove(rule.packageName).apply();

        //if (rule.notify != )
        //    notify.edit().remove(rule.packageName).apply();
        //else
        //    notify.edit().putBoolean(rule.packageName, rule.notify).apply();

        rule.updateChanged(context);
        Log.i(TAG, "Updated " + rule);

        List<Rule> listModified = new ArrayList<>();
        for (String pkg : rule.related) {
            for (Rule related : listAll)
                if (related.packageName.equals(pkg)) {
                    related.wifi_blocked = rule.wifi_blocked;
                    related.other_blocked = rule.other_blocked;
                    related.apply = rule.apply;
                    related.screen_wifi = rule.screen_wifi;
                    related.screen_other = rule.screen_other;
                    related.roaming = rule.roaming;
                    related.lockdown = rule.lockdown;
                    related.notify = rule.notify;
                    listModified.add(related);
                }
        }

        List<Rule> listSearch = (root ? new ArrayList<>(listAll) : listAll);
        listSearch.remove(rule);
        for (Rule modified : listModified)
            listSearch.remove(modified);
        for (Rule modified : listModified)
            updateRule(context, modified, false, listSearch);

        if (root) {
            notifyDataSetChanged();
            NotificationManagerCompat.from(context).cancel(rule.uid);
            ServiceSinkhole.reload("rule changed", context, false);
        }
    }

    @Override
    public AdapterRule.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(inflater.inflate(R.layout.rule, parent, false));
    }

    @Override
    public long getItemId(int position) {
        Rule rule = listFiltered.get(position);
        return rule.packageName.hashCode() * 100000L + rule.uid;
    }

    @Override
    public int getItemCount() {
        return listFiltered.size();
    }

    // HeartGuard change - launch a dialog to allow a URL (package specific or not)
    private void launchAllowUrlPage(final Context context, String packagename, String daddr) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.allowhost, null, false);
        final EditText etDAddr = view.findViewById(R.id.allow_new_access_daddr);
        final EditText etPackageName = view.findViewById(R.id.allow_new_access_package_name);
        etDAddr.setText(daddr);
        etPackageName.setText(packagename);
        if (packagename.isEmpty()) {
            ((TextView) view.findViewById(R.id.allow_new_access_title)).setText(R.string.title_allow_globally);
            ((TextView) view.findViewById(R.id.allow_new_access_package_label)).setText(R.string.allow_all_apps);
            etPackageName.setVisibility(View.GONE);
        }

        AlertDialog dialog;
        dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newpackagename = etPackageName.getText().toString();
                        String newdaddr = etDAddr.getText().toString();
                        Log.w(TAG, String.format("click yes: newpackagename=\"%s\" newdaddr=\"%s\"", newpackagename, newdaddr));

                        // Make sure that the text doesn't have whitespaces.
                        // The package name can be empty, but the daddr can't (if they want to allow a whole package, they click on the app wifi logo)
                        if (newdaddr.matches("\\S+")) {
                            if (newpackagename.matches("\\S+") || newpackagename.length() == 0) {
                                String destinationtext;

                                if (newdaddr.equals("directip")) {
                                    destinationtext = "directip";
                                }
                                else if (newdaddr.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") ||
                                    newdaddr.matches("[0-9a-fA-F:]+") ||
                                    newdaddr.matches("(\\d+.\\d+.\\d+.\\d+)/(\\d+)")) {
                                    destinationtext = "ip:" + newdaddr;
                                } else {
                                    destinationtext = "host:" + newdaddr;
                                }

                                String newruletext;
                                if (newpackagename.length() == 0) {
                                    newruletext = "allow " + destinationtext;
                                } else {
                                    newruletext = "allow package:" + newpackagename + " " + destinationtext;
                                }
                                RulesManager.getInstance(context).queueRuleText(context, newruletext);
                            }
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                    }
                })
                .create();
        dialog.show();
    }

    // HeartGuard change - launch a dialog to ignore a URL (package specific or not)
    private void launchIgnoreUrlPage(final Context context, String packagename, String daddr) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.ignorehost, null, false);
        final EditText etDAddr = view.findViewById(R.id.ignore_access_daddr);
        final EditText etPackageName = view.findViewById(R.id.ignore_access_package_name);
        etDAddr.setText(daddr);
        etPackageName.setText(packagename);

        AlertDialog dialog;
        dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newpackagename = etPackageName.getText().toString().trim();
                        String newdaddr = etDAddr.getText().toString().trim();
                        Log.w(TAG, String.format("yes ignore: newpackagename=\"%s\" newdaddr=\"%s\"", newpackagename, newdaddr));

                        // Make sure that the text doesn't have whitespaces.
                        // The package name can be empty, but the daddr can't (if they want to allow a whole package, they click on the app wifi logo)

                        String ruletext;
                        boolean bWhitespace = false;
                        if ((newdaddr.length() > 0) && (!newdaddr.matches("\\S+")))
                        {
                            bWhitespace = true;
                        }
                        if ((newpackagename.length() > 0) && (!newpackagename.matches("\\S+")))
                        {
                            bWhitespace = true;
                        }
                        if (bWhitespace)
                        {
                            Log.w(TAG, "Contains whitespace");
                            dialog.dismiss();
                            return;
                        }
                        if (newdaddr.length() == 0 && newpackagename.length() == 0)
                        {
                            Log.w(TAG, "Ignore rules can't be empty");
                            dialog.dismiss();
                            return;
                        } else if (newpackagename.length() > 0) {
                            if (newdaddr.length() > 0) {
                                ruletext = String.format("ignore package:%s host:%s", newpackagename, newdaddr);
                            } else {
                                ruletext = String.format("ignore package:%s", newpackagename);
                            }
                        } else {
                            ruletext = String.format("ignore host:%s", newdaddr);
                        }
                        RulesManager.getInstance(context).queueRuleText(context, ruletext);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                    }
                })
                .create();
        dialog.show();
    }
}
