package heartguard.heartguard.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.util.List;

final class AppPauseDialog {
    static void show(Context context, String packageName, String appName) {
        View view = LayoutInflater.from(context).inflate(R.layout.app_pause, null);
        EditText input = view.findViewById(R.id.app_pause_amount);
        Spinner unit = view.findViewById(R.id.app_pause_unit);
        TextView preview = view.findViewById(R.id.app_pause_preview);
        TextView status = view.findViewById(R.id.app_pause_status);
        android.widget.Button stateAction = view.findViewById(R.id.app_pause_state_action);
        CompactUi.frameless(stateAction);
        ((TextView) view.findViewById(R.id.app_pause_name)).setText(appName);
        try { ((android.widget.ImageView) view.findViewById(R.id.app_pause_icon)).setImageDrawable(context.getPackageManager().getApplicationIcon(packageName)); }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { view.findViewById(R.id.app_pause_icon).setVisibility(View.GONE); }
        String[] related = null;
        try { related = context.getPackageManager().getPackagesForUid(context.getPackageManager().getApplicationInfo(packageName, 0).uid); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        TextView shared = view.findViewById(R.id.app_pause_shared);
        if (related != null && related.length > 1) {
            shared.setVisibility(View.VISIBLE);
            shared.setText(context.getString(R.string.app_pause_shared, android.text.TextUtils.join(", ", related)));
        }
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.app_pause_title).setView(view)
                .setPositiveButton(R.string.app_pause_action, null).setNegativeButton(android.R.string.cancel, null).create();
        DurationInput[] duration = new DurationInput[1];
        Runnable refresh = () -> {
            List<AppPause.Entry> entries = AppPause.read(context, packageName);
            boolean occupied = !entries.isEmpty();
            boolean active = false;
            for (AppPause.Entry entry : entries) active |= entry.active;
            status.setVisibility(occupied ? View.VISIBLE : View.GONE);
            if (occupied) status.setText(entries.get(0).label(context));
            input.setEnabled(!occupied); unit.setEnabled(!occupied);
            stateAction.setVisibility(occupied ? View.VISIBLE : View.GONE);
            stateAction.setText(active ? R.string.app_pause_resume : R.string.app_pause_cancel_pending);
            stateAction.setCompoundDrawablesRelativeWithIntrinsicBounds(active ? R.drawable.ic_app_resume : 0, 0, 0, 0);
            stateAction.setCompoundDrawablePadding(CompactUi.dp(context, 8));
            if (active) CompactUi.actionButton(stateAction); else CompactUi.destructiveButton(stateAction);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(occupied ? View.GONE : View.VISIBLE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(occupied ? R.string.list_done : android.R.string.cancel);
            DialogSurface.applyButtonStyles(dialog);
            if (occupied) duration[0].setSeconds(entries.get(0).rule.seconds);
            try {
                int seconds = occupied ? entries.get(0).rule.seconds : seconds(input, unit);
                int delay = RulesManager.getInstance(context).getSharedUidDelay(context, packageName, false);
                preview.setText(active ? context.getString(R.string.app_pause_active)
                        : context.getString(R.string.app_pause_duration, RuleDelay.duration(context, seconds))
                        + (!occupied && delay > 0 ? "\n" + context.getString(R.string.app_pause_delay, RuleDelay.duration(context, delay)) : ""));
            } catch (IllegalArgumentException invalid) { preview.setText(R.string.app_pause_invalid); }
        };
        duration[0] = new DurationInput(input, unit, 300, () -> { if (dialog.isShowing()) refresh.run(); });
        DatabaseHelper.RuleChangedListener listener = () -> view.post(() -> { if (dialog.isShowing()) refresh.run(); });
        dialog.setOnDismissListener(ignored -> DatabaseHelper.getInstance(context).removeRuleChangedListener(listener));
        dialog.show(); DialogSurface.apply(dialog);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                int seconds = seconds(input, unit);
                if (!AppPause.read(context, packageName).isEmpty()) { refresh.run(); return; }
                RulesManager.getInstance(context).queueRuleText(context, AppPauseRule.text(packageName, seconds));
                Toast.makeText(context, R.string.rule_queued, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (IllegalArgumentException invalid) { input.setError(context.getString(R.string.app_pause_invalid)); }
        });
        stateAction.setOnClickListener(button -> { AppPause.resume(context, packageName); dialog.dismiss(); });
        DatabaseHelper.getInstance(context).addRuleChangedListener(listener);
        refresh.run();
    }

    private static int seconds(EditText input, Spinner unit) {
        int seconds = RuleDelay.parseSeconds(input.getText().toString(), (RuleDelay.Unit) unit.getSelectedItem());
        if (seconds == 0) throw new IllegalArgumentException();
        return seconds;
    }
}
