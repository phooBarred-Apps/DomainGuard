package heartguard.heartguard.main;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Activity to display all current/pending rules in a window
// Much implementation copied from ActivityForwarding (because why not)

public class ActivityRulesList extends AppCompatActivity {
    private static final String TAG = "NetGuard.ActRulesList";

    private static final int REQUEST_LOGFILE = 1;

    private ListView lvRulesList;
    private AdapterRulesList adapter;
    private String findQuery = "";
    private String searchableRules = "";
    private final List<Integer> ruleStarts = new ArrayList<>();
    private final List<Long> ruleIds = new ArrayList<>();
    private int match = -1;
    private int matchRow = -1;
    private boolean searchExpanded;
    private final RawRuleFilter ruleFilter = new RawRuleFilter();
    private TextView filterTitle;
    private Drawable filterCaret;
    private RawRuleFilterPopup filterPopup;

    private DatabaseHelper.RuleChangedListener listener = new DatabaseHelper.RuleChangedListener() {
        @Override
        public void onChanged() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (adapter != null) refreshRules();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rules_list);

        getSupportActionBar().setTitle(R.string.rules_list);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        lvRulesList = findViewById(R.id.lvRulesList);
        adapter = new AdapterRulesList(this, null);
        lvRulesList.setAdapter(adapter);
        lvRulesList.setEmptyView(findViewById(R.id.rawRulesEmpty));
        if (savedInstanceState != null) {
            findQuery = savedInstanceState.getString("findQuery", "");
            searchExpanded = savedInstanceState.getBoolean("searchExpanded");
            ruleFilter.globalOnly = savedInstanceState.getBoolean("ruleFilterGlobal");
            ruleFilter.allTypes = savedInstanceState.getInt("ruleFilterAllTypes", RawRuleFilter.ALL) & RawRuleFilter.ALL;
            ruleFilter.globalTypes = savedInstanceState.getInt("ruleFilterGlobalTypes", RawRuleFilter.ALL) & RawRuleFilter.ALL;
        }
        setupFilterTitle();
        refreshRules();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        findViewById(R.id.rawPrevious).setOnClickListener(view -> findMatch(true, false));
        findViewById(R.id.rawNext).setOnClickListener(view -> findMatch(false, false));
        lvRulesList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Cursor cursor = (Cursor)adapter.getItem(position);

                int colRuleText = cursor.getColumnIndexOrThrow("ruletext");
                int colEnacted = cursor.getColumnIndexOrThrow("enacted");

