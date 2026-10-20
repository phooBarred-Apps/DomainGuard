package heartguard.heartguard.main;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A user-initiated update session. Firewall settings are never changed. */
public class ActivityUpdates extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final UpdateClient client = new UpdateClient();
    private TextView status;
    private ProgressBar progress;
    private Button action;
    private UpdateRelease release;
    private File apk;
    private boolean waitingForPermission;

    @Override protected void onCreate(Bundle state) {
        Util.setTheme(this);
        super.onCreate(state);
        setContentView(R.layout.app_updates);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        status = findViewById(R.id.update_status);
        progress = findViewById(R.id.update_progress);
        action = findViewById(R.id.update_action);
        check();
    }

    private void check() {
        busy(R.string.update_checking, true);
        worker.execute(() -> {
            try {
                UpdateRelease found = client.check(BuildConfig.VERSION_NAME);
                post(() -> {
                    release = found;
                    if (found == null)
                        ready(getString(R.string.update_current, BuildConfig.VERSION_NAME), R.string.check_updates, this::check);
                    else
                        ready(getString(R.string.update_available, BuildConfig.VERSION_NAME, found.version, found.notes),
                                R.string.update_download, this::download);
                });
            } catch (IOException | RuntimeException failure) { fail(failure, this::check); }
        });
    }

    private void download() {
        busy(R.string.update_downloading, false);
        worker.execute(() -> {
            File downloaded = null;
            try {
                downloaded = client.download(release, new File(getCacheDir(), "updates"),
                        percent -> post(() -> progress.setProgress(percent)));
                UpdateApk.verify(this, downloaded, release);
                File verified = downloaded;
                post(() -> { apk = verified; install(); });
            } catch (IOException | RuntimeException failure) {
                if (downloaded != null) downloaded.delete();
                fail(failure, this::download);
            }
        });
    }

    private void install() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            ready(getString(R.string.update_allow_install), R.string.update_permission, () -> {
                try {
                    waitingForPermission = true;
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    waitingForPermission = false;
                    ready(getString(R.string.update_unavailable), R.string.update_install, this::install);
                }
            });
            return;
        }
        busy(R.string.update_verifying, true);
        worker.execute(() -> {
            try {
                UpdateApk.verify(this, apk, release);
                post(this::openInstaller);
            } catch (IOException | RuntimeException failure) { fail(failure, this::download); }
        });
    }

    private void openInstaller() {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ready(getString(R.string.update_install_prompt), R.string.update_install, this::install);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            ready(getString(R.string.update_unavailable), R.string.update_install, this::install);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForPermission) {
            waitingForPermission = false;
            install();
        }
    }

    @Override protected void onDestroy() {
        client.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void busy(int message, boolean indeterminate) {
        status.setText(message);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(indeterminate);
        progress.setProgress(0);
        action.setEnabled(false);
    }

    private void ready(String message, int label, Runnable next) {
        status.setText(message);
        progress.setVisibility(View.GONE);
        action.setText(label);
        action.setEnabled(true);
        action.setOnClickListener(view -> next.run());
    }

    private void fail(Exception failure, Runnable retry) {
        String detail = failure instanceof IOException ? failure.getMessage() : "The update could not be processed.";
        post(() -> ready(detail, R.string.update_retry, retry));
    }

    private void post(Runnable callback) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) callback.run(); });
    }
}
