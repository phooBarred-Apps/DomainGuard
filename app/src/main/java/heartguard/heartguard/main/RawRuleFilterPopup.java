package heartguard.heartguard.main;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.drawable.DrawableCompat;

/** Two scope headers, each with a separate menu for its three rule types. */
final class RawRuleFilterPopup {
    private final Context context;
    private final RawRuleFilter filter;
    private final Runnable changed;
    private final PopupWindow window;
    private final Button[] headers = new Button[2];
    private final ImageButton[] menus = new ImageButton[2];
    private final View[] underlines = new View[2];
    private final SwitchCompat[] switches = new SwitchCompat[3];
    private final int[] types = {RawRuleFilter.BLOCK, RawRuleFilter.ALLOW, RawRuleFilter.OTHER};
    private final LinearLayout choices;
    private boolean expanded;
    private boolean binding;

    RawRuleFilterPopup(Context context, RawRuleFilter filter, Runnable changed, Runnable dismissed) {
        this.context = context;
        this.filter = filter;
        this.changed = changed;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout headerRow = new LinearLayout(context);
        content.addView(headerRow);
        for (int i = 0; i < 2; i++) {
            final boolean global = i == 1;
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            headerRow.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout row = new LinearLayout(context);
            column.addView(row);
            Button label = CompactUi.button(context, global ? R.string.raw_filter_global : R.string.raw_filter_all,
                    view -> select(global, false));
            label.setId(global ? R.id.rawFilterGlobal : R.id.rawFilterAll);
            label.setTextSize(14);
            label.setMinWidth(0);
            label.setMinimumWidth(0);
            label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            label.setPaddingRelative(dp(16), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton caret = CompactUi.icon(context, R.drawable.ic_dropdown_compact,
                    global ? R.string.raw_filter_global : R.string.raw_filter_all,
                    view -> select(global, !expanded || filter.globalOnly != global));
            caret.setId(global ? R.id.rawFilterGlobalMenu : R.id.rawFilterAllMenu);
            caret.setContentDescription(context.getString(R.string.raw_filter_types_description, label.getText()));
            row.addView(caret, new LinearLayout.LayoutParams(dp(48), dp(48)));
            View underline = new View(context);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(2));
            line.setMargins(dp(6), 0, dp(6), dp(6));
            column.addView(underline, line);
            headers[i] = label; menus[i] = caret; underlines[i] = underline;
        }
        choices = new LinearLayout(context);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(0, 0, 0, dp(6));
        content.addView(choices);
        for (int i = 0; i < types.length; i++) {
            final int type = types[i];
            SwitchCompat toggle = new SwitchCompat(context);
            toggle.setId(new int[]{R.id.rawFilterBlock, R.id.rawFilterAllow, R.id.rawFilterOther}[i]);
            toggle.setTextSize(15);
            toggle.setMinHeight(dp(54));
            toggle.setPaddingRelative(dp(16), dp(6), dp(16), dp(6));
            toggle.setCompoundDrawablePadding(dp(12));
            toggle.setSwitchPadding(dp(12));
            int positive = CompactUi.color(context, R.attr.colorActionPositive);
            int muted = CompactUi.color(context, android.R.attr.textColorSecondary);
            int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
            toggle.setThumbTintList(new ColorStateList(states, new int[]{positive, muted}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{
                    androidx.core.graphics.ColorUtils.setAlphaComponent(positive, 90),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(muted, 75)}));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (binding) return;
                filter.setEnabled(type, checked);
                changed.run();
            });
            choices.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
            switches[i] = toggle;
        }
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content);
        int width = Math.min(dp(360), context.getResources().getDisplayMetrics().widthPixels - dp(24));
        window = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CompactUi.color(context, android.R.attr.colorBackgroundFloating));
        background.setCornerRadius(dp(4));
        window.setBackgroundDrawable(background);
        window.setElevation(dp(8));
        window.setOutsideTouchable(true);
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setOnDismissListener(dismissed::run);
        bind();
    }

    void show(View anchor) { window.showAsDropDown(anchor, -dp(8), 0, Gravity.START); }
    void dismiss() { window.dismiss(); }

    private void select(boolean global, boolean openMenu) {
        filter.globalOnly = global;
        expanded = openMenu;
        changed.run();
        bind();
        window.update(window.getWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void bind() {
        binding = true;
        int accent = CompactUi.color(context, R.attr.colorActionPositive);
        int muted = CompactUi.color(context, android.R.attr.textColorSecondary);
        for (int i = 0; i < 2; i++) {
            boolean selected = filter.globalOnly == (i == 1);
            headers[i].setSelected(selected);
            headers[i].setTextColor(selected ? accent : muted);
            headers[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            underlines[i].setBackgroundColor(selected ? accent : android.graphics.Color.TRANSPARENT);
            menus[i].setImageTintList(ColorStateList.valueOf(selected ? accent : muted));
            menus[i].setRotation(selected && expanded ? 180 : 0);
        }
        choices.setVisibility(expanded ? View.VISIBLE : View.GONE);
        int[] labels = filter.globalOnly
                ? new int[]{R.string.raw_filter_global_blocks, R.string.raw_filter_global_allows, R.string.raw_filter_global_other}
                : new int[]{R.string.raw_filter_blocks, R.string.raw_filter_allows, R.string.raw_filter_other};
        int[] icons = filter.globalOnly
                ? new int[]{R.drawable.host_global_blocked, R.drawable.host_global_allowed, R.drawable.ic_global_rules}
                : new int[]{R.drawable.ic_close_white_24dp, R.drawable.ic_check_white_24dp, R.drawable.ic_other_rules};
        for (int i = 0; i < switches.length; i++) {
            SwitchCompat toggle = switches[i];
            toggle.setText(labels[i]);
            toggle.setChecked((filter.types() & types[i]) != 0);
            Drawable icon = AppCompatResources.getDrawable(context, icons[i]).mutate();
            if (!filter.globalOnly || i == 2) DrawableCompat.setTint(icon, i == 0
                    ? CompactUi.color(context, R.attr.colorActionDestructive) : i == 1 ? accent : muted);
            icon.setBounds(0, 0, dp(24), dp(24));
            toggle.setCompoundDrawablesRelative(icon, null, null, null);
        }
        binding = false;
    }

    static int title(RawRuleFilter filter) {
        switch (filter.types()) {
            case RawRuleFilter.ALL: return filter.globalOnly ? R.string.raw_filter_global : R.string.raw_filter_all;
            case RawRuleFilter.BLOCK: return filter.globalOnly ? R.string.raw_filter_global_blocks : R.string.raw_filter_blocks;
            case RawRuleFilter.ALLOW: return filter.globalOnly ? R.string.raw_filter_global_allows : R.string.raw_filter_allows;
            case RawRuleFilter.OTHER: return filter.globalOnly ? R.string.raw_filter_global_other : R.string.raw_filter_other;
            default: return filter.globalOnly ? R.string.raw_filter_global_mixed : R.string.raw_filter_mixed;
        }
    }

    private int dp(int value) { return CompactUi.dp(context, value); }
}
