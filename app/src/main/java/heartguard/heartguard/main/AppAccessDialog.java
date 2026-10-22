package heartguard.heartguard.main;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

/** Show the actual applicable delay before allowing or blocking the app. */
final class AppAccessDialog {
    static void show(Context context, Rule app, boolean allow) {
        String ruleText = (allow ? "" : "- ") + "allow package:" + app.packageName;
        LinearLayout title = new LinearLayout(context);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(CompactUi.dp(context, 24), CompactUi.dp(context, 24), CompactUi.dp(context, 24), CompactUi.dp(context, 8));
        ImageView icon = new ImageView(context);
        try { icon.setImageDrawable(context.getPackageManager().getApplicationIcon(app.packageName)); }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { icon.setVisibility(View.GONE); }
        title.addView(icon, new LinearLayout.LayoutParams(CompactUi.dp(context, 32), CompactUi.dp(context, 32)));
        TextView question = new TextView(context);
        question.setTextAppearance(context, R.style.TextLarge);
        question.setText(context.getString(allow ? R.string.app_allow_title : R.string.app_block_title, app.name));
        question.setPadding(CompactUi.dp(context, 12), 0, 0, 0);
        title.addView(question, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout body = new LinearLayout(context);
        body.setPadding(CompactUi.dp(context, 24), CompactUi.dp(context, 16), CompactUi.dp(context, 24), CompactUi.dp(context, 8));
        DelayNotice notice = new DelayNotice(context);
        body.addView(notice, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int action = allow ? R.string.title_allow : R.string.title_block;
        Runnable refresh = () -> notice.setDelay(RulesManager.getInstance(context).previewDelay(context, ruleText),
                context.getString(R.string.delay_notice_explanation, context.getString(action)));
        AlertDialog dialog = new AlertDialog.Builder(context).setCustomTitle(title).setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(action, (d, which) -> RulesManager.getInstance(context).queueRuleText(context, ruleText)).create();
        DatabaseHelper.RuleChangedListener listener = () -> body.post(() -> { if (dialog.isShowing()) refresh.run(); });
        dialog.setOnDismissListener(ignored -> DatabaseHelper.getInstance(context).removeRuleChangedListener(listener));
        dialog.show(); DialogSurface.apply(dialog);
        // Adding either an allow or a block follows the existing positive-action color convention.
        CompactUi.actionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        DatabaseHelper.getInstance(context).addRuleChangedListener(listener);
        refresh.run();
    }
}
