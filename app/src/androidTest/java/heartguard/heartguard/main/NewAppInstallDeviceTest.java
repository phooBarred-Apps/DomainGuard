package heartguard.heartguard.main;

import android.content.Context;
import android.os.Build;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Run with scripts/test-new-app-delay.ps1 on a disposable emulator, not a user's installation. */
@RunWith(AndroidJUnit4.class)
public class NewAppInstallDeviceTest {
    private static final String APP = "test.domainguard.installfixture";
    private Context context;
    private RulesManager rules;

    @Before public void setup() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("runInstallFixture"));
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        rules = RulesManager.getInstance(context); barrier();
    }

    // Test: Arm the new-app policy before the host script stops this process and installs the fixture APK.
    // Why: A remote installation need not happen while the VPN or application process is running.
    @Test public void prepareStoppedInstall() throws Exception {
        assertEquals(0, rules.getDefaultDelay());
        assertFalse(NewAppDelay.read(context).overridden);
        assertFalse(PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(NewAppDelay.KNOWN_PACKAGES, java.util.Collections.emptySet()).contains(APP));
        rules.queueRuleText(context, NewAppDelay.PREFIX + "21600"); barrier();
        assertEquals(21600, NewAppDelay.read(context).seconds);
    }

    // Test: Restart after a real APK install, then update and reinstall it while the process is running.
    // Why: Package events and startup discovery must both assign the delay without overwriting a configured override.
    @Test public void verifyStoppedInstallAndReinstall() throws Exception {
        assertNotNull(context.getPackageManager().getPackageInfo(APP, 0));
        assertEquals(21600, rules.getPauseDelay(APP));
        assertTrue(rules.hasCustomDelay(APP));
        rules.queueRuleText(context, "delay package:" + APP + " 25200"); barrier();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.executeShellCommand("pm install -r /data/local/tmp/domainguard-install-fixture.apk").contains("Success"));
        barrier();
        assertEquals(25200, rules.getPauseDelay(APP));
        assertTrue(device.executeShellCommand("pm uninstall " + APP).contains("Success"));
        assertTrue(device.executeShellCommand("pm install /data/local/tmp/domainguard-install-fixture.apk").contains("Success"));
        barrier();
        assertEquals(25200, rules.getPauseDelay(APP));
    }

    // Test: An actual package-added event applies the policy while DomainGuard is open but the VPN is not running.
    // Why: Enrollment must not depend on the VPN service's receiver being registered.
    @Test public void liveInstallWithoutVpn() throws Exception {
        assertFalse(rules.getPreferenceEnabled(context));
        assertFalse(rules.hasCustomDelay(APP));
        rules.queueRuleText(context, NewAppDelay.PREFIX + "21600"); barrier();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.executeShellCommand("pm install /data/local/tmp/domainguard-install-fixture.apk").contains("Success"));
        long until = android.os.SystemClock.elapsedRealtime() + 10000;
        while (!rules.hasCustomDelay(APP) && android.os.SystemClock.elapsedRealtime() < until)
            android.os.SystemClock.sleep(50);
        assertEquals(21600, rules.getPauseDelay(APP));
        assertTrue(rules.hasCustomDelay(APP));
    }

    private void barrier() throws Exception {
        CountDownLatch done = new CountDownLatch(1); rules.handler.post(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }
}
