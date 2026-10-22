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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

public class ActivityMain extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "NetGuard.Main";

    private boolean running = false;
    private ImageView ivIcon;
    private ImageView ivQueue;
    private SwitchCompat swEnabled;
    private ImageView ivMetered;
    private SwipeRefreshLayout swipeRefresh;
    private AdapterRule adapter = null;
    private int applicationGeneration;
    private MenuItem menuSearch = null;
    private Menu mainMenu;
    private boolean searchDomains;
    private boolean restoringSearch;
    private boolean restoreSearchFocus;
    private boolean searchExpanded;
    private boolean resumed;
    private String domainQuery = "";
    private AlertDialog dialogFirst = null;
    private AlertDialog dialogVpn = null;
    private AlertDialog dialogDoze = null;
    private AlertDialog dialogLegend = null;
    private AlertDialog dialogHelp = null;
    private AlertDialog dialogAbout = null;


    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_INVITE = 2;
    private static final int REQUEST_LOGCAT = 3;
    public static final int REQUEST_ROAMING = 4;

    private static final int MIN_SDK = Build.VERSION_CODES.LOLLIPOP_MR1;

    public static final String ACTION_RULES_CHANGED = "eu.faircode.netguard.ACTION_RULES_CHANGED";
    public static final String ACTION_QUEUE_CHANGED = "eu.faircode.netguard.ACTION_QUEUE_CHANGED";
    public static final String EXTRA_REFRESH = "Refresh";
    public static final String EXTRA_SEARCH = "Search";
    public static final String EXTRA_RELATED = "Related";
    public static final String EXTRA_APPROVE = "Approve";
    public static final String EXTRA_LOGCAT = "Logcat";
    public static final String EXTRA_CONNECTED = "Connected";
    public static final String EXTRA_METERED = "Metered";
    public static final String EXTRA_SIZE = "Size";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));
        Util.logExtras(getIntent());

        // Check minimum Android version
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            Log.i(TAG, "SDK=" + Build.VERSION.SDK_INT);
            super.onCreate(savedInstanceState);
            setContentView(R.layout.android);
            return;
        }

        // HeartGuard change - don't quit just because of Xposed
        // Check for Xposed
        //if (Util.hasXposed(this)) {
        //    Log.i(TAG, "Xposed running");
        //    super.onCreate(savedInstanceState);
        //    setContentView(R.layout.xposed);
        //    return;
        //}

        // HeartGuard change - debug the "A resource failed to call close." message
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build());

        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (savedInstanceState != null) {
            searchDomains = savedInstanceState.getBoolean("searchDomains");
            searchExpanded = savedInstanceState.getBoolean("searchExpanded");
            domainQuery = savedInstanceState.getString("domainQuery", "");
        }
        updateSearchContent();

        running = true;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = RulesManager.getInstance(ActivityMain.this).getPreferenceEnabled(this);
        boolean initialized = prefs.getBoolean("initialized", false);

        // Upgrade
        ReceiverAutostart.upgrade(initialized, this);

        if (!getIntent().hasExtra(EXTRA_APPROVE)) {
            if (enabled)
                ServiceSinkhole.start("UI", this);
            else
                ServiceSinkhole.stop("UI", this, false);
        }

        // Action bar
        final View actionView = getLayoutInflater().inflate(R.layout.actionmain, null, false);
        ivIcon = actionView.findViewById(R.id.ivIcon);
        ivQueue = actionView.findViewById(R.id.ivQueue);
        ivQueue.setVisibility(View.GONE);
        swEnabled = actionView.findViewById(R.id.swEnabled);
        ivMetered = actionView.findViewById(R.id.ivMetered);
        ivMetered.setVisibility(View.GONE);

        // Icon
        ivIcon.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                menu_about();
                return true;
            }
        });

        // Title
        getSupportActionBar().setTitle(null);

        // On/off switch
        swEnabled.setChecked(enabled);
        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Log.i(TAG, "Switch=" + isChecked);
                //prefs.edit().putBoolean(Rule.PREFERENCE_STRING_ENABLED, isChecked).apply();
                // Reset the UI to the current status of RulesManager
                final boolean enabled = RulesManager.getInstance(ActivityMain.this).getPreferenceEnabled(ActivityMain.this);
                swEnabled.setChecked(enabled);

                // If the state has changed, then the user pressed it.
                if (enabled != isChecked) {
                    userToggledEnabled();
                }
            }
        });
        if (enabled)
            checkDoze();

        getSupportActionBar().setDisplayShowCustomEnabled(true);
        getSupportActionBar().setCustomView(actionView);

        // Disabled warning
        TextView tvDisabled = findViewById(R.id.tvDisabled);
        tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);

        // Application list
        RecyclerView rvApplication = findViewById(R.id.rvApplication);
        rvApplication.setHasFixedSize(false);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setAutoMeasureEnabled(true);
        rvApplication.setLayoutManager(llm);
        adapter = new AdapterRule(this, findViewById(R.id.vwPopupAnchor));
        adapter.restoreViewState(savedInstanceState == null ? null : savedInstanceState.getBundle("appListView"));
        adapter.setViewChangedListener(this::updateListSummary);
        rvApplication.setAdapter(adapter);
        ((androidx.recyclerview.widget.SimpleItemAnimator) rvApplication.getItemAnimator()).setSupportsChangeAnimations(false);
        rvApplication.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            // An expanded app can be taller than the viewport. After the keyboard
            // shrinks it, reveal the focused field rather than the app row's edge.
            if (bottom - top < oldBottom - oldTop) {
                View focused = rvApplication.findFocus();
                if (focused instanceof android.widget.EditText)
                    rvApplication.post(() -> {
                        if (focused.hasFocus())
                            focused.requestRectangleOnScreen(new android.graphics.Rect(
                                    0, 0, focused.getWidth(), focused.getHeight()), true);
                    });
            }
        });
        findViewById(R.id.resetListFilters).setOnClickListener(view -> resetListFilters());
        findViewById(R.id.copyMatchingDomains).setOnClickListener(view -> adapter.copyMatchingDomains());
        androidx.appcompat.widget.TooltipCompat.setTooltipText(findViewById(R.id.resetListFilters), getString(R.string.reset_search_filters));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(findViewById(R.id.copyMatchingDomains), getString(R.string.domain_search_copy));
        adapter.setDomainSearch(searchDomains, domainQuery);

        // Swipe to refresh
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorPrimary, tv, true);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(Color.WHITE, Color.WHITE, Color.WHITE);
        swipeRefresh.setProgressBackgroundColorSchemeColor(tv.data);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                Rule.clearCache(ActivityMain.this);
                ServiceSinkhole.reload("pull", ActivityMain.this, false);
                updateApplicationList(null);
            }
        });

        // Hint usage
        final LinearLayout llUsage = findViewById(R.id.llUsage);
        Button btnUsage = findViewById(R.id.btnUsage);
        boolean hintUsage = prefs.getBoolean("hint_usage", true);
        llUsage.setVisibility(hintUsage ? View.VISIBLE : View.GONE);
        btnUsage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.edit().putBoolean("hint_usage", false).apply();
                llUsage.setVisibility(View.GONE);
                showHints();
            }
        });

        final LinearLayout llFairEmail = findViewById(R.id.llFairEmail);
        TextView tvFairEmail = findViewById(R.id.tvFairEmail);
        tvFairEmail.setMovementMethod(LinkMovementMethod.getInstance());
        Button btnFairEmail = findViewById(R.id.btnFairEmail);
        boolean hintFairEmail = prefs.getBoolean("hint_fairemail", true);
        llFairEmail.setVisibility(hintFairEmail ? View.VISIBLE : View.GONE);
        btnFairEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.edit().putBoolean("hint_fairemail", false).apply();
                llFairEmail.setVisibility(View.GONE);
            }
        });

        showHints();

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Listen for rule set changes
        IntentFilter ifr = new IntentFilter(ACTION_RULES_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onRulesChanged, ifr);

        // Listen for queue changes
        IntentFilter ifq = new IntentFilter(ACTION_QUEUE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onQueueChanged, ifq);

        // Listen for added/removed applications
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        registerReceiver(packageChangedReceiver, intentFilter);

        // First use
        if (!initialized) {
            // Create view
            LayoutInflater inflater = LayoutInflater.from(this);
            View view = inflater.inflate(R.layout.first, null, false);

            TextView tvFirst = view.findViewById(R.id.tvFirst);
            TextView tvEula = view.findViewById(R.id.tvEula);
            TextView tvPrivacy = view.findViewById(R.id.tvPrivacy);
            tvFirst.setMovementMethod(LinkMovementMethod.getInstance());
            tvEula.setMovementMethod(LinkMovementMethod.getInstance());
            tvPrivacy.setMovementMethod(LinkMovementMethod.getInstance());

            // Show dialog
            dialogFirst = new AlertDialog.Builder(this)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(R.string.app_agree, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (running) {
                                prefs.edit().putBoolean("initialized", true).apply();
                            }
                        }
                    })
                    .setNegativeButton(R.string.app_disagree, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (running)
                                finish();
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialogInterface) {
                            dialogFirst = null;
                        }
                    })
                    .create();
            dialogFirst.show();
        }

        // Fill application list
        updateApplicationList(savedInstanceState == null ? getIntent().getStringExtra(EXTRA_SEARCH) : null);

        // Logging still respects the user-controlled rule policy.
        if (!RulesManager.getInstance(this).getPreferenceAllowLogging(this))
            prefs.edit().putBoolean(Rule.PREFERENCE_STRING_LOG, false).apply();

        // Handle intent
        checkExtras(getIntent());
    }

    // HeartGuard function
    // Asks for VPN permission, if necessary, and calls itself again
    private void userToggledEnabled() {
        Intent prepare = null;
        // If the switch state doesn't match the set state, then that means the user touched it

        // Take this user interaction moment to take the chance to prepare the VPN capability
        try {
            prepare = VpnService.prepare(ActivityMain.this);
            if (prepare != null) {
                startActivityForResult(prepare, REQUEST_VPN);
            }
        } catch (Throwable ex) {
            // Prepare failed
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        final boolean enabled = RulesManager.getInstance(ActivityMain.this).getPreferenceEnabled(ActivityMain.this);

        if (prepare == null) {
            String enable_disable;
            if (enabled == false) {
                enable_disable = "enable";
            } else {
                enable_disable = "disable";
            }

            String message = ActivityMain.this.getString(R.string.change_enabled, enable_disable);

            Util.areYouSure(ActivityMain.this, message, new Util.DoubtListener() {
                @Override
                public void onSure() {
                    String ruletext;
                    if (enabled) {
                        ruletext = "- feature enabled";
                    } else {
                        ruletext = "feature enabled";
                    }
                    RulesManager.getInstance(ActivityMain.this).queueRuleText(ActivityMain.this, ruletext);
                }
            });
        }

    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(TAG, "New intent");
        Util.logExtras(intent);
        super.onNewIntent(intent);

        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this))
            return;

        setIntent(intent);

        if (Build.VERSION.SDK_INT >= MIN_SDK) {
            if (intent.hasExtra(EXTRA_REFRESH))
                updateApplicationList(intent.getStringExtra(EXTRA_SEARCH));
            else
                updateSearch(intent.getStringExtra(EXTRA_SEARCH));
            checkExtras(intent);
        }
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resume");

        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) {
            super.onResume();
            return;
        }

        DatabaseHelper.getInstance(this).addAccessChangedListener(accessChangedListener);
        if (adapter != null)
            adapter.notifyDataSetChanged();

        super.onResume();
        resumed = true;
        if (adapter != null) adapter.refreshTraffic();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "Pause");
        super.onPause();
        resumed = false;

        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this))
            return;

        DatabaseHelper.getInstance(this).removeAccessChangedListener(accessChangedListener);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i(TAG, "Config");
        super.onConfigurationChanged(newConfig);

        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this))
            return;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroy");

        if (Build.VERSION.SDK_INT < MIN_SDK || Util.hasXposed(this)) {
            super.onDestroy();
            return;
        }

        running = false;
        if (adapter != null) adapter.dispose();
        adapter = null;

        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);

        LocalBroadcastManager.getInstance(this).unregisterReceiver(onRulesChanged);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(onQueueChanged);
        unregisterReceiver(packageChangedReceiver);

        if (dialogFirst != null) {
            dialogFirst.dismiss();
            dialogFirst = null;
        }
        if (dialogVpn != null) {
            dialogVpn.dismiss();
            dialogVpn = null;
        }
        if (dialogDoze != null) {
            dialogDoze.dismiss();
            dialogDoze = null;
        }
        if (dialogLegend != null) {
            dialogLegend.dismiss();
            dialogLegend = null;
        }
        if (dialogHelp != null) {
            dialogHelp.dismiss();
            dialogHelp = null;
        }
        if (dialogAbout != null) {
            dialogAbout.dismiss();
            dialogAbout = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));
        Util.logExtras(data);

        if (requestCode == REQUEST_VPN) {
            // Handle VPN approval
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            //prefs.edit().putBoolean(Rule.PREFERENCE_STRING_ENABLED, resultCode == RESULT_OK).apply();
            if (resultCode == RESULT_OK) {
                //ServiceSinkhole.start("prepared", this);

                //Toast on = Toast.makeText(ActivityMain.this, R.string.msg_on, Toast.LENGTH_LONG);
                //on.setGravity(Gravity.CENTER, 0, 0);
                //on.show();

                userToggledEnabled();
                checkDoze();
            } else if (resultCode == RESULT_CANCELED) {
                //TODO: safety check for this as a user reminder
                Toast.makeText(this, R.string.msg_vpn_cancelled, Toast.LENGTH_LONG).show();
            }

        } else if (requestCode == REQUEST_INVITE) {
            // Do nothing

        } else if (requestCode == REQUEST_LOGCAT) {
            // Send logcat by e-mail
            if (resultCode == RESULT_OK) {
                Uri target = data.getData();
                if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                    target = Uri.parse(target + "/logcat.txt");
                Log.i(TAG, "Export URI=" + target);
                Util.sendLogcat(target, this);
            }

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_ROAMING)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ServiceSinkhole.reload("permission granted", this, false);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        Log.i(TAG, "Preference " + name + "=" + prefs.getAll().get(name));
        if (Rule.PREFERENCE_STRING_ENABLED.equals(name)) {
            // Get enabled
            boolean enabled = RulesManager.getInstance(ActivityMain.this).getPreferenceEnabled(this);

            // Display disabled warning
            TextView tvDisabled = findViewById(R.id.tvDisabled);
            tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);

            // Check switch state
            SwitchCompat swEnabled = getSupportActionBar().getCustomView().findViewById(R.id.swEnabled);
            if (swEnabled.isChecked() != enabled)
                swEnabled.setChecked(enabled);

        } else if (Rule.PREFERENCE_STRING_LIST_CONTROLS.equals(name)) {
            invalidateOptionsMenu();
        } else if (//Rule.PREFERENCE_STRING_WHITELIST_WIFI.equals(name) ||
                //Rule.PREFERENCE_STRING_SCREEN_ON.equals(name) ||
                //Rule.PREFERENCE_STRING_SCREEN_WIFI.equals(name) ||
                //Rule.PREFERENCE_STRING_WHITELIST_OTHER.equals(name) ||
                //Rule.PREFERENCE_STRING_SCREEN_OTHER.equals(name) ||
                //Rule.PREFERENCE_STRING_WHITELIST_ROAMING.equals(name) ||
                Rule.PREFERENCE_STRING_SHOW_USER.equals(name) ||
                Rule.PREFERENCE_STRING_SHOW_SYSTEM.equals(name) ||
                Rule.PREFERENCE_STRING_SHOW_NOINTERNET.equals(name) ||
                Rule.PREFERENCE_STRING_SHOW_DISABLED.equals(name) ||
                Rule.PREFERENCE_STRING_SORT.equals(name) ||
                "imported".equals(name)) {
            updateApplicationList(null);

            final LinearLayout llWhitelist = findViewById(R.id.llWhitelist);
            boolean screen_on = RulesManager.getInstance(this).getPreferenceScreenOn(this);
            boolean whitelist_wifi = RulesManager.getInstance(this).getPreferenceWhitelistWifi(this);
            boolean whitelist_other = RulesManager.getInstance(this).getPreferenceWhitelistOther(this);
            boolean hintWhitelist = prefs.getBoolean("hint_whitelist", true);
            llWhitelist.setVisibility(!(whitelist_wifi || whitelist_other) && screen_on && hintWhitelist ? View.VISIBLE : View.GONE);

        } else if (Rule.PREFERENCE_STRING_MANAGE_SYSTEM.equals(name)) {
            invalidateOptionsMenu();
            updateApplicationList(null);

            LinearLayout llSystem = findViewById(R.id.llSystem);
            boolean system = RulesManager.getInstance(ActivityMain.this).getPreferenceManageSystem(ActivityMain.this);
            boolean hint = prefs.getBoolean("hint_system", true);
            llSystem.setVisibility(!system && hint ? View.VISIBLE : View.GONE);

        } else if (Rule.PREFERENCE_STRING_HIDE_ALLOWED_DOMAINS.equals(name)
                || Rule.PREFERENCE_STRING_ALWAYS_SHOW_APP_DETAILS.equals(name)
                || Rule.PREFERENCE_STRING_ALWAYS_SHOW_DOMAIN_SEARCH.equals(name)
                || CompactDomainLists.ENABLED.equals(name) || CompactDomainLists.ROWS.equals(name)
                || "domain_timestamp_format".equals(name)) {
            if (adapter != null) { adapter.notifyDataSetChanged(); adapter.refreshTraffic(); }
        } else if ("theme".equals(name) || "dark_theme".equals(name))
            recreate();
    }

    private DatabaseHelper.AccessChangedListener accessChangedListener = new DatabaseHelper.AccessChangedListener() {
        @Override
        public void onChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (adapter != null && adapter.isLive())
                        adapter.refreshTraffic();
                }
            });
        }
    };

    private BroadcastReceiver onRulesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            if (adapter != null)
                if (intent.hasExtra(EXTRA_CONNECTED) && intent.hasExtra(EXTRA_METERED)) {
                    ivIcon.setImageResource(Util.isNetworkActive(ActivityMain.this)
                            ? R.drawable.ic_security_white_24dp
                            : R.drawable.ic_security_white_24dp_60);
                    if (intent.getBooleanExtra(EXTRA_CONNECTED, false)) {
                        if (intent.getBooleanExtra(EXTRA_METERED, false))
                            adapter.setMobileActive();
                        else
                            adapter.setWifiActive();
                    } else {
                        adapter.setDisconnected();
                    }
                } else
                    updateApplicationList(null);
            // HeartGuard change - rules changes can also include main UI changes
            updateMainUi();
        }
    };

    private BroadcastReceiver onQueueChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            int size = intent.getIntExtra(EXTRA_SIZE, -1);
            ivIcon.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        }
    };

    private BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            updateApplicationList(null);
        }
    };

    @Override
    public void invalidateOptionsMenu() {
        // Rebuilding the toolbar collapses its old SearchView and clears its text.
        // That is not a user edit and must not reset the adapter's active query.
        if (menuSearch != null) {
            SearchView oldSearch = (SearchView) menuSearch.getActionView();
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(oldSearch);
            restoreSearchFocus |= oldSearch.hasFocus() && oldSearch.hasWindowFocus()
                    && insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
            searchExpanded |= menuSearch.isActionViewExpanded();
            menuSearch.setOnActionExpandListener(null);
            oldSearch.setOnQueryTextListener(null);
            oldSearch.setOnCloseListener(null);
        }
        super.invalidateOptionsMenu();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Returning from a rule dialog or another screen must not resume app-search editing.
        if (!hasFocus && menuSearch != null) ((SearchView) menuSearch.getActionView()).clearFocus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (Build.VERSION.SDK_INT < MIN_SDK)
            return false;

        PackageManager pm = getPackageManager();

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        mainMenu = menu;

        String placement = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(Rule.PREFERENCE_STRING_LIST_CONTROLS, "always");
        for (int id : new int[]{R.id.menu_filter, R.id.menu_sort}) {
            menu.findItem(id).setVisible(true);
            menu.findItem(id).setShowAsAction("if_room".equals(placement)
                    ? MenuItem.SHOW_AS_ACTION_IF_ROOM : "menu".equals(placement)
                    ? MenuItem.SHOW_AS_ACTION_NEVER : MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        // Search
        menuSearch = menu.findItem(R.id.menu_search);
        menuSearch.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                searchExpanded = true;
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                searchExpanded = false;
                bindSearchModeShortcut();
                if (getIntent().hasExtra(EXTRA_SEARCH) && !getIntent().getBooleanExtra(EXTRA_RELATED, false))
                    finish();
                return true;
            }
        });

        final SearchView searchView = (SearchView) menuSearch.getActionView();
        // Keep a magnifying glass available for switching modes while the editor is expanded.
        searchView.setIconifiedByDefault(false);
        View modeIcon = searchView.findViewById(androidx.appcompat.R.id.search_mag_icon);
        modeIcon.setContentDescription(getString(R.string.search_mode_switch));
        modeIcon.setOnLongClickListener(this::showSearchModes);
        modeIcon.setOnClickListener(this::showSearchModes);
        int modeIconSize = (int) (48 * getResources().getDisplayMetrics().density);
        modeIcon.getLayoutParams().width = modeIconSize;
        modeIcon.getLayoutParams().height = modeIconSize;
        searchView.setQueryHint(getString(searchDomains ? R.string.domain_search_hint : R.string.list_search_apps));
        String savedQuery = currentSearchQuery();
        if (!savedQuery.isEmpty() || searchExpanded || searchDomains) {
            if (restoreSearchFocus) {
                menuSearch.expandActionView();
                searchView.setQuery(savedQuery, false);
            } else restoreSearch(savedQuery);
        }
        restoreSearchFocus = false;
        bindSearchModeShortcut();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                applySearchQuery(query);
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (!restoringSearch) applySearchQuery(newText);
                return true;
            }
        });
        searchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                Intent intent = getIntent();
                intent.removeExtra(EXTRA_SEARCH);

                applySearchQuery("");
                return true;
            }
        });



        //if (!Util.hasValidFingerprint(this) || getIntentInvite(this).resolveActivity(pm) == null)
        //    menu.removeItem(R.id.menu_invite);

        //if (getIntentSupport().resolveActivity(getPackageManager()) == null)
        //    menu.removeItem(R.id.menu_support);

        //menu.findItem(R.id.menu_apps).setEnabled(getIntentApps(this).resolveActivity(pm) != null);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (!RulesManager.getInstance(this).getPreferenceAllowLogging(this)) {
            menu.removeItem(R.id.menu_log);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "Menu=" + item.getTitle());

        // Handle item selection
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        switch (item.getItemId()) {
            case R.id.menu_domain_search:
                setSearchMode(true);
                return true;
            case R.id.menu_display_settings:
                startActivity(new Intent(this, ActivitySettings.class).putExtra("open_display_settings", true));
                return true;
            case R.id.menu_help_tutorials:
                showHelpTutorials();
                return true;
            case R.id.menu_global_lists:
                GlobalListsDialog.choose(this);
                return true;
            case R.id.menu_filter:
                showAppFilters();
                return true;
            case R.id.menu_sort:
                showAppSort();
                return true;
            case R.id.menu_updates:
                startActivity(new Intent(this, ActivityUpdates.class));
                return true;
            // HeartGuard change - option to show rules list window
            case R.id.menu_show_rules_list:
                // Updating the rules at this time, in case the alarm hasn't gone off
                RulesManager.getInstance(this).startTrueTimeRequest(this);
                startActivity(new Intent(this, ActivityRulesList.class));
                return true;

            //case R.id.menu_lockdown:
            //    menu_lockdown(item);
            //    return true;

            case R.id.menu_log:
                if (RulesManager.getInstance(this).getPreferenceAllowLogging(this)) {
                    if (Util.canFilter(this))
                        startActivity(new Intent(this, ActivityLog.class));
                    else
                        Toast.makeText(this, R.string.msg_unavailable, Toast.LENGTH_SHORT).show();
                }
                return true;

            case R.id.menu_settings:
                startActivity(new Intent(this, ActivitySettings.class));
                return true;

            // HeartGuard change - removed menu items

            //case R.id.menu_invite:
            //    startActivityForResult(getIntentInvite(this), REQUEST_INVITE);
            //    return true;

            // HeartGuard change - removed menu items
            //case R.id.menu_support:
            //    startActivity(getIntentSupport());
            //    return true;

            case R.id.menu_about:
                menu_about();
                return true;

            // HeartGuard change - removed menu items
            //case R.id.menu_apps:
            //    menu_apps();
            //    return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showHints() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean hintUsage = prefs.getBoolean("hint_usage", true);

        // Hint white listing
        final LinearLayout llWhitelist = findViewById(R.id.llWhitelist);
        Button btnWhitelist = findViewById(R.id.btnWhitelist);
        boolean whitelist_wifi = RulesManager.getInstance(this).getPreferenceWhitelistWifi(this);
        boolean whitelist_other = RulesManager.getInstance(this).getPreferenceWhitelistOther(this);
        boolean hintWhitelist = prefs.getBoolean("hint_whitelist", true);
        llWhitelist.setVisibility(!(whitelist_wifi || whitelist_other) && hintWhitelist && !hintUsage ? View.VISIBLE : View.GONE);
        btnWhitelist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.edit().putBoolean("hint_whitelist", false).apply();
                llWhitelist.setVisibility(View.GONE);
            }
        });

        // Hint push messages
        final LinearLayout llPush = findViewById(R.id.llPush);
        Button btnPush = findViewById(R.id.btnPush);
        boolean hintPush = prefs.getBoolean("hint_push", true);
        llPush.setVisibility(hintPush && !hintUsage ? View.VISIBLE : View.GONE);
        btnPush.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.edit().putBoolean("hint_push", false).apply();
                llPush.setVisibility(View.GONE);
            }
        });

        // Hint system applications
        final LinearLayout llSystem = findViewById(R.id.llSystem);
        Button btnSystem = findViewById(R.id.btnSystem);
        boolean system = RulesManager.getInstance(ActivityMain.this).getPreferenceManageSystem(ActivityMain.this);
        boolean hintSystem = prefs.getBoolean("hint_system", true);
        llSystem.setVisibility(!system && hintSystem ? View.VISIBLE : View.GONE);
        btnSystem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.edit().putBoolean("hint_system", false).apply();
                llSystem.setVisibility(View.GONE);
            }
        });
    }

    private void checkExtras(Intent intent) {
        // Approve request
        if (intent.hasExtra(EXTRA_APPROVE)) {
            Log.i(TAG, "Requesting VPN approval");
            swEnabled.toggle();
        }

        if (intent.hasExtra(EXTRA_LOGCAT)) {
            Log.i(TAG, "Requesting logcat");
            Intent logcat = getIntentLogcat();
            if (logcat.resolveActivity(getPackageManager()) != null)
                startActivityForResult(logcat, REQUEST_LOGCAT);
        }
    }

    private void updateApplicationList(final String search) {
        Log.i(TAG, "Update search=" + search);
        final int generation = ++applicationGeneration;
        if (search != null) adapter.setQuery(search);

        new AsyncTask<Object, Object, List<Rule>>() {
            private boolean refreshing = true;

            @Override
            protected void onPreExecute() {
                swipeRefresh.post(new Runnable() {
                    @Override
                    public void run() {
                        if (refreshing)
                            swipeRefresh.setRefreshing(true);
                    }
                });
            }

            @Override
            protected List<Rule> doInBackground(Object... arg) {
                return Rule.getRules(true, ActivityMain.this);
            }

            @Override
            protected void onPostExecute(List<Rule> result) {
                if (running && generation == applicationGeneration) {
                    if (adapter != null) {
                        adapter.set(result);
                        updateSearch(search);
                    }

                    if (swipeRefresh != null) {
                        refreshing = false;
                        swipeRefresh.setRefreshing(false);
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void updateSearch(String search) {
        if (search == null) return;
        adapter.setQuery(search);
        if (searchDomains) setSearchMode(false);
        if (menuSearch != null) {
            restoreSearch(search);
        }
    }

    private String currentSearchQuery() {
        return searchDomains ? domainQuery : adapter.options.query;
    }

    private void applySearchQuery(String query) {
        if (searchDomains) { domainQuery = query; adapter.setDomainSearch(true, query); }
        else if (adapter != null) adapter.setQuery(query);
    }

    private void restoreSearch(String query) {
        SearchView search = (SearchView) menuSearch.getActionView();
        int descendants = search.getDescendantFocusability();
        // Expanding a SearchView normally requests editor focus and schedules the IME.
        // Restoring a filter is not an editing gesture, and must leave other fields alone.
        restoringSearch = true;
        try {
            search.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            menuSearch.expandActionView();
            search.setQuery(query, false);
            search.clearFocus();
        } finally {
            search.setDescendantFocusability(descendants);
            restoringSearch = false;
        }
    }

    private boolean showSearchModes(View anchor) {
        if (menuSearch != null) ((SearchView) menuSearch.getActionView()).clearFocus();
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, R.id.menu_search, 0, R.string.search_mode_apps).setChecked(!searchDomains);
        popup.getMenu().add(0, R.id.menu_domain_search, 1, R.string.domain_search_title).setChecked(searchDomains);
        popup.getMenu().setGroupCheckable(0, true, true);
        popup.setOnMenuItemClickListener(item -> {
            setSearchMode(item.getItemId() == R.id.menu_domain_search);
            return true;
        });
        popup.show();
        return true;
    }

    private void bindSearchModeShortcut() {
        View decor = getWindow().getDecorView();
        // The action item is created and given its default long-press tooltip during layout.
        decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                View button = findViewById(R.id.menu_search);
                if (button != null) button.setOnLongClickListener(ActivityMain.this::showSearchModes);
                return true;
            }
        });
    }

    private void setSearchMode(boolean domains) {
        searchDomains = domains;
        updateSearchContent();
        adapter.setDomainSearch(domains, domainQuery);
        if (menuSearch != null) {
            SearchView search = (SearchView) menuSearch.getActionView();
            search.setQueryHint(getString(domains ? R.string.domain_search_hint : R.string.list_search_apps));
            restoreSearch(currentSearchQuery());
        }
    }

    private void updateSearchContent() {
        if (adapter != null) adapter.setDomainSearch(searchDomains, domainQuery);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (adapter != null) state.putBundle("appListView", adapter.saveViewState());
        state.putBoolean("searchDomains", searchDomains);
        state.putBoolean("searchExpanded", menuSearch != null && menuSearch.isActionViewExpanded());
        state.putString("domainQuery", domainQuery);
    }

    private void resetListFilters() {
        adapter.options.reset();
        domainQuery = "";
        searchDomains = false;
        adapter.setDomainSearch(false, "");
        getIntent().removeExtra(EXTRA_SEARCH);
        if (menuSearch != null) {
            SearchView search = (SearchView) menuSearch.getActionView();
            search.setQuery("", false);
            search.setQueryHint(getString(R.string.list_search_apps));
            search.clearFocus();
            menuSearch.collapseActionView();
        }
        adapter.applyOptions(false);
    }

    private void showAppFilters() {
        AppFilterDialog.show(this, adapter.options, () -> adapter.applyOptions(false), this::resetListFilters);
    }

    private void showAppSort() {
        SortDialog.show(this, R.string.list_sort_apps, ListSort.APPS, adapter.options.sort,
                ListSort.APP_DEFAULT, selected -> {
                    adapter.options.sort = selected;
                    adapter.applyOptions(false);
                });
    }

    private void updateListSummary() {
        AppListOptions options = adapter.options;
        java.util.List<String> active = new java.util.ArrayList<>();
        if (!searchDomains && !options.query.trim().isEmpty()) active.add(getString(R.string.list_search_active, options.query));
        if (options.access != 0) active.add(getResources().getStringArray(R.array.list_access_choices)[options.access]);
        if (options.activity != 0) active.add(getResources().getStringArray(R.array.list_activity_choices)[options.activity]);
        if (options.type != 0) active.add(getResources().getStringArray(R.array.list_type_choices)[options.type]);
        if (!options.includeNoInternet) active.add(getString(R.string.list_filter_internet_only));
        if (!options.includeDisabled) active.add(getString(R.string.list_filter_enabled_only));
        boolean filtered = !active.isEmpty() || searchDomains;
        String summary = getString(R.string.list_app_count, adapter.getItemCount(), adapter.totalCount());
        if (searchDomains) summary += " · " + getString(R.string.matching_domain_count, adapter.matchingDomainCount());
        ((TextView) findViewById(R.id.listSummary)).setText(summary);
        findViewById(R.id.listSummaryRow).setVisibility(filtered ? View.VISIBLE : View.GONE);
        findViewById(R.id.copyMatchingDomains).setVisibility(searchDomains ? View.VISIBLE : View.GONE);
        findViewById(R.id.copyMatchingDomains).setEnabled(adapter.matchingDomainCount() > 0);
        findViewById(R.id.emptyApplications).setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void checkDoze() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final Intent doze = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            if (Util.batteryOptimizing(this) && getPackageManager().resolveActivity(doze, 0) != null) {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                if (!prefs.getBoolean("nodoze", false)) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(R.layout.doze, null, false);
                    final CheckBox cbDontAsk = view.findViewById(R.id.cbDontAsk);
                    dialogDoze = new AlertDialog.Builder(this)
                            .setView(view)
                            .setCancelable(true)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked()).apply();
                                    startActivity(doze);
                                }
                            })
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    prefs.edit().putBoolean("nodoze", cbDontAsk.isChecked()).apply();
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialogInterface) {
                                    dialogDoze = null;
                                    checkDataSaving();
                                }
                            })
                            .create();
                    dialogDoze.show();
                } else
                    checkDataSaving();
            } else
                checkDataSaving();
        }
    }

    private void checkDataSaving() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final Intent settings = new Intent(
                    Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            if (Util.dataSaving(this) && getPackageManager().resolveActivity(settings, 0) != null)
                try {
                    final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    if (!prefs.getBoolean("nodata", false)) {
                        LayoutInflater inflater = LayoutInflater.from(this);
                        View view = inflater.inflate(R.layout.datasaving, null, false);
                        final CheckBox cbDontAsk = view.findViewById(R.id.cbDontAsk);
                        dialogDoze = new AlertDialog.Builder(this)
                                .setView(view)
                                .setCancelable(true)
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        prefs.edit().putBoolean("nodata", cbDontAsk.isChecked()).apply();
                                        startActivity(settings);
                                    }
                                })
                                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        prefs.edit().putBoolean("nodata", cbDontAsk.isChecked()).apply();
                                    }
                                })
                                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                    @Override
                                    public void onDismiss(DialogInterface dialogInterface) {
                                        dialogDoze = null;
                                    }
                                })
                                .create();
                        dialogDoze.show();
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, ex + "\n" + ex.getStackTrace());
                }
        }
    }

    private void showHelpTutorials() {
        if (dialogHelp != null) return;
        View view = getLayoutInflater().inflate(R.layout.help_tutorials, null, false);
        view.findViewById(R.id.helpOpenLegend).setOnClickListener(button -> menu_legend());
        dialogHelp = new AlertDialog.Builder(this)
                .setCustomTitle(CompactUi.header(this, R.string.help_tutorials, 0, 0, null))
                .setView(view)
                .setPositiveButton(R.string.list_done, null)
                .setOnDismissListener(dialog -> dialogHelp = null)
                .create();
        dialogHelp.show();
        DialogSurface.apply(dialogHelp);
    }

    private void menu_legend() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOn, tv, true);
        int colorOn = tv.data;
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        int colorOff = tv.data;

        // Create view
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.legend, null, false);
        ImageView ivLockdownOn = view.findViewById(R.id.ivLockdownOn);
        ImageView ivWifiOn = view.findViewById(R.id.ivWifiOn);
        ImageView ivWifiOff = view.findViewById(R.id.ivWifiOff);
        ImageView ivOtherOn = view.findViewById(R.id.ivOtherOn);
        ImageView ivOtherOff = view.findViewById(R.id.ivOtherOff);
        ImageView ivScreenOn = view.findViewById(R.id.ivScreenOn);
        ImageView ivHostAllowed = view.findViewById(R.id.ivHostAllowed);
        ImageView ivHostBlocked = view.findViewById(R.id.ivHostBlocked);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Drawable wrapLockdownOn = DrawableCompat.wrap(ivLockdownOn.getDrawable());
            Drawable wrapWifiOn = DrawableCompat.wrap(ivWifiOn.getDrawable());
            Drawable wrapWifiOff = DrawableCompat.wrap(ivWifiOff.getDrawable());
            Drawable wrapOtherOn = DrawableCompat.wrap(ivOtherOn.getDrawable());
            Drawable wrapOtherOff = DrawableCompat.wrap(ivOtherOff.getDrawable());
            Drawable wrapScreenOn = DrawableCompat.wrap(ivScreenOn.getDrawable());
            Drawable wrapHostAllowed = DrawableCompat.wrap(ivHostAllowed.getDrawable());
            Drawable wrapHostBlocked = DrawableCompat.wrap(ivHostBlocked.getDrawable());

            DrawableCompat.setTint(wrapLockdownOn, colorOff);
            DrawableCompat.setTint(wrapWifiOn, colorOn);
            DrawableCompat.setTint(wrapWifiOff, colorOff);
            DrawableCompat.setTint(wrapOtherOn, colorOn);
            DrawableCompat.setTint(wrapOtherOff, colorOff);
            DrawableCompat.setTint(wrapScreenOn, colorOn);
            DrawableCompat.setTint(wrapHostAllowed, colorOn);
            DrawableCompat.setTint(wrapHostBlocked, colorOff);
        }


        // Show dialog
        dialogLegend = new AlertDialog.Builder(this)
                .setCustomTitle(SortDialog.title(this, R.string.menu_legend))
                .setPositiveButton(R.string.list_done, null)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        dialogLegend = null;
                    }
                })
                .create();
        dialogLegend.show();
        DialogSurface.apply(dialogLegend);
    }

    //private void menu_lockdown(MenuItem item) {
    //    item.setChecked(!item.isChecked());
    //    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    //    prefs.edit().putBoolean(Rule.PREFERENCE_STRING_LOCKDOWN, item.isChecked()).apply();
    //    ServiceSinkhole.reload(Rule.PREFERENCE_STRING_LOCKDOWN, this, false);
    //}

    private void menu_about() {
        // Create view
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.about, null, false);
        TextView tvVersionName = view.findViewById(R.id.tvVersionName);
        TextView tvVersionCode = view.findViewById(R.id.tvVersionCode);
        Button btnRate = view.findViewById(R.id.btnRate);
        TextView tvEula = view.findViewById(R.id.tvEula);
        TextView tvPrivacy = view.findViewById(R.id.tvPrivacy);

        // Show version
        tvVersionName.setText(Util.getSelfVersionName(this));
        if (!Util.hasValidFingerprint(this))
            tvVersionName.setTextColor(Color.GRAY);
        tvVersionCode.setText(Integer.toString(Util.getSelfVersionCode(this)));

        // Handle license
        tvEula.setMovementMethod(LinkMovementMethod.getInstance());
        tvPrivacy.setMovementMethod(LinkMovementMethod.getInstance());

        // Handle logcat
        view.setOnClickListener(new View.OnClickListener() {
            private short tap = 0;
            private Toast toast = Toast.makeText(ActivityMain.this, "", Toast.LENGTH_SHORT);

            @Override
            public void onClick(View view) {
                tap++;
                if (tap == 7) {
                    tap = 0;
                    toast.cancel();

                    Intent intent = getIntentLogcat();
                    if (intent.resolveActivity(getPackageManager()) != null)
                        startActivityForResult(intent, REQUEST_LOGCAT);

                } else if (tap > 3) {
                    toast.setText(Integer.toString(7 - tap));
                    toast.show();
                }
            }
        });

        // HeartGuard change - removed menu items
        btnRate.setVisibility(View.GONE);
        //// Handle rate
        //btnRate.setVisibility(getIntentRate(this).resolveActivity(getPackageManager()) == null ? View.GONE : View.VISIBLE);
        //btnRate.setOnClickListener(new View.OnClickListener() {
        //    @Override
        //    public void onClick(View view) {
        //        startActivity(getIntentRate(ActivityMain.this));
        //    }
        //});

        // Show dialog
        dialogAbout = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        dialogAbout = null;
                    }
                })
                .create();
        dialogAbout.show();
    }

    // HeartGuard change - removed menu items
    //private void menu_apps() {
    //    startActivity(getIntentApps(this));
    //}

    private static Intent getIntentInvite(Context context) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name));
        intent.putExtra(Intent.EXTRA_TEXT, context.getString(R.string.msg_try) + "\n\nhttps://www.netguard.me/\n\n");
        return intent;
    }

    // HeartGuard change - removed menu items
    //private static Intent getIntentApps(Context context) {
    //    return new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/dev?id=8420080860664580239"));
    //}

    //private static Intent getIntentRate(Context context) {
    //    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.getPackageName()));
    //    if (intent.resolveActivity(context.getPackageManager()) == null)
    //        intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + context.getPackageName()));
    //    return intent;
    //}

    // HeartGuard change - removed menu items
    //private static Intent getIntentSupport() {
    //    Intent intent = new Intent(Intent.ACTION_VIEW);
    //    intent.setData(Uri.parse("https://github.com/M66B/NetGuard/blob/master/FAQ.md"));
    //    return intent;
    //}

    private Intent getIntentLogcat() {
        Intent intent;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            if (Util.isPackageInstalled("org.openintents.filemanager", this)) {
                intent = new Intent("org.openintents.action.PICK_DIRECTORY");
            } else {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=org.openintents.filemanager"));
            }
        } else {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "logcat.txt");
        }
        return intent;
    }

    // HeartGuard change - also updates the main UI on "rules changes"
    private void updateMainUi() {
        // Get enabled
        boolean enabled = RulesManager.getInstance(ActivityMain.this).getPreferenceEnabled(ActivityMain.this);

        // Display disabled warning
        TextView tvDisabled = findViewById(R.id.tvDisabled);
        tvDisabled.setVisibility(enabled ? View.GONE : View.VISIBLE);

        // Check switch state
        SwitchCompat swEnabled = getSupportActionBar().getCustomView().findViewById(R.id.swEnabled);
        if (swEnabled.isChecked() != enabled)
            swEnabled.setChecked(enabled);

        invalidateOptionsMenu();
        updateApplicationList(null);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Update with the manage_system state
        LinearLayout llSystem = findViewById(R.id.llSystem);
        boolean system = RulesManager.getInstance(ActivityMain.this).getPreferenceManageSystem(ActivityMain.this);
        boolean hint = prefs.getBoolean("hint_system", true);
        llSystem.setVisibility(!system && hint ? View.VISIBLE : View.GONE);
    }
}
