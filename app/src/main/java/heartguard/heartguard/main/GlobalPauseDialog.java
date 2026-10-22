package heartguard.heartguard.main;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

final class GlobalPauseDialog {
    static void show(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.global_pause, null);
        LinearLayout body = view.findViewById(R.id.global_pause_content);
        SwitchCompat autoResume = view.findViewById(R.id.global_pause_auto_resume);
        DelayNotice notice = new DelayNotice(context);
        body.addView(notice);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.global_off_title).setView(view)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.global_off_action, null).create();
        DurationInput[] input = new DurationInput[1];
        Runnable refresh = () -> {
            boolean pause = autoResume.isChecked();
            view.findViewById(R.id.global_off_description).setVisibility(pause ? View.GONE : View.VISIBLE);
            view.findViewById(R.id.global_pause_duration).setVisibility(pause ? View.VISIBLE : View.GONE);
            int seconds = 300;
            android.widget.TextView preview = view.findViewById(R.id.global_pause_preview);
            try {
                if (input[0] != null) seconds = input[0].seconds();
                if (seconds < 1) throw new IllegalArgumentException();
                preview.setText(RuleDelay.duration(context, seconds));
            } catch (IllegalArgumentException invalid) { preview.setText(R.string.app_pause_invalid); }
            int delay = RulesManager.getInstance(context).getGlobalProtectionDelay();
            String detail = pause ? context.getString(R.string.delay_notice_pause_explanation, RuleDelay.duration(context, seconds, true))
                    : context.getString(R.string.delay_notice_explanation, context.getString(R.string.global_off_action));
            notice.setDelay(delay, detail);
            if (dialog.isShowing()) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(pause ? R.string.app_pause_action : R.string.global_off_action);
                CompactUi.destructiveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            }
        };
        input[0] = new DurationInput(view.findViewById(R.id.global_pause_amount), view.findViewById(R.id.global_pause_unit), 300, refresh);
        autoResume.setOnCheckedChangeListener((button, checked) -> refresh.run());
        DatabaseHelper.RuleChangedListener listener = () -> view.post(() -> { if (dialog.isShowing()) refresh.run(); });
        dialog.setOnDismissListener(ignored -> DatabaseHelper.getInstance(context).removeRuleChangedListener(listener));
        dialog.show();
        DialogSurface.apply(dialog);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            RulesManager rules = RulesManager.getInstance(context);
            if (!rules.getPreferenceEnabled(context)) { dialog.dismiss(); return; }
            if (autoResume.isChecked()) {
                if (!AppPause.read(context, null).isEmpty()) {
                    Toast.makeText(context, R.string.global_pause_changed, Toast.LENGTH_SHORT).show();
                    dialog.dismiss(); return;
                }
                try {
                    int seconds = input[0].seconds();
                    if (seconds < 1) throw new IllegalArgumentException();
                    rules.queueRuleText(context, AppPauseRule.text(null, seconds));
                } catch (IllegalArgumentException invalid) {
                    input[0].amount.setError(context.getString(R.string.app_pause_invalid)); return;
                }
            } else rules.queueRuleText(context, "- feature enabled");
            Toast.makeText(context, R.string.rule_queued, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        DatabaseHelper.getInstance(context).addRuleChangedListener(listener);
        refresh.run();
    }
}