                final String ruletext = cursor.getString(colRuleText);
                final String displayableRuletext = getDisplayTextFromRuletext(ruletext, 1, 0);
                boolean enacted = cursor.getInt(colEnacted) > 0;
                //Log.w(TAG, "clicked id " + Long.toString(id) + " or, from cursor, " + Long.toString(otherid));
                PopupMenu popup = new PopupMenu(ActivityRulesList.this, view);
                popup.inflate(R.menu.rules_list_popup);
                CompactUi.destructiveMenuItem(ActivityRulesList.this, popup.getMenu().findItem(R.id.menu_ruleslist_popup_delete));
                CompactUi.destructiveMenuItem(ActivityRulesList.this, popup.getMenu().findItem(R.id.menu_ruleslist_popup_abandon));
                if (!enacted) {
                    popup.getMenu().removeItem(R.id.menu_ruleslist_popup_delete);
                }
                if (enacted) {
                    popup.getMenu().removeItem(R.id.menu_ruleslist_popup_abandon);
                }
                popup.getMenu().findItem(R.id.menu_ruleslist_popup_title).setTitle(displayableRuletext);
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem menuItem) {
                        int menuitem = menuItem.getItemId();
                        if (menuitem == R.id.menu_ruleslist_popup_delete) {
                            String message = ActivityRulesList.this.getString(R.string.confirm_delete_rule, displayableRuletext);
                            Util.confirmRemoval(ActivityRulesList.this, message, new Util.DoubtListener() {
                                @Override
                                public void onSure() {
                                    try {
                                        RulesManager.getInstance(ActivityRulesList.this).queueRuleText(ActivityRulesList.this, "- " + ruletext);
                                    } catch (Throwable e) {
                                        Log.e(TAG, "Got exception: " + e);
                                    }
                                }
                            });
                            return true;
                        } else if (menuitem == R.id.menu_ruleslist_popup_abandon) {
                            String message = ActivityRulesList.this.getString(R.string.confirm_abandon_rule, displayableRuletext);
                            Util.confirmRemoval(ActivityRulesList.this, message, new Util.DoubtListener() {
                                @Override
                                public void onSure() {
                                    try {
                                        RulesManager.getInstance(ActivityRulesList.this).queueRuleText(ActivityRulesList.this, "abandon " + ruletext);
                                    } catch (Throwable e) {
                                        Log.e(TAG, "Got exception: " + e);
                                    }
                                }
                            });
                            return true;
                        } else if (menuitem == R.id.menu_ruleslist_popup_clipboard) {
                            ClipboardManager clipboard = (ClipboardManager) ActivityRulesList.this.getSystemService(Context.CLIPBOARD_SERVICE);
                            ClipData clip = ClipData.newPlainText("netguard", displayableRuletext);
                            clipboard.setPrimaryClip(clip);
                            return true;
                        }

                        return false;
                    }
                });
                popup.show();
            }
        });
    }

    // Rules changed listener is added only in onResume and not onCreate - is that good?
    // Doing it the way ActivityForwarding and ActivityLog do
    @Override
    protected void onResume() {
        super.onResume();
        DatabaseHelper.getInstance(this).addRuleChangedListener(listener);
        if (adapter != null) {
            refreshRules();
        }
    }

    @Override
    protected void onPause() {
        if (filterPopup != null) filterPopup.dismiss();
        super.onPause();
        DatabaseHelper.getInstance(this).removeRuleChangedListener(listener);
    }

    @Override
    protected void onDestroy() {
        if (adapter != null) {
            adapter.changeCursor(null);
        }
        adapter = null;
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.rulelistmenu, menu);
        androidx.appcompat.widget.SearchView search = new androidx.appcompat.widget.SearchView(this);
        search.setQueryHint(getString(R.string.raw_find));
        MenuItem item = menu.add(0, R.id.raw_search, 0, R.string.raw_find);
        item.setIcon(R.drawable.ic_search_white_24dp);
        item.setActionView(search);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            public boolean onMenuItemActionExpand(MenuItem ignored) {
                if (filterPopup != null) filterPopup.dismiss();
                searchExpanded = true;
                findViewById(R.id.rawSearchNavigation).setVisibility(View.VISIBLE);
                return true;
            }
            public boolean onMenuItemActionCollapse(MenuItem ignored) {
                searchExpanded = false;
                findQuery = "";
                showMatch(-1, false);
                findViewById(R.id.rawSearchNavigation).setVisibility(View.GONE);
                return true;
            }
        });
        search.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
            public boolean onQueryTextSubmit(String text) { findMatch(false, false); return true; }
            public boolean onQueryTextChange(String text) { findQuery = text; findMatch(false, true); return true; }
        });
        if (searchExpanded) {
            String restoredQuery = findQuery;
            item.expandActionView();
            search.setQuery(restoredQuery, false);
            search.clearFocus();
        }
        return true;
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("findQuery", findQuery);
        state.putBoolean("searchExpanded", searchExpanded);
        state.putBoolean("ruleFilterGlobal", ruleFilter.globalOnly);
        state.putInt("ruleFilterAllTypes", ruleFilter.allTypes);
        state.putInt("ruleFilterGlobalTypes", ruleFilter.globalTypes);
    }

    private void setupFilterTitle() {
        Context toolbarContext = getSupportActionBar().getThemedContext();
        filterTitle = new androidx.appcompat.widget.AppCompatTextView(toolbarContext);
        filterTitle.setId(R.id.rawFilterTitle);
        filterTitle.setTextAppearance(toolbarContext, androidx.appcompat.R.style.TextAppearance_AppCompat_Widget_ActionBar_Title);
        filterTitle.setSingleLine(true);
        filterTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filterTitle.setGravity(Gravity.CENTER_VERTICAL);
        filterTitle.setCompoundDrawablePadding(CompactUi.dp(this, 6));
        filterTitle.setPaddingRelative(0, 0, CompactUi.dp(this, 8), 0);
        CompactUi.frameless(filterTitle);
        filterCaret = androidx.appcompat.content.res.AppCompatResources.getDrawable(toolbarContext, R.drawable.ic_rule_filter_caret).mutate();
        androidx.core.graphics.drawable.DrawableCompat.setTint(filterCaret, filterTitle.getCurrentTextColor());
        int size = CompactUi.dp(this, 24);
        filterCaret.setBounds(0, 0, size, size);
        filterTitle.setCompoundDrawablesRelative(null, null, filterCaret, null);
        filterTitle.setOnClickListener(view -> {
            if (filterPopup != null) { filterPopup.dismiss(); return; }
            filterPopup = new RawRuleFilterPopup(this, ruleFilter, this::refreshRules, () -> {
                filterPopup = null;
                filterCaret.setLevel(0);
            });
            filterCaret.setLevel(10000);
            filterPopup.show(filterTitle);
        });
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        getSupportActionBar().setCustomView(filterTitle, new androidx.appcompat.app.ActionBar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL));
        getSupportActionBar().setDisplayShowCustomEnabled(true);
    }

    private Cursor filteredRules() {
        try (Cursor all = DatabaseHelper.getInstance(this).getAllRulesSorted()) {
            MatrixCursor filtered = new MatrixCursor(all.getColumnNames());
            int textColumn = all.getColumnIndexOrThrow("ruletext");
            while (all.moveToNext()) {
                if (!ruleFilter.matches(all.getString(textColumn))) continue;
                Object[] row = new Object[all.getColumnCount()];
                for (int column = 0; column < row.length; column++) {
                    switch (all.getType(column)) {
                        case Cursor.FIELD_TYPE_INTEGER: row[column] = all.getLong(column); break;
                        case Cursor.FIELD_TYPE_FLOAT: row[column] = all.getDouble(column); break;
                        case Cursor.FIELD_TYPE_BLOB: row[column] = all.getBlob(column); break;
                        case Cursor.FIELD_TYPE_STRING: row[column] = all.getString(column); break;
                        default: row[column] = null;
                    }
                }
                filtered.addRow(row);
            }
            return filtered;
        }
    }

    private void refreshRules() {
        int first = lvRulesList.getFirstVisiblePosition();
        long firstId = first < ruleIds.size() ? ruleIds.get(first) : -1;
        int firstTop = lvRulesList.getChildCount() > 0 ? lvRulesList.getChildAt(0).getTop() : lvRulesList.getPaddingTop();
        long previousId = matchRow < 0 ? -1 : ruleIds.get(matchRow);
        int previousOffset = matchRow < 0 ? 0 : match - ruleStarts.get(matchRow);
        // Clear the old highlight before the new cursor notifies the ListView.
        matchRow = -1;
        adapter.changeCursor(filteredRules());
        int title = RawRuleFilterPopup.title(ruleFilter);
        filterTitle.setText(title);
        filterTitle.setContentDescription(getString(R.string.raw_filter_description, getString(title)));
        ((TextView) findViewById(R.id.rawRulesEmpty)).setText(!ruleFilter.globalOnly && ruleFilter.types() == RawRuleFilter.ALL
                ? R.string.raw_rules_empty : R.string.raw_filter_empty);
        Cursor cursor = adapter.getCursor();
        ruleStarts.clear();
        ruleIds.clear();
        StringBuilder text = new StringBuilder();
        int from = 0;
        if (cursor != null) {
            int originalPosition = cursor.getPosition();
            cursor.moveToPosition(-1);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                if (id == previousId) from = text.length() + previousOffset;
                ruleIds.add(id);
                ruleStarts.add(text.length());
                // Search only what is displayed: partner secrets stay excluded.
                text.append(getDisplayTextFromRuleCursor(cursor)).append('\n');
            }
            cursor.moveToPosition(originalPosition);
        }
        searchableRules = text.toString();
        showMatch(RuleTextSearch.find(searchableRules, findQuery, from, false), !findQuery.isEmpty());
        if (findQuery.isEmpty()) {
            int position = ruleIds.indexOf(firstId);
            lvRulesList.setSelectionFromTop(Math.max(0, position), position < 0 ? lvRulesList.getPaddingTop() : firstTop);
        }
    }

    private void findMatch(boolean backwards, boolean restart) {
        int from = restart || match < 0 ? 0 : match + (backwards ? -1 : 1);
        showMatch(RuleTextSearch.find(searchableRules, findQuery, from, backwards), true);
    }

    private void showMatch(int offset, boolean reveal) {
        match = offset;
        matchRow = -1;
        TextView status = findViewById(R.id.rawSearchStatus);
        if (match < 0) status.setText(findQuery.isEmpty() ? "" : getString(R.string.raw_no_match));
        else {
            int index = Collections.binarySearch(ruleStarts, match);
            matchRow = index >= 0 ? index : -index - 2;
            status.setText(getString(R.string.raw_rule_found, matchRow + 1));
        }
        adapter.notifyDataSetChanged();
        if (reveal && matchRow >= 0) lvRulesList.setSelectionFromTop(matchRow, lvRulesList.getPaddingTop());
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case R.id.menu_rules_list_add:
                launchAddRulePage(ActivityRulesList.this);
                return true;
            case R.id.copy_all_to_clipboard:
                allRulesToClipboard();
                return true;
            case R.id.copy_all_to_text:
                allRulesToText();
                return true;
            case R.id.enter_expedite_password:
                launchExpeditePage(this);
                return true;
            case R.id.enter_multiple_rules:
                startActivity(new Intent(this, ActivityRulesEntry.class));
                return true;
            case R.id.raw_edit:
                Intent i = new Intent(this, ActivityRulesEntry.class);
                i.putExtra(ActivityRulesEntry.SPECIFY_MODE, ActivityRulesEntry.MODE_EDIT_RULES);
                startActivity(i);
                return true;
            case R.id.add_expedite_partner:
                launchAddPartnerPage(this);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    // Launch a dialog to add an arbitrary rule
    private void launchAddRulePage(final Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.addrule, null, false);
        final EditText etRuleText = view.findViewById(R.id.etRuleText);

        AlertDialog dialog;
        dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newruletext = etRuleText.getText().toString();
                        Log.w(TAG, String.format("click yes: newruletext=\"%s\"", newruletext));

                        try {
                            RulesManager.getInstance(context).queueRuleText(context, newruletext);
                        } catch (Throwable t) {
                            Log.w(TAG, String.format("New rule \"%s\" got exception %s", newruletext, t.toString()));
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

    // Launch a dialog to expedite
    private void launchExpeditePage(final Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.expedite, null, false);
        final EditText etPasscode = view.findViewById(R.id.etPasscode);

        AlertDialog dialog;
        dialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String passcode = etPasscode.getText().toString();

                        RulesManager.getInstance(context).enterExpeditePassword(context, passcode);

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

    private String allRulesToString() {
        int num = 0;
        String message = "";
        try (Cursor cursor = DatabaseHelper.getInstance(this).getAllRulesSorted()) {
            while (cursor.moveToNext()) {
                if (num > 0) {
                    message += "\n";
                }
                num += 1;

                message += getDisplayTextFromRuleCursor(cursor);
            }
        }

        return message;
    }

    private void allRulesToClipboard() {
        String message = allRulesToString();
        ClipboardManager clipboard = (ClipboardManager) ActivityRulesList.this.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("netguard", message);
        clipboard.setPrimaryClip(clip);
    }

    private Intent getIntentOutputRules() {
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
            intent.putExtra(Intent.EXTRA_TITLE, "heartguard_rules.txt");
        }
        return intent;
    }

    private void allRulesToText() {
        Intent intent = getIntentOutputRules();
        if (intent.resolveActivity(getPackageManager()) != null)
            startActivityForResult(intent, REQUEST_LOGFILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));

        if (requestCode == REQUEST_LOGFILE) {
            // Write rules to file
            if (resultCode == RESULT_OK) {
                Uri target = data.getData();
                if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                    target = Uri.parse(target + "/logcat.txt");
                Log.i(TAG, "Export URI=" + target);
                //Util.sendLogcat(target, this);
                OutputStream out = null;
                try {
                    Log.i(TAG, "Writing logcat URI=" + target);
                    out = getContentResolver().openOutputStream(target);
                    out.write(allRulesToString().getBytes());
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                } finally {
                    if (out != null)
                        try {
                            out.close();
                        } catch (IOException ignored) {
                        }
                }
            }
        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // Translate stored ruletext into displayable ruletext
    // i.e. hide passwords, and label pending rules as pending
    // If you don't want rules to be labeled as pending, set enacted=1 and enactTime=0
    public static String getDisplayTextFromRuletext(String ruletext, int enacted, long enactTime) {
        // Don't show the user the secret information contained in expedite partners!
        ruletext = RulesManager.sanitizeRuletext(ruletext);

        if (enacted > 0) {
            return ruletext;
        } else {
            SimpleDateFormat x = new SimpleDateFormat("LLL dd - HH:mm:ss");
            String timemessage = x.format(new Date(enactTime));
            return "### " + ruletext + " (enacts at " + timemessage + ")";
        }
    }

    public static String getDisplayTextFromRuleCursor(Cursor cursor) {
        int colRuleText = cursor.getColumnIndexOrThrow("ruletext");
        int colEnacted = cursor.getColumnIndexOrThrow("enacted");
        int colEnactTime = cursor.getColumnIndexOrThrow("enact_time");

        String ruletext = cursor.getString(colRuleText);
        int enacted = cursor.getInt(colEnacted);
        long enactTime = cursor.getLong(colEnactTime);

        return getDisplayTextFromRuletext(ruletext, enacted, enactTime);
    }

    private class AdapterRulesList extends CursorAdapter {

        public AdapterRulesList(Context context, Cursor cursor) {
            super(context, cursor, 0);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return LayoutInflater.from(context).inflate(R.layout.rule_display, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            TextView tvRuleText = view.findViewById(R.id.tvRuleText);
            String text = ActivityRulesList.getDisplayTextFromRuleCursor(cursor);
            if (cursor.getPosition() == matchRow) {
                android.text.SpannableString highlighted = new android.text.SpannableString(text);
                int start = match - ruleStarts.get(matchRow);
                highlighted.setSpan(new android.text.style.BackgroundColorSpan(0x6680cbc4),
                        start, Math.min(text.length(), start + findQuery.length()), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvRuleText.setText(highlighted);
            } else tvRuleText.setText(text);
        }
    }

    // Give a random 16-character Base32 key as a suggestion
    String getRandomSecretKey() {
        Random r = new Random();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String key = "";
        for (int i = 0; i < 16; i++) {
            key += chars.charAt(r.nextInt(chars.length()));
        }
        return key;
    }

    // Check if the secret key works
    boolean isSecretKeyOkay(String secretKey) {
        return secretKey.matches("[A-Z2-7]+");
    }

    // Launch a dialog to add an expedite partner
    private void launchAddPartnerPage(final Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.addpartner, null, false);
        final EditText etPartnerNickname = view.findViewById(R.id.partner_nickname);
        final EditText etSecretKey = view.findViewById(R.id.partner_secret_key);

        etSecretKey.setText(getRandomSecretKey());

        final AlertDialog alertDialog;
        alertDialog = new AlertDialog.Builder(context)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // No action - this gets overridden
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
        // Override the onClickListener as soon as this dialog is displayed
        // Otherwise, it will always get dismissed
        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                Button b = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String nickname = etPartnerNickname.getText().toString();
                        String secretKey = etSecretKey.getText().toString();

                        // Nickname - no leading whitespace, and middle whitespace turned into underscores
                        nickname = nickname.trim().replaceAll("\\s+", "_");
                        if (nickname.length() == 0) {
                            nickname = getString(R.string.accountability_partner_nickname);
                        }
                        etPartnerNickname.setText(nickname);

                        //Secret Key - must be base 32, no whitespace
                        secretKey = secretKey.trim().toUpperCase();
                        etSecretKey.setText(secretKey);
                        if (!isSecretKeyOkay(secretKey)) {
                            Toast.makeText(ActivityRulesList.this, R.string.secret_key_rules, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        String ruletext = "partner name:" + nickname + " totp:" + secretKey;
                        RulesManager.getInstance(context).queueRuleText(context, ruletext);
                        alertDialog.dismiss();
                    }
                });
            }
        });
        alertDialog.show();
    }
}
