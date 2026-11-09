package heartguard.heartguard.main;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** The switch represents remaining protection, even while a global change is partly effective. */
final class GlobalProtectionStatus {
    private final Activity activity;
    private final View toggle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ObjectAnimator animation;
    private boolean running;
    private Snapshot state;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            render();
            handler.postDelayed(this, 1000);
        }
    };

    GlobalProtectionStatus(Activity activity, View toggle) {
        this.activity = activity; this.toggle = toggle;
    }

    void start() { running = true; refresh(); handler.removeCallbacks(tick); handler.post(tick); }
    void stop() { running = false; handler.removeCallbacks(tick); stopAnimation(); }
    void refresh() { state = read(activity); render(); }

    private void stopAnimation() {
        if (animation != null) { animation.cancel(); animation = null; }
        toggle.setAlpha(1f);
    }

    private void render() {
        boolean changing = state != null && state.end > System.currentTimeMillis();
        toggle.setContentDescription(activity.getString(changing ? R.string.global_status_switch : R.string.app_name));
        boolean animate = running && changing && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
        if (animate && animation == null) {
            animation = ObjectAnimator.ofFloat(toggle, View.ALPHA, 1f, .55f);
            animation.setDuration(1800);
            animation.setRepeatCount(ValueAnimator.INFINITE);
            animation.setRepeatMode(ValueAnimator.REVERSE);
            animation.start();
        } else if (!animate) stopAnimation();
    }

    boolean showIfChanging() {
        refresh();
        if (state == null || state.end <= System.currentTimeMillis()) return false;
        show(activity, state); return true;
    }

    static final class Snapshot {
        final String text;
        final long starts, end, created;
        final boolean pause;
        final GlobalRuleSchedule waits;
        Snapshot(String text, long starts, long end, long created, boolean pause, GlobalRuleSchedule waits) {
            this.text = text; this.starts = starts; this.end = end; this.created = created;
            this.pause = pause; this.waits = waits;
        }
        boolean same(Snapshot other) {
            return other != null && text.equals(other.text) && created == other.created
                    && starts == other.starts && end == other.end;
        }
    }

    static Snapshot read(android.content.Context context) {
        try (Cursor row = DatabaseHelper.getInstance(context).getRuleMatchingRuletext("- feature enabled")) {
            if (row.moveToFirst()) {
                GlobalRuleSchedule waits = RulesManager.schedule(row);
                long end = row.getLong(row.getColumnIndexOrThrow("enact_time"));
                return new Snapshot("- feature enabled", waits.hasDefault() ? waits.defaultAt() : end,
                        end, row.getLong(row.getColumnIndexOrThrow("create_time")), false, waits);
            }
        }
        for (AppPause.Entry entry : AppPause.read(context, null)) {
            long start = entry.active ? entry.time - entry.rule.seconds * 1000L : entry.time;
            long end = start + entry.rule.seconds * 1000L;
            if (end > System.currentTimeMillis())
                return new Snapshot(entry.rule.getRuleText(), start, end, entry.created, true, entry.rule.globalWaits);
        }
        return null;
    }

    static String date(long time) { return new SimpleDateFormat("M/d hh:mm:ss a", Locale.US).format(new Date(time)); }

    private static void show(Activity activity, Snapshot shown) {
        long now = System.currentTimeMillis();
        boolean started = shown.starts <= now;
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int padding = CompactUi.dp(activity, 24); body.setPadding(padding, padding / 2, padding, padding / 2);
        addText(activity, body, activity.getString(started ? R.string.global_status_started : R.string.global_status_pending,
                date(shown.starts)));
        if (shown.pause) addText(activity, body, activity.getString(R.string.global_status_resume_at, date(shown.end)));
        RulesManager rules = RulesManager.getInstance(activity);
        boolean listed = false;
        for (Map.Entry<String, Long> entry : shown.waits.appDeadlines().entrySet()) {
            try {
                android.content.pm.ApplicationInfo app = activity.getPackageManager().getApplicationInfo(entry.getKey(), 0);
                long deadline = rules.readyAt(shown.waits, app.uid);
                if (deadline <= now) continue;
                if (!listed) addText(activity, body, activity.getString(R.string.global_status_apps));
                listed = true;
                TextView row = addText(activity, body, activity.getPackageManager().getApplicationLabel(app) + "\n"
                        + (shown.pause && deadline >= shown.end ? activity.getString(R.string.global_status_whole_pause)
                        : activity.getString(R.string.global_status_off_at, date(deadline))));
                android.graphics.drawable.Drawable icon = activity.getPackageManager().getApplicationIcon(app);
                int size = CompactUi.dp(activity, 32); icon.setBounds(0, 0, size, size);
                row.setCompoundDrawablesRelative(icon, null, null, null);
                row.setCompoundDrawablePadding(CompactUi.dp(activity, 12));
            } catch (android.content.pm.PackageManager.NameNotFoundException missing) { /* Backup may contain absent apps. */ }
        }
        if (shown.waits.latest() > Math.max(now, shown.starts))
            addText(activity, body, activity.getString(rules.useStrictUnknownDelay()
                    ? R.string.global_status_unknown : R.string.global_status_unknown_default));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.global_status_title).setView(scroll)
                .setNegativeButton(R.string.list_done, null)
                .setPositiveButton(started ? R.string.app_pause_resume : R.string.app_pause_cancel_pending, null).create();
        dialog.show(); DialogSurface.apply(dialog);
        CompactUi.dismissButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
        if (started) CompactUi.actionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        else CompactUi.destructiveButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            AlertDialog confirm = new AlertDialog.Builder(activity)
                    .setTitle(started ? R.string.global_pause_resume_title : R.string.global_status_cancel_title)
                    .setMessage(R.string.global_status_cancel_body).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.confirm_yes, (d, which) -> {
                        if (!shown.same(read(activity))) {
                            Toast.makeText(activity, R.string.global_pause_changed, Toast.LENGTH_SHORT).show(); return;
                        }
                        if (shown.pause) AppPause.resume(activity, null);
                        else rules.queueRuleText(activity, "abandon - feature enabled");
                        dialog.dismiss();
                    }).create();
            confirm.show(); DialogSurface.apply(confirm);
            CompactUi.dismissButton(confirm.getButton(AlertDialog.BUTTON_NEGATIVE));
            if (started) CompactUi.actionButton(confirm.getButton(AlertDialog.BUTTON_POSITIVE));
            else CompactUi.destructiveButton(confirm.getButton(AlertDialog.BUTTON_POSITIVE));
        });
    }

    private static TextView addText(Activity activity, LinearLayout body, String text) {
        TextView label = new TextView(activity); label.setText(text); label.setTextSize(16);
        label.setPadding(0, CompactUi.dp(activity, 8), 0, CompactUi.dp(activity, 8));
        body.addView(label); return label;
    }
}
