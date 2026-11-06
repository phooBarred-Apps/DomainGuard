package heartguard.heartguard.main;

import android.content.Context;
import android.database.Cursor;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Requires a TCP/UDP echo server on the host port passed as pauseNetworkPort. */
@RunWith(AndroidJUnit4.class)
public class AppPauseNetworkDeviceTest {
    private boolean hadUpdateCheck;
    private boolean updateCheck;
    private boolean changedPreferences;

    @Before public void isolateNetworkFixture() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        hadUpdateCheck = prefs.contains("update_check");
        updateCheck = prefs.getBoolean("update_check", true);
        // The service's update check uses the command queue. An external GitHub request
        // must not determine when a host-local VPN fixture becomes ready.
        prefs.edit().putBoolean("update_check", false).commit();
        changedPreferences = true;
        assertTrue("No inherited global pause", AppPause.read(context, null).isEmpty());
        assertTrue("No inherited app pause", AppPause.read(context, "com.android.shell").isEmpty());
    }

    @After public void restoreNetworkFixture() {
        if (!changedPreferences) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences.Editor prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit();
        if (hadUpdateCheck) prefs.putBoolean("update_check", updateCheck);
        else prefs.remove("update_check");
        prefs.commit();
    }

    // Test: Rule processing can finish while Android has not yet delivered VPN startup.
    // Why: A rules-handler barrier or a fixed sleep does not establish a ready VPN.
    @Test public void nativeStartupWaitsForVpn() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RulesManager rules = RulesManager.getInstance(context);
        assertFalse("Start with the disposable VPN stopped", rules.getPreferenceEnabled(context));
        awaitVpn(context, false);
        List<String> owned = new ArrayList<>();
        CountDownLatch mainHeld = new CountDownLatch(1), releaseMain = new CountDownLatch(1);
        FutureTask<Void> ready = new FutureTask<>(() -> { awaitVpnReady(context); return null; });
        Thread waiter = new Thread(ready, "VPN readiness test");
        try {
            shell("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
            assertNull(VpnService.prepare(context));
            add(context, rules, owned, "feature capture_all_traffic");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                mainHeld.countDown();
                try { releaseMain.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(mainHeld.await(2, TimeUnit.SECONDS));
            add(context, rules, owned, "feature enabled");
            assertTrue("Rule is already enacted", rules.getPreferenceEnabled(context));
            waiter.start();
            try { ready.get(200, TimeUnit.MILLISECONDS); fail("VPN readiness must wait for Android startup"); }
            catch (TimeoutException expected) { /* Rules ready, but the VPN is not ready. */ }
            releaseMain.countDown();
            ready.get(20, TimeUnit.SECONDS);
        } finally {
            releaseMain.countDown();
            waiter.interrupt(); waiter.join(2000);
            cleanup(context, rules, owned, false);
        }
    }

    // Test: Rejected DNS responses cross the native bridge into Recent traffic with their name and owner.
    // Why: Capture-all with system apps allowed used to suppress DNS blocks even with every display filter off.
    @Test public void nativeBlockedDnsIsVisible() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("dnsFixture"));
        assertTrue("Disposable emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RulesManager rules = RulesManager.getInstance(context);
        DatabaseHelper db = DatabaseHelper.getInstance(context);
        assertEquals(0, RuleDelay.read(context).seconds);
        assertFalse("Match the reported system-app setting", rules.getPreferenceManageSystem(context));
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        java.util.Map<String, Object> saved = new java.util.HashMap<>(prefs.getAll());
        String[] changedPrefs = {Rule.PREFERENCE_STRING_LOG, AccessNotification.NOTIFY_GLOBAL_BLOCKS,
                DomainFilters.ALLOWED_KEY, DomainFilters.BLOCKED_KEY, "recent_filter_default_type", "recent_filter_default_access"};
        prefs.edit().putBoolean(Rule.PREFERENCE_STRING_LOG, false).putBoolean(AccessNotification.NOTIFY_GLOBAL_BLOCKS, false)
                .putInt(DomainFilters.ALLOWED_KEY, 0).putInt(DomainFilters.BLOCKED_KEY, 0)
                .putInt("recent_filter_default_type", 0).putInt("recent_filter_default_access", 0).commit();
        boolean wasEnabled = rules.getPreferenceEnabled(context);
        List<String> owned = new ArrayList<>();
        android.app.Activity activity = null;
        try {
            shell("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
            assertNull(VpnService.prepare(context));
            if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_CAPTURE_ALL_TRAFFIC))
                add(context, rules, owned, "feature capture_all_traffic");
            add(context, rules, owned, "blockdomain dns.native.invalid");
            if (!wasEnabled) add(context, rules, owned, "feature enabled");
            ServiceSinkhole.start("blocked DNS fixture", context);
            awaitVpnReady(context);
            long ready = SystemClock.elapsedRealtime() + 20000;
            String reply = "";
            while (!reply.contains("fixture-dns-rcode-3-answers-0") && SystemClock.elapsedRealtime() < ready)
                reply = sendFixture(context, "53", dnsQuery("dns.native.invalid", 1, true), "dns");
            assertTrue("Global block still rejects the DNS answer", reply.contains("fixture-dns-rcode-3-answers-0"));
            AccessEntry tcp = awaitDns(db, "dns.native.invalid", 6, 2000, DomainAttribution.DNS_BLOCKED);
            assertEquals("Resolver IP is retained separately", "10.0.2.2", tcp.destinationIp);
            assertEquals(GlobalRuleIndicators.BLOCKED, tcp.globalScope);

            assertTrue(sendFixture(context, "53", dnsQuery("dns.native.invalid", 1, false), "dns-udp")
                    .contains("fixture-dns-rcode-3-answers-0"));
            // Android 12 cannot assign this deliberately unconnected UDP socket to an app.
            AccessEntry unknown = awaitDns(db, "dns.native.invalid", 17, -1, DomainAttribution.DNS_BLOCKED);
            assertEquals(0, unknown.allowed);

            assertTrue(sendFixture(context, "53", dnsQuery("service.native.invalid", 65, true), "dns")
                    .contains("fixture-dns-rcode-3-answers-0"));
            awaitDns(db, "service.native.invalid", 6, 2000, DomainAttribution.DNS_SERVICE_BINDING);
            assertFalse("A service-binding rejection is not a global domain rule", rules.isGlobalDomainBlocked("service.native.invalid"));

            android.app.NotificationManager manager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
                assertFalse("Global alerts stay off", String.valueOf(notification.getTag()).contains("dns.native.invalid"));
            try (Cursor rows = db.getRecentAccess(System.currentTimeMillis() - 60000, java.util.Collections.emptySet(), null,
                    new DomainFilters(DomainFilters.HIDE_ALL, DomainFilters.SHOW), ListSort.RECENT, 1000)) {
                boolean found = false;
                while (rows.moveToNext()) if (new AccessEntry(rows).id == unknown.id) found = true;
                assertTrue("Hide allowed keeps blocked unknown-owner DNS", found);
            }
            try (Cursor rows = db.getRecentAccess(System.currentTimeMillis() - 60000, java.util.Collections.emptySet(), null,
                    new DomainFilters(DomainFilters.SHOW, DomainFilters.HIDE_GLOBAL), ListSort.RECENT, 1000)) {
                while (rows.moveToNext()) assertNotEquals("Hide global blocks uses the queried domain", unknown.id, new AccessEntry(rows).id);
            }
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(new android.content.Intent(context,
                    ActivityRecentTraffic.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
            androidx.test.uiautomator.UiObject2 row = device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.text("dns.native.invalid")), 5000);
            assertNotNull("Rejected lookup is visible in Recent traffic", row);
            assertTrue("Unknown owner is visible", device.hasObject(androidx.test.uiautomator.By.text(context.getString(R.string.recent_traffic_unknown_app))));
            row.click();
            device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(
                    context.getString(R.string.domain_identification))), 5000).click();
            androidx.test.uiautomator.UiObject2 explanation = device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile(".*DNS server: 10\\.0\\.2\\.2.*",
                            java.util.regex.Pattern.DOTALL))), 5000);
            assertNotNull("DNS server is not presented as the website IP", explanation);
            assertTrue(explanation.getText().contains(context.getString(R.string.domain_evidence_dns_blocked)));
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "blocked-dns-explanation.png"));
            device.pressBack(); device.pressBack();
            assertTrue("Logging and reviewing do not weaken the global block", rules.isGlobalDomainBlocked("dns.native.invalid"));
            String port = InstrumentationRegistry.getArguments().getString("pauseNetworkPort");
            add(context, rules, owned, "block package:com.android.shell ip:10.0.2.2");
            assertBlocked(context, port, false, true, "Explicit exception blocks an otherwise allowed system app");
        } finally {
            if (activity != null) { final android.app.Activity open = activity; InstrumentationRegistry.getInstrumentation().runOnMainSync(open::finish); }
            cleanup(context, rules, owned, wasEnabled);
            android.content.SharedPreferences.Editor restore = prefs.edit();
            for (String key : changedPrefs) {
                Object value = saved.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else restore.remove(key);
            }
            restore.commit();
            for (String name : new String[]{"dns.native.invalid", "service.native.invalid"}) {
                db.getWritableDatabase().delete("access", "daddr=?", new String[]{name});
                db.getWritableDatabase().delete("log", "dname=?", new String[]{name});
                db.getWritableDatabase().delete("dns", "qname=?", new String[]{name});
                db.getWritableDatabase().delete("dns_attribution", "name=?", new String[]{name});
            }
        }
    }

    private static AccessEntry awaitDns(DatabaseHelper db, String name, int protocol, int uid, int source) {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < end) {
            try (Cursor rows = db.getRecentAccess(System.currentTimeMillis() - 60000, java.util.Collections.emptySet(), null,
                    new DomainFilters(DomainFilters.SHOW, DomainFilters.SHOW), ListSort.RECENT, 1000)) {
                while (rows.moveToNext()) {
                    AccessEntry entry = new AccessEntry(rows, db.getGlobalRuleIndicators());
                    if (name.equals(entry.address) && entry.protocol == protocol && rows.getInt(rows.getColumnIndexOrThrow("uid")) == uid) {
                        assertEquals(source, entry.nameSource); assertEquals(0, entry.allowed); return entry;
                    }
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing DNS event: " + name + " protocol " + protocol + " uid " + uid);
    }

    private static byte[] dnsQuery(String name, int type, boolean tcp) throws Exception {
        java.io.ByteArrayOutputStream query = new java.io.ByteArrayOutputStream();
        query.write(new byte[]{0x29,0x12,1,0,0,1,0,0,0,0,0,0});
        for (String label : name.split("\\.")) { query.write(label.length()); query.write(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
        query.write(new byte[]{0,0,(byte)type,0,1});
        if (!tcp) return query.toByteArray();
        java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
        framed.write(0); framed.write(query.size()); framed.write(query.toByteArray()); return framed.toByteArray();
    }

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

    // Test: Adding an app block brings an excluded system app into the replacement VPN.
    // Why: Stable-routing policy tests must not replace coverage of automatic capture changes.
    @Test public void nativeRoutingTransitionEnforcesBlock() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RulesManager rules = RulesManager.getInstance(context);
        assertFalse("Start without capture-all", rules.getPreferenceCaptureAllTraffic(context));
        assertFalse("System apps initially bypass the VPN", rules.getPreferenceManageSystem(context));
        boolean wasEnabled = rules.getPreferenceEnabled(context);
        List<String> owned = new ArrayList<>();
        String port = InstrumentationRegistry.getArguments().getString("pauseNetworkPort", "");
        try {
            shell("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
            assertNull(VpnService.prepare(context));
            if (!wasEnabled) add(context, rules, owned, "feature enabled");
            ServiceSinkhole.start("routing transition fixture", context);
            awaitVpn(context, true);
            Network previous = vpnNetwork(context);
            assertNotNull(previous);
            assertTrue("Excluded app initially reaches fixture", hostnameProbe(port).contains("fixture-received"));
            add(context, rules, owned, "block package:com.android.shell ip:10.0.2.2");
            assertTrue("App block automatically enables capture", rules.getPreferenceCaptureAllTraffic(context));
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while ((vpnNetwork(context) == null || previous.equals(vpnNetwork(context)))
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertNotNull("Replacement VPN established", vpnNetwork(context));
            assertNotEquals("Routing exclusions updated", previous, vpnNetwork(context));
            awaitVpnReady(context);
            assertBlocked(context, port, false, true, "Replacement VPN blocks TCP");
            assertBlocked(context, port, true, false, "Replacement VPN blocks UDP");
        } finally { cleanup(context, rules, owned, wasEnabled); }
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
            // Keep routing stable when the explicit block is added: this test checks
            // native rule enforcement, not traffic sent while Android replaces a VPN.
            if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_CAPTURE_ALL_TRAFFIC))
                add(context, rules, owned, "feature capture_all_traffic");
            if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_MANAGE_SYSTEM)) add(context, rules, owned, "feature manage_system");
            add(context, rules, owned, "allow package:com.android.shell ip:10.0.2.2");
            if (!wasEnabled) add(context, rules, owned, "feature enabled");
            ServiceSinkhole.start("hostname network fixture", context);
            awaitVpnReady(context);
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
            assertBlocked(context, port, false, true, "Attribution must not bypass a block");
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
        assertTrue("Host-local fixture required", port.matches("[0-9]{1,5}"));
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
            if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_CAPTURE_ALL_TRAFFIC))
                add(context, rules, owned, "feature capture_all_traffic");
            if (!temporaryAllow) add(context, rules, owned, "block package:com.android.shell ip:10.0.2.2");
            else if (!rules.hasFeatureRule(FeatureRule.FEATURE_NAME_MANAGE_SYSTEM))
                add(context, rules, owned, "feature manage_system");
            if (!wasEnabled) add(context, rules, owned, "feature enabled");
            ServiceSinkhole.start("pause network fixture", context);
            awaitVpnReady(context);
            assertBlocked(context, port, false, false, "TCP blocked before pause");
            assertBlocked(context, port, true, false, "UDP blocked before pause");
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
            assertBlocked(context, port, false, false, "Fresh TCP blocked after pause");
            assertBlocked(context, port, true, false, "Fresh UDP blocked after pause");
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
            else {
                // Finish teardown before another test can enqueue its own startup.
                // stopService alone cannot close a VPN still bound by Android.
                ServiceSinkhole.stop("pause network fixture cleanup", context, false);
                awaitVpn(context, false);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        context.stopService(new android.content.Intent(context, ServiceSinkhole.class)));
                awaitServiceStopped(context);
            }
    }

    private static void awaitServiceStopped(Context context) throws InterruptedException {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            boolean running = false;
            for (android.app.ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE))
                if (ServiceSinkhole.class.getName().equals(info.service.getClassName())) running = true;
            if (!running) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Previous VPN service did not finish teardown");
    }

    private static void awaitVpnReady(Context context) throws Exception {
        awaitVpn(context, true);
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        String port = InstrumentationRegistry.getArguments().getString("pauseNetworkPort", "");
        long deadline = SystemClock.elapsedRealtime() + 15000;
        String route = "";
        do {
            route = sendFixture(context, port, new byte[0], "route").trim();
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                android.net.LinkProperties link = manager.getLinkProperties(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || link == null) continue;
                for (android.net.LinkAddress address : link.getLinkAddresses())
                    if (route.equals("fixture-route:" + address.getAddress().getHostAddress())) return;
            }
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Shell UID route did not enter the VPN: " + route);
    }

    private static void awaitVpn(Context context, boolean expected) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            if ((vpnNetwork(context) != null) == expected) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("VPN did not become " + (expected ? "ready" : "stopped"));
    }

    private static Network vpnNetwork(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return network;
        }
        return null;
    }

    private static void assertBlocked(Context context, String port, boolean udp, boolean hostname, String message) throws Exception {
        long sent = System.currentTimeMillis();
        String response = hostname ? hostnameProbe(port) : probe(port, udp);
        assertFalse(message + ": " + response, response.contains(hostname ? "fixture-received" : "probe"));
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            try (Cursor rows = DatabaseHelper.getInstance(context).getReadableDatabase().rawQuery(
                    "SELECT ID FROM access WHERE uid=2000 AND dport=? AND protocol=? AND allowed=0 AND time>=?",
                    new String[]{port, udp ? "17" : "6", Long.toString(sent)})) {
                if (rows.moveToFirst()) return;
            }
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message + ": no fresh blocked request owned by shell UID 2000; a timeout alone is not proof of VPN enforcement");
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
