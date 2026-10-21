package heartguard.heartguard.main;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class UpdateLogsDeviceTest {
    private Activity updates;
    private ActivityUpdateLogs logs;

    @After public void cleanup() {
        onMain(() -> {
            if (logs != null) logs.finish();
            if (updates != null) updates.finish();
        });
    }

    // Test: Open MW release history from updates, choose older notes, retain selection on recreation, and return.
    // Why: Browsing old releases must work independently of whether the installed app has an update available.
    @Test public void browsesPublishedMwNotesAndRetainsSelectedVersion() throws Exception {
        assumeTrue("Requires GitHub connectivity", "true".equals(
                InstrumentationRegistry.getArguments().getString("runLiveUpdate")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        updates = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, ActivityUpdates.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(() -> updates.findViewById(R.id.update_show_logs).performClick());
        logs = resumedLogs(null);
        boolean loaded = device.wait(Until.hasObject(By.res(context.getPackageName(), "update_logs_versions")), 20000);
        String[] loadingStatus = new String[1];
        onMain(() -> loadingStatus[0] = ((TextView) logs.findViewById(R.id.update_logs_notes)).getText().toString());
        assertTrue(loadingStatus[0], loaded);
        String[] selected = new String[1];
        String[] newestNotes = new String[1];
        onMain(() -> {
            Spinner versions = logs.findViewById(R.id.update_logs_versions);
            assertTrue("Published history has multiple MW releases", versions.getCount() > 1);
            assertEquals(0, versions.getSelectedItemPosition());
            String previous = null;
            for (int i = 0; i < versions.getCount(); i++) {
                String version = versions.getItemAtPosition(i).toString().split(" ")[0];
                assertNotNull(UpdateRelease.versionFromTag("v" + version));
                if (previous != null) assertTrue(UpdateRelease.compare(previous, version) > 0);
                previous = version;
            }
            newestNotes[0] = ((TextView) logs.findViewById(R.id.update_logs_notes)).getText().toString();
            selected[0] = versions.getItemAtPosition(versions.getCount() - 1).toString();
        });
        device.findObject(By.res(context.getPackageName(), "update_logs_versions")).click();
        assertTrue(device.wait(Until.hasObject(By.text(selected[0])), 5000));
        device.waitForIdle();
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "update-logs-dropdown.png"));
        device.findObject(By.text(selected[0])).click();
        awaitSelection(selected[0]);
        String[] olderNotes = new String[1];
        onMain(() -> {
            Spinner versions = logs.findViewById(R.id.update_logs_versions);
            assertEquals(selected[0], versions.getSelectedItem().toString());
            olderNotes[0] = ((TextView) logs.findViewById(R.id.update_logs_notes)).getText().toString();
            assertFalse(olderNotes[0].trim().isEmpty());
            assertNotEquals(newestNotes[0], olderNotes[0]);
        });
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "update-logs-older.png"));
        ActivityUpdateLogs previous = logs;
        onMain(() -> previous.recreate());
        logs = resumedLogs(previous);
        assertTrue(device.wait(Until.hasObject(By.res(context.getPackageName(), "update_logs_versions")), 20000));
        onMain(() -> {
            assertEquals(selected[0], ((Spinner) logs.findViewById(R.id.update_logs_versions)).getSelectedItem().toString());
            assertEquals(olderNotes[0], ((TextView) logs.findViewById(R.id.update_logs_notes)).getText().toString());
        });
        device.pressBack();
        assertTrue(device.wait(Until.hasObject(By.res(context.getPackageName(), "update_show_logs")), 5000));
    }

    private ActivityUpdateLogs resumedLogs(Activity previous) {
        ActivityUpdateLogs[] result = new ActivityUpdateLogs[1];
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            onMain(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof ActivityUpdateLogs && activity != previous) result[0] = (ActivityUpdateLogs) activity;
            });
            if (result[0] != null) return result[0];
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Update logs activity did not resume");
    }

    private void awaitSelection(String expected) {
        boolean[] matched = {false};
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            onMain(() -> matched[0] = expected.equals(((Spinner) logs.findViewById(R.id.update_logs_versions)).getSelectedItem().toString()));
            if (matched[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Version selection did not change to " + expected);
    }

    private void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
