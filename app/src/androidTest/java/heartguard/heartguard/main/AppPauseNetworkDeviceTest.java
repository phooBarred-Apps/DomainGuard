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

    // Test: HTTP and TLS names cross the native VPN bridge; ECH stays uncertain and blocked flows stay blocked.
    // Why: Parser-only tests cannot catch JNI signature, ownership, ordering or forwarding mistakes.
    @Test public void nativeHostnameEvidence() throws Exception {
        String port = InstrumentationRegistry.getArguments().getString("pauseNetworkPort", "");
        assertTrue("Host-local fixture required", port.matches("[0-9]{1,5}"));
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RulesManager rules = RulesManager.getInstance(context);
        assertEquals(0, RuleDelay.read(context).seconds);
        boolean wasEnabled = rules.getPreferenceEnabled(context);
        List<String> owned = new ArrayList<>();
        try {
            shell("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
            assertNull(VpnService.prepare(context));
            if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_MANAGE_SYSTEM)) add(context, rules, owned, "feature manage_system");
            add(context, rules, owned, "allow package:com.android.shell ip:10.0.2.2");
            if (!wasEnabled) add(context, rules, owned, "feature enabled");
            ServiceSinkhole.start("hostname network fixture", context);
            long ready = SystemClock.elapsedRealtime() + 20000;
            boolean recorded = false;
            while (!recorded && SystemClock.elapsedRealtime() < ready) {
                hostnameProbe(port);
                try (Cursor rows = DatabaseHelper.getInstance(context).getReadableDatabase().rawQuery(
                        "SELECT ID FROM access WHERE uid=2000 AND dport=? AND allowed=1", new String[]{port})) {
                    recorded = rows.moveToFirst();
                }
                if (!recorded) SystemClock.sleep(100);
            }
            assertTrue("Fixture request recorded through VPN", recorded);
            byte[][] payloads = {
                "GET / HTTP/1.1\r\nHost: mail.http.invalid\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                HostnameFixture.hello("mail.tls.invalid", false), HostnameFixture.hello("cover.invalid", true)
            };
            int[] sources = {DomainAttribution.HTTP, DomainAttribution.TLS, DomainAttribution.ENCRYPTED};
            String[] names = {"mail.http.invalid", "mail.tls.invalid", "10.0.2.2"};
            for (int i = 0; i < payloads.length; i++) {
                assertTrue("Fixture payload delivered", sendFixture(context, port, payloads[i], "echo").contains("fixture-received"));
                long end = SystemClock.elapsedRealtime() + 5000;
                boolean found = false;
                while (!found && SystemClock.elapsedRealtime() < end) {
                    try (Cursor rows = DatabaseHelper.getInstance(context).getReadableDatabase().rawQuery(
                            "SELECT observed_name,name_source,daddr,allowed FROM access WHERE uid=2000 AND dport=?", new String[]{port})) {
                        while (rows.moveToNext()) if (names[i].equals(rows.getString(0)) && sources[i] == rows.getInt(1)) {
                            assertEquals("10.0.2.2", rows.getString(2)); assertEquals(1, rows.getInt(3)); found = true;
                        }
                    }
                    if (!found) SystemClock.sleep(100);
                }
                assertTrue("Native evidence " + names[i], found);
            }
            if ("true".equals(InstrumentationRegistry.getArguments().getString("dnsFixture"))) {
                java.io.ByteArrayOutputStream query = new java.io.ByteArrayOutputStream();
                query.write(new byte[]{0x29,0x10,1,0,0,1,0,0,0,0,0,0});
                for (String label : "dns.native.invalid".split("\\.")) {
                    query.write(label.length()); query.write(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
                query.write(new byte[]{0,0,1,0,1});
                java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
                framed.write(0); framed.write(query.size()); framed.write(query.toByteArray());
                try {
                    assertTrue(sendFixture(context, "53", framed.toByteArray(), "dns").contains("fixture-received"));
                    try (Cursor rows = DatabaseHelper.getInstance(context).getReadableDatabase().rawQuery(
                            "SELECT uid,name,expires-time FROM dns_attribution WHERE ip='192.0.2.92'", null)) {
                        assertTrue("Synthetic DNS response recorded", rows.moveToFirst());
                        assertEquals("Native DNS owner", 2000, rows.getInt(0));
                        assertEquals("dns.native.invalid", rows.getString(1)); assertEquals(60000, rows.getLong(2));
                    }
                } finally {
                    DatabaseHelper.getInstance(context).getWritableDatabase().delete("dns_attribution", "ip=?", new String[]{"192.0.2.92"});
                    DatabaseHelper.getInstance(context).getWritableDatabase().delete("dns", "resource=?", new String[]{"192.0.2.92"});
                }
            }
            add(context, rules, owned, "block package:com.android.shell ip:10.0.2.2");
            assertNotNull("Explicit block enacted", WhitelistManager.getInstance(context).getExplicitBlock(context, "10.0.2.2", 2000));
            long blocked = SystemClock.elapsedRealtime() + 10000;
            while (hostnameProbe(port).contains("fixture-received") && SystemClock.elapsedRealtime() < blocked) SystemClock.sleep(100);
            assertFalse("Attribution must not bypass a block", hostnameProbe(port).contains("fixture-received"));
        } finally { cleanup(context, rules, owned, wasEnabled); }
    }

    private static String hostnameProbe(String port) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return sendFixture(context, port, "probe".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "echo");
    }

    private static String sendFixture(Context context, String port, byte[] bytes, String mode) throws Exception {
        File data = File.createTempFile("hostname-", ".bin", context.getExternalFilesDir(null));
        File command = null;
        try {
            try (FileOutputStream output = new FileOutputStream(data)) { output.write(bytes); }
            String apk = InstrumentationRegistry.getInstrumentation().getContext().getPackageCodePath();
            command = script("CLASSPATH=" + apk + " app_process / heartguard.heartguard.main.NativeTrafficClient "
                    + port + " " + data.getAbsolutePath() + " " + mode);
            return shell("sh " + command.getAbsolutePath());
        } finally { data.delete(); if (command != null) command.delete(); }
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
            cleanup(context, rules, owned, wasEnabled);
        }
    }

    private static void cleanup(Context context, RulesManager rules, List<String> owned, boolean wasEnabled) throws Exception {
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
