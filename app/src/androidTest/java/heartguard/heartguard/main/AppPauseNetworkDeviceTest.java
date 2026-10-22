package heartguard.heartguard.main;

import android.content.Context;
import android.database.Cursor;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Requires a TCP/UDP echo server on the host port passed as pauseNetworkPort. */
@RunWith(AndroidJUnit4.class)
public class AppPauseNetworkDeviceTest {
    // Test: Real TCP and UDP sessions stop carrying data when the app pause expires.
    // Why: Packet-policy unit checks cannot detect a native session retaining a stale allow decision.
    @Test public void nativeConnectionsStopAtPauseExpiry() throws Exception {
        verifyExpiry(false, false);
    }

    // Test: Temporary IP allows stop existing TCP/UDP sessions and reject new ones on expiry.
    // Why: An expired allow must not survive in native connections or in the cached IP decision table.
    @Test public void nativeConnectionsStopAtTemporaryAllowExpiry() throws Exception {
        verifyExpiry(true, false);
    }

    // Test: Global pause expiry stops real TCP/UDP sessions even while background cleanup is held.
    // Why: A global bypass must restore protection for existing connections, not only new requests.
    @Test public void nativeConnectionsStopAtGlobalPauseExpiry() throws Exception {
        verifyExpiry(false, true);
    }

    private void verifyExpiry(boolean temporaryAllow, boolean global) throws Exception {
        String port = InstrumentationRegistry.getArguments().getString("pauseNetworkPort", "");
        assumeTrue(port.matches("[0-9]{1,5}"));
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RulesManager rules = RulesManager.getInstance(context);
        assertEquals(0, RuleDelay.read(context).seconds);
        boolean wasEnabled = rules.getPreferenceEnabled(context);
        assertTrue(AppPause.read(context, "com.android.shell").isEmpty());
        List<String> owned = new ArrayList<>();
        CountDownLatch allowCleanup = new CountDownLatch(1);
        try {
            shell("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
            assertNull(VpnService.prepare(context));
            if (!temporaryAllow) add(context, rules, owned, "block package:com.android.shell ip:10.0.2.2");
            else if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_MANAGE_SYSTEM))
                add(context, rules, owned, "feature manage_system");
            if (!wasEnabled) add(context, rules, owned, "feature enabled");
            ServiceSinkhole.start("pause network fixture", context);
            SystemClock.sleep(2500);
            assertFalse("TCP blocked before pause", probe(port, false).contains("probe"));
            assertFalse("UDP blocked before pause", probe(port, true).contains("probe"));
            String timed = temporaryAllow ? TemporaryRuleText.text("allow package:com.android.shell ip:10.0.2.2", 15)
                    : AppPauseRule.text(global ? null : "com.android.shell", 15);
            add(context, rules, owned, timed);
            long deadline;
            try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(timed)) {
                assertTrue(cursor.moveToFirst());
                deadline = cursor.getLong(cursor.getColumnIndexOrThrow("enact_time")) + 15000;
            }
            SystemClock.sleep(1500);
            // Hold background cleanup past expiry: only packet/cache/native deadline checks can stop these flows.
            CountDownLatch cleanupHeld = new CountDownLatch(1);
            rules.handler.post(() -> {
                cleanupHeld.countDown();
                try { allowCleanup.await(40, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(cleanupHeld.await(5, TimeUnit.SECONDS));
            Flow tcp = new Flow(port, false);
            Flow udp = new Flow(port, true);
            tcp.start(); udp.start();
            tcp.join(30000); udp.join(30000);
            assertFalse("TCP client ended", tcp.isAlive()); assertFalse("UDP client ended", udp.isAlive());
            for (Flow flow : new Flow[]{tcp, udp}) {
                assertNull(flow.failure);
                assertTrue("Connection must carry data during pause: " + flow.getName(), flow.replies >= 3);
                assertTrue("Connection must stop at expiry: " + flow.getName() + " last=" + flow.lastReply + " expiry=" + deadline,
                        flow.lastReply <= deadline + 1500);
            }
            assertFalse(rules.isUidPaused(2000));
            assertFalse("Fresh TCP blocked after pause", probe(port, false).contains("probe"));
            assertFalse("Fresh UDP blocked after pause", probe(port, true).contains("probe"));
            try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(timed)) {
                assertTrue("Expiry enforcement must succeed before cleanup removes the rule", cursor.moveToFirst());
            }
        } finally {
            allowCleanup.countDown(); barrier(rules);
            List<Long> ids = new ArrayList<>();
            DatabaseHelper db = DatabaseHelper.getInstance(context);
            try (Cursor cursor = db.getAllRules()) {
                while (cursor.moveToNext())
                    if (owned.contains(cursor.getString(cursor.getColumnIndexOrThrow("ruletext"))))
                        ids.add(cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            }
            db.removeRulesById(ids.toArray(new Long[0]));
            java.lang.reflect.Method refresh = RulesManager.class.getDeclaredMethod("getAllEnactedRulesFromDb", Context.class);
            refresh.setAccessible(true); refresh.invoke(rules, context);
            WhitelistManager.getInstance(context).updateRulesFromRulesManager(context);
            rules.startTrueTimeRequest(context); barrier(rules);
            if (wasEnabled) ServiceSinkhole.reload("pause network fixture cleanup", context, false);
            else ServiceSinkhole.stop("pause network fixture cleanup", context, false);
        }
    }

    private static void add(Context context, RulesManager rules, List<String> owned, String text) throws Exception {
        try (Cursor cursor = DatabaseHelper.getInstance(context).getRuleMatchingRuletext(text)) {
            assertFalse("Fixture rule must not already exist", cursor.moveToFirst());
        }
        owned.add(text); rules.queueRuleText(context, text); barrier(rules);
    }
    private static void barrier(RulesManager rules) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); rules.handler.post(latch::countDown);
        assertTrue(latch.await(15, TimeUnit.SECONDS));
    }
    private static String probe(String port, boolean udp) throws Exception {
        File script = script("echo probe | timeout 4 toybox nc " + (udp ? "-u " : "") + "-w 2 -W 2 10.0.2.2 " + port);
        try { return shell("sh " + script.getAbsolutePath()); }
        finally { script.delete(); }
    }
    private static File script(String command) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("pause-network-", ".sh", context.getExternalFilesDir(null));
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write((command + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return file;
    }
    private static String shell(String command) throws Exception {
        StringBuilder output = new StringBuilder();
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             BufferedReader reader = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(descriptor)))) {
            String line; while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        return output.toString();
    }
    private static final class Flow extends Thread {
        final String port;
        final boolean udp;
        int replies;
        long lastReply;
        Exception failure;
        Flow(String port, boolean udp) { super(udp ? "UDP" : "TCP"); this.port = port; this.udp = udp; }
        @Override public void run() {
            File script = null;
            try {
                script = script("(i=0; while [ $i -lt 21 ]; do echo tick-$i; i=$((i+1)); sleep 1; done) | timeout 24 toybox nc "
                    + (udp ? "-u " : "") + "-w 3 -W 3 10.0.2.2 " + port);
                try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("sh " + script.getAbsolutePath());
                 BufferedReader reader = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(descriptor)))) {
                String line;
                while ((line = reader.readLine()) != null)
                    if (line.startsWith("tick-")) { replies++; lastReply = System.currentTimeMillis(); }
                }
            } catch (Exception error) { failure = error; }
            finally { if (script != null) script.delete(); }
        }
    }
}
