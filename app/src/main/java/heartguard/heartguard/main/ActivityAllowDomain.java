package heartguard.heartguard.main;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** Hosts the existing rule dialog above the previous app, without opening the main app list. */
public class ActivityAllowDomain extends AppCompatActivity {
    private AlertDialog dialog;

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        getTheme().applyStyle(R.style.NotificationDialogWindow, true);
        super.onCreate(state);
        if (!AccessNotification.OPTIONS.equals(getIntent().getAction()) || !validTarget()) {
            finishAndRemoveTask();
            return;
        }
        dialog = DomainRuleDialog.show(this, getIntent().getStringExtra("package"),
                getIntent().getStringExtra("host"), false, this::validTarget, () -> {
                    if (!isFinishing() && !isDestroyed() && !isChangingConfigurations()) finishAndRemoveTask();
                });
    }

    private boolean validTarget() {
        if (AccessNotification.validTarget(this, getIntent())) return true;
        Toast.makeText(this, R.string.notification_app_unavailable, Toast.LENGTH_SHORT).show();
        return false;
    }

    @Override protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
