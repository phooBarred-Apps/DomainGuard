package heartguard.heartguard.main;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** Only visible during a pending or active global pause. */
final class GlobalPauseBar {
    private final Activity activity;
    private final View strip;
    private final TextView text;
    private final Button action;
    private final ImageView icon;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppPause.Entry entry;
    private Boolean renderedActive;
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (entry != null && entry.time <= System.currentTimeMillis()) refresh();
            else render();
            handler.postDelayed(this, 1000);
        }
    };

    GlobalPauseBar(Activity activity) {
        this.activity = activity;
        strip = activity.findViewById(R.id.globalPauseBar);
        text = activity.findViewById(R.id.globalPauseText);
        action = activity.findViewById(R.id.globalPauseAction);
        icon = activity.findViewById(R.id.globalPauseIcon);
        strip.setBackgroundColor(DelayNotice.surface(activity));
        CompactUi.frameless(action);
        action.setOnClickListener(view -> confirm());
    }

    void start() { running = true; refresh(); handler.removeCallbacks(tick); handler.post(tick); }
    void stop() { running = false; handler.removeCallbacks(tick); }

    void refresh() {
        entry = null;
        for (AppPause.Entry candidate : AppPause.read(activity, null))
            if (entry == null || (candidate.active && !entry.active)
                    || (candidate.active == entry.active && (candidate.active ? candidate.time > entry.time : candidate.time < entry.time)))
                entry = candidate;
        render();
    }

    private void render() {
        strip.setVisibility(entry == null ? View.GONE : View.VISIBLE);
        if (entry == null) return;
        String label = activity.getString(entry.active ? R.string.global_pause_active : R.string.global_pause_pending,
                countdown(Math.max(1, (entry.time - System.currentTimeMillis() + 999) / 1000)));
        if (!android.text.TextUtils.equals(text.getText(), label)) text.setText(label);
        if (renderedActive == null || renderedActive != entry.active) {
            renderedActive = entry.active;
            icon.setImageResource(entry.active ? R.drawable.ic_app_pause : R.drawable.ic_delay_clock);
            action.setText(entry.active ? R.string.global_pause_resume : R.string.global_pause_cancel);
            action.setContentDescription(activity.getString(entry.active ? R.string.app_pause_resume : R.string.app_pause_cancel_pending));
            if (entry.active) CompactUi.actionButton(action); else CompactUi.destructiveButton(action);
        }
    }

    private String countdown(long seconds) {
        int divisor = seconds >= 86400 ? 86400 : seconds >= 3600 ? 3600 : seconds >= 60 ? 60 : 1;
        int resource = divisor == 86400 ? R.plurals.pause_short_days : divisor == 3600 ? R.plurals.pause_short_hours
                : divisor == 60 ? R.plurals.pause_short_minutes : R.plurals.pause_short_seconds;
        int value = (int) ((seconds + divisor - 1) / divisor);
        return activity.getResources().getQuantityString(resource, value, value);
    }

    private void confirm() {
        refresh();
        if (entry == null) return;
        AppPause.Entry shown = entry;
        List<AppPause.Entry> snapshot = AppPause.read(activity, null);
        String date = new SimpleDateFormat("M/d hh:mm:ss a", Locale.US).format(new java.util.Date(shown.time));
        String message = shown.active ? activity.getString(R.string.global_pause_resume_body, date)
                : activity.getString(R.string.global_pause_cancel_body, RuleDelay.duration(activity, shown.rule.seconds, true), date);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(shown.active ? R.string.global_pause_resume_title : R.string.global_pause_cancel_title)
                .setMessage(message).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.confirm_yes, (d, which) -> {
                    List<AppPause.Entry> current = AppPause.read(activity, null);
                    if (!same(snapshot, current)) {
                        Toast.makeText(activity, R.string.global_pause_changed, Toast.LENGTH_SHORT).show();
                        refresh(); return;
                    }
                    AppPause.resume(activity, null);
                }).create();
        dialog.show(); DialogSurface.apply(dialog);
        CompactUi.dismissButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        if (shown.active) CompactUi.actionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        else CompactUi.destructiveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
    }

    private static boolean same(List<AppPause.Entry> before, List<AppPause.Entry> after) {
        if (before.size() != after.size()) return false;
        for (AppPause.Entry first : before) {
            boolean found = false;
            for (AppPause.Entry second : after)
                found |= first.active == second.active && first.time == second.time
                        && first.rule.getRuleText().equals(second.rule.getRuleText());
            if (!found) return false;
        }
        return true;
    }
}
