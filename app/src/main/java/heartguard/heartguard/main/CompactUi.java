package heartguard.heartguard.main;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.TooltipCompat;

/** Shared sizing and accessible, frameless controls. */
final class CompactUi {
    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static void frameless(View view) {
        if (view instanceof ImageButton)
            view.setBackground(new CompactImageButton.Feedback(view.getContext()));
        else {
            TypedValue background = new TypedValue();
            view.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
            view.setBackgroundResource(background.resourceId);
        }
        view.setElevation(0);
        view.setStateListAnimator(null);
        view.setMinimumHeight(dp(view.getContext(), 48));
        if (view instanceof Button) ((Button) view).setAllCaps(false);
    }

    static Button button(Context context, int label, View.OnClickListener click) {
        Button button = new Button(context);
        button.setText(label);
        frameless(button);
        if (label == R.string.list_set_default) actionButton(button);
        button.setOnClickListener(click);
        return button;
    }

    static void dismissButton(Button button) {
        android.content.res.TypedArray colors = button.getContext().obtainStyledAttributes(
                new int[]{android.R.attr.textColorSecondary});
        try { button.setTextColor(colors.getColorStateList(0)); }
        finally { colors.recycle(); }
    }

    static int color(Context context, int attribute) {
        android.content.res.TypedArray colors = context.obtainStyledAttributes(new int[]{attribute});
        try { return colors.getColor(0, android.graphics.Color.TRANSPARENT); }
        finally { colors.recycle(); }
    }

    static void actionButton(Button button) {
        buttonColor(button, R.attr.colorActionPositive);
    }

    static void destructiveButton(Button button) {
        buttonColor(button, R.attr.colorActionDestructive);
    }

    static void destructiveMenuItem(Context context, android.view.MenuItem item) {
        if (item == null) return;
        android.text.SpannableString title = new android.text.SpannableString(item.getTitle());
        title.setSpan(new android.text.style.ForegroundColorSpan(color(context, R.attr.colorActionDestructive)),
                0, title.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        item.setTitle(title);
    }

    private static void buttonColor(Button button, int attribute) {
        int enabled = color(button.getContext(), attribute);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{androidx.core.graphics.ColorUtils.setAlphaComponent(enabled, 100), enabled}));
    }

    static ImageButton icon(Context context, int image, int description, View.OnClickListener click) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(image);
        button.setContentDescription(context.getString(description));
        TooltipCompat.setTooltipText(button, context.getString(description));
        button.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        frameless(button);
        button.setOnClickListener(click);
        return button;
    }

    static LinearLayout header(Context context, int label, int image, int description, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), dp(context, 12), dp(context, 8), dp(context, 4));
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextAppearance(context, R.style.TextLarge);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (image != 0) row.addView(icon(context, image, description, click));
        return row;
    }

    static void help(Context context, int title, CharSequence text) {
        ScrollView scroll = new ScrollView(context);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        TextView body = new TextView(context);
        body.setText(text);
        body.setTextAppearance(context, R.style.TextMedium);
        body.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 12));
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(context).setCustomTitle(header(context, title, 0, 0, null))
                .setView(scroll).setPositiveButton(R.string.list_done, null).create();
        dialog.show();
        DialogSurface.apply(dialog);
    }
}
